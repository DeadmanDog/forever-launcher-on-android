package app.gamenative.ui.screen.wow

import android.content.Context
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.KeyValueSet
import org.json.JSONObject

/** Uses the existing component catalog and container format. Call disk operations on IO. */
internal object WowDxvkSelector {
    private val dxvkType = ContentProfile.ContentType.CONTENT_TYPE_DXVK

    fun identifier(profile: ContentProfile): String =
        ContentsManager.getEntryName(profile).replaceFirst("DXVK-", "dxvk-")

    private fun version(profile: ContentProfile): String = identifier(profile).removePrefix("dxvk-")

    fun installedProfiles(context: Context): List<ContentProfile> {
        val manager = ContentsManager(context)
        manager.syncContents() // A fresh local catalog; no remote metadata or downloads.
        return manager.getProfiles(dxvkType).orEmpty().filter {
            it.type == dxvkType && !it.verName.isNullOrBlank() &&
                it.remoteUrl.isNullOrBlank() &&
                ContentsManager.getInstallDir(context, it).isDirectory &&
                java.io.File(ContentsManager.getInstallDir(context, it), ContentsManager.PROFILE_NAME).isFile
        }.sortedWith(compareBy({ it.verName }, { it.verCode }))
    }

    fun currentProfile(wrapper: String, config: String, profiles: List<ContentProfile>): ContentProfile? {
        val entry = if (wrapper.equals("dxvk", ignoreCase = true)) {
            "dxvk-${KeyValueSet(config).get("version")}"
        } else wrapper
        return profiles.singleOrNull { it.type == dxvkType && identifier(it).equals(entry, ignoreCase = true) }
    }

    fun apply(context: Context, selectedId: String, isSessionActive: () -> Boolean) {
        check(!isSessionActive()) { "DXVK cannot be changed while a game session is active." }
        val profiles = installedProfiles(context) // Revalidate installation at Apply time.
        val container = ContainerManager(context).getContainerById(WowContainerProfile.CONTAINER_ID)
            ?: error("WoW container has not been created yet")
        applyToContainer(container, selectedId, profiles, isSessionActive)
    }

    internal fun applyToContainer(
        container: Container,
        selectedId: String,
        installedProfiles: List<ContentProfile>,
        isSessionActive: () -> Boolean,
    ) {
        check(container.id == WowContainerProfile.CONTAINER_ID) { "Not the WoW container." }
        check(!isSessionActive()) { "DXVK cannot be changed while a game session is active." }
        val profile = installedProfiles.singleOrNull {
            it.type == dxvkType && identifier(it) == selectedId
        } ?: error("Selected DXVK profile is not installed or is invalid. No fallback was applied.")
        require(!profile.verName.isNullOrBlank() && profile.verName.none { it == ',' || it == '=' }) {
            "Invalid DXVK profile version."
        }
        val oldWrapper = container.getDXWrapper()
        val oldConfig = container.getDXWrapperConfig()
        val newConfig = KeyValueSet(oldConfig).put("version", version(profile)).toString()
        check(!isSessionActive()) { "DXVK cannot be changed while a game session is active." }
        try {
            container.setDXWrapper(identifier(profile))
            container.setDXWrapperConfig(newConfig)
            container.saveData()
            // saveData returns void and can swallow write errors: verify persistence.
            val saved = JSONObject(container.configFile.readText())
            check(saved.getString("dxwrapper") == identifier(profile) && saved.getString("dxwrapperConfig") == newConfig) {
                "Unable to save the selected DXVK profile."
            }
        } catch (e: Exception) {
            container.setDXWrapper(oldWrapper)
            container.setDXWrapperConfig(oldConfig)
            throw e
        }
    }
}
