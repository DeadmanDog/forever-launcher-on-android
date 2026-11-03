package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.gamenative.PluviaApp
import app.gamenative.ui.screen.wow.WowDisplaySettings
import app.gamenative.ui.screen.wow.WowHardwareScale
import app.gamenative.ui.screen.wow.WowLaunchPolicy
import app.gamenative.ui.screen.wow.rememberTx
import app.gamenative.ui.screen.wow.tx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun WowDisplaySettingsSection(current: String, blocked: Boolean, onSaved: () -> Unit, onBusyChanged: (Boolean) -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val t = rememberTx()
    var selected by remember(current) { mutableStateOf(current) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tx("Display resolution", "Kijelzőfelbontás"), style = MaterialTheme.typography.titleMedium)
        Text(tx("Current virtual display: ", "Jelenlegi virtuális kijelző: ") + current.replace('x', '×'))
        Text(tx("The size WoW renders at. Lower = more FPS but a softer image. Saved for this container; apply while the game is stopped. WoW's own resolution and render scale are aligned on launch.",
            "Ekkora képet renderel a WoW. Kisebb = több FPS, de kevésbé éles kép. A konténerhez mentődik; leállított játék mellett alkalmazd. A WoW saját felbontása és render scale-je indításkor igazodik."),
            style = MaterialTheme.typography.bodySmall)
        val fullscreen = remember { WowDisplaySettings.fullscreen(context) }
        WowDisplaySettings.options(current, fullscreen).forEach { option ->
            Row(Modifier.fillMaxWidth().selectable(selected == option.value, enabled = !blocked && !saving,
                role = Role.RadioButton, onClick = { selected = option.value; message = null })) {
                RadioButton(selected == option.value, onClick = null, enabled = !blocked && !saving)
                Spacer(Modifier.width(8.dp))
                Text(if (option.fullscreen) tx("Full screen", "Teljes képernyő") + " (${option.value.replace('x', '×')})" else option.label,
                    modifier = Modifier.padding(vertical = 4.dp))
            }
        }
        TextButton(enabled = !blocked && !saving, onClick = {
            saving = true; onBusyChanged(true); message = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { WowDisplaySettings.apply(context, selected) { PluviaApp.keepAlive } }
                    message = t("Resolution saved. The next Play uses ", "Felbontás mentve. A következő indítás ezt használja: ") + selected.replace('x', '×')
                    onSaved()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { message = e.message ?: t("Unable to save display resolution.", "A felbontás nem menthető.") }
                finally { saving = false; onBusyChanged(false) }
            }
        }) { Text(if (saving) tx("Saving…", "Mentés…") else tx("Apply resolution", "Felbontás alkalmazása")) }
        message?.let { Text(it) }
        if (selected == fullscreen) {
            Text(tx("Full screen: 720 pixels high in this phone's own display aspect, so the game fills the screen without being stretched.",
                "Teljes képernyő: 720 pixel magas, a telefon kijelzőjének saját képarányában, így a játék kitölti a képernyőt, nyújtás nélkül."),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Saved launch choice; applied when the next game surface is created. */
@Composable
internal fun HardwareScaleSwitch(resolution: String, blocked: Boolean) {
    val context = LocalContext.current.applicationContext
    var enabled by remember { mutableStateOf(WowLaunchPolicy.enabled(context, WowHardwareScale.KEY)) }
    var error by remember { mutableStateOf<String?>(null) }
    val scanout = remember { WowLaunchPolicy.enabled(context, "scanout") }
    val fitsDisplay = remember(resolution) {
        val wm = context.getSystemService(android.view.WindowManager::class.java)
        val size = if (android.os.Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
            else context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        WowHardwareScale.fitsDisplay(size.first, size.second, resolution)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(tx("Hardware scaling (experimental)", "Hardveres skálázás (kísérleti)"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            WowSwitch(checked = enabled, enabled = !blocked, onCheckedChange = {
                try { WowLaunchPolicy.save(context, WowHardwareScale.KEY, it); enabled = it; error = null }
                catch (e: Exception) { error = e.message }
            })
        }
        Text(tx("The display's own scaler enlarges the game image instead of the GPU, so the GPU has a little less work. Works with the HUD and touch controls. Only active when the resolution has the display's aspect ratio (the Full screen resolution) and Screen Effects use Linear or no scaling with no effect (not FSR, DLS, Natural, Nearest or CRT). Applies from the next Play.",
            "A játék képét a GPU helyett a kijelző saját skálázója nagyítja fel, így a GPU-nak kicsit kevesebb a munkája. HUD-dal és érintéses vezérlőkkel is működik. Csak akkor hat, ha a felbontás képaránya megegyezik a kijelzőével (a Teljes képernyő felbontás), és a játék közbeni Screen Effects menüben Linear vagy nincs skálázás, effekt nélkül (nem FSR, DLS, Natural, Nearest vagy CRT). A következő indítástól érvényes."),
            style = MaterialTheme.typography.bodySmall)
        if (enabled && !fitsDisplay) Text(tx("The current resolution (${resolution.replace('x', '×')}) does not match the display's aspect ratio, so this has no effect with it.",
            "A jelenlegi felbontás (${resolution.replace('x', '×')}) képaránya eltér a kijelzőétől, ezért ezzel nem hat."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (enabled && scanout) Text(tx("Fullscreen scanout is on (Advanced tab). It already scales in hardware, so this switch has no effect while it is on.",
            "A teljes képernyős scanout be van kapcsolva (Haladó fül). Az már hardveresen skáláz, ezért addig ez a kapcsoló nem hat."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
