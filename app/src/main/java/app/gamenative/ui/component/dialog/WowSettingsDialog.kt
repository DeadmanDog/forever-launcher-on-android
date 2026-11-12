package app.gamenative.ui.component.dialog

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.PluviaApp
import app.gamenative.ui.screen.wow.DeveloperShell
import app.gamenative.ui.screen.wow.DeveloperShellDialog
import app.gamenative.ui.screen.wow.WowContainerProfile
import app.gamenative.ui.screen.wow.WowLanguage
import app.gamenative.ui.screen.wow.WowQuickSettings
import app.gamenative.ui.screen.wow.WowRuntimeTuning
import app.gamenative.ui.screen.wow.rememberTx
import app.gamenative.ui.screen.wow.tx
import com.winlator.container.ContainerFiles
import com.winlator.container.ContainerManager
import com.winlator.core.KeyValueSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SettingsTab { QUICK, GAME, COMPONENTS, ADVANCED }

/** Container facts read on IO; the UI never holds a mutable Container. */
private data class SettingsSnapshot(
    val status: WowQuickSettings.Status,
    val fps: WowQuickSettings.FpsLimit,
    val screenSize: String,
    val environment: String,
    val hybridAvailable: Boolean,
    val legacyTuDefault: Boolean,
    /** The saved graphics API is DX12 (VKD3D): DX11-only choices are greyed out, DX12-only ones enabled. */
    val dx12: Boolean,
    val gamescope: Boolean,
    val limiter: String,
    val info: List<Pair<String, String>>,
    /** What the next launch resolves the present mode from, besides the Quick tab choice (WowLaunchEnvironment.resolvePresentMode). */
    val present: WowQuickPresentInputs = WowQuickPresentInputs(),
)

/**
 * Single entry point for WoW Forever settings: Quick / Game / Components / Advanced (with the expert tools and diagnostics).
 * Every section keeps its existing validated save path; nothing is applied while a game runs.
 */
