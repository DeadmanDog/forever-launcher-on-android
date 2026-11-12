package app.gamenative.ui.component.dialog

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.gamenative.PluviaApp
import app.gamenative.ui.screen.wow.WowVkd3dComponents
import app.gamenative.ui.screen.wow.rememberTx
import app.gamenative.ui.screen.wow.tx
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.contents.ContentProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which part of the DX12 controls to show; Settings places them on different tabs. */
internal enum class WowVkd3dPart { PROFILES, API, ALL }

@Composable
internal fun WowVkd3dSection(blocked: Boolean, onBusy: (Boolean) -> Unit,
    part: WowVkd3dPart = WowVkd3dPart.ALL, onChanged: () -> Unit = {},
    /** The saved API when the caller already knows it (Settings snapshot): shown at once instead of DX11 until the load ends. */
    initialApi: String? = null) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val t = rememberTx()
    var profiles by remember { mutableStateOf<List<ContentProfile>>(emptyList()) }
    var container by remember { mutableStateOf<Container?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var currentApi by remember { mutableStateOf(initialApi ?: WowVkd3dComponents.DX11) }
    var chosenApi by remember { mutableStateOf(initialApi ?: WowVkd3dComponents.DX11) }
    var revision by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var remove by remember { mutableStateOf<String?>(null) }
    // Picking another VKD3D asks once and then applies it (no separate Apply button).
    var confirmApply by remember { mutableStateOf<String?>(null) }
    var confirmApi by remember { mutableStateOf<String?>(null) }
    var chooseDelete by remember { mutableStateOf(false) }
    LaunchedEffect(busy) { onBusy(busy) }
    DisposableEffect(Unit) { onDispose { onBusy(false) } }
    LaunchedEffect(revision) {
        // The first load is silent: no "Working…" and no blocking of the other sections while the dialog opens (it flashed).
        val silent = revision == 0
        if (!silent) busy = true
        try {
            val snapshot = withContext(Dispatchers.IO) {
                WowVkd3dComponents.profiles(context) to ContainerManager(context).getContainerById("wow_forever")
            }
            profiles = snapshot.first; container = snapshot.second
            selected = container?.getExtra(WowVkd3dComponents.PROFILE_KEY)?.takeIf { it.isNotBlank() }
            currentApi = container?.getExtra(WowVkd3dComponents.API_KEY).orEmpty().ifBlank { WowVkd3dComponents.DX11 }
            chosenApi = currentApi.takeIf { it in setOf(WowVkd3dComponents.DX11, WowVkd3dComponents.DX12) } ?: WowVkd3dComponents.DX11
            if (chosenApi != currentApi) message = t("Invalid saved graphics API. Select DX11 to repair it.", "Érvénytelen mentett grafikus API. A javításhoz válaszd a DX11-et.")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message }
        finally { if (!silent) busy = false }
    }
    fun run(action: () -> String) {
        if (blocked || busy || PluviaApp.keepAlive) return
        busy = true; message = null
        scope.launch {
            try { message = withContext(Dispatchers.IO) { action() }; revision++; onChanged() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: t("DX12 operation failed.", "A DX12-művelet nem sikerült.") }
            finally { busy = false }
        }
    }
    val enabled = !blocked && !busy && !PluviaApp.keepAlive
    val show = { p: WowVkd3dPart -> part == WowVkd3dPart.ALL || part == p }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run { val p = WowVkd3dComponents.importWcp(context, uri) { PluviaApp.keepAlive }
            app.gamenative.ui.screen.wow.WowComponentNames.display(p.verName).let { n -> t("$n imported. Select it in the list to switch.", "$n importálva. A váltáshoz válaszd ki a listában.") } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (show(WowVkd3dPart.API)) {
            Text(tx("Graphics API", "Grafikus API"), style = MaterialTheme.typography.titleMedium)
            Text(tx("Chooses the DirectX version WoW runs on. DX11 uses DXVK, DX12 uses VKD3D; both translate to Vulkan. Switching swaps the DLLs on the next launch.",
                "Ezt a DirectX-verziót használja a WoW. A DX11 a DXVK-t, a DX12 a VKD3D-t használja; mindkettő Vulkanra fordít. A váltás a következő indításkor cseréli a DLL-eket."),
                style = MaterialTheme.typography.bodySmall)
            for ((api, label) in listOf(WowVkd3dComponents.DX11 to tx("DirectX 11 — DXVK", "DirectX 11 — DXVK"),
                    WowVkd3dComponents.DX12 to tx("DirectX 12 — VKD3D", "DirectX 12 — VKD3D"))) {
                Row(Modifier.fillMaxWidth().selectable(selected = chosenApi == api, enabled = enabled && (container != null || initialApi != null), role = Role.RadioButton, onClick = { if (api != currentApi && container != null) { chosenApi = api; confirmApi = api } }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = chosenApi == api, onClick = null, enabled = enabled && (container != null || initialApi != null)); Spacer(Modifier.width(8.dp)); Text(label, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
            confirmApi?.let { api ->
                val name = if (api == WowVkd3dComponents.DX12) "DirectX 12 (VKD3D)" else "DirectX 11 (DXVK)"
                AlertDialog(onDismissRequest = { confirmApi = null; chosenApi = currentApi },
                    title = { Text(tx("Switch graphics API?", "Váltasz grafikus API-t?")) },
                    text = { Text(tx("$name will be used from the next launch; the DLLs are swapped then.", "A következő indítástól ez lesz használatban: $name; a DLL-ek akkor cserélődnek.")) },
                    confirmButton = { TextButton(onClick = { confirmApi = null
                        run { WowVkd3dComponents.applyApi(context, api, { PluviaApp.keepAlive })
                            t("Graphics API switched. DLLs are applied on the next launch.", "Grafikus API átállítva. A DLL-ek a következő indításkor cserélődnek.") } }) { Text(tx("Switch", "Váltás")) } },
                    dismissButton = { TextButton(onClick = { confirmApi = null; chosenApi = currentApi }) { Text(tx("Cancel", "Mégse")) } })
            }
        }
        if (show(WowVkd3dPart.PROFILES)) {
            Text("VKD3D (DX12)", style = MaterialTheme.typography.titleMedium)
            Text(tx("Translates DirectX 12 to Vulkan. Used only when the graphics API is DX12.",
                "A DirectX 12-t fordítja Vulkanra. Csak akkor használatos, ha a grafikus API DX12."), style = MaterialTheme.typography.bodySmall)
            profiles.forEach { profile ->
                val id = WowVkd3dComponents.id(profile)
                Row(Modifier.fillMaxWidth().selectable(selected = selected == id, enabled = enabled, role = Role.RadioButton, onClick = {
                    selected = id
                    if (container != null && id != container?.getExtra(WowVkd3dComponents.PROFILE_KEY)) confirmApply = id
                }), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = selected == id, onClick = null, enabled = enabled)
                    Spacer(Modifier.width(8.dp))
                    val name = app.gamenative.ui.screen.wow.WowComponentNames.display(profile.verName)
                    Text(name + if (id == container?.getExtra(WowVkd3dComponents.PROFILE_KEY)) tx(" · CURRENT", " · JELENLEGI") else "", Modifier.weight(1f))
                    // The description sits behind the info button, as for the driver, Proton and DXVK.
                    app.gamenative.ui.screen.wow.WowComponentNames.description(profile.verName, profile.desc.orEmpty(), app.gamenative.ui.screen.wow.LocalWowHungarian.current, app.gamenative.ui.screen.wow.LocalWowLanguage.current)
                        .takeIf { it.isNotBlank() }?.let { WowInfoButton(name, it) }
                }
            }
            val current = container?.getExtra(WowVkd3dComponents.PROFILE_KEY)
            WowActionRow {
            TextButton(enabled = enabled, onClick = { picker.launch(arrayOf("*/*")) }) { Text(tx("Import VKD3D WCP", "VKD3D WCP importálása")) }
            TextButton(enabled = enabled && profiles.any { WowVkd3dComponents.id(it) != current }, onClick = { chooseDelete = true }) { Text(tx("Delete VKD3D", "VKD3D törlése")) }
            }
            if (chooseDelete) WowDeleteChooser(tx("Delete which VKD3D?", "Melyik VKD3D-t törlöd?"),
                profiles.filter { WowVkd3dComponents.id(it) != current }.map { WowVkd3dComponents.id(it) to app.gamenative.ui.screen.wow.WowComponentNames.display(it.verName) },
                onPick = { id -> chooseDelete = false; remove = id }, onDismiss = { chooseDelete = false })
        }
        if (busy) Text(tx("Working…", "Folyamatban…"))
        message?.let { Text(it) }
    }
    confirmApply?.let { id -> AlertDialog(onDismissRequest = { confirmApply = null; selected = container?.getExtra(WowVkd3dComponents.PROFILE_KEY)?.takeIf { it.isNotBlank() } },
        title = { Text(tx("Switch VKD3D?", "Váltasz VKD3D-t?")) },
        text = { val name = app.gamenative.ui.screen.wow.WowComponentNames.display(id); Text(tx("$name will be used from the next launch.", "A következő indítástól ez lesz használatban: $name.")) },
        confirmButton = { TextButton(onClick = { confirmApply = null; run { WowVkd3dComponents.applyProfile(context, id) { PluviaApp.keepAlive }
            t("VKD3D switched.", "VKD3D átállítva.") } }) { Text(tx("Switch", "Váltás")) } },
        dismissButton = { TextButton(onClick = { confirmApply = null; selected = container?.getExtra(WowVkd3dComponents.PROFILE_KEY)?.takeIf { it.isNotBlank() } }) { Text(tx("Cancel", "Mégse")) } }) }
    remove?.let { id -> AlertDialog(onDismissRequest = { remove = null }, title = { Text(tx("Delete VKD3D?", "Törlöd a VKD3D-t?")) }, text = { Text(app.gamenative.ui.screen.wow.WowComponentNames.display(id)) },
        confirmButton = { TextButton(onClick = { remove = null; run { WowVkd3dComponents.delete(context, id, active = { PluviaApp.keepAlive }); t("VKD3D deleted.", "VKD3D törölve.") } }) { Text(tx("Delete", "Törlés")) } },
        dismissButton = { TextButton(onClick = { remove = null }) { Text(tx("Cancel", "Mégse")) } }) }
}
