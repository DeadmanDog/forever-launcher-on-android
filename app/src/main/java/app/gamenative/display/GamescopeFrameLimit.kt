package app.gamenative.display

/** Keeps a requested cap across asynchronous compositor/injector startup. No Android/JNI dependencies. */
internal class GamescopeFrameLimit {
    companion object {
        /** While the app is in the background Gamescope's limiter holds the game at 15 FPS at most (XServer: PresentExtension). */
        const val BACKGROUND_FPS = 15
        fun effectiveLimit(user: Int, background: Boolean) =
            if (!background) user else if (user > 0) minOf(user, BACKGROUND_FPS) else BACKGROUND_FPS
    }
    @Volatile var pending = true
        private set
    private var requested = 0
    private var applied: Int? = null
    private var ready = false
    private var retryAfter = Long.MIN_VALUE

    @Synchronized fun beginSession() {
        pending = true
        ready = false
        applied = null
        retryAfter = Long.MIN_VALUE
    }

    @Synchronized fun request(fps: Int) {
        val next = fps.coerceIn(0, 1000)
        if (requested != next) {
            pending = applied != next
            requested = next
            retryAfter = Long.MIN_VALUE
        }
    }

    @Synchronized fun requested() = requested

    /** An error means the command was not accepted; retry later, without JNI on every frame. */
    @Synchronized fun apply(now: Long, frameAvailable: Boolean, send: (Int) -> Int) {
        if (frameAvailable) ready = true
        if (!ready || applied == requested || now < retryAfter) return
        if (send(requested) == 0) { applied = requested; pending = false }
        else retryAfter = now + 500_000_000L
    }

}
