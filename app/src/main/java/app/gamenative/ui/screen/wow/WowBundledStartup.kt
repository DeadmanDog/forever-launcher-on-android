package app.gamenative.ui.screen.wow

import kotlinx.coroutines.CancellationException

/** Launcher-only failure boundary; the installer and saved container are unchanged. */
internal object WowBundledStartup {
    suspend fun install(onFailure: (String) -> Unit, action: suspend () -> Unit): Boolean {
        try {
            action()
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onFailure(error.message ?: "Bundled component installation failed")
            return false
        }
    }
}
