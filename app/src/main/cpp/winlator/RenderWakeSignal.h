#pragma once

#include <chrono>
#include <condition_variable>
#include <mutex>

// State may be published atomically before notify(). Taking the wait mutex
// orders notification after a concurrent predicate check has entered wait.
// Atomic state alone cannot close that check-to-sleep window.
class RenderWakeSignal {
public:
    template <typename Predicate>
    void wait(Predicate predicate) {
        std::unique_lock<std::mutex> lock(mutex_);
        condition_.wait(lock, predicate);
    }

    // Like wait(), but gives up after [timeout]: returns whether the predicate held. Used only while something (a Present
    // buffer hand-back) must be checked without any other event to wake the thread; otherwise the thread sleeps for good.
    template <typename Rep, typename Period, typename Predicate>
    bool waitFor(std::chrono::duration<Rep, Period> timeout, Predicate predicate) {
        std::unique_lock<std::mutex> lock(mutex_);
        return condition_.wait_for(lock, timeout, predicate);
    }

    void notify() {
        std::lock_guard<std::mutex> lock(mutex_);
        condition_.notify_one();
    }

private:
    std::mutex mutex_;
    std::condition_variable condition_;
};
