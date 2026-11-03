package app.gamenative.ui.component.dialog

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.gamenative.PluviaApp
import app.gamenative.ui.screen.wow.WowVoiceChat
import app.gamenative.ui.screen.wow.rememberTx
import app.gamenative.ui.screen.wow.tx
import kotlinx.coroutines.*

@Composable
internal fun WowVoiceChatSection(blocked: Boolean, onBusy: (Boolean) -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val t = rememberTx()
    var status by remember { mutableStateOf<WowVoiceChat.Status?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var permissionPending by remember { mutableStateOf(false) }
    var confirmRepair by remember { mutableStateOf(false) }
    var rawMic by remember { mutableStateOf(WowVoiceChat.rawMic(context)) }
    val active = { PluviaApp.keepAlive || PluviaApp.xEnvironment != null }
    LaunchedEffect(Unit) {
        try { status = withContext(Dispatchers.IO) { WowVoiceChat.load(context) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message }
    }
    fun save(enabled: Boolean) {
        working = true; onBusy(true); message = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { WowVoiceChat.saveMicrophone(context, enabled, active) }
                status = withContext(Dispatchers.IO) { WowVoiceChat.load(context) }
                message = t("Microphone setting saved for the next Play.", "Mikrofonbeállítás mentve a következő indításhoz.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message }
            finally { working = false; onBusy(false) }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionPending = false
        if (granted) save(true)
        else {
            onBusy(false)
            message = t("Permission denied. Microphone capture remains unchanged. Enable permission in Android app settings to try again.",
                "Engedély megtagadva. A mikrofonbeállítás nem változott. Újrapróbáláshoz engedélyezd a mikrofont az Android alkalmazásbeállításaiban.")
        }
    }
    val disabled = blocked || working || permissionPending || status == null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tx("Voice chat", "Hangcsevegés"), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(tx("Microphone capture", "Mikrofon használata"), modifier = Modifier.weight(1f))
            WowSwitch(checked = status?.microphone == true, enabled = !disabled, onCheckedChange = { value ->
                if (value && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    permissionPending = true; onBusy(true); permission.launch(Manifest.permission.RECORD_AUDIO)
                } else save(value)
            })
        }
        Text(tx("Optional, off by default. Enabling requests Android microphone permission and exposes the microphone to Wine on the next Play. Also enable voice input in WoW. No recording is started here.",
            "Opcionális, alapból kikapcsolva. Bekapcsoláskor Android mikrofonengedélyt kér, majd a következő indításkor elérhetővé teszi a mikrofont a Wine számára. A WoW-ban is engedélyezd a hangbemenetet. Itt nem indul felvétel."),
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(tx("Raw microphone (no echo cancellation)", "Nyers mikrofon (echo-szűrés nélkül)"), modifier = Modifier.weight(1f))
            WowSwitch(checked = rawMic, enabled = !disabled, onCheckedChange = { value ->
                try {
                    WowVoiceChat.saveRawMic(context, value, active)
                    rawMic = value
                    message = t("Microphone mode saved for the next Play.", "A mikrofon módja mentve a következő indításhoz.")
                } catch (e: Exception) { message = e.message }
            })
        }
        Text(tx("Off: the Android voice-call input (echo cancellation, noise suppression). On: the plain microphone. Try On if your voice is not picked up or the level does not follow it, especially when the sound plays from the phone speaker.",
            "Ki: az Android hívás-bemenet (echo- és zajszűréssel). Be: a sima mikrofon. Próbáld bekapcsolva, ha a hangod nem jut át vagy a jelszint nem követi, különösen ha a hang a telefon hangszórójából szól."),
            style = MaterialTheme.typography.bodySmall)
        Text(tx("The WoW microphone test plays your voice back live. Use headphones for it: from the phone speaker the sound feeds back into the microphone and whistles.",
            "A WoW mikrofonteszt élőben visszajátssza a hangod. Használj hozzá fülhallgatót: a telefon hangszórójából a hang visszajut a mikrofonba, és sűvít."),
            style = MaterialTheme.typography.bodySmall)
        status?.let {
            Text(tx("Voice helper files: ", "Voice segédfájlok: ") + if (it.proxy && it.libraries) tx("present", "megvan") else tx("missing or incomplete", "hiányzik vagy hiányos"),
                style = MaterialTheme.typography.bodySmall)
            if (!it.captureModule) Text(tx("The PulseAudio capture module is missing. Microphone input needs a repaired app runtime.",
                "A PulseAudio mikrofonmodul hiányzik. A hangbemenethez az alkalmazás runtime-jának javítása szükséges."), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        Text(tx("Repair downloads and verifies only the installed build's Windows ARM64 voice proxy and its libraries. It does not update WoW or change game settings. Error 14 can occur when these files are absent.",
            "A javítás kizárólag a telepített játékverzió Windows ARM64 voice proxyját és könyvtárait tölti le és ellenőrzi. Nem frissíti a WoW-ot és nem módosít játékbeállítást. A hiányuk 14-es hibát okozhat."),
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !disabled, onClick = { confirmRepair = true }) { Text(tx("Repair voice helper files", "Voice segédfájlok javítása")) }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (confirmRepair) AlertDialog(onDismissRequest = { confirmRepair = false },
        title = { Text(tx("Repair voice files?", "Javítod a voice fájlokat?")) },
        text = { Text(tx("Downloads missing or mismatching voice files from Blizzard's CDN into the game's Utils directory. Stop the game first. Your main EXE, Config.wtf and caches stay unchanged.",
            "A Blizzard CDN-ről letölti a hiányzó vagy eltérő voice fájlokat a játék Utils mappájába. Előbb állítsd le a játékot. A fő EXE, a Config.wtf és a cache-ek változatlanok maradnak.")) },
        confirmButton = { TextButton(enabled = !disabled, onClick = {
            confirmRepair = false; working = true; onBusy(true); message = null
            scope.launch {
                try {
                    val count = withContext(Dispatchers.IO) {
                        val job = currentCoroutineContext()
                        WowVoiceChat.repair(context, { job.ensureActive(); active() }, t) { step ->
                            scope.launch { message = step }
                        }
                    }
                    status = withContext(Dispatchers.IO) { WowVoiceChat.load(context) }
                    message = if (count == 0) t("Voice files already match the installed build.", "A voice fájlok már megfelelnek a telepített játékverziónak.")
                        else t("Verified voice files installed: ", "Ellenőrzött voice fájlok telepítve: ") + count
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = e.message ?: t("Voice repair failed.", "A voice javítás nem sikerült.") }
                finally { working = false; onBusy(false) }
            }
        }) { Text(tx("Repair", "Javítás")) } },
        dismissButton = { TextButton(onClick = { confirmRepair = false }) { Text(tx("Cancel", "Mégse")) } })
}
