package app.gamenative.ui.screen.wow

import android.content.Context
import com.winlator.container.ContainerManager
import com.winlator.core.envvars.EnvVars
import org.json.JSONObject

/** Draft mutations are in memory; only explicit Save persists the envVars field. */
internal object WowEnvironmentEditor {
    data class Entry(val name: String, val value: String)

    /** How the launcher treats a saved variable: taken as it is, ignored (the launcher owns it), or appended to the launcher's list. */
    enum class Handling { OVERRIDE, IGNORED, APPENDED }
    fun handling(name: String): Handling = when (name) {
        in WowLaunchEnvironment.LAUNCHER_OWNED -> Handling.IGNORED
        in WowLaunchEnvironment.APPENDED_LISTS -> Handling.APPENDED
        else -> Handling.OVERRIDE
    }
    fun load(saved: String): List<Entry> = EnvVars(saved).let { env -> env.map { Entry(it, env.get(it)) } }
    fun validate(entry: Entry) {
        require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(entry.name)) { "Invalid environment variable name." }
        require('\u0000' !in entry.value) { "Environment values cannot contain NUL." }
    }
    fun add(entries: List<Entry>, entry: Entry): List<Entry> {
        validate(entry)
        require(entries.none { it.name == entry.name }) { "An environment variable with this name already exists." }
        return entries + entry
    }
    fun edit(entries: List<Entry>, original: String, entry: Entry): List<Entry> {
        validate(entry)
        require(entries.any { it.name == original }) { "Environment variable no longer exists." }
        require(entries.none { it.name != original && it.name == entry.name }) { "An environment variable with this name already exists." }
        return entries.map { if (it.name == original) entry else it }
    }
    fun delete(entries: List<Entry>, name: String) = entries.filterNot { it.name == name }
    fun serialize(entries: List<Entry>): String {
        require(entries.map { it.name }.distinct().size == entries.size) { "Duplicate environment variable names." }
        return EnvVars().apply { entries.forEach { validate(it); put(it.name, it.value) } }.toString()
    }
    fun save(context: Context, entries: List<Entry>, original: String, active: () -> Boolean): String {
        val serialized = serialize(entries)
        check(!active()) { "Environment settings cannot be saved during an active session." }
        val container = ContainerManager(context).getContainerById(WowContainerProfile.CONTAINER_ID)
            ?: error("WoW container has not been created yet")
        check(container.envVars == original) { "Saved environment changed. Close and reopen the editor before saving." }
        check(!active()) { "Environment settings cannot be saved during an active session." }
        container.envVars = serialized
        container.saveData()
        check(JSONObject(container.configFile.readText()).getString("envVars") == serialized) { "Unable to save environment variables." }
        return serialized
    }
}
