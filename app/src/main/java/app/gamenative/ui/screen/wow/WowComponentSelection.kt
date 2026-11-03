package app.gamenative.ui.screen.wow

import com.winlator.core.KeyValueSet

/** Current comes from a saved container snapshot; selection is a separate UI intent. */
internal data class WowComponentSelection(val current: String, val selected: String?) {
    fun refresh(saved: String, installed: Set<String>) = WowComponentSelection(
        saved, selected?.takeIf { it in installed } ?: saved.takeIf { it in installed },
    )
    fun select(id: String) = copy(selected = id)
    fun canApply(installed: Set<String>) = selected != null && selected in installed && selected != current
    companion object {
        fun driverCurrent(config: String, version: String): String =
            KeyValueSet(config).get("version").ifBlank { version }
    }
}

internal fun runtimeDisplayName(verName: String): String = verName
    .replace(Regex("[-_]?arm64ec", RegexOption.IGNORE_CASE), "")
    .replace(Regex("^proton[-_]?", RegexOption.IGNORE_CASE), "Proton ")
    .replace(Regex("^wine[-_]?", RegexOption.IGNORE_CASE), "Wine ")
