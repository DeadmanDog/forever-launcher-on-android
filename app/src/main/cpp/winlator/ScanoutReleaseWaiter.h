#pragma once
// Hands a scanout Present buffer back to its client only when SurfaceFlinger no longer shows it.
//
// The game layer shows buffer k until buffer k+1 replaces it, and the display may still read k until the release fence of that
// transaction signals. So the token of k is registered when k+1 is set (expect), the transaction's completion callback brings
// the fence (complete), and this thread waits for the fence without blocking the X thread or the binder thread that runs the
// callback. Both waits have a deadline: a completion that never comes, or a fence that never signals, still frees the buffer
// (late rather than never), so a client can not hang on a missing callback.
#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <fcntl.h>
#include <functional>
#include <mutex>
#include <poll.h>
#include <thread>
#include <unistd.h>
#include <vector>

class ScanoutReleaseWaiter {
public:
    using Sink = std::function<void(int64_t)>;

    ScanoutReleaseWaiter(Sink sink, int64_t callbackTimeoutNs, int64_t fenceTimeoutNs)
        : sink_(std::move(sink)), callbackTimeoutNs_(callbackTimeoutNs), fenceTimeoutNs_(fenceTimeoutNs) {}
    ~ScanoutReleaseWaiter() { stop(); }
    ScanoutReleaseWaiter(const ScanoutReleaseWaiter&) = delete;
    ScanoutReleaseWaiter& operator=(const ScanoutReleaseWaiter&) = delete;

    /** Registers [token] as waiting for its transaction's completion. Returns its id, or 0 when it cannot be tracked (stopped, no
     *  thread, out of memory): the caller then hands the token back itself. */
    uint64_t expect(int64_t token) {
        std::lock_guard<std::mutex> lk(mutex_);
        if (stopped_ || token == 0 || !startLocked()) return 0;
        try {
            Entry e; e.id = ++nextId_; e.token = token; e.fd = -1; e.deadlineNs = nowNs() + callbackTimeoutNs_;
            entries_.push_back(e);
            wakeLocked();
            return e.id;
        } catch (...) { return 0; }
    }

    /** The completion of [id] arrived. [fenceFd] (owned by this call; -1 = none) signals when the buffer is free. */
    void complete(uint64_t id, int fenceFd) {
        std::lock_guard<std::mutex> lk(mutex_);
        consecutiveCallbackTimeouts_.store(0, std::memory_order_relaxed);
        for (auto& e : entries_) {
            if (e.id != id || e.completed) continue;
            e.completed = true;
            e.fd = fenceFd;
            e.deadlineNs = nowNs() + fenceTimeoutNs_;
            wakeLocked();
            return;
        }
        // Unknown: already released by its deadline, or the waiter was stopped. Only the descriptor is left to close.
        if (fenceFd >= 0) close(fenceFd);
    }

    /** Stops the thread; tokens still waiting are handed back now (their layer is gone with the renderer). Later complete() calls
     *  only close their descriptor. */
    void stop() {
        std::vector<Entry> left;
        std::thread joinable;
        {
            std::lock_guard<std::mutex> lk(mutex_);
            if (stopped_) return;
            stopped_ = true;
            wakeLocked();
            joinable.swap(thread_);
        }
        if (joinable.joinable()) {
            if (joinable.get_id() == std::this_thread::get_id()) joinable.detach();
            else joinable.join();
        }
        {
            std::lock_guard<std::mutex> lk(mutex_);
            left.swap(entries_);
            if (wakeRead_ >= 0) { close(wakeRead_); wakeRead_ = -1; }
            if (wakeWrite_ >= 0) { close(wakeWrite_); wakeWrite_ = -1; }
        }
        for (auto& e : left) {
            if (e.fd >= 0) close(e.fd);
            if (sink_) sink_(e.token);
        }
    }

