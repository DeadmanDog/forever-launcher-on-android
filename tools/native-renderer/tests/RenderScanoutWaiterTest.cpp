// Host test of ScanoutReleaseWaiter (the scanout buffer hand-back): pipes stand in for release fences.
#include "ScanoutReleaseWaiter.h"
#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <set>
#include <vector>

static std::mutex m; static std::vector<int64_t> got;
static void sink(int64_t t) { std::lock_guard<std::mutex> l(m); got.push_back(t); }
static size_t count() { std::lock_guard<std::mutex> l(m); return got.size(); }
static bool has(int64_t t) { std::lock_guard<std::mutex> l(m); for (auto v : got) if (v == t) return true; return false; }
static void check(bool ok, const char* what) { if (!ok) { fprintf(stderr, "FAIL %s\n", what); exit(2); } }
static void sleepMs(int ms) { std::this_thread::sleep_for(std::chrono::milliseconds(ms)); }
static bool fdOpen(int fd) { return fcntl(fd, F_GETFD) != -1; }

int main() {
    const int64_t ms = 1000000;
    {   // released only once the completion came AND its fence signalled
        ScanoutReleaseWaiter w(sink, 2000 * ms, 2000 * ms);
        uint64_t id = w.expect(11);
        check(id != 0, "a token can be tracked");
        sleepMs(50); check(!has(11), "no hand-back before the completion");
        int p[2]; check(pipe(p) == 0, "pipe");
        w.complete(id, p[0]);
        sleepMs(50); check(!has(11), "no hand-back while the fence is not signalled");
        check(write(p[1], "x", 1) == 1, "signal");
        for (int i = 0; i < 100 && !has(11); i++) sleepMs(5);
        check(has(11), "handed back once the fence signalled");
        check(!fdOpen(p[0]), "the fence descriptor is closed");
        close(p[1]);
    }
    got.clear();
    {   // a completion without a fence hands back at once
        ScanoutReleaseWaiter w(sink, 2000 * ms, 2000 * ms);
        uint64_t id = w.expect(21);
        w.complete(id, -1);
        for (int i = 0; i < 100 && !has(21); i++) sleepMs(5);
        check(has(21), "no fence: handed back at the completion");
        check(w.consecutiveCallbackTimeouts() == 0, "a completion that came is no timeout");
    }
    got.clear();
    {   // deadlines: a completion that never comes, a fence that never signals
        ScanoutReleaseWaiter w(sink, 60 * ms, 60 * ms);
        w.expect(31);
        uint64_t id = w.expect(32);
        int p[2]; check(pipe(p) == 0, "pipe");
        w.complete(id, p[0]);
        sleepMs(30); check(count() == 0, "nothing before the deadlines");
        for (int i = 0; i < 100 && count() < 2; i++) sleepMs(5);
        check(has(31) && has(32), "both handed back by their deadlines (never held for good)");
        check(w.consecutiveCallbackTimeouts() == 1, "one completion never came");
        check(!fdOpen(p[0]), "the never-signalled fence is closed");
        close(p[1]);
    }
    got.clear();
    {   // stop: what still waits is handed back; a late completion only closes its descriptor
        ScanoutReleaseWaiter w(sink, 5000 * ms, 5000 * ms);
        uint64_t a = w.expect(41); w.expect(42);
        int p[2]; check(pipe(p) == 0, "pipe");
        w.stop();
        check(has(41) && has(42) && count() == 2, "stop hands back every waiting token");
        w.complete(a, p[0]);
        check(!fdOpen(p[0]), "a completion after stop closes its fence");
        check(count() == 2, "and hands back nothing twice");
        check(w.expect(43) == 0, "no tracking after stop: the caller hands back itself");
        close(p[1]);
    }
    got.clear();
    {   // many in flight, completed out of order, from other threads
        ScanoutReleaseWaiter w(sink, 3000 * ms, 3000 * ms);
        std::vector<uint64_t> ids; std::vector<int> writers;
        for (int i = 0; i < 64; i++) ids.push_back(w.expect(100 + i));
        std::vector<std::thread> ts;
        for (int i = 63; i >= 0; i--) {
            int p[2]; check(pipe(p) == 0, "pipe"); writers.push_back(p[1]);
            ts.emplace_back([&w, id = ids[i], fd = p[0]] { w.complete(id, fd); });
        }
        for (auto& t : ts) t.join();
        for (int fd : writers) { check(write(fd, "x", 1) == 1, "signal"); }
        for (int i = 0; i < 200 && count() < 64; i++) sleepMs(5);
        std::set<int64_t> unique(got.begin(), got.end());
        check(count() == 64 && unique.size() == 64, "every token exactly once");
        for (int fd : writers) close(fd);
    }
    puts("PASS scanout release waiter");
}
