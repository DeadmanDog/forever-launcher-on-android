package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.gamenative.ui.screen.wow.WowGameConfig
import app.gamenative.ui.screen.wow.WowLaunchEnvironment
import app.gamenative.ui.screen.wow.WowLaunchPolicy
import app.gamenative.ui.screen.wow.WowRuntimeTuning
import app.gamenative.ui.screen.wow.tx
import com.winlator.core.envvars.EnvVars

/** One saved launch choice rendered as a titled radio group. */
@Composable
private fun TuningChoice(key: String, title: String, hint: String?, values: List<Pair<String, String>>,
                         blocked: Boolean, onError: (String?) -> Unit, isEnabled: (String) -> Boolean = { true },
                         unavailable: String? = null, onSelected: (String) -> Unit = {}, current: String? = null) {
    val context = LocalContext.current.applicationContext
    // [current]: what the game really uses while nothing is chosen here (the install's region, Config.wtf's language/account).
    var selected by remember(key) { mutableStateOf(WowRuntimeTuning.selected(context, key).takeIf { v -> v.isNotEmpty() && values.any { it.first == v } } ?: current ?: WowRuntimeTuning.selected(context, key)) }
    // A choice that does nothing with the current API or present mode stays visible but greyed out, with the reason.
    Column(Modifier.alpha(if (unavailable != null) 0.45f else 1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        unavailable?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        values.forEach { (value, text) ->
            val enabled = !blocked && unavailable == null && isEnabled(value)
            Row(Modifier.fillMaxWidth().selectable(selected = value == selected, enabled = enabled, role = Role.RadioButton, onClick = {
                try { WowRuntimeTuning.save(context, key, value); selected = value; onSelected(value); onError(null) }
                catch (e: Exception) { onError(e.message) }
            }), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = value == selected, onClick = null, enabled = enabled)
                Spacer(Modifier.width(8.dp))
                Text(text, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

/** Null when the next launch presents with FIFO; otherwise why the FIFO-only choice does nothing (a saved override or the menu choice). */
@Composable
internal fun fifoLatencyUnavailable(inputs: WowQuickPresentInputs, uiChoice: String): String? {
    val mode = inputs.effective(uiChoice)
    if (mode == "fifo") return null
    val name = when (mode) { "mailbox" -> "Mailbox"; "immediate" -> "Immediate"; "relaxed" -> "FIFO relaxed"; else -> mode }
    return if (inputs.savedOverride) tx("Only with FIFO: MESA_VK_WSI_PRESENT_MODE in Environment Variables sets $name.",
        "Csak FIFO-val érhető el: a környezeti változókban a MESA_VK_WSI_PRESENT_MODE most $name módot állít be.")
    else tx("Only with FIFO: the present mode is $name now.", "Csak FIFO-val érhető el: most $name a megjelenítési mód.")
}

@Composable private fun onlyDx11() = tx("Only with DX11 (DXVK): the game runs DX12 now.", "Csak DX11-gyel (DXVK) érhető el: most DX12 fut.")
@Composable private fun onlyDx12() = tx("Only with DX12 (VKD3D): the game runs DX11 now.", "Csak DX12-vel (VKD3D) érhető el: most DX11 fut.")

/** The saved container values the next launch resolves the present mode from, together with the Quick tab choice. */
internal data class WowQuickPresentInputs(val savedEnvironment: String = "", val driverMode: String = "", val rendererMode: String = "") {
    /** The mode the next launch uses for [uiChoice]: the same resolver as the launch, so a saved MESA_VK_WSI_PRESENT_MODE wins. */
    fun effective(uiChoice: String) = WowLaunchEnvironment.resolvePresentMode(savedEnvironment, uiChoice, driverMode, rendererMode).trim().lowercase()
    val savedOverride: Boolean get() = EnvVars(savedEnvironment).has("MESA_VK_WSI_PRESENT_MODE")
}

/** The game's own choices for the Settings "Game" tab: which region the next launch connects to and which game account it logs into. */
@Composable
internal fun WowGameChoicesSection(blocked: Boolean) {
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current.applicationContext
    // Nothing chosen yet: the radio marks what the game really uses now, so the list needs no "keep the current one" entry.
    val now = remember { runCatching { WowGameConfig.current(java.io.File(app.gamenative.ui.screen.wow.GamePath.load(context))) }.getOrNull() }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(tx("REGION AND ACCOUNT", "RÉGIÓ ÉS FIÓK"), style = MaterialTheme.typography.titleMedium)
        TuningChoice(WowRuntimeTuning.REGION, tx("Region", "Régió"),
            tx("The game region the next launch connects to. The game data is the same for every region, so you can switch any time.",
                "A játékrégió, amelyhez a következő indítás csatlakozik. A játékfájlok minden régióban ugyanazok, így bármikor válthatsz."),
            listOf("eu" to tx("Europe", "Európa"), "us" to tx("Americas", "Amerika"),
                "kr" to tx("Korea", "Korea"), "tw" to tx("Taiwan", "Tajvan")), blocked, { error = it }, current = now?.region)
        TuningChoice(WowRuntimeTuning.GAME_LOCALE, tx("Game language", "Játék nyelve"),
            tx("The language of the game's texts and voices. Realms of every language are in their region (German, French, Spanish and Russian realms are in Europe). A new language may download its files on the first start.",
                "A játék szövegeinek és hangjainak nyelve. Minden nyelv realmjei a saját régiójukban vannak (a német, francia, spanyol és orosz realmek Európában). Új nyelvnél az első indítás letöltheti a nyelv fájljait."),
            listOf("enUS" to "English", "deDE" to "Deutsch", "frFR" to "Français",
                "esES" to "Español (EU)", "esMX" to "Español (AL)", "ptBR" to "Português (BR)", "ruRU" to "Русский", "koKR" to "한국어",
                "zhCN" to "简体中文", "zhTW" to "繁體中文"), blocked, { error = it }, current = now?.locale)
        TuningChoice(WowRuntimeTuning.GAME_ACCOUNT, tx("Game account", "Játékfiók"),
            tx("Only when your Battle.net account has more than one WoW game account (WoW1, WoW2). The next launch logs into the account chosen here.",
                "Csak ha a Battle.net-fiókodhoz több WoW-játékfiók tartozik (WoW1, WoW2). A következő indítás az itt választott fiókba lép be."),
            listOf("1" to "WoW1", "2" to "WoW2"), blocked, { error = it }, current = now?.account)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/** Everyday launch choices for the Settings "Quick" tab. Applied on the next Play. */
@Composable
internal fun WowQuickTuningSection(blocked: Boolean, hybridAvailable: Boolean, dx12: Boolean, presentInputs: WowQuickPresentInputs = WowQuickPresentInputs()) {
    val context = LocalContext.current.applicationContext
    var error by remember { mutableStateOf<String?>(null) }
    var present by remember { mutableStateOf(WowRuntimeTuning.selected(context, "present")) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TuningChoice("gpu", tx("GPU power mode", "GPU-mód"),
            tx("How the GPU clock behaves while you play. Balanced (default): the phone manages the clock and raises it only when the game really waits for the GPU. Performance: keeps the GPU at its highest clock (more heat and power; useful when the phone itself is in Balance mode, which caps the CPU). Regulated: keeps the clock above the chosen share of the maximum; it needs kernel support, otherwise it behaves like Balanced.",
                "Hogyan viselkedik a GPU órajele játék közben. Balanced (alapértelmezett): a telefon kezeli az órajelet, és csak akkor emeli, ha a játék valóban a GPU-ra vár. Performance: a GPU-t a legmagasabb órajelen tartja (több hő és fogyasztás; akkor hasznos, ha maga a telefon Balance módban van, mert az a CPU-t korlátozza). Szabályozott: a maximum kiválasztott hányada fölött tartja az órajelet; kernel-támogatás kell hozzá, nélküle Balancedként működik."),
            listOf("" to tx("Balanced (default)", "Balanced (alapértelmezett)"), "1" to "Performance") +
                WowRuntimeTuning.gpuFloors.map { "f$it" to tx("Regulated, min. $it%", "Szabályozott, min. $it%") }, blocked, { error = it })
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TuningChoice("pacing", tx("FPS limit pacing", "FPS-limit ütemezése"),
            tx("Who holds the frame rate to the limit. App, vsync-locked (recommended): the app releases frames in step with the display's refresh, so they stay evenly spaced. App, timer: the app uses its own timer instead of the display refresh, which can drift slightly. DXVK / VKD3D: the graphics layer limits with its own timer, which can make motion judder. App, vsync + phase (experimental): like vsync-locked, and the app also measures where frames reach the display and shifts their timing a few milliseconds so they finish mid-refresh: a small timing wobble then no longer moves a frame to the neighbouring refresh (measured at 60 Hz with FIFO). A limiter typed into Environment Variables always uses DXVK / VKD3D.",
                "Ki tartja a képkockasebességet a limiten. App, vsync-hez kötött (ajánlott): az app a kijelző frissítésével lépésben engedi tovább a képkockákat, így egyenletes marad a ritmus. App, időzítős: az app saját időzítőt használ a kijelző frissítése helyett, ez kicsit elcsúszhat. DXVK / VKD3D: a grafikus réteg saját időzítővel limitál, ez ránghatóvá teheti a mozgást. App, vsync + időzítés-igazítás (kísérleti): mint a vsync-hez kötött, de az app azt is méri, mikor érnek a kockák a kijelzőhöz, és néhány ezredmásodperccel eltolja őket a frissítés közepére, így egy kis időzítés-ingadozás már nem viszi át a kockát a szomszéd frissítésre (60 Hz-en, FIFO-val mérve). A környezeti változókba kézzel beírt limiter mindig a DXVK / VKD3D utat használja."),
            listOf("" to tx("App, vsync-locked (recommended)", "App, vsync-hez kötött (ajánlott)"),
                "phase" to tx("App, vsync + phase (experimental)", "App, vsync + időzítés-igazítás (kísérleti)"),
                "timer" to tx("App, timer", "App, időzítős"), "api" to "DXVK / VKD3D"), blocked, { error = it })
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TuningChoice("async", tx("DX11 shader compilation", "DX11 shaderfordítás"),
            tx("How DX11 shaders are compiled while you play. GPL (default): links pipelines quickly and never skips a draw, so loading is fast and play is smooth. Hybrid: skips draws while shaders compile, but compiles each shader in full, which can cause hitches on this GPU. Async: the older background mode.",
                "Hogyan fordítódnak a DX11 shaderek játék közben. GPL (alapértelmezett): gyorsan összekapcsol és nem hagy ki rajzolást, ezért gyors a betöltés és sima a játék. Hibrid: kihagy rajzolást, amíg a shader fordul, de minden shadert teljesen fordít, ami ezen a GPU-n akadást okozhat. Async: a régebbi, háttérben fordító mód."),
            listOf("2" to tx("Hybrid", "Hibrid"),
                "0" to tx("GPL (default)", "GPL (alapértelmezett)"), "1" to "Async"), blocked, { error = it }, { it != "2" || hybridAvailable },
            unavailable = if (dx12) onlyDx11() else null)
        if (!hybridAvailable) Text(tx("Hybrid needs the bundled DXVK: apply it on the Components tab.",
            "A hibridhez a beépített DXVK kell: alkalmazd a Komponensek fülön."),
            style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TuningChoice("present", tx("Present mode", "Megjelenítési mód (present)"),
            tx("FIFO (default): every frame reaches the screen in order; with the vsync-locked pacing this measured smoothest. Mailbox: a newer frame replaces one still waiting, a little less latency, but now and then a frame is dropped (a small jerk).",
                "FIFO (alapértelmezett): minden kocka sorban kerül a képre; a vsync-hez kötött ütemezéssel ez mérhetően a legsimább. Mailbox: az újabb kocka lecseréli a még várakozót, kicsit kisebb a késleltetés, de időnként kiesik egy kocka (apró rándulás)."),
            listOf("fifo" to tx("FIFO (default)", "FIFO (alapértelmezett)"), "mailbox" to "Mailbox"), blocked, { error = it },
            onSelected = { present = it })
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TuningChoice("fifolatency", tx("FIFO latency", "FIFO késleltetés"),
            tx("Only with FIFO. Smooth (default): frames queue up as they come, about 60 ms from frame to screen at 60 Hz, never a skipped frame. Lower (experimental): after a hitch the app takes a queued frame out (one skipped frame) and stops at the first level that works (about 43 ms). Lowest (experimental): also tries one step further each time; if that does not hold it costs one more small hitch, then the app keeps the level that held.",
                "Csak FIFO-val. Sima (alapértelmezett): a kockák úgy állnak sorba, ahogy jönnek, 60 Hz-en kb. 60 ms a kockától a képig, soha nincs kihagyott kocka. Alacsonyabb (kísérleti): akadás után az app kivesz egy beragadt kockát (egy kihagyás), és az első működő szinten megáll (kb. 43 ms). Legalacsonyabb (kísérleti): minden alkalommal megpróbál még egy lépést lejjebb; ha az nem tart, az még egy apró akadás, utána a tartós szinten marad."),
            listOf("" to tx("Smooth (default)", "Sima (alapértelmezett)"), "low" to tx("Lower (experimental)", "Alacsonyabb (kísérleti)"),
                "lowest" to tx("Lowest (experimental)", "Legalacsonyabb (kísérleti)")), blocked, { error = it },
            unavailable = fifoLatencyUnavailable(presentInputs, present))
        Text(tx("A value typed in Environment Variables always overrides these choices.",
            "A környezeti változókba kézzel beírt érték mindig felülírja ezeket."), style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/** Experimental and diagnostic launch switches for the Settings "Advanced" tab. */
@Composable
internal fun WowAdvancedTuningSection(blocked: Boolean, savedEnvironment: String, legacyTuDefault: Boolean, dx12: Boolean) {
    val context = LocalContext.current.applicationContext
    var memory by remember { mutableStateOf(WowLaunchPolicy.memory(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(tx("Experimental settings. Applied on the next Play only; container settings are preserved.",
            "Kísérleti beállítások. Csak a következő indításra érvényesek; a konténer beállításai megmaradnak."),
            style = MaterialTheme.typography.bodySmall)
        TuningChoice("latency", tx("DX12 maximum latency", "DX12 max. késleltetés"),
            tx("How many frames the CPU may prepare ahead of the GPU. Lower = less input lag, but the GPU then idles between frames: on this phone 1 frame let the GPU clock drop and cost FPS. Leave Default unless you are testing.",
                "Hány képkockát készíthet elő a CPU a GPU előtt. Kisebb = kevesebb input lag, de a GPU a kockák között üresjáratban áll: ezen a telefonon az 1 képkocka levitte a GPU órajelét, és FPS-be került. Hagyd alapértelmezésen, hacsak nem tesztelsz."),
            listOf("" to tx("Default", "Alapértelmezett"), "1" to tx("1 frame", "1 képkocka"), "2" to tx("2 frames", "2 képkocka"), "3" to tx("3 frames", "3 képkocka")),
            blocked, { error = it },
            unavailable = if (dx12) null else onlyDx12())
        TuningChoice("images", tx("DX12 swapchain images", "DX12 swapchain képszám"),
            tx("How many image buffers the game's swapchain asks for; the driver may raise or lower it to its own limits. Fewer can shorten the queue but can also stall the GPU; more is steadier. Not the same as the maximum latency above.",
                "Hány képpuffert kér a játék swapchainje; a driver a saját minimumához/maximumához igazíthatja. A kevesebb rövidítheti a sort, de meg is akaszthatja a GPU-t; a több egyenletesebb. Nem azonos a fenti max. késleltetéssel."),
            listOf("" to tx("Default", "Alapértelmezett"), "2" to "2", "3" to "3", "4" to "4"), blocked, { error = it },
            unavailable = if (dx12) null else onlyDx12())
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val owned = !legacyTuDefault && WowLaunchPolicy.hasSavedTuDebugDisplay(savedEnvironment)
        Text(tx("GPU memory mode", "GPU memóriamód"), style = MaterialTheme.typography.titleSmall)
        Text(tx("Where the GPU draws. Sysmem: directly in system memory (recommended). GMEM: in the GPU's small fast on-chip memory, tile by tile. Driver default: Turnip decides per frame.",
            "Hol rajzol a GPU. Sysmem: közvetlenül a rendszermemóriában (ajánlott). GMEM: a GPU kicsi, gyors belső memóriájában, csempénként. A driver alapértelmezése: a Turnip képkockánként dönt."),
            style = MaterialTheme.typography.bodySmall)
        if (owned) Text(tx("Saved TU_DEBUG overrides this selector. Remove TU_DEBUG in Environment Variables to use it.",
            "A mentett TU_DEBUG felülírja ezt. A használatához töröld a TU_DEBUG-ot a környezeti változók közül."),
            style = MaterialTheme.typography.bodySmall)
        for (mode in WowLaunchPolicy.Memory.entries) {
            Row(Modifier.fillMaxWidth().selectable(selected = mode == memory, enabled = !blocked && !owned, role = Role.RadioButton, onClick = {
                try { WowLaunchPolicy.saveMemory(context, mode); memory = mode; error = null }
                catch (e: Exception) { error = e.message }
            }), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == memory, onClick = null, enabled = !blocked && !owned)
                Spacer(Modifier.width(8.dp))
                Text(when (mode) { WowLaunchPolicy.Memory.SYSMEM -> "Sysmem"; WowLaunchPolicy.Memory.GMEM -> "GMEM"
                    WowLaunchPolicy.Memory.DEFAULT -> tx("Driver default", "A driver alapértelmezése") })
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TuningChoice("handoff", tx("Proton sync wake handoff", "Proton szinkron-átadás (handoff)"),
            tx("Wakes only the waiting thread that gets the signal. Off = original behaviour.",
                "Csak azt a várakozó szálat ébreszti, amelyik megkapja a jelet. Ki = eredeti működés."),
            listOf("0" to tx("Off (default)", "Ki (alapértelmezett)"), "1" to tx("On", "Be")), blocked, { error = it })
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TuningChoice(WowRuntimeTuning.CPU_PROFILE, tx("Processor the game sees", "A játék által látott processzor"),
            tx("Android gives the game no processor name or vendor, so WoW logs both as unknown. Only the name, vendor and max-frequency text change; the real cores and speed are untouched. A saved WINE_CPU_NAME / WINE_CPU_VENDOR wins.",
                "Az Android nem ad a játéknak processzornevet és gyártót, ezért a WoW mindkettőt ismeretlennek naplózza. Csak a név, a gyártó és a max. frekvencia szövege változik, a valódi magok és a sebesség nem. A mentett WINE_CPU_NAME / WINE_CPU_VENDOR nyer."),
            listOf("" to tx("Real: this phone's Snapdragon (default)", "Valós: a telefon Snapdragonja (alapérték)"),
                "laptop" to tx("Snapdragon X Plus laptop", "Snapdragon X Plus laptop"),
                "off" to tx("Android default (unknown)", "Android alapérték (ismeretlen)")), blocked, { error = it })
        TuningChoice(WowRuntimeTuning.GPU_PROFILE, tx("GPU the game sees", "A játék által látott GPU"),
            tx("Only the vendor, name, id and reported video memory (up to 8 GB, shared with the system) change; the real Adreno and its driver do the work. The game shows 'family: Unknown' for Qualcomm GPUs by design. If the game misbehaves after a change, go back to the default. A saved DXVK_VEGAS_* variable wins.",
                "Csak a gyártó, a név, az azonosító és a jelzett videomemória (legfeljebb 8 GB, a rendszerrel osztott) változik, a munkát a valódi Adreno és a driver végzi. A játék Qualcomm GPU-nál tervezetten 'family: Unknown'-t ír. Ha a játék rosszul működik változtatás után, térj vissza az alapértékre. A mentett DXVK_VEGAS_* változó nyer."),
            listOf("" to tx("Real: this phone's Adreno (default)", "Valós: a telefon Adrenója (alapérték)"),
                "laptop" to tx("Qualcomm Adreno X1 laptop", "Qualcomm Adreno X1 laptop"),
                "off" to tx("NVIDIA GeForce name", "NVIDIA GeForce név")), blocked, { error = it })
        Text(tx("Display experiments", "Megjelenítési kísérletek"), style = MaterialTheme.typography.titleSmall)
        for ((key, label, hint) in listOf(
            Triple("scanout", tx("Fullscreen scanout (hide HUD and touch controls)", "Teljes képernyős scanout (HUD és érintéses vezérlők elrejtve)"),
                tx("The game image goes straight to the display without the app's GPU compositing pass. Only works while the HUD, touch controls and quick menu are hidden. While on, Hardware scaling (on this tab) is not used.",
                    "A játék képe az app GPU-s összerakása nélkül, közvetlenül a kijelzőre kerül. Csak akkor működik, ha a HUD, az érintéses vezérlők és a gyorsmenü el vannak rejtve. Amíg be van kapcsolva, a hardveres skálázás (ezen a fülön) nem használatos.")),
            Triple(WowRuntimeTuning.LOW_LATENCY, tx("Low input latency (DX11)", "Alacsony bemeneti késés (DX11)"),
                tx("The game keeps only one frame ahead of the screen (DXVK frame latency 1) instead of up to three, so input reaches the picture sooner. Off = the game may queue frames ahead for slightly smoother but later frames. A maxFrameLatency typed into DXVK_CONFIG wins.",
                    "A játék csak egy képkockával jár a kijelző előtt (DXVK frame latency 1), nem akár hárommal, így a bevitel hamarabb látszik a képen. Ki = a játék előre sorba állíthat képkockákat: picit simább, de későbbi kép. A DXVK_CONFIG-ba írt maxFrameLatency mindig nyer.")),
            Triple(WowRuntimeTuning.CPU_CLASSES, tx("Report big/little CPU cores (experimental)", "Nagy/kis CPU-magok jelzése (kísérleti)"),
                tx("Tells the game which cores are the fastest ones (the phone's prime cores) the way a hybrid laptop CPU does, so WoW's own logs show Hybrid:true and it may place its busiest threads on them. Off = all cores look equal (default). Changes how WoW schedules threads: compare FPS before keeping it on.",
                    "Jelzi a játéknak, melyek a leggyorsabb magok (a telefon prime magjai), mint egy hibrid laptop-CPU-nál, így a WoW naplója Hybrid:true-t mutat, és a legterheltebb szálait ezekre teheti. Ki = minden mag egyformának látszik (alapérték). Megváltoztatja, hogyan ütemez a WoW: a megtartás előtt hasonlítsd össze az FPS-t.")),
            Triple("xnonblock", tx("Non-blocking X11 output (experimental)", "Nem blokkoló X11 kimenet (kísérleti)"),
                tx("Normally the display server waits while a program is not reading its answers or events, and that wait can freeze everyone else for a moment. With this on, the unread output is queued and sent when the program reads again (a program that never reads is dropped after 64 MB). Off by default: it changes the protocol output path, so if anything misbehaves, turn it off.",
                    "Alapból a megjelenítő szerver vár, amíg egy program nem olvassa a válaszait vagy eseményeit, és ez a várakozás egy pillanatra mindenki mást megállíthat. Bekapcsolva az el nem olvasott kimenet sorba kerül, és akkor megy ki, amikor a program újra olvas (a soha nem olvasó programot 64 MB után eldobja). Alapból ki van kapcsolva: a protokoll kimeneti útját érinti, ezért ha bármi hibásan működik, kapcsold ki.")),
            Triple(WowRuntimeTuning.HYBRID_GPL, tx("Hybrid keeps GPL (DX11)", "Hibrid megtartja a GPL-t (DX11)"),
                tx("Only matters when DX11 shader compilation is set to Hybrid. Normally Hybrid compiles every missing shader in full, which can freeze the render thread for 100+ ms. With this on, Hybrid fast-links pipelines like GPL does and still skips draws only while a shader is not ready. Experimental and off by default: if the game crashes or the screen goes black, turn it off or use GPL.",
                    "Csak akkor számít, ha a DX11 shaderfordítás Hibrid. Hibridben alapból minden hiányzó shader teljes fordítással készül, ami 100+ ms-ra megállíthatja a render szálat. Bekapcsolva a Hibrid a GPL-hez hasonlóan gyorsan összekapcsolja a pipeline-okat, és csak addig hagy ki rajzolást, amíg egy shader nem kész. Kísérleti és alapból ki van kapcsolva: ha a játék összeomlik vagy fekete a kép, kapcsold ki, vagy használj GPL-t.")),
            Triple(WowRuntimeTuning.VEGAS_BINDSKIP, tx("DXVK bind-skip (DX11)", "DXVK bind-skip (DX11)"),
                tx("Skips a pipeline bind when the previous draw in the same command buffer already used the same pipeline, which saves CPU work on the game's render thread. Experimental and off by default: if you see missing geometry or glitches, turn it off. It only affects DX11 (DXVK); the Quick Menu has no switch for it.",
                    "Kihagy egy pipeline-kötést, ha az ugyanabban a parancspufferben előző rajzolás már ugyanazt a pipeline-t használta; ez CPU-munkát spórol a játék render szálán. Kísérleti és alapból ki van kapcsolva: ha hiányzó geometriát vagy hibákat látsz, kapcsold ki. Csak a DX11-et (DXVK) érinti; a Quick Menüben nincs hozzá kapcsoló.")),
            Triple("presentidle", tx("Safe buffer hand-back (Present, experimental)", "Biztonságos pufferátadás (Present, kísérleti)"),
                tx("The game is told a frame buffer is free again only after the app has finished drawing it, instead of at once. This rules out the rare case where the game overwrites a buffer that is still on its way to the screen (a flash or torn frame). It does not make anything faster: if the display is late it can cost a little smoothness, so leave it off unless you see such glitches. Takes effect from the next launch.",
                    "A játék csak azután kapja meg, hogy egy képkocka-puffer újra szabad, hogy az app végzett a kirajzolásával, nem azonnal. Ez kizárja azt a ritka esetet, amikor a játék felülír egy puffert, ami még úton van a kijelző felé (villanás vagy tört kép). Semmit nem gyorsít: ha a megjelenítés késik, picit ronthatja a simaságot, ezért csak akkor kapcsold be, ha ilyen hibákat látsz. A következő indítástól él.")),
            Triple("priority", tx("Display thread priority", "Megjelenítő szálak prioritása"),
                tx("Gives the app's frame-presenting threads higher priority so frames are delivered on time.",
                    "Magasabb prioritást ad az app képkocka-megjelenítő szálainak, hogy a képkockák időben kikerüljenek.")),
            Triple("adpf", tx("ADPF compositor hints (Android 13+)", "ADPF compositor-jelzések (Android 13+)"),
                tx("Tells Android how long each frame of the compositor takes, so it can raise CPU clocks before frames run late.",
                    "Jelzi az Androidnak, mennyi ideig tart a compositor egy képkockája, hogy időben emelhesse a CPU órajelét.")))) {
            var enabled by remember(key) { mutableStateOf(WowLaunchPolicy.enabled(context, key)) }
            val unavailable = if (dx12 && key in dx11Only) onlyDx11() else null
            Column(Modifier.alpha(if (unavailable != null) 0.45f else 1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Text(label, modifier = Modifier.weight(1f))
                    WowSwitch(checked = enabled, enabled = !blocked && unavailable == null, onCheckedChange = {
                        try { WowLaunchPolicy.save(context, key, it); enabled = it; error = null }
                        catch (e: Exception) { error = e.message }
                    })
                }
                unavailable?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                Text(hint, style = MaterialTheme.typography.bodySmall)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/** Switches that only act on the DX11 path (DXVK / Vegas). */
private val dx11Only = setOf(WowRuntimeTuning.LOW_LATENCY, WowRuntimeTuning.HYBRID_GPL, WowRuntimeTuning.VEGAS_BINDSKIP)
