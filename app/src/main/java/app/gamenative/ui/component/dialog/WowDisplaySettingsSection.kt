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
import androidx.compose.ui.draw.alpha
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
        WowDisplayBackendSection(blocked || saving, onBusyChanged, onSaved)
        Text(tx("Display resolution", "Kijelzőfelbontás"), style = MaterialTheme.typography.titleMedium)
        Text(tx("Current virtual display: ", "Jelenlegi virtuális kijelző: ") + current.replace('x', '×'))
        Text(tx("The size WoW renders at. Lower = more FPS but a softer image. Saved for this container; apply while the game is stopped. WoW's own resolution is aligned on launch; its render scale stays as you set it in the game (kept separately for DX11 and DX12).",
            "Ekkora képet renderel a WoW. Kisebb = több FPS, de kevésbé éles kép. A konténerhez mentődik; leállított játék mellett alkalmazd. A WoW saját felbontása indításkor igazodik; a render scale úgy marad, ahogy a játékban beállítod (DX11-hez és DX12-höz külön megjegyezve)."),
            style = MaterialTheme.typography.bodySmall)
        val fullscreen = remember { WowDisplaySettings.fullscreen(context) }
        WowDisplaySettings.options(current, fullscreen).forEach { option ->
            Row(Modifier.fillMaxWidth().selectable(selected == option.value, enabled = !blocked && !saving,
                role = Role.RadioButton, onClick = { selected = option.value; message = null }), verticalAlignment = Alignment.CenterVertically) {
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

/** Saved launch choice; applied when the next game surface is created. 1.3.2: hardware scaling and fullscreen scanout in one. */
@Composable
internal fun HardwareScaleSwitch(resolution: String, blocked: Boolean, gamescope: Boolean) {
    val context = LocalContext.current.applicationContext
    var mode by remember { mutableStateOf(WowLaunchPolicy.displayOutput(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    val fitsDisplay = remember(resolution) {
        val wm = context.getSystemService(android.view.WindowManager::class.java)
        val size = if (android.os.Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
            else context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        WowHardwareScale.fitsDisplay(size.first, size.second, resolution)
    }
    Column(Modifier.alpha(if (gamescope) 0.45f else 1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tx("Game image output", "A játék képének megjelenítése"), style = MaterialTheme.typography.titleSmall)
        if (gamescope) Text(xserverOnly(), style = MaterialTheme.typography.labelMedium)
        Text(tx("How the game image reaches the display. Hardware scaling (default): the app puts the game, the HUD and the touch controls together and the display's own scaler enlarges the image, so the GPU has a little less work; only active when the resolution has the display's aspect ratio (the Full screen resolution) and Screen Effects use Linear or no scaling with no effect (not FSR, DLS, Natural, Nearest or CRT). App compositing: the app's GPU pass also does the scaling. Fullscreen scanout: the game image goes straight to the display without the app's GPU pass and is scaled in hardware; works with the performance HUD, but the touch controls and the quick menu must be hidden (while they show, the app composites). Applies from the next Play.",
            "Hogyan jut a játék képe a kijelzőre. Hardveres skálázás (alapértelmezett): az app rakja össze a játékot, a HUD-ot és az érintéses vezérlőket, a képet pedig a kijelző saját skálázója nagyítja fel, így a GPU-nak kicsit kevesebb a munkája; csak akkor hat, ha a felbontás képaránya megegyezik a kijelzőével (a Teljes képernyő felbontás), és a játék közbeni Screen Effects menüben Linear vagy nincs skálázás, effekt nélkül (nem FSR, DLS, Natural, Nearest vagy CRT). App általi összerakás: a nagyítást is az app GPU-s lépése végzi. Teljes képernyős scanout: a játék képe az app GPU-s lépése nélkül, közvetlenül a kijelzőre kerül, hardveres nagyítással; teljesítmény-HUD-dal működik, de az érintéses vezérlőknek és a gyorsmenünek rejtve kell lenniük (amíg látszanak, az app rakja össze a képet). A következő indítástól él."),
            style = MaterialTheme.typography.bodySmall)
        for (option in WowLaunchPolicy.DisplayOutput.entries) {
            Row(Modifier.fillMaxWidth().selectable(selected = option == mode, enabled = !blocked && !gamescope, role = Role.RadioButton, onClick = {
                try { WowLaunchPolicy.saveDisplayOutput(context, option); mode = option; error = null }
                catch (e: Exception) { error = e.message }
            }), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = option == mode, onClick = null, enabled = !blocked && !gamescope)
                Spacer(Modifier.width(8.dp))
                Text(when (option) {
                    WowLaunchPolicy.DisplayOutput.HARDWARE_SCALE -> tx("Hardware scaling (default)", "Hardveres skálázás (alapértelmezett)")
                    WowLaunchPolicy.DisplayOutput.COMPOSITOR -> tx("App compositing", "App általi összerakás")
                    WowLaunchPolicy.DisplayOutput.SCANOUT -> tx("Fullscreen scanout (touch controls hidden)", "Teljes képernyős scanout (érintéses vezérlők elrejtve)")
                })
            }
        }
        if (!gamescope && mode == WowLaunchPolicy.DisplayOutput.HARDWARE_SCALE && !fitsDisplay) Text(tx("The current resolution (${resolution.replace('x', '×')}) does not match the display's aspect ratio, so this has no effect with it.",
            "A jelenlegi felbontás (${resolution.replace('x', '×')}) képaránya eltér a kijelzőétől, ezért ezzel nem hat."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
