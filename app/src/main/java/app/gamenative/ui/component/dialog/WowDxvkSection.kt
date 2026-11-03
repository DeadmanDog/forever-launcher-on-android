package app.gamenative.ui.component.dialog

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.gamenative.PluviaApp
import app.gamenative.ui.screen.wow.WowDeveloperComponents
import app.gamenative.ui.screen.wow.WowDxvkImporter
import app.gamenative.ui.screen.wow.WowDxvkSelector
import app.gamenative.ui.screen.wow.rememberTx
import app.gamenative.ui.screen.wow.tx
import com.winlator.container.ContainerManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.KeyValueSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** DXVK list/import/apply/delete. Import installs only; Apply writes the container. */
@Composable
internal fun WowDxvkSection(blocked: Boolean, onBusy: (Boolean) -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val t = rememberTx()
    var profiles by remember { mutableStateOf<List<ContentProfile>>(emptyList()) }
    var currentWrapper by remember { mutableStateOf("") }
    var currentVersion by remember { mutableStateOf("") }
    var currentId by remember { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var confirmDxvk by remember { mutableStateOf<String?>(null) }
    var chooseDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableStateOf(0) }
    var deleteDxvk by remember { mutableStateOf<ContentProfile?>(null) }
    LaunchedEffect(busy) { onBusy(busy) }
    DisposableEffect(Unit) { onDispose { onBusy(false) } }
    LaunchedEffect(revision) {
        try {
            val snapshot = withContext(Dispatchers.IO) {
                val c = ContainerManager(context).getContainerById("wow_forever")
                Triple(WowDxvkSelector.installedProfiles(context), c?.getDXWrapper().orEmpty(), KeyValueSet(c?.getDXWrapperConfig().orEmpty()).get("version"))
            }
            profiles = snapshot.first; currentWrapper = snapshot.second; currentVersion = snapshot.third
            currentId = WowDxvkSelector.currentProfile(currentWrapper, "version=$currentVersion", profiles)?.let(WowDxvkSelector::identifier)
            if (selectedId == null || profiles.none { WowDxvkSelector.identifier(it) == selectedId }) selectedId = currentId
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message }
    }
    fun run(action: () -> String) {
        if (blocked || busy || PluviaApp.keepAlive) return
        busy = true; message = null
        scope.launch {
            try { message = withContext(Dispatchers.IO) { action() }; revision++; onChanged() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: t("DXVK operation failed.", "A DXVK-művelet nem sikerült.") }
            finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run {
            val profile = WowDxvkImporter.importWcp(context, uri) { PluviaApp.keepAlive }
            t("${app.gamenative.ui.screen.wow.WowComponentNames.display(profile.verName)} imported. Select it in the list to switch.", "${app.gamenative.ui.screen.wow.WowComponentNames.display(profile.verName)} importálva. A váltáshoz válaszd ki a listában.")
        }
    }
    val enabled = !blocked && !busy && !PluviaApp.keepAlive
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("DXVK (DX11)", style = MaterialTheme.typography.titleMedium)
        Text(tx("Translates DirectX 11 to Vulkan. The bundled DXVK 3.1.1 is recommended and selected by default.",
            "A DirectX 11-et fordítja Vulkanra. A beépített DXVK 3.1.1 az ajánlott, és alapból ez van kiválasztva."), style = MaterialTheme.typography.bodySmall)
        if (currentId == null && currentWrapper.isNotBlank()) Text(tx("Current value does not match an installed DXVK profile.", "A jelenlegi érték nem egyezik egyik telepített DXVK-profillal sem."))
        if (profiles.isEmpty()) Text(tx("No installed DXVK profiles found.", "Nincs telepített DXVK-profil."))
        profiles.forEach { profile ->
            val id = WowDxvkSelector.identifier(profile)
            Row(Modifier.fillMaxWidth().selectable(selected = selectedId == id, enabled = enabled, role = Role.RadioButton,
                onClick = { message = null; if (id != currentId) { selectedId = id; confirmDxvk = id } }),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selectedId == id, onClick = null, enabled = enabled)
                Spacer(Modifier.width(8.dp))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(app.gamenative.ui.screen.wow.WowComponentNames.display(profile.verName) + if (id == currentId) tx(" · CURRENT", " · JELENLEGI") else "")
                        if (!app.gamenative.ui.screen.wow.WowComponentNames.isBundled(app.gamenative.ui.screen.wow.WowComponentNames.display(profile.verName))) Text("versionCode: ${profile.verCode}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!profile.desc.isNullOrBlank()) WowInfoButton(app.gamenative.ui.screen.wow.WowComponentNames.display(profile.verName), app.gamenative.ui.screen.wow.WowComponentNames.description(profile.verName, profile.desc.orEmpty(), app.gamenative.ui.screen.wow.LocalWowHungarian.current, app.gamenative.ui.screen.wow.LocalWowLanguage.current))
                }
            }
        }
        WowActionRow {
        TextButton(enabled = enabled, onClick = {
            try { picker.launch(arrayOf("*/*")) } catch (e: Exception) { message = e.message }
        }) { Text(tx("Import DXVK WCP", "DXVK WCP importálása")) }
        TextButton(enabled = enabled && profiles.any { WowDxvkSelector.identifier(it) != currentId }, onClick = { chooseDelete = true }) { Text(tx("Delete DXVK", "DXVK törlése")) }
        }
        confirmDxvk?.let { id ->
            val name = profiles.singleOrNull { WowDxvkSelector.identifier(it) == id }?.let { app.gamenative.ui.screen.wow.WowComponentNames.display(it.verName) } ?: id
            AlertDialog(onDismissRequest = { confirmDxvk = null; selectedId = currentId },
                title = { Text(tx("Switch DXVK?", "Váltasz DXVK-t?")) },
                text = { Text(tx("$name will be used from the next launch.", "A következő indítástól ez lesz használatban: $name.")) },
                confirmButton = { TextButton(onClick = { confirmDxvk = null
                    run { WowDxvkSelector.apply(context, id) { PluviaApp.keepAlive }; t("DXVK switched.", "DXVK átállítva.") } }) { Text(tx("Switch", "Váltás")) } },
                dismissButton = { TextButton(onClick = { confirmDxvk = null; selectedId = currentId }) { Text(tx("Cancel", "Mégse")) } })
        }
        if (chooseDelete) WowDeleteChooser(tx("Delete which DXVK?", "Melyik DXVK-t törlöd?"),
            profiles.filter { WowDxvkSelector.identifier(it) != currentId }.map { WowDxvkSelector.identifier(it) to app.gamenative.ui.screen.wow.WowComponentNames.display(it.verName) },
            onPick = { id -> chooseDelete = false; deleteDxvk = profiles.singleOrNull { WowDxvkSelector.identifier(it) == id } }, onDismiss = { chooseDelete = false })
        if (busy) Text(tx("Working…", "Folyamatban…"))
        message?.let { Text(it) }
    }
    deleteDxvk?.let { profile ->
        AlertDialog(onDismissRequest = { deleteDxvk = null }, title = { Text(tx("Delete DXVK?", "Törlöd a DXVK-t?")) }, text = { Text(app.gamenative.ui.screen.wow.WowComponentNames.display(profile.verName)) },
            confirmButton = { TextButton(onClick = {
                deleteDxvk = null
                run {
                    WowDeveloperComponents.delete(context, WowDeveloperComponents.Entry(WowDeveloperComponents.Kind.DXVK,
                        WowDxvkSelector.identifier(profile), profile.desc.orEmpty(), ContentsManager.getInstallDir(context, profile)))
                    t("DXVK deleted.", "DXVK törölve.")
                }
            }) { Text(tx("Delete", "Törlés")) } }, dismissButton = { TextButton(onClick = { deleteDxvk = null }) { Text(tx("Cancel", "Mégse")) } })
    }
}
