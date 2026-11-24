#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <poll.h>
#include <thread>

static std::atomic<int> interruptions{0};
static int interruptedPoll(pollfd* fds, nfds_t count, int timeout) {
    int remaining = interruptions.load();
    if (remaining > 0 && interruptions.fetch_sub(1) > 0) {
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
        errno = EINTR;
        return -1;
    }
    return ::poll(fds, count, timeout);
}
#define poll interruptedPoll
#include "ScanoutReleaseWaiter.h"
#undef poll

static void check(bool ok, const char* message) {
    if (!ok) { std::fprintf(stderr, "FAIL %s\n", message); std::exit(2); }
}
template<class Predicate> static void await(Predicate predicate) {
    const auto limit = std::chrono::steady_clock::now() + std::chrono::seconds(2);
    while (!predicate() && std::chrono::steady_clock::now() < limit)
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    check(predicate(), "test did not make progress");
}
int main(int argc, char** argv) {
    int which = argc > 1 ? std::atoi(argv[1]) : 0;
    std::atomic<int> released{0};
    ScanoutReleaseWaiter waiter([&](int64_t) { ++released; }, 40000000, 40000000);
    if (which == 0) {
        uint64_t late = 0;
        for (int i = 1; i <= 8; ++i) late = waiter.expect(i);
        await([&] { return released.load() == 8; });
        check(waiter.consecutiveCallbackTimeouts() == 8, "eight missed completions");
        int fds[2]; check(pipe(fds) == 0, "pipe");
        waiter.complete(late, fds[0]);
        check(fcntl(fds[0], F_GETFD) == -1, "late completion closes fence");
        std::printf("late callback: missed counter %d (expected 8)\n", waiter.consecutiveCallbackTimeouts());
        check(waiter.consecutiveCallbackTimeouts() == 8, "late completion erased missing-callback history");
        close(fds[1]);
        auto valid = waiter.expect(9);
        waiter.complete(valid, -1);
        await([&] { return released.load() == 9; });
        check(waiter.consecutiveCallbackTimeouts() == 0, "timely callback resets failure streak");
    }
    if (which == 1) {
        interruptions = 40;
        auto begin = std::chrono::steady_clock::now();
        waiter.expect(11);
        await([&] { return released.load() == 1; });
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - begin).count();
        std::printf("40ms release deadline under EINTR: %lldms\n", (long long)elapsed);
        check(elapsed < 120, "interruptions restarted poll deadline");
    }
    waiter.stop();
    puts("PASS scanout waiter progress");
}
