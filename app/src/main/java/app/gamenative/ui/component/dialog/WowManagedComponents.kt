package app.gamenative.ui.component.dialog

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import app.gamenative.ui.screen.wow.WowComponentSelection
import app.gamenative.ui.screen.wow.runtimeDisplayName
import app.gamenative.ui.screen.wow.WowComponentNames
import org.json.JSONObject
import java.io.File
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import app.gamenative.PluviaApp
import app.gamenative.ui.screen.wow.WowDeveloperComponents
import app.gamenative.ui.screen.wow.rememberTx
import app.gamenative.ui.screen.wow.tx
import com.winlator.container.ContainerManager
import com.winlator.contents.ContentsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun WowManagedComponents(active: Boolean, onChanged: () -> Unit, onBusyChanged: (Boolean) -> Unit,
                                  /** One card per kind: the GPU driver and Wine/Proton are shown separately. */
                                  kinds: List<WowDeveloperComponents.Kind> = listOf(WowDeveloperComponents.Kind.TURNIP, WowDeveloperComponents.Kind.RUNTIME)) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val t = rememberTx()
    val hungarian = app.gamenative.ui.screen.wow.LocalWowHungarian.current
    val languageCode = app.gamenative.ui.screen.wow.LocalWowLanguage.current
    var entries by remember { mutableStateOf<List<WowDeveloperComponents.Entry>>(emptyList()) }
    var driverState by remember { mutableStateOf(WowComponentSelection("", null)) }
    var runtimeState by remember { mutableStateOf(WowComponentSelection("", null)) }
    var labels by remember { mutableStateOf<Map<String, Pair<String, String>>>(emptyMap()) }
    suspend fun refresh() {
        val snapshot = withContext(Dispatchers.IO) {
            val c = ContainerManager(context).getContainerById("wow_forever") ?: error(t("WoW container has not been created yet", "A WoW konténer még nem jött létre"))
            val rawDrivers = WowDeveloperComponents.drivers(context)
            val driverMetadata = rawDrivers.associate { entry -> entry.id to JSONObject(File(entry.directory, "meta.json").readText()) }
            val drivers = rawDrivers.map { it.copy(description = driverMetadata.getValue(it.id).optString("description")) }
            val runtimes = WowDeveloperComponents.runtimeProfiles(context)
            val all = drivers + runtimes.map {
                WowDeveloperComponents.Entry(WowDeveloperComponents.Kind.RUNTIME, WowDeveloperComponents.runtimeId(it), it.desc.orEmpty(), ContentsManager.getInstallDir(context, it))
            }
            val names = drivers.associate { e ->
                val meta = driverMetadata.getValue(e.id)
                e.id to (WowComponentNames.display(meta.optString("name", e.id)) to WowComponentNames.description(meta.optString("name", e.id), meta.optString("driverVersion", e.description), hungarian, languageCode))
            } + runtimes.associate { p ->
                WowDeveloperComponents.runtimeId(p) to (WowComponentNames.display(p.verName).let { if (it == p.verName) runtimeDisplayName(it) else it } to WowComponentNames.description(p.verName, "ARM64EC", hungarian, languageCode))
            }
            Pair(all, Triple(WowComponentSelection.driverCurrent(c.graphicsDriverConfig, c.graphicsDriverVersion), c.wineVersion, names))
        }
        entries = snapshot.first
        labels = snapshot.second.third
        driverState = driverState.refresh(snapshot.second.first, entries.filter { it.kind == WowDeveloperComponents.Kind.TURNIP }.map { it.id }.toSet())
        runtimeState = runtimeState.refresh(snapshot.second.second, entries.filter { it.kind == WowDeveloperComponents.Kind.RUNTIME }.map { it.id }.toSet())
    }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<WowDeveloperComponents.Entry?>(null) }
    // Selecting another driver/runtime asks once and applies it; no Apply button.
    var pendingApply by remember { mutableStateOf<WowDeveloperComponents.Entry?>(null) }
    var deleteFrom by remember { mutableStateOf<WowDeveloperComponents.Kind?>(null) }
    var importRuntime by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        try { refresh() } catch (e: Exception) { message = e.message }
    }
    fun operation(block: () -> Unit) {
        busy = true; onBusyChanged(true); message = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { check(!PluviaApp.keepAlive) { t("Active game session.", "A játék fut.") }; block() }
                refresh(); message = t("Operation completed.", "Kész."); onChanged()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: t("Operation failed.", "A művelet nem sikerült.") }
            finally { busy = false; onBusyChanged(false) }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        busy = false; onBusyChanged(false)
        if (uri != null) operation {
            if (importRuntime) WowDeveloperComponents.importRuntime(context, uri) else WowDeveloperComponents.importDriver(context, uri)
        }
    }
    for (kind in kinds) {
        val isDriver = kind == WowDeveloperComponents.Kind.TURNIP
        val state = if (isDriver) driverState else runtimeState
        val selected = state.selected
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (isDriver) tx("GPU driver (Turnip)", "GPU driver (Turnip)") else tx("Wine / Proton", "Wine / Proton"), style = MaterialTheme.typography.titleMedium)
            Text(if (isDriver) tx("The Vulkan driver the game draws with. Its GPU mode is on the Quick tab.",
                    "A Vulkan driver, amivel a játék rajzol. A GPU-módja a Gyors fülön állítható.")
                else tx("Runs the Windows game. The bundled Proton is recommended. Switching migrates the prefix (can take a minute); game files and settings are kept.",
                    "Ez futtatja a Windowsos játékot. A beépített Proton az ajánlott. Váltáskor a prefix átalakul (egy percig is tarthat); a játékfájlok és a beállítások megmaradnak."),
                style = MaterialTheme.typography.bodySmall)
            val group = entries.filter { it.kind == kind }
            if (group.isEmpty()) Text(tx("No compatible installed components.", "Nincs kompatibilis telepített komponens."))
            if (state.current.isNotBlank() && group.none { it.id == state.current }) Text(tx("Current value does not match an installed compatible component.", "A jelenlegi érték nem egyezik egyik telepített komponenssel sem."))
            group.forEach { e ->
                Row(Modifier.fillMaxWidth().selectable(selected = selected == e.id, enabled = !busy && !active,
                    role = Role.RadioButton, onClick = {
                        message = null
                        if (e.id != state.current) {
                            if (isDriver) driverState = driverState.select(e.id) else runtimeState = runtimeState.select(e.id)
                            pendingApply = e
                        }
                    }), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == e.id, onClick = null, enabled = !busy && !active)
                    Spacer(Modifier.width(8.dp))
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text("${labels[e.id]?.first ?: e.id}${if (state.current == e.id) tx(" · CURRENT", " · JELENLEGI") else ""}", Modifier.weight(1f))
                        // Version line, ID and the (long) release notes live behind the info button.
                        val bundled = WowComponentNames.isBundled(labels[e.id]?.first.orEmpty())
                        val details = listOfNotNull(labels[e.id]?.second, "ID: ${e.id}", e.description.takeIf { it.isNotBlank() && !bundled }).joinToString("\n\n")
                        WowInfoButton(labels[e.id]?.first ?: e.id, details)
                    }
                }
            }
            WowActionRow {
            TextButton(enabled = !busy && !active, onClick = {
                importRuntime = !isDriver; busy = true; onBusyChanged(true)
                try { picker.launch(arrayOf("*/*")) } catch (e: Exception) { busy = false; onBusyChanged(false); message = e.message }
            }) { Text(if (isDriver) tx("Import driver", "Driver importálása") else tx("Import Wine/Proton WCP", "Wine/Proton WCP importálása")) }
            TextButton(enabled = !busy && !active && group.any { it.id != state.current }, onClick = { deleteFrom = kind }) {
                Text(if (isDriver) tx("Delete driver", "Driver törlése") else tx("Delete runtime", "Runtime törlése"))
            }
            }
        }
    }
    if (busy) CircularProgressIndicator()
    message?.let { Text(it) }
    fun revert(entry: WowDeveloperComponents.Entry) {
        if (entry.kind == WowDeveloperComponents.Kind.TURNIP) driverState = driverState.select(driverState.current)
        else runtimeState = runtimeState.select(runtimeState.current)
    }
    pendingApply?.let { entry ->
        val isDriver = entry.kind == WowDeveloperComponents.Kind.TURNIP
        val name = labels[entry.id]?.first ?: entry.id
        AlertDialog(onDismissRequest = { pendingApply = null; revert(entry) },
            title = { Text(if (isDriver) tx("Switch GPU driver?", "Váltasz GPU drivert?") else tx("Switch Wine / Proton?", "Váltasz Wine / Protont?")) },
            text = { Text(if (isDriver) tx("$name will be used from the next launch.", "A következő indítástól ez lesz használatban: $name.")
                else tx("$name will be used from the next launch. The prefix is migrated now (can take a minute); game files and settings are kept.",
                    "A következő indítástól ez lesz használatban: $name. A prefix most átalakul (egy percig is tarthat); a játékfájlok és a beállítások megmaradnak.")) },
            confirmButton = { TextButton(onClick = {
                pendingApply = null
                operation {
                    val c = ContainerManager(context).getContainerById("wow_forever") ?: error(t("Container missing.", "A konténer hiányzik."))
                    if (isDriver) WowDeveloperComponents.applyDriver(c, entry) else WowDeveloperComponents.applyRuntime(context, c, entry.id)
                }
            }) { Text(tx("Switch", "Váltás")) } },
            dismissButton = { TextButton(onClick = { pendingApply = null; revert(entry) }) { Text(tx("Cancel", "Mégse")) } })
    }
    deleteFrom?.let { kind ->
        val state = if (kind == WowDeveloperComponents.Kind.TURNIP) driverState else runtimeState
        WowDeleteChooser(if (kind == WowDeveloperComponents.Kind.TURNIP) tx("Delete which driver?", "Melyik drivert törlöd?") else tx("Delete which runtime?", "Melyik runtime-ot törlöd?"),
            entries.filter { it.kind == kind && it.id != state.current }.map { it.id to (labels[it.id]?.first ?: it.id) },
            onPick = { id -> deleteFrom = null; pendingDelete = entries.singleOrNull { it.kind == kind && it.id == id } }, onDismiss = { deleteFrom = null })
    }
    pendingDelete?.let { entry ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text(tx("Delete component?", "Törlöd a komponenst?")) }, text = { Text(labels[entry.id]?.first ?: entry.id) },
            confirmButton = { TextButton(onClick = { pendingDelete = null; operation { WowDeveloperComponents.delete(context, entry) } }) { Text(tx("Delete", "Törlés")) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(tx("Cancel", "Mégse")) } })
    }
}
