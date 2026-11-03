package app.gamenative.ui.screen.wow

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.FileUtils
import java.io.File

/** DXVK-only adapter for the existing WCP staging/install pipeline. Call on IO. */
internal object WowDxvkImporter {
    private val lock = ContentsManager.getLocalInstallLock()
    private val dxvk = ContentProfile.ContentType.CONTENT_TYPE_DXVK

    fun importWcp(context: Context, uri: Uri, isSessionActive: () -> Boolean): ContentProfile = synchronized(lock) {
        check(!isSessionActive()) { "DXVK cannot be imported while a game session is active." }
        val manager = ContentsManager(context)
        var failedInstallDir: File? = null
        try {
            val fileName = if (uri.scheme == null || uri.scheme == "file") uri.lastPathSegment else {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }
            require(fileName?.endsWith(".wcp", ignoreCase = true) == true) { "Please select a .wcp file." }
            val profile = perform { manager.extraContentFile(uri, dxvk, it) }
            validateProfile(manager, profile)
            val installDir = ContentsManager.getInstallDir(context, profile)
            check(!installDir.exists()) {
                "A DXVK profile named ${profile.verName} is already installed. It was not overwritten."
            }
            check(!isSessionActive()) { "DXVK cannot be imported while a game session is active." }
            failedInstallDir = installDir
            perform { manager.finishInstallContent(profile, it) }
            failedInstallDir = null // The atomic staging move succeeded; this is now a complete component.
            WowLaunchEnvironment.invalidateDxvkCapabilities()
            manager.syncContents()
            check(manager.getProfiles(dxvk).orEmpty().any { it.verName == profile.verName && it.verCode == profile.verCode }) {
                "Imported DXVK could not be found in the component catalog."
            }
            profile
        } catch (e: ImportFailure) {
            // An ERROR_EXIST may indicate another installer won the destination race.
            if (e.reason != ContentsManager.InstallFailedReason.ERROR_EXIST) {
                failedInstallDir?.let { FileUtils.delete(it) }
            }
            throw e
        } catch (e: Exception) {
            failedInstallDir?.let { FileUtils.delete(it) }
            throw e
        } finally {
            ContentsManager.cleanTmpDir(context)
        }
    }

    private fun validateProfile(manager: ContentsManager, profile: ContentProfile) {
        require(profile.type == dxvk) { "Only DXVK WCP profiles can be imported here." }
        require(!profile.verName.isNullOrBlank() && profile.verName != "." && profile.verName != ".." &&
            profile.verName.none { it == '/' || it == '\\' || it == ',' || it == '=' || it.isISOControl() }) {
            "Invalid DXVK versionName."
        }
        require(profile.verCode >= 0) { "Invalid DXVK versionCode." }
        require(!profile.fileList.isNullOrEmpty()) { "DXVK profile must contain a non-empty files list." }
        require(manager.getUnTrustedContentFiles(profile).isEmpty()) {
            "DXVK profile contains targets outside the allowed DXVK DLL paths."
        }
    }

    private class ImportFailure(val reason: ContentsManager.InstallFailedReason, message: String) : IllegalStateException(message)

    private fun perform(operation: (ContentsManager.OnInstallFinishedCallback) -> Unit): ContentProfile {
        var result: ContentProfile? = null
        var failure: ImportFailure? = null
        operation(object : ContentsManager.OnInstallFinishedCallback {
            override fun onSucceed(profile: ContentProfile) { result = profile }
            override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                val message = e?.message ?: when (reason) {
                    ContentsManager.InstallFailedReason.ERROR_BADTAR -> "Invalid WCP archive or unsafe archive paths."
                    ContentsManager.InstallFailedReason.ERROR_NOPROFILE -> "WCP archive is missing profile.json."
                    ContentsManager.InstallFailedReason.ERROR_BADPROFILE -> "WCP profile.json is invalid."
                    ContentsManager.InstallFailedReason.ERROR_MISSINGFILES -> "WCP archive is missing a required component file."
                    ContentsManager.InstallFailedReason.ERROR_UNTRUSTPROFILE -> "WCP profile contains unsafe file paths."
                    ContentsManager.InstallFailedReason.ERROR_EXIST -> "DXVK profile already exists; it was not overwritten."
                    else -> "DXVK WCP installation failed ($reason)."
                }
                failure = ImportFailure(reason, message)
            }
        })
        failure?.let { throw it }
        return result ?: error("WCP installation returned no result.")
    }
}
