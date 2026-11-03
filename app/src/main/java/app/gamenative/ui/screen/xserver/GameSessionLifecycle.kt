package app.gamenative.ui.screen.xserver

import java.util.concurrent.atomic.AtomicBoolean

/** Created synchronously with each screen; never reset asynchronously or reused after exit. */
internal class GameSessionLifecycle {
    private val exiting = AtomicBoolean(false)
    val isExiting: Boolean get() = exiting.get()
    fun beginExit(): Boolean = exiting.compareAndSet(false, true)

    /** Re-check after dispatch: callbacks queued before disposal must not touch the next screen. */
    fun dispatchIfActive(schedule: (Runnable) -> Unit, action: () -> Unit) {
        schedule(Runnable { if (!isExiting) action() })
    }
}