@Composable
internal fun WowSettingsDialog(
    onDismissRequest: () -> Unit,
    language: WowLanguage,
    onLanguageChange: (WowLanguage) -> Unit,
    /** The launcher's game-storage switch (private vs shared storage), shown on the Game tab; null while no game is found. */
    gameStorage: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val t = rememberTx()
    var tab by remember { mutableStateOf(SettingsTab.QUICK) }
    var snapshot by remember { mutableStateOf<SettingsSnapshot?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val sessionActive = PluviaApp.keepAlive   // snapshot state: no polling
    var busySections by remember { mutableStateOf(emptySet<String>()) }
    var showEnvironment by remember { mutableStateOf(false) }
    var showShell by remember { mutableStateOf(false) }
    var fpsMessage by remember { mutableStateOf<String?>(null) }
    fun busy(key: String, value: Boolean) { busySections = if (value) busySections + key else busySections - key }
    val busy = busySections.isNotEmpty()
    val blocked = busy || sessionActive
    val refresh: () -> Unit = { revision++ }

    LaunchedEffect(revision) {
        try {
            snapshot = withContext(Dispatchers.IO) {
                val c = ContainerManager(context).getContainerById(WowContainerProfile.CONTAINER_ID) ?: return@withContext null
                SettingsSnapshot(
                    status = WowQuickSettings.status(context, c),
                    fps = WowQuickSettings.fpsLimit(c),
                    screenSize = c.screenSize,
                    environment = DeveloperShell.redactedEnvironment(c.envVars),
                    hybridAvailable = WowRuntimeTuning.supportsHybrid(c),
                    legacyTuDefault = WowContainerProfile.hasLegacyManagedTuDebug(c),
                    dx12 = runCatching { app.gamenative.ui.screen.wow.WowVkd3dComponents.api(c) == app.gamenative.ui.screen.wow.WowVkd3dComponents.DX12 }.getOrDefault(false),
                    gamescope = app.gamenative.display.WowDisplayBackend.isGamescope(c),
                    limiter = WowRuntimeTuning.description(context, c),
                    present = if (app.gamenative.display.WowDisplayBackend.isGamescope(c))
                        WowQuickPresentInputs(c.envVars, rendererMode = "fifo")
                    else WowQuickPresentInputs(c.envVars, KeyValueSet(c.graphicsDriverConfig).get("presentMode"), c.rendererPresentMode),
                    info = listOf(
                        "Container ID" to c.id, "executablePath" to c.executablePath, "execArgs" to c.execArgs,
                        "variant" to c.containerVariant, "Wine/Proton" to c.wineVersion, "DXVK" to c.getDXWrapper(),
                        "DXVK config version" to KeyValueSet(c.getDXWrapperConfig()).get("version"),
                        "graphicsDriver" to c.graphicsDriver, "graphicsDriverConfig" to c.graphicsDriverConfig,
                        "graphicsDriverVersion" to c.graphicsDriverVersion, "displayRenderer" to c.displayRenderer,
                        "screenSize" to c.screenSize, "basePrefix" to c.basePrefix,
                        t("Container root", "Konténer gyökérkönyvtára") to c.rootDir.absolutePath, "Prefix upper" to ContainerFiles.upperDir(c).absolutePath,
                        t("Drives", "Meghajtók") to c.drives,
                    ),
                )
            }
            loadError = if (snapshot == null) t("The WoW container has not been created yet. Press Play once first.",
                "A WoW konténer még nem jött létre. Előbb indítsd el egyszer a játékot.") else null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { loadError = e.message }
    }

    val tabs = listOf(SettingsTab.QUICK to tx("Quick", "Gyors"), SettingsTab.GAME to tx("Game", "Játék"), SettingsTab.COMPONENTS to tx("Components", "Komponensek"),
        SettingsTab.ADVANCED to tx("Advanced", "Haladó"))
    val tabButton: @Composable (SettingsTab, String, Boolean) -> Unit = { value, label, fill ->
        WowTab(label, selected = tab == value, enabled = !busy, fill = fill, onClick = { tab = value })
    }
    // Each tab starts at its top instead of inheriting the previous tab's scroll position.
    val contentScroll = remember(tab) { ScrollState(0) }
    val content: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.verticalScroll(contentScroll), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (sessionActive) Text(tx("The game is running: settings can be changed after it stops.", "A játék fut: a beállítások a leállítás után módosíthatók."),
                color = MaterialTheme.colorScheme.error)
            loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val s = snapshot
            when (tab) {
                SettingsTab.QUICK -> {
                    WowCard {
                        Text(tx("WHEN THE APP OPENS", "AZ APP MEGNYITÁSAKOR"), style = MaterialTheme.typography.titleMedium)
                        var autoLaunch by remember { mutableStateOf(app.gamenative.ui.screen.wow.WoWLauncherState.autoLaunchEnabled(context)) }
                        for ((value, label) in listOf(true to tx("Start the game at once", "Azonnal induljon a játék"),
                                false to tx("Open the home page", "A kezdőlap nyíljon meg"))) {
                            Row(Modifier.selectable(selected = autoLaunch == value, role = Role.RadioButton, onClick = {
                                autoLaunch = value; app.gamenative.ui.screen.wow.WoWLauncherState.setAutoLaunch(context, value)
                            }), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = autoLaunch == value, onClick = null); Spacer(Modifier.width(8.dp)); Text(label, modifier = Modifier.padding(vertical = 4.dp))
                            }
                        }
                    }
                    if (s != null) {
                        WowCard { WowVkd3dSection(blocked, onBusy = { busy("api", it) }, part = WowVkd3dPart.API, onChanged = refresh,
                            initialApi = if (s.dx12) app.gamenative.ui.screen.wow.WowVkd3dComponents.DX12 else app.gamenative.ui.screen.wow.WowVkd3dComponents.DX11) }
                        WowCard {
                            FpsLimitSection(s.fps, blocked, fpsMessage) { limit ->
                                scope.launch {
                                    busy("fps", true)
                                    try {
                                        withContext(Dispatchers.IO) { WowQuickSettings.saveFpsLimit(context, limit) { PluviaApp.keepAlive } }
                                        fpsMessage = t("Saved. Applies on the next Play (the in-game Quick Menu can change it too).",
                                            "Mentve. A következő indítástól érvényes (játék közben a Quick Menüben is állítható).")
                                        refresh()
                                    } catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { fpsMessage = e.message }
                                    finally { busy("fps", false) }
                                }
                            }
                        }
                        WowCard { WowQuickTuningSection(blocked, s.hybridAvailable, s.dx12, s.present, s.gamescope) }
                        WowCard { WowDisplaySettingsSection(current = s.screenSize, blocked = blocked, onSaved = refresh, onBusyChanged = { busy("display", it) }) }
                    }
                    WowCard {
                        Text(tx("THEME", "TÉMA"), style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            for ((value, label) in listOf(app.gamenative.ui.theme.WowThemeChoice.BLUE to tx("Blue", "Kék"),
                                    app.gamenative.ui.theme.WowThemeChoice.RED to tx("Red", "Piros"))) {
                                val selected = app.gamenative.ui.theme.WowTheme.choice == value
                                Row(Modifier.selectable(selected = selected, role = Role.RadioButton,
                                    onClick = { app.gamenative.ui.theme.WowTheme.save(context, value) }), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = selected, onClick = null); Spacer(Modifier.width(8.dp)); Text(label, modifier = Modifier.padding(vertical = 4.dp)); Spacer(Modifier.width(12.dp))
                                }
                            }
                        }
                    }
                    WowCard {
                        Text(tx("LANGUAGE", "NYELV"), style = MaterialTheme.typography.titleMedium)
                        Text(tx("The language of the launcher and of these settings.", "A kezdőoldal és ezek a beállítások nyelve."),
                            style = MaterialTheme.typography.bodySmall)
                        // Every language in its own name; wraps on a narrow screen.
                        WowActionRow {
                            for (value in WowLanguage.entries) {
                                val label = if (value == WowLanguage.SYSTEM) tx("System", "Rendszer") else value.label
                                Row(Modifier.selectable(selected = language == value, role = Role.RadioButton, onClick = { onLanguageChange(value) }),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = language == value, onClick = null); Spacer(Modifier.width(8.dp)); Text(label, modifier = Modifier.padding(vertical = 4.dp)); Spacer(Modifier.width(12.dp))
                                }
                            }
                        }
                    }
                }
                SettingsTab.GAME -> {
                    WowCard { WowGameChoicesSection(blocked) }
                    // Where the game lives: its own card right under Region and account.
                    gameStorage?.let { storage ->
                        WowCard {
                            Text(tx("Game storage", "Játék tárolása"), style = MaterialTheme.typography.titleMedium)
                            storage()
                        }
                    }
                    if (s != null) WowCard { WowVoiceChatSection(blocked, onBusy = { busy("voice", it) }) }
                    WowCard { WowCacheDiagnosticsSection(WowCachePart.CACHES, onBusyChanged = { busy("cache", it) }) }
                }
                SettingsTab.COMPONENTS -> if (s != null) {
                    WowCard { WowManagedComponents(blocked, onChanged = refresh, onBusyChanged = { busy("managed-driver", it) },
                        kinds = listOf(app.gamenative.ui.screen.wow.WowDeveloperComponents.Kind.TURNIP)) }
                    WowCard { WowManagedComponents(blocked, onChanged = refresh, onBusyChanged = { busy("managed-runtime", it) },
                        kinds = listOf(app.gamenative.ui.screen.wow.WowDeveloperComponents.Kind.RUNTIME)) }
                    WowCard { WowDxvkSection(blocked, onBusy = { busy("dxvk", it) }, onChanged = refresh) }
                    WowCard { WowVkd3dSection(blocked, onBusy = { busy("vkd3d", it) }, part = WowVkd3dPart.PROFILES, onChanged = refresh) }
                }
                SettingsTab.ADVANCED -> if (s != null) {
                    WowCard { WowAdvancedTuningSection(blocked, s.environment, s.legacyTuDefault, s.dx12, s.gamescope) }
                    WowCard { HardwareScaleSwitch(s.screenSize, blocked, s.gamescope) }
                    WowCard {
                        Text(tx("EXPERT TOOLS", "SZAKÉRTŐI ESZKÖZÖK"), style = MaterialTheme.typography.titleMedium)
                        Text(tx("For advanced users: edit the container's environment variables by hand, or open a command shell inside it. A manually typed value overrides the switches above.",
                            "Haladóknak: a konténer környezeti változóinak kézi szerkesztése, vagy parancssor a konténeren belül. A kézzel beírt érték felülírja a fenti kapcsolókat."),
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(enabled = !blocked, onClick = { showEnvironment = true }, shape = RoundedCornerShape(14.dp)) { Text(tx("Environment variables", "Környezeti változók")) }
                        OutlinedButton(enabled = !blocked, onClick = { showShell = true }, shape = RoundedCornerShape(14.dp)) { Text(tx("Developer shell", "Fejlesztői shell")) }
                        // Diagnostics belong to the expert tools; each part opens only when tapped.
                        WowExpandable(tx("Diagnostic logging", "Diagnosztikai naplózás")) {
                            WowCacheDiagnosticsSection(WowCachePart.DIAGNOSTICS, onBusyChanged = { busy("diagnostics", it) }, showTitle = false)
                        }
                        WowExpandable(tx("Runtime info", "Runtime-információ")) {
                            Text(tx("API limiter: ", "API-limiter: ") + s.limiter, style = MaterialTheme.typography.bodySmall)
                            s.info.forEach { (label, value) ->
                                Text("$label: ${value.ifBlank { tx("(not set)", "(nincs beállítva)") }}",
                                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                            Text(tx("Environment (sensitive values redacted):", "Környezet (érzékeny értékek elrejtve):"), style = MaterialTheme.typography.labelLarge)
                            Text(s.environment.ifBlank { "-" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
    val title: @Composable () -> Unit = {
        Text(tx("SETTINGS", "BEÁLLÍTÁSOK"), fontSize = 22.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Serif,
            letterSpacing = 5.sp, color = WowUi.Gold)
    }
    val closeButton: @Composable (Boolean) -> Unit = { fill ->
        OutlinedButton(enabled = !busy, onClick = onDismissRequest, shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Color(0xFFA0AEC0).copy(alpha = 0.6f)),
            modifier = if (fill) Modifier.fillMaxWidth().height(48.dp) else Modifier.height(48.dp)) {
            Text(tx("Close", "Bezárás"), color = WowUi.Gold, fontWeight = FontWeight.Bold)
        }
    }

    // Opened once its data is in (about a tenth of a second): an empty dialog filling in step by step flashed.
    if (snapshot == null && loadError == null) return
    WowLauncherTheme {
        Dialog(onDismissRequest = { if (!busy) onDismissRequest() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            // Full-screen dialog in the launcher's look: navy gradient, gold serif title, card sections.
            // Drawn 15% smaller than the system scale, like the Quick Menu, so every tab uses one compact size.
            val systemDensity = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(systemDensity.density * 0.85f, systemDensity.fontScale),
            ) {
            Box(Modifier.fillMaxSize().background(WowUi.background)) {
                BoxWithConstraints(Modifier.fillMaxSize().padding(20.dp)) {
                    // Landscape: tabs and status in a side column so the content keeps the full height.
                    if (maxWidth > maxHeight && maxWidth >= 600.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.width(240.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                title()
                                Spacer(Modifier.height(2.dp))
                                for ((value, label) in tabs) tabButton(value, label, true)
                                Spacer(Modifier.height(2.dp))
                                closeButton(true)
                            }
                            content(Modifier.weight(1f).fillMaxHeight())
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            title()
                            // Equal tabs across the width, every label on one line: nothing to drag sideways, no word broken in two.
                            WowTabRow(tabs.map { it.second }, tabs.indexOfFirst { it.first == tab }, enabled = !busy, maxSize = 15.sp) { tab = tabs[it].first }
                            content(Modifier.weight(1f).fillMaxWidth())
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { closeButton(false) }
                        }
                    }
                }
            }
            }
        }
    if (showShell) DeveloperShellDialog { showShell = false }
    if (showEnvironment) WowEnvironmentEditorDialog(onClose = { showEnvironment = false }, onSaved = refresh)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FpsLimitSection(current: WowQuickSettings.FpsLimit, blocked: Boolean, message: String?, onSave: (WowQuickSettings.FpsLimit) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Text(tx("FPS limit", "FPS-limit"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            WowSwitch(checked = current.enabled, enabled = !blocked, onCheckedChange = { onSave(current.copy(enabled = it)) })
        }
        Text(tx("By default the app paces frames to the display (see \"FPS limit pacing\" below). Turn off WoW's own Max Foreground FPS.",
            "Alapból az app a kijelzőhöz igazítva limitál (lásd lejjebb: „FPS-limit ütemezése”). A WoW saját Max Foreground FPS-ét kapcsold ki."),
            style = MaterialTheme.typography.bodySmall)
        // Wraps instead of scrolling sideways, so every target (up to 144) is visible on a narrow screen.
        WowActionRow {
            // A target saved before the list changed (72 ...) stays visible and selected until another is picked.
            (WowQuickSettings.fpsTargets + current.target).distinct().sorted().forEach { target ->
                FilterChip(selected = current.target == target, enabled = !blocked && current.enabled,
                    onClick = { onSave(current.copy(target = target)) }, label = { Text("$target") })
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** A button like "Environment variables" / "Developer shell" that opens its content in the same full-screen window as those. */
@Composable
internal fun WowExpandable(title: String, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, shape = RoundedCornerShape(14.dp)) { Text(title) }
    if (open) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { open = false }) { Text(tx("Close", "Bezárás")) }
                content()
            }
        }
    }
}
