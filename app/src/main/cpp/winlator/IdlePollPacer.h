#pragma once
#include <algorithm>
#include <cstdint>

/**
 * When the render thread looks at the frame fences while hand-back ("presentidle") tokens are in flight and nothing else wakes it.
 * Before 2026-11-12 it slept 3, 6, 12 ... 50 ms from every real wake-up, so a compositor frame that finished after ~5 ms cost two
 * or three wake-ups (each one renderMutex twice plus a fence query) and its buffer went back to the game at 9 ms. Now the first
 * look is aimed at the measured finish time: a look that already finds the frame done pulls the aim in by 1/32, a frame
 * found later moves it halfway to where it was found. A miss looks again soon (a quarter of the aim), then backs off as before.
 * All values are nanoseconds. Pure logic: tools/native-renderer/tests/run_idle_pace_regressions.py tests it on the host.
 */
class IdlePollPacer {
public:
    static constexpr int64_t kMs = 1000000;
    static constexpr int64_t kMinAim = 1 * kMs, kMaxAim = 20 * kMs, kMaxStep = 50 * kMs;
    /** After this much waiting without a real wake-up the loop stops looking (the old ~3 s limit). */
    static constexpr int64_t kGiveUpNs = 3500 * kMs;

    /** A new stretch of waiting starts (after a real wake-up). */
    void begin() { polls_ = 0; elapsed_ = 0; next_ = aim_; }
    int64_t nextWaitNs() const { return next_; }
    int64_t aimNs() const { return aim_; }
    bool givenUp() const { return waited_ >= kGiveUpNs; }
    /** A real wake-up: the give-up clock starts again. */
    void woken() { waited_ = 0; begin(); }

    /** The timed wait of nextWaitNs() ended without a real wake-up; [progress]: a finished frame handed buffers back. */
    void polled(bool progress) {
        elapsed_ += next_; waited_ += next_; ++polls_;
        if (progress) {
            aim_ = polls_ == 1 ? std::max(kMinAim, aim_ - aim_ / 32) : std::clamp(aim_ + (elapsed_ - aim_) / 2, kMinAim, kMaxAim);
            begin();
        } else {
            next_ = polls_ == 1 ? std::max(kMinAim, aim_ / 4) : std::min(next_ * 2, kMaxStep);
        }
    }

private:
    int64_t aim_ = 3 * kMs, next_ = 3 * kMs, elapsed_ = 0, waited_ = 0;
    int polls_ = 0;
};
