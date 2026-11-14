package app.gamenative.ui.screen.wow

import kotlinx.coroutines.CancellationException

/** Launcher-only failure boundary; the installer and saved container are unchanged. */
internal object WowBundledStartup {
    // English is reported; the launcher shows it through localizedStatus.
    private val FAILED = "Bundled component installation failed" to "A beépített komponensek telepítése nem sikerült"

    suspend fun install(onFailure: (String) -> Unit, action: suspend () -> Unit): Boolean {
        try {
            action()
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onFailure(error.message ?: FAILED.first)
            return false
        }
    }
}
