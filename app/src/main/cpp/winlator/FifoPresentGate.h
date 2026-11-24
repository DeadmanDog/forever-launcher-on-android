#pragma once
#include <algorithm>
#include <cstdint>

/**
 * FIFO present gating of the render loop, as pure functions (tools/native-renderer/tests/run_fifo_gate_regressions.py).
 * holdUntilNs: while the game presents, a cursor- or scene-only change waits for the next game frame and goes out with it; only if
 * the game is late by a refresh is it presented alone. 1.2.21: the game's next frame is due one GAME interval after the last
 * (the limit's period when that is longer than a refresh), not one refresh: with 60 FPS on 120 Hz (or 30 on 60 Hz) the hold
 * ended exactly when the game frame was due, the cursor frame went out first, and the game frame then waited the 3/4-refresh
 * spacing and missed its refresh (a repeated frame whenever the pointer moved).
 * minSpacingNs: presents are at least 3/4 of a refresh apart (a burst after a hitch must not fill the FIFO queue).
 */
struct FifoPresentGate {
    static constexpr int64_t kDefaultRefreshNs = 16666667;

    static int64_t refresh(int64_t refreshNs) { return refreshNs > 0 ? refreshNs : kDefaultRefreshNs; }

    /**
     * 1.2.26: the longest gap between two game frames the vsync-locked limiter (VsyncPacingCore) produces: the limit's interval
     * rounded UP to whole refreshes (60 FPS on 144 Hz releases 2,3,2,2,3 refreshes apart: 20.8 ms, not 16.7). A limit within 2% of
     * a whole number of refreshes is snapped by the pacer and stays that number. One refresh without a limit or above the rate.
     * With the interval itself the holds below left only 2.8 ms for the game's frame-time wobble on 144 Hz: a moving cursor's
     * frame took the vsync first and the game frame showed a refresh late (run_gate_jitter_regressions.py).
     */
    static int64_t longestGameGapNs(int64_t refreshNs, int64_t gameIntervalNs) {
        const int64_t r = refresh(refreshNs);
        if (gameIntervalNs <= r) return r;
        return (gameIntervalNs - r / 50 + r - 1) / r * r;
    }

    static int64_t holdUntilNs(int64_t lastGamePresentNs, int64_t refreshNs, int64_t gameIntervalNs) {
        const int64_t r = refresh(refreshNs);
        return lastGamePresentNs + longestGameGapNs(refreshNs, gameIntervalNs) + r;
    }

    static int64_t minSpacingNs(int64_t refreshNs) { return refresh(refreshNs) * 3 / 4; }

    /**
     * 1.2.23: how much one more queued frame adds to the time to the screen, the unit of the FIFO drain decisions: one refresh
     * when the game runs at the panel rate, one GAME frame interval when it is limited below it (60 FPS on 144 Hz: 16.7 ms, not
     * 6.9). With the refresh the "3/4 above the floor" test sat inside the wobble of a 2,3,2,2,3 refresh pattern and skipped
     * frames that were not queued (a visible stutter each). 0 or less (unknown refresh) stays as it is.
     */
    static int64_t drainStepNs(int64_t refreshNs, int64_t frameIntervalNs) {
        return refreshNs <= 0 ? refreshNs : std::max(refreshNs, frameIntervalNs);
    }

    /**
     * 1.2.23: how long a FIFO drain skip waits for the next game frame before the skipped content goes out after all (a replay:
     * the skip took nothing out). It was two refreshes: 13.9 ms on the player's 144 Hz panel while the 60 FPS limit releases
     * frames 13.9 and 20.8 ms apart (2,3,2,2,3 refreshes), so most skips there replayed, the same on 120 Hz (16.7 ms, exactly
     * the frame spacing). Now the game's frame interval plus one refresh, like holdUntilNs; at the panel rate the same as before.
     */
    static int64_t drainHoldNs(int64_t refreshNs, int64_t frameIntervalNs) {
        const int64_t r = refresh(refreshNs);
        return longestGameGapNs(refreshNs, frameIntervalNs) + r;
    }
};
