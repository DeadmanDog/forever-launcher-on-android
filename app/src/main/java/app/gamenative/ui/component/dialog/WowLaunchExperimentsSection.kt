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
@Composable internal fun xserverOnly() = tx("Available with XServer. Gamescope uses its own compositor.",
    "XServer módban érhető el. A Gamescope saját kompozitort használ.")

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
internal fun WowQuickTuningSection(blocked: Boolean, hybridAvailable: Boolean, dx12: Boolean, presentInputs: WowQuickPresentInputs, gamescope: Boolean) {
    val context = LocalContext.current.applicationContext
    var error by remember { mutableStateOf<String?>(null) }
    var present by remember { mutableStateOf(WowRuntimeTuning.selected(context, "present")) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TuningChoice("gpu", tx("GPU power mode", "GPU-mód"),
            tx("How the GPU clock behaves while you play. Balanced (default): the clock follows the load; the driver raises it only as far as the game needs (with DX12 at 1 frame latency: as far as the FPS limit needs). Performance: the GPU stays at its highest clock (about 1200 MHz) all the time: more heat and power.",
                "Hogyan viselkedik a GPU órajele játék közben. Balanced (alapértelmezett): az órajel a terheléshez igazodik; a driver csak annyira emeli, amennyire a játéknak kell (DX12-nél 1 képkocka késleltetéssel: amennyi az FPS-limithez kell). Performance: a GPU folyamatosan a legmagasabb órajelen (kb. 1200 MHz) marad: több hő és fogyasztás."),
            listOf("" to tx("Balanced (default)", "Balanced (alapértelmezett)"), "1" to tx("Performance", "Performance")), blocked, { error = it })
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TuningChoice("pacing", tx("FPS limit pacing", "FPS-limit ütemezése"),
            tx("Who holds the frame rate to the limit. App, vsync-aligned (recommended): the app releases frames in step with the display's refresh and shifts them a few milliseconds so they finish mid-refresh, so they stay evenly spaced. DXVK / VKD3D: the graphics layer limits with its own timer, which can make motion judder; a fallback. A limiter typed into Environment Variables always uses DXVK / VKD3D.",
                "Ki tartja a képkockasebességet a limiten. App, vsync-hez igazított (ajánlott): az app a kijelző frissítésével lépésben engedi tovább a képkockákat, és néhány ezredmásodperccel a frissítés közepére igazítja őket, így egyenletes marad a ritmus. DXVK / VKD3D: a grafikus réteg saját időzítővel limitál, ez ránghatóvá teheti a mozgást; tartalék megoldás. A környezeti változókba kézzel beírt limiter mindig a DXVK / VKD3D utat használja."),
            listOf("" to tx("App, vsync-aligned (recommended)", "App, vsync-hez igazított (ajánlott)"),
                "api" to "DXVK / VKD3D"), blocked, { error = it },
            unavailable = if (gamescope) xserverOnly() else null)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TuningChoice("async", tx("DX11 shader compilation", "DX11 shaderfordítás"),
            tx("How DX11 shaders are compiled while you play. Hybrid (default): skips a draw only while its shader compiles; with \"Hybrid keeps GPL\" (Advanced) it also links pipelines quickly. GPL: links pipelines quickly and never skips a draw. Async: the older background mode.",
                "Hogyan fordítódnak a DX11 shaderek játék közben. Hibrid (alapértelmezett): csak addig hagy ki egy rajzolást, amíg a shadere fordul; a „Hibrid megtartja a GPL-t” kapcsolóval (Haladó) gyorsan össze is kapcsolja a pipeline-okat. GPL: gyorsan összekapcsol, és soha nem hagy ki rajzolást. Async: a régebbi, háttérben fordító mód."),
            listOf("2" to tx("Hybrid (default)", "Hibrid (alapértelmezett)"),
                "0" to tx("GPL", "GPL"), "1" to "Async"), blocked, { error = it }, { it != "2" || hybridAvailable },
            unavailable = if (dx12) onlyDx11() else null)
        if (!hybridAvailable) Text(tx("Hybrid needs the bundled DXVK: apply it on the Components tab.",
            "A hibridhez a beépített DXVK kell: alkalmazd a Komponensek fülön."),
            style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TuningChoice("present", tx("Present mode", "Megjelenítési mód (present)"),
            tx("FIFO (default): every frame reaches the screen in order; with the app's vsync-aligned pacing this measured smoothest. Mailbox: a newer frame replaces one still waiting, a little less latency, but now and then a frame is dropped (a small jerk).",
                "FIFO (alapértelmezett): minden kocka sorban kerül a képre; az app vsync-hez igazított ütemezésével ez mérhetően a legsimább. Mailbox: az újabb kocka lecseréli a még várakozót, kicsit kisebb a késleltetés, de időnként kiesik egy kocka (apró rándulás)."),
            listOf("fifo" to tx("FIFO (default)", "FIFO (alapértelmezett)"), "mailbox" to "Mailbox"), blocked, { error = it },
            onSelected = { present = it })
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TuningChoice("fifolatency", tx("FIFO latency", "FIFO késleltetés"),
            tx("Only with FIFO. Smooth (default): frames queue up as they come, about 60 ms from frame to screen at 60 Hz, never a skipped frame. Lower (experimental): after a hitch the app takes a queued frame out (one skipped frame) and stops at the first level that works (about 43 ms). Lowest (experimental): also tries one step further each time; if that does not hold it costs one more small hitch, then the app keeps the level that held.",
                "Csak FIFO-val. Sima (alapértelmezett): a kockák úgy állnak sorba, ahogy jönnek, 60 Hz-en kb. 60 ms a kockától a képig, soha nincs kihagyott kocka. Alacsonyabb (kísérleti): akadás után az app kivesz egy beragadt kockát (egy kihagyás), és az első működő szinten megáll (kb. 43 ms). Legalacsonyabb (kísérleti): minden alkalommal megpróbál még egy lépést lejjebb; ha az nem tart, az még egy apró akadás, utána a tartós szinten marad."),
            listOf("" to tx("Smooth (default)", "Sima (alapértelmezett)"), "low" to tx("Lower (experimental)", "Alacsonyabb (kísérleti)"),
                "lowest" to tx("Lowest (experimental)", "Legalacsonyabb (kísérleti)")), blocked, { error = it },
            unavailable = if (gamescope) xserverOnly() else fifoLatencyUnavailable(presentInputs, present))
        Text(tx("A value typed in Environment Variables always overrides these choices.",
            "A környezeti változókba kézzel beírt érték mindig felülírja ezeket."), style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/** Experimental and diagnostic launch switches for the Settings "Advanced" tab. */
@Composable
internal fun WowAdvancedTuningSection(blocked: Boolean, savedEnvironment: String, legacyTuDefault: Boolean, dx12: Boolean, gamescope: Boolean) {
    val context = LocalContext.current.applicationContext
    var memory by remember { mutableStateOf(WowLaunchPolicy.memory(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(tx("Experimental settings. Applied on the next Play only; container settings are preserved.",
            "Kísérleti beállítások. Csak a következő indításra érvényesek; a konténer beállításai megmaradnak."),
            style = MaterialTheme.typography.bodySmall)
        val owned = !legacyTuDefault && WowLaunchPolicy.hasSavedTuDebugDisplay(savedEnvironment)
        Text(tx("GPU memory mode", "GPU memóriamód"), style = MaterialTheme.typography.titleSmall)
        Text(tx("Where the GPU draws. Sysmem: directly in system memory (recommended). GMEM: in the GPU's small fast on-chip memory, tile by tile. Driver default: for WoW, TurnRIP measures each render pass both ways and keeps the faster one.",
            "Hol rajzol a GPU. Sysmem: közvetlenül a rendszermemóriában (ajánlott). GMEM: a GPU kicsi, gyors belső memóriájában, csempénként. A driver alapértelmezése: WoW-nál a TurnRIP minden render passt mindkét módon lemér, és a gyorsabbat tartja meg."),
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
        Text(tx("Display experiments", "Megjelenítési kísérletek"), style = MaterialTheme.typography.titleSmall)
        for ((key, label, hint) in listOf(
            Triple(WowRuntimeTuning.LOW_LATENCY, tx("Low input latency (DX11)", "Alacsony bemeneti késés (DX11)"),
                tx("The game keeps only one frame ahead of the screen (DXVK frame latency 1) instead of up to three, so input reaches the picture sooner. Off = the game may queue frames ahead for slightly smoother but later frames. A maxFrameLatency typed into DXVK_CONFIG wins.",
                    "A játék csak egy képkockával jár a kijelző előtt (DXVK frame latency 1), nem akár hárommal, így a bevitel hamarabb látszik a képen. Ki = a játék előre sorba állíthat képkockákat: picit simább, de későbbi kép. A DXVK_CONFIG-ba írt maxFrameLatency mindig nyer.")),
            Triple(WowRuntimeTuning.LOW_LATENCY_DX12, tx("Low input latency (DX12)", "Alacsony bemeneti késés (DX12)"),
                tx("The game keeps only one frame ahead of the screen (VKD3D swapchain latency 1) instead of up to three, so input reaches the picture sooner. The bundled TurnRIP then raises the GPU clock by frame time, so it costs no FPS. Off = VKD3D's own 3 frames. A VKD3D_SWAPCHAIN_LATENCY_FRAMES typed into Environment Variables wins.",
                    "A játék csak egy képkockával jár a kijelző előtt (VKD3D swapchain latency 1), nem akár hárommal, így a bevitel hamarabb látszik a képen. A beépített TurnRIP ilyenkor a képidő alapján emeli a GPU órajelét, így nem kerül FPS-be. Ki = a VKD3D saját 3 képkockája. A környezeti változókba írt VKD3D_SWAPCHAIN_LATENCY_FRAMES mindig nyer.")),
            Triple(WowRuntimeTuning.CPU_CLASSES, tx("Report big/little CPU cores (experimental)", "Nagy/kis CPU-magok jelzése (kísérleti)"),
                tx("Tells the game which cores are the fastest ones (the phone's prime cores) the way a hybrid laptop CPU does, so WoW's own logs show Hybrid:true and it may place its busiest threads on them. On by default. Off = all cores look equal. Changes how WoW schedules threads.",
                    "Jelzi a játéknak, melyek a leggyorsabb magok (a telefon prime magjai), mint egy hibrid laptop-CPU-nál, így a WoW naplója Hybrid:true-t mutat, és a legterheltebb szálait ezekre teheti. Alapból bekapcsolva. Ki = minden mag egyformának látszik. Megváltoztatja, hogyan ütemez a WoW.")),
            Triple(app.gamenative.display.GamescopeWindowPolicy.DIRECT_SCANOUT, tx("Gamescope direct scanout (experimental)", "Gamescope közvetlen kijelzés (kísérleti)"),
                tx("Gamescope only. Gamescope hands the game's own frame to the display instead of redrawing it on the GPU every frame, which saves power. It falls back to redrawing by itself when that is not possible (effects, a refused frame). If the picture flickers or goes black, turn it off. Takes effect from the next launch.",
                    "Csak Gamescope-pal. A Gamescope a játék saját képkockáját adja a kijelzőnek, ahelyett hogy minden képkockánál újrarajzolná a GPU-n, így kevesebbet fogyaszt. Ha ez nem lehetséges (effektek, visszautasított képkocka), magától visszavált az újrarajzolásra. Ha villog vagy fekete a kép, kapcsold ki. A következő indítástól él.")),
            Triple(WowRuntimeTuning.GAME_GPU_WAIT, tx("DX12: wait for the GPU before showing a frame", "DX12: a GPU megvárása a kép megjelenítése előtt"),
                tx("XServer with DX12 only. The game's frame reaches the screen only once the GPU has finished drawing it. Without this the screen could sometimes show the previous picture (the loading bar jumping back, stutter while the GPU is busy). The FPS limit and frame pacing stay as they are. Takes effect from the next launch.",
                    "Csak XServer + DX12 esetén. A játék képe csak akkor kerül a kijelzőre, amikor a GPU már befejezte a rajzolását. Enélkül a képernyőn néha az előző kép jelenhetett meg (a betöltőcsík visszaugrik, rángás, amikor a GPU terhelt). Az FPS-limit és a képütemezés nem változik. A következő indítástól él.")),
            Triple(WowRuntimeTuning.EVENT_POOL, tx("Reuse Wine events (Proton r32)", "Wine-események újrahasznosítása (Proton r32)"),
                tx("For a minute or two after login WoW creates and closes thousands of small events a second. Each one used to go through Wine's single server, and the game's main thread had to queue behind them: the picture stuttered. With this on, Proton keeps such events and reuses them, so the server is left alone. Off = exactly the previous Proton (r31) behaviour. Takes effect from the next launch.",
                    "Belépés után egy-két percig a WoW másodpercenként több ezer kis eseményt hoz létre és zár be. Ezek eddig mind a Wine egyetlen szerverén mentek át, és a játék fő szálának sorban kellett állnia mögöttük: akadt a kép. Bekapcsolva a Proton megtartja és újrahasznosítja ezeket az eseményeket, így a szervert nem terhelik. Ki = pontosan az előző Proton (r31) működése. A következő indítástól él.")),
            Triple("xnonblock", tx("Non-blocking X11 output (experimental)", "Nem blokkoló X11 kimenet (kísérleti)"),
                tx("Normally the display server waits while a program is not reading its answers or events, and that wait can freeze everyone else for a moment. With this on, the unread output is queued and sent when the program reads again (a program that never reads is dropped after 64 MB). Off by default: it changes the protocol output path, so if anything misbehaves, turn it off.",
                    "Alapból a megjelenítő szerver vár, amíg egy program nem olvassa a válaszait vagy eseményeit, és ez a várakozás egy pillanatra mindenki mást megállíthat. Bekapcsolva az el nem olvasott kimenet sorba kerül, és akkor megy ki, amikor a program újra olvas (a soha nem olvasó programot 64 MB után eldobja). Alapból ki van kapcsolva: a protokoll kimeneti útját érinti, ezért ha bármi hibásan működik, kapcsold ki.")),
            Triple(WowRuntimeTuning.HYBRID_GPL, tx("Hybrid keeps GPL (DX11)", "Hibrid megtartja a GPL-t (DX11)"),
                tx("Only matters when DX11 shader compilation is set to Hybrid. Normally Hybrid compiles every missing shader in full, which can freeze the render thread for 100+ ms. With this on, Hybrid fast-links pipelines like GPL does and still skips draws only while a shader is not ready. On by default: if the game crashes or the screen goes black, turn it off or use GPL.",
                    "Csak akkor számít, ha a DX11 shaderfordítás Hibrid. Hibridben alapból minden hiányzó shader teljes fordítással készül, ami 100+ ms-ra megállíthatja a render szálat. Bekapcsolva a Hibrid a GPL-hez hasonlóan gyorsan összekapcsolja a pipeline-okat, és csak addig hagy ki rajzolást, amíg egy shader nem kész. Alapból bekapcsolva: ha a játék összeomlik vagy fekete a kép, kapcsold ki, vagy használj GPL-t.")),
            Triple(WowRuntimeTuning.VEGAS_BINDSKIP, tx("DXVK bind-skip (DX11)", "DXVK bind-skip (DX11)"),
                tx("Skips a pipeline bind when the previous draw in the same command buffer already used the same pipeline, which saves CPU work on the game's render thread. On by default: if you see missing geometry or glitches, turn it off. It only affects DX11 (DXVK); the Quick Menu has no switch for it.",
                    "Kihagy egy pipeline-kötést, ha az ugyanabban a parancspufferben előző rajzolás már ugyanazt a pipeline-t használta; ez CPU-munkát spórol a játék render szálán. Alapból bekapcsolva: ha hiányzó geometriát vagy hibákat látsz, kapcsold ki. Csak a DX11-et (DXVK) érinti; a Quick Menüben nincs hozzá kapcsoló.")),
            Triple("presentidle", tx("Safe buffer hand-back (Present, experimental)", "Biztonságos pufferátadás (Present, kísérleti)"),
                tx("The game is told a frame buffer is free again only after the app has finished drawing it, instead of at once. This rules out the rare case where the game overwrites a buffer that is still on its way to the screen (a flash or torn frame). It does not make anything faster; on by default. Takes effect from the next launch.",
                    "A játék csak azután kapja meg, hogy egy képkocka-puffer újra szabad, hogy az app végzett a kirajzolásával, nem azonnal. Ez kizárja azt a ritka esetet, amikor a játék felülír egy puffert, ami még úton van a kijelző felé (villanás vagy tört kép). Semmit nem gyorsít; alapból bekapcsolva. A következő indítástól él.")),
            Triple("priority", tx("Display thread priority", "Megjelenítő szálak prioritása"),
                tx("Gives the app's frame-presenting threads higher priority so frames are delivered on time.",
                    "Magasabb prioritást ad az app képkocka-megjelenítő szálainak, hogy a képkockák időben kikerüljenek.")),
            Triple("adpf", tx("ADPF compositor hints (Android 13+)", "ADPF compositor-jelzések (Android 13+)"),
                tx("Tells Android how long each frame of the compositor takes, so it can raise CPU clocks before frames run late.",
                    "Jelzi az Androidnak, mennyi ideig tart a compositor egy képkockája, hogy időben emelhesse a CPU órajelét.")))) {
            var enabled by remember(key) { mutableStateOf(WowLaunchPolicy.enabled(context, key)) }
            val unavailable = when (switchNeeds(key, dx12, gamescope)) {
                SwitchNeeds.XSERVER -> xserverOnly()
                SwitchNeeds.GAMESCOPE -> tx("Available with Gamescope. XServer uses the app's own compositor.",
                    "Gamescope módban érhető el. Az XServer az app saját kompozitorát használja.")
                SwitchNeeds.DX11 -> onlyDx11()
                SwitchNeeds.DX12 -> onlyDx12()
                null -> null
            }
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

/** What an Advanced-tab switch needs to act; the switch is greyed out with the reason while the next launch lacks it. */
internal enum class SwitchNeeds { DX11, DX12, XSERVER, GAMESCOPE }

/** Null when the switch acts on the next launch with this API and display backend. */
internal fun switchNeeds(key: String, dx12: Boolean, gamescope: Boolean): SwitchNeeds? = when {
    gamescope && key in xserverOnlySettings -> SwitchNeeds.XSERVER
    !gamescope && key in gamescopeOnlySettings -> SwitchNeeds.GAMESCOPE
    dx12 && key in dx11Only -> SwitchNeeds.DX11
    !dx12 && key in dx12Only -> SwitchNeeds.DX12
    else -> null
}

/** Switches that only act on the DX11 path (DXVK / Vegas); 1.1.7: Low input latency is the DX11 one, DX12 has LOW_LATENCY_DX12. */
private val dx11Only = setOf(WowRuntimeTuning.LOW_LATENCY, WowRuntimeTuning.HYBRID_GPL, WowRuntimeTuning.VEGAS_BINDSKIP)
/** WowLaunchEnvironment applies the GPU wait only for XServer + DX12; since 1.1.7 DX12 has its own low-latency switch. */
private val dx12Only = setOf(WowRuntimeTuning.LOW_LATENCY_DX12, WowRuntimeTuning.GAME_GPU_WAIT)
private val xserverOnlySettings = setOf("scanout", "xnonblock", "presentidle", "priority", "adpf", WowRuntimeTuning.GAME_GPU_WAIT)
private val gamescopeOnlySettings = setOf(app.gamenative.display.GamescopeWindowPolicy.DIRECT_SCANOUT)
