package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.gamenative.ui.screen.wow.tx
import app.gamenative.display.WowDisplayBackend
import com.winlator.container.ContainerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun WowDisplayBackendSection(blocked: Boolean, onBusyChanged: (Boolean) -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(WowDisplayBackend.XSERVER) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        current = withContext(Dispatchers.IO) { ContainerManager(context).getContainerById("wow_forever")?.getExtra(WowDisplayBackend.KEY, WowDisplayBackend.XSERVER) }
        selected = current ?: WowDisplayBackend.XSERVER
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tx("Display backend", "Megjelenítési háttérrendszer"), style = MaterialTheme.typography.titleMedium)
        for ((id,label) in listOf(WowDisplayBackend.XSERVER to "XServer", WowDisplayBackend.GAMESCOPE to tx("Gamescope (experimental)", "Gamescope (kísérleti)"))) {
            // 1.1.8: the whole row picks, the dot sits level with its label (it hung above the text).
            val enabled = !blocked && !busy && current != null
            Row(Modifier.fillMaxWidth().selectable(selected = selected == id, enabled = enabled, role = Role.RadioButton, onClick = { selected = id }),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected == id, onClick = null, enabled = enabled)
                Spacer(Modifier.width(8.dp))
                Text(label, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
        Text(tx("Gamescope uses its own Xwayland. Apply while WoW is stopped; used on the next Play. Existing Wine, DXVK and driver selections are retained.", "A Gamescope saját Xwaylandet használ. Leállított játék mellett alkalmazd; a következő indításkor lép életbe. A Wine, DXVK és driver választása megmarad."), style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = !blocked && !busy && current != null && selected != current, onClick = {
            busy = true; onBusyChanged(true); error = null
            scope.launch {
                try { withContext(Dispatchers.IO) { WowDisplayBackend.apply(context,selected) }; current = selected; onSaved() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "Display backend preparation failed" }
                finally { busy = false; onBusyChanged(false) }
            }
        }) { Text(if (busy) tx("Preparing…", "Előkészítés…") else tx("Apply display backend", "Megjelenítés alkalmazása")) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
    }
}