    /** Completions that did not come in time, in a row (reset by every completion that does come). */
    int consecutiveCallbackTimeouts() const { return consecutiveCallbackTimeouts_.load(std::memory_order_relaxed); }
    size_t pending() { std::lock_guard<std::mutex> lk(mutex_); return entries_.size(); }

private:
    struct Entry { uint64_t id = 0; int64_t token = 0; int fd = -1; int64_t deadlineNs = 0; bool completed = false; };

    static int64_t nowNs() {
        return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
    }

    bool startLocked() {
        if (thread_.joinable()) return true;
        if (startFailed_) return false;
        int fds[2];
        if (pipe(fds) != 0) { startFailed_ = true; return false; }
        for (int fd : fds) {
            fcntl(fd, F_SETFD, FD_CLOEXEC);
            fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);
        }
        wakeRead_ = fds[0]; wakeWrite_ = fds[1];
        try { thread_ = std::thread(&ScanoutReleaseWaiter::run, this); }
        catch (...) {
            close(wakeRead_); close(wakeWrite_); wakeRead_ = wakeWrite_ = -1;
            startFailed_ = true;
            return false;
        }
        return true;
    }

    void wakeLocked() {
        if (wakeWrite_ < 0) return;
        const char one = 1;
        ssize_t ignored = write(wakeWrite_, &one, 1);   // a full pipe already wakes the thread
        (void)ignored;
    }

    void run() {
        std::vector<pollfd> fds;
        std::vector<uint64_t> ids;
        std::vector<int64_t> ready;
        for (;;) {
            int timeoutMs = -1;
            {
                std::unique_lock<std::mutex> lk(mutex_);
                if (stopped_) return;
                fds.clear(); ids.clear();
                fds.push_back(pollfd{wakeRead_, POLLIN, 0}); ids.push_back(0);
                const int64_t now = nowNs();
                for (const auto& e : entries_) {
                    const int64_t left = e.deadlineNs - now;
                    const int ms = left <= 0 ? 0 : (int)std::min<int64_t>((left + 999999) / 1000000, 60000);
                    if (timeoutMs < 0 || ms < timeoutMs) timeoutMs = ms;
                    if (e.fd >= 0) { fds.push_back(pollfd{e.fd, POLLIN, 0}); ids.push_back(e.id); }
                }
            }
            int n;
            do { n = poll(fds.data(), (nfds_t)fds.size(), timeoutMs); } while (n < 0 && errno == EINTR);
            ready.clear();
            {
                std::lock_guard<std::mutex> lk(mutex_);
                if (stopped_) return;
                if (fds[0].revents) { char buf[64]; while (read(wakeRead_, buf, sizeof buf) > 0) {} }
                const int64_t now = nowNs();
                for (size_t i = 0; i < entries_.size();) {
                    Entry& e = entries_[i];
                    bool signalled = false;
                    if (e.fd >= 0)
                        for (size_t k = 1; k < fds.size(); ++k)
                            if (ids[k] == e.id && fds[k].fd == e.fd && fds[k].revents) { signalled = true; break; }
                    const bool noFence = e.completed && e.fd < 0;
                    const bool expired = now >= e.deadlineNs;
                    if (signalled || noFence || expired) {
                        if (expired && !e.completed) consecutiveCallbackTimeouts_.fetch_add(1, std::memory_order_relaxed);
                        if (e.fd >= 0) close(e.fd);
                        try { ready.push_back(e.token); } catch (...) {}
                        entries_.erase(entries_.begin() + (long)i);
                    } else {
                        ++i;
                    }
                }
            }
            for (int64_t token : ready) if (sink_) sink_(token);
        }
    }

    Sink sink_;
    const int64_t callbackTimeoutNs_, fenceTimeoutNs_;
    std::mutex mutex_;
    std::vector<Entry> entries_;
    std::thread thread_;
    uint64_t nextId_ = 0;
    int wakeRead_ = -1, wakeWrite_ = -1;
    bool stopped_ = false, startFailed_ = false;
    std::atomic<int> consecutiveCallbackTimeouts_{0};
};
