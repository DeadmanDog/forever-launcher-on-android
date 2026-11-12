package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.gamenative.PluviaApp
import app.gamenative.ui.screen.wow.WowLaunchEnvironment
import app.gamenative.ui.screen.wow.WowCacheCleaner
import app.gamenative.ui.screen.wow.WowDiagnostics
import app.gamenative.ui.screen.wow.DiagnosticExport
import app.gamenative.ui.screen.wow.rememberTx
import app.gamenative.ui.screen.wow.tx
import com.winlator.container.ContainerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which half of the section to show: diagnostic logging (Diagnostics tab) or the game caches (Game tab). */
internal enum class WowCachePart { DIAGNOSTICS, CACHES }

@Composable
internal fun WowCacheDiagnosticsSection(part: WowCachePart, onBusyChanged: (Boolean) -> Unit = {}, showTitle: Boolean = true) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val t = rememberTx()
    var current by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var confirmLocation by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    val active = PluviaApp.keepAlive || PluviaApp.xEnvironment != null   // keepAlive is snapshot state: no polling
    var caches by remember { mutableStateOf(emptySet<WowCacheCleaner.Kind>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var logging by remember { mutableStateOf(WowDiagnostics.enabled(context)) }
    var retention by remember { mutableIntStateOf(WowDiagnostics.retention(context)) }
    var detailedLogging by remember { mutableStateOf(WowDiagnostics.detailed(context)) }
    var audioLogging by remember { mutableStateOf(WowDiagnostics.audioDebugSaved(context)) }
    var gpuProfiling by remember { mutableStateOf(WowDiagnostics.gpuProfileSaved(context)) }
    LaunchedEffect(saving) { onBusyChanged(saving) }
    DisposableEffect(Unit) { onDispose { onBusyChanged(false) } }
    LaunchedEffect(Unit) {
        try {
            current = withContext(Dispatchers.IO) {
                val c = ContainerManager(context).getContainerById("wow_forever") ?: error(t("WoW container has not been created yet", "A WoW konténer még nem jött létre"))
                WowLaunchEnvironment.location(c)
            }
            selected = current
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message }
        finally { loading = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (loading || saving) CircularProgressIndicator()
        if (part == WowCachePart.DIAGNOSTICS) {
        if (showTitle) Text(tx("Diagnostic logging", "Diagnosztikai naplózás"), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.Top) {
            Text(tx("Diagnostic logging", "Diagnosztikai naplózás"), modifier = Modifier.weight(1f))
            WowSwitch(checked = logging, enabled = !saving && !active, onCheckedChange = { value ->
                saving = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            WowDiagnostics.save(context, value) { PluviaApp.keepAlive || PluviaApp.xEnvironment != null }
                        }
                        logging = value
                        message = if (value) t("Diagnostic logging enabled. Applies on the next launch.", "Diagnosztikai naplózás bekapcsolva. A következő indítástól érvényes.")
                            else t("Diagnostic logging disabled. Applies on the next launch.", "Diagnosztikai naplózás kikapcsolva. A következő indítástól érvényes.")
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = e.message ?: t("Unable to save diagnostic setting.", "A diagnosztikai beállítás nem menthető.") }
                    finally { saving = false }
                }
            })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.Top) {
            Text(tx("Detailed runtime logging", "Részletes runtime-naplózás"), modifier = Modifier.weight(1f))
            WowSwitch(checked = detailedLogging, enabled = logging && !saving && !active, onCheckedChange = { value ->
                saving = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { WowDiagnostics.saveDetailed(context,value) { PluviaApp.keepAlive || PluviaApp.xEnvironment != null } }
                        detailedLogging=value;message=t("Diagnostic detail level saved. Applies on the next launch.", "Részletességi szint mentve. A következő indítástól érvényes.")
                    } catch(e:CancellationException) { throw e }
                    catch(e:Exception) { message=e.message }
                    finally { saving=false }
                }
            })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.Top) {
            Text(tx("Audio diagnostics (voice chat / microphone)", "Hang-diagnosztika (hangcsevegés / mikrofon)"), modifier = Modifier.weight(1f))
            WowSwitch(checked = audioLogging, enabled = logging && !saving && !active, onCheckedChange = { value ->
                saving = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { WowDiagnostics.saveAudioDebug(context, value) { PluviaApp.keepAlive || PluviaApp.xEnvironment != null } }
                        audioLogging = value; message = t("Audio diagnostics saved. Applies on the next launch.", "A hang-diagnosztika mentve. A következő indítástól érvényes.")
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = e.message }
                    finally { saving = false }
                }
            })
        }
        Text(tx("Records the Wine audio trace (winepulse, mmdevapi), the PulseAudio server log and a microphone bridge log (how much sound the phone microphone delivers and how much reaches the game) into the diagnostic export. Short tests only: it slows the game and the logs get large. Needs diagnostic logging on.",
            "Rögzíti a Wine hang-nyomkövetését (winepulse, mmdevapi), a PulseAudio szerver naplóját és egy mikrofon-bridge naplót (mennyi hangot ad a telefon mikrofonja és mennyi jut el a játékig) a diagnosztikai exportba. Csak rövid teszthez: lassítja a játékot és nagy naplókat ad. A diagnosztikai naplózás bekapcsolása kell hozzá."))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.Top) {
            Text(tx("GPU driver profile (what the game asks of the driver)", "GPU-driver profil (mit kér a játék a drivertől)"), modifier = Modifier.weight(1f))
            WowSwitch(checked = gpuProfiling, enabled = logging && !saving && !active, onCheckedChange = { value ->
                saving = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { WowDiagnostics.saveGpuProfile(context, value) { PluviaApp.keepAlive || PluviaApp.xEnvironment != null } }
                        gpuProfiling = value; message = t("GPU driver profile saved. Applies on the next launch.", "A GPU-driver profil mentve. A következő indítástól érvényes.")
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = e.message }
                    finally { saving = false }
                }
            })
        }
        Text(tx("TurnRIP records for 2 frames every 10 seconds which GPU work the game requests (render passes, tile loads/stores, resolves, blits, clears, compute) into the diagnostic export. Each sample causes a short hitch, so turn it off for normal play; the per-second driver counters are recorded with diagnostic logging alone. Needs diagnostic logging on.",
            "A TurnRIP 10 másodpercenként 2 képkockán át rögzíti, milyen GPU-munkát kér a játék (render passok, tile betöltés/mentés, resolve, blit, törlés, compute) a diagnosztikai exportba. Minden minta egy rövid döccenést okoz, ezért normál játékhoz kapcsold ki; a driver másodpercenkénti számlálóit a diagnosztikai naplózás magában is rögzíti. A diagnosztikai naplózás bekapcsolása kell hozzá."))
        Text(tx("Off by default. Enabled mode records runtime errors, app/Android exit data and renderer/CPU samples. Detailed mode adds Wine tracing and DXVK debug messages for short reproductions; it can affect performance.",
            "Alapból ki van kapcsolva. Bekapcsolva rögzíti a runtime-hibákat, az app/Android kilépési adatokat és a renderer/CPU mintákat. A részletes mód Wine-nyomkövetést és DXVK debug üzeneteket is ad rövid hibareprodukcióhoz; ez lassíthat."))
        Text(tx("Retained diagnostic sessions: $retention (logs of this many recent launches are kept)", "Megőrzött diagnosztikai sessionök: $retention (ennyi legutóbbi indítás naplója marad meg)"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 20).forEach { count ->
                TextButton(enabled = !saving && !active && retention != count, onClick = {
                    try { WowDiagnostics.saveRetention(context, count) { PluviaApp.keepAlive || PluviaApp.xEnvironment != null }; retention = count }
                    catch (e: Exception) { message = e.message }
                }) { Text(count.toString()) }
            }
        }
        Text(tx("Runtime logs rotate at 2 MiB per file, retaining one previous file per stream. The first 512 KiB of runtime output is kept separately in startup.log.",
            "A runtime-naplók 2 MiB-onként fordulnak, folyamonként egy előző fájl marad meg. A runtime-kimenet első 512 KiB-ja külön, a startup.log-ban marad meg."))
        TextButton(enabled = !saving && !active, onClick = {
            saving=true
            scope.launch {
                try {
                    val file=withContext(Dispatchers.IO) { DiagnosticExport.create(WowDiagnostics.directory(context),java.io.File(context.cacheDir,"diagnostic_exports")) }
                    val uri=androidx.core.content.FileProvider.getUriForFile(context,context.packageName+".fileprovider",file)
                    val intent=android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type="application/zip";putExtra(android.content.Intent.EXTRA_STREAM,uri)
                        clipData=android.content.ClipData.newRawUri("Diagnostics",uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent,t("Share diagnostics", "Diagnosztika megosztása")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch(e:CancellationException) { throw e }
                catch(e:Exception) { message=e.message ?: t("Unable to export diagnostic logs.", "A diagnosztikai naplók nem exportálhatók.") }
                finally { saving=false }
            }
        }) { Text(tx("Share diagnostics", "Diagnosztika megosztása")) }
        Text(tx("Diagnostic files can contain paths and runtime details. Review them before sharing.",
            "A diagnosztikai fájlok útvonalakat és runtime-részleteket tartalmazhatnak. Megosztás előtt nézd át őket."))
        WowDiagnostics.lastError(context)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (part == WowCachePart.CACHES) {
        Text(tx("Cache location", "Cache helye"), style = MaterialTheme.typography.titleMedium)
        val locations = listOf(WowLaunchEnvironment.GAME_DIRECTORY to tx("Game executable directory", "A játék mappája"),
            WowLaunchEnvironment.GAMENATIVE_DEFAULT to tx("App storage (default)", "App tárhelye (alapértelmezett)"))
        locations.forEach { (id, label) ->
            // Picking the other location asks once and saves it; no separate Save button.
            Row(Modifier.fillMaxWidth().selectable(selected = selected == id, enabled = current != null && !saving && !active,
                role = Role.RadioButton, onClick = { message = null; if (id != current) { selected = id; confirmLocation = id } }), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected == id, onClick = null, enabled = current != null && !saving && !active)
                Spacer(Modifier.width(8.dp))
                Text(label + if (current == id) tx(" · CURRENT", " · JELENLEGI") else "")
            }
        }
        Text(tx("Game directory keeps the DXVK, VKD3D and Mesa caches with the game. Explicit cache paths in Environment Variables override this choice. Changes take effect on the next launch.",
            "A játék mappája opció a DXVK, VKD3D és Mesa cache-t a játék mellett tartja. A környezeti változókban megadott cache-útvonal felülírja. A következő indítástól érvényes."))
        confirmLocation?.let { choice ->
            val name = locations.firstOrNull { it.first == choice }?.second.orEmpty()
            AlertDialog(onDismissRequest = { confirmLocation = null; selected = current },
                title = { Text(tx("Change the cache location?", "Módosítod a cache helyét?")) },
                text = { Text(tx("From the next launch the caches are kept here: $name. The caches built so far stay in the old place; the game builds them again in the new one.",
                    "A következő indítástól ide kerülnek a cache-ek: $name. Az eddig felépült cache-ek a régi helyen maradnak, a játék az újban újra felépíti őket.")) },
                confirmButton = { TextButton(onClick = {
                    confirmLocation = null
                    saving = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { WowLaunchEnvironment.saveLocation(context, choice) { (PluviaApp.keepAlive || PluviaApp.xEnvironment != null) } }
                            current = choice; selected = choice; message = t("Cache location saved.", "Cache helye mentve.")
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { selected = current; message = e.message }
                        finally { saving = false }
                    }
                }) { Text(tx("Change", "Módosítás")) } },
                dismissButton = { TextButton(onClick = { confirmLocation = null; selected = current }) { Text(tx("Cancel", "Mégse")) } })
        }
        HorizontalDivider()
        Text(tx("Clear caches", "Cache törlése"), style = MaterialTheme.typography.titleMedium)
        WowCacheCleaner.Kind.entries.forEach { kind ->
            Row(Modifier.fillMaxWidth().selectable(selected = kind in caches,
                enabled = current != null && !saving && !active, role = Role.Checkbox,
                onClick = { caches = if (kind in caches) caches - kind else caches + kind }),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(checked = kind in caches, onCheckedChange = null)
                Text(when (kind) {
                    WowCacheCleaner.Kind.DXVK -> tx("DXVK (DX11 pipeline cache)", "DXVK (DX11 pipeline-cache)")
                    WowCacheCleaner.Kind.VKD3D -> tx("VKD3D (DX12 pipeline cache)", "VKD3D (DX12 pipeline-cache)")
                    WowCacheCleaner.Kind.GPU -> tx("GPU / Mesa (driver shader cache)", "GPU / Mesa (a driver shader-cache-e)")
                    WowCacheCleaner.Kind.WOW -> tx("World of Warcraft Cache folder", "World of Warcraft Cache mappa")
                })
            }
        }
        Text(tx("Only selected cache files are removed. Game files, WTF settings, prefix and runtime profiles are kept. Caches rebuild on the next launch; the first run may stutter.",
            "Csak a kijelölt cache-fájlok törlődnek. A játékfájlok, a WTF-beállítások, a prefix és a runtime-profilok megmaradnak. A cache a következő indításkor újraépül; az első futás akadhat."))
        TextButton(enabled = current != null && !saving && !active && caches.isNotEmpty(), onClick = {
            val selection = caches
            saving = true
            message = null
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { WowCacheCleaner.clear(context, selection) }
                    message = t("Cache cleanup complete: ${result.entries} entries removed.", "Cache-törlés kész: ${result.entries} elem törölve.")
                    caches = emptySet()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = e.message ?: t("Cache cleanup failed.", "A cache-törlés nem sikerült.") }
                finally { saving = false }
            }
        }) { Text(tx("Clear selected caches", "Kijelölt cache törlése")) }
        }
        if (active) Text(tx("Cache and diagnostic settings cannot be changed during an active session.", "Játék közben a cache- és diagnosztikai beállítások nem módosíthatók."))
        message?.let { Text(it) }
    }
}
