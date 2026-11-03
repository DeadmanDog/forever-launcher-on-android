#pragma once

#include <utility>

// Acquire semaphores must be consumed if recording/submission exits early.
// The callback owns recovery; the guard adds no allocation on the frame path.
template <typename Recovery>
class AcquiredFrameGuard {
public:
    explicit AcquiredFrameGuard(Recovery recovery) : recovery_(std::move(recovery)) {}
    AcquiredFrameGuard(const AcquiredFrameGuard&) = delete;
    AcquiredFrameGuard& operator=(const AcquiredFrameGuard&) = delete;
    ~AcquiredFrameGuard() noexcept { if (armed_) recovery_(); }

    void arm() noexcept { armed_ = true; }
    void submitted() noexcept { armed_ = false; }

private:
    Recovery recovery_;
    bool armed_ = false;
};
