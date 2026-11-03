package app.gamenative.ui.screen.xserver

import android.view.KeyEvent

/** Owns the entire Back key sequence so its release cannot dismiss a newly opened menu. */
internal class GameBackKeyHandler {
    private var pendingDownTime: Long? = null

    fun handle(event: KeyEvent, gameRunning: Boolean, onBack: () -> Unit): Boolean {
        if (!gameRunning) {
            pendingDownTime = null
            return false
        }
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) pendingDownTime = event.downTime
            }
            KeyEvent.ACTION_UP -> {
                val completesPress = pendingDownTime == event.downTime
                pendingDownTime = null
                if (completesPress && !event.isCanceled) onBack()
            }
        }
        // Consume repeats, cancelled releases and unmatched releases as well.
        return true
    }
}
