package app.gamenative.ui.screen.wow

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.ui.component.dialog.WowCard
import app.gamenative.ui.component.dialog.WowLauncherTheme
import app.gamenative.ui.component.dialog.WowTabRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import app.gamenative.ui.component.dialog.WowUi
import app.gamenative.utils.Net
import app.gamenative.wow.addons.AddonCategory
import app.gamenative.wow.addons.AddonDb
import app.gamenative.wow.addons.AddonFlavor
import app.gamenative.wow.addons.AddonListing
import app.gamenative.wow.addons.AddonManager
import app.gamenative.wow.addons.InstalledAddon
import app.gamenative.wow.addons.NeedsWebsiteDownload
import app.gamenative.wow.addons.ProviderIds
import app.gamenative.wow.addons.provider.CurseForgeKey
import app.gamenative.wow.addons.provider.CurseForgeProvider
import app.gamenative.wow.addons.provider.GitHubProvider
import app.gamenative.wow.addons.provider.WowInterfaceProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owner of addon writes (install, update, remove): they run in this app-wide scope, so closing the addon page never cuts one in
 * half, and the launcher keeps Play disabled while one is running (Codex OPT13-04). Searches stay in the page's own scope and are
 * cancelled with it.
 */
internal object WowAddonJobs {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var running by mutableStateOf(0)
        private set
    /** The running write as (English, Hungarian), shared by every page instance: a reopened page shows it too; null when idle. */
    var label by mutableStateOf<Pair<String, String>?>(null)
        private set
    fun begin(label: Pair<String, String>? = null) { running++; if (label != null) this.label = label }
    fun end() { running--; if (running == 0) label = null }
}

/**
 * Addon manager behind the launcher (swipe left from the home page). Sources: GitHub releases and WoWInterface, merged into one
 * list; only addons with a WoW Forever file are offered (the addon backend is from WoWForeverywhere, GPL-3.0).
 */
internal class WowAddonsController(gameRoot: File, private val scope: CoroutineScope) {
    enum class Mode { INSTALLED, BROWSE }

    val addonsDir = File(gameRoot, "${WowClientDownloader.FLAVOR_DIR}/Interface/AddOns")
    private val curse = CurseForgeProvider()
    private val manager = AddonManager(
        addonsDir = addonsDir,
        db = AddonDb.forAddonsDir(addonsDir),
        // 1.3.4 (user): Browse and search use CurseForge only (the player's own API key). The other two stay for updating what
        // was installed from them earlier and for "From link".
        providers = listOf(curse, WowInterfaceProvider(), GitHubProvider()),
        http = Net.http,
        browseWith = setOf(ProviderIds.CURSEFORGE),
    )

    var mode by mutableStateOf(Mode.INSTALLED); private set
    /** Browse filters (1.3.5): a category (null = all) and the list order. */
    var category by mutableStateOf<AddonCategory?>(null); private set
    var sort by mutableStateOf(CurseForgeProvider.Sort.POPULAR); private set
    fun pickCategory(value: AddonCategory?) { category = value; curse.category = value; if (mode == Mode.BROWSE) load() }
    fun pickSort(value: CurseForgeProvider.Sort) { sort = value; curse.sort = value; if (mode == Mode.BROWSE) load() }
    var query by mutableStateOf(""); private set
    var listings by mutableStateOf<List<AddonListing>>(emptyList()); private set
    var installed by mutableStateOf<List<AddonListing>?>(null); private set
    var loading by mutableStateOf(false); private set
    /** The last browse reached none of the sources: the empty list is a network failure, not "nothing found". */
    var unreachable by mutableStateOf(false); private set
    /** The running write as (English, Hungarian); null when idle. It may belong to a page that was closed meanwhile. */
    val working: Pair<String, String>? get() = WowAddonJobs.label
    /** A write runs (from this page or from an earlier, closed one): every write action waits for it. */
    val busy: Boolean get() = WowAddonJobs.running > 0
    var message by mutableStateOf<Pair<String, String>?>(null)
    val updates get() = installed?.count { it.updateAvailable } ?: 0
    private var loadJob: Job? = null

    fun refreshInstalled() {
        scope.launch {
            installed = runCatching { withContext(Dispatchers.IO) { manager.installed() } }.getOrDefault(emptyList())
            if (mode == Mode.INSTALLED) showInstalled()
        }
    }

    fun switchMode(newMode: Mode) {
        mode = newMode
        if (newMode == Mode.INSTALLED) showInstalled() else load()
    }

    fun search(text: String) {
        query = text
        if (mode == Mode.INSTALLED) showInstalled() else load(debounce = true)
    }

    private fun showInstalled() {
        val q = query.trim().lowercase()
        listings = installed.orEmpty().filter { q.isEmpty() || q in it.remote.name.lowercase() }
    }

    private fun load(debounce: Boolean = false) {
        loadJob?.cancel()
        loadJob = scope.launch {
            if (debounce) delay(400)
            loading = true
            try {
                val found = withContext(Dispatchers.IO) {
                    if (query.isBlank()) manager.popularOutcome(includeClassic = false, category = category)
                    else manager.searchOutcome(query, includeClassic = false, category = category)
                }
                unreachable = found.noSourceReachable
                // Only what WoW Forever loads: a file made for Forever (Classic-only and unknown builds are left out).
                listings = found.listings.filter { it.bestFlavor == AddonFlavor.FOREVER }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unreachable = true
                listings = emptyList()
            } finally {
                loading = false
            }
        }
    }

    /** CurseForge-website-only files waiting for the in-app browser download (1.3.6); the first one is shown. */
    var websiteQueue by mutableStateOf<List<NeedsWebsiteDownload>>(emptyList()); private set

    fun browserDownloaded(request: NeedsWebsiteDownload, url: String, headers: Map<String, String>) {
        websiteQueue = websiteQueue.drop(1)
        act("Installing ${request.remote.name}" to "${request.remote.name} telepítése") {
            manager.installFromBrowser(request.remote, request.file, url, headers)
        }
    }

    fun skipWebsite() { websiteQueue = websiteQueue.drop(1) }

    fun install(l: AddonListing) = act("Installing ${l.remote.name}" to "${l.remote.name} telepítése") { manager.install(l.remote) }
    fun update(l: AddonListing) = act("Updating ${l.remote.name}" to "${l.remote.name} frissítése") {
        val record = l.installed ?: return@act
        if (record.flavor == AddonFlavor.CLASSIC && l.bestFlavor == AddonFlavor.FOREVER) manager.install(l.remote) else manager.update(record)
    }
    fun remove(record: InstalledAddon) = act("Removing ${record.name}" to "${record.name} eltávolítása") { manager.remove(record) }
    fun updateAll() = act("Updating all addons" to "Minden addon frissítése") {
        manager.updateAll()
        val failures = manager.lastUpdateFailures
        val website = failures.mapNotNull { it.second as? NeedsWebsiteDownload }
        if (website.isNotEmpty()) withContext(Dispatchers.Main) { websiteQueue = websiteQueue + website }
        val others = failures.filter { it.second !is NeedsWebsiteDownload }.map { it.first.name }
        if (others.isNotEmpty()) throw java.io.IOException("not updated: " + others.joinToString(", "))
    }
    fun installFromUrl(url: String) = act("Installing from link" to "Telepítés linkről") { manager.installFromUrl(url.trim()) }

    private fun act(label: Pair<String, String>, block: suspend () -> Unit) {
        // One write at a time for the whole app: each page instance has its own AddonManager (and lock), so the shared count is the gate.
        if (busy || app.gamenative.PluviaApp.keepAlive) return
        message = null
        WowAddonJobs.begin(label)
        WowAddonJobs.scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                message = "${label.first}: done." to "${label.second}: kész."
            } catch (e: CancellationException) {
                throw e
            } catch (e: NeedsWebsiteDownload) {
                websiteQueue = websiteQueue + e
                message = null
            } catch (e: Exception) {
                val reason = e.message ?: "unknown error"
                message = "${label.first} failed: $reason" to "${label.second} nem sikerült: $reason"
            } finally {
                WowAddonJobs.end()
                installed = runCatching { withContext(Dispatchers.IO) { manager.installed() } }.getOrDefault(installed.orEmpty())
                if (mode == Mode.INSTALLED) showInstalled() else listings = listings.map { refreshed(it) }
            }
        }
    }

    private fun refreshed(l: AddonListing): AddonListing {
        val record = installed?.firstOrNull { it.remote.provider == l.remote.provider && it.remote.projectId == l.remote.projectId }
        return l.copy(installed = record?.installed, updateAvailable = record?.updateAvailable ?: false)
    }
}

/** The addon page: installed addons with updates, a search over the sources, and install from a link. */
@Composable
internal fun WowAddonsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gamePath = remember { GamePath.load(context) }
    val ready = remember { GamePath.isReady(context, gamePath) }
    val c = remember(gamePath) { WowAddonsController(File(gamePath), scope) }
    // Also after a write that an earlier, closed page started has finished.
    val idle = WowAddonJobs.running == 0
    LaunchedEffect(c, idle) { if (ready && idle) c.refreshInstalled() }
    var linkDialog by remember { mutableStateOf(false) }
    var keyDialog by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(CurseForgeKey.load(context.applicationContext).isNotBlank()) }
    var removing by remember { mutableStateOf<InstalledAddon?>(null) }

    WowLauncherTheme {
        // The same frame as Settings: 15 % smaller than the system scale, serif gold title, equal tabs, Close at the bottom right.
        val systemDensity = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(systemDensity.density * 0.85f, systemDensity.fontScale),
        ) {
        Box(Modifier.fillMaxSize().background(WowUi.background).windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column {
                    Text(tx("ADDONS", "ADDONOK"), fontSize = 22.sp, fontWeight = FontWeight.Black,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, letterSpacing = 5.sp, color = WowUi.Gold)
                    Text(tx("Only addons made for WoW Forever.", "Csak a WoW Forever-hez készült addonok."),
                        color = WowUi.Muted, fontSize = 12.sp)
                }
                if (!ready) {
                    WowCard { Text(tx("Install the game first: the addons go into its Interface/AddOns folder.",
                        "Előbb telepítsd a játékot: az addonok az Interface/AddOns mappájába kerülnek.")) }
                    Spacer(Modifier.weight(1f))
                    AddonsCloseButton(onBack)
                    return@Column
                }
                val updates = c.updates
                val modes = listOf(WowAddonsController.Mode.INSTALLED, WowAddonsController.Mode.BROWSE)
                WowTabRow(listOf(tx("Installed", "Telepítettek") + if (updates > 0) " ($updates ↑)" else "", tx("Browse", "Böngészés")),
                    modes.indexOf(c.mode), enabled = true, maxSize = 15.sp) { c.switchMode(modes[it]) }
                // 1.3.6: the search field has the full width (beside the buttons it was squeezed to nothing on a phone held upright),
                // the actions are one compact scrollable row under it.
                OutlinedTextField(value = c.query, onValueChange = c::search, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(tx("Search addons", "Addon keresése"), maxLines = 1) })
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (c.mode == WowAddonsController.Mode.INSTALLED && updates > 0) {
                        AddonAction(tx("Update all", "Mindet frissít") + " ($updates)", primary = true, enabled = !c.busy, onClick = c::updateAll)
                    }
                    AddonAction(tx("From link", "Linkről"), enabled = !c.busy) { linkDialog = true }
                    AddonAction(tx("CurseForge key", "CurseForge-kulcs"), enabled = !c.busy) { keyDialog = true }
                }
                if (c.mode == WowAddonsController.Mode.BROWSE && hasKey) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((sort, label) in listOf(CurseForgeProvider.Sort.POPULAR to tx("Popular", "Népszerű"),
                            CurseForgeProvider.Sort.UPDATED to tx("Recently updated", "Nemrég frissült"),
                            CurseForgeProvider.Sort.DOWNLOADS to tx("Most downloaded", "Legtöbb letöltés"),
                            CurseForgeProvider.Sort.NAME to tx("Name", "Név"))) AddonChip(label, c.sort == sort) { c.pickSort(sort) }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AddonChip(tx("All categories", "Minden kategória"), c.category == null) { c.pickCategory(null) }
                        for (category in AddonCategory.entries) AddonChip(categoryLabel(category), c.category == category) { c.pickCategory(category) }
                    }
                }
                c.working?.let { (en, hu) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text(tx(en, hu), color = WowUi.Gold)
                    }
                }
                c.message?.let { (en, hu) -> Text(tx(en, hu), color = if ("failed" in en || "Couldn't" in en) WowUi.Error else WowUi.Muted, fontSize = 12.sp) }
                when {
                    c.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    c.mode == WowAddonsController.Mode.BROWSE && !hasKey -> Text(
                        tx("Browsing uses CurseForge and needs your own free API key. Get one at console.curseforge.com, then tap \"CurseForge key\".",
                            "A böngészés a CurseForge-ot használja, ehhez a saját, ingyenes API-kulcsod kell. Igényelj egyet a console.curseforge.com oldalon, majd koppints a „CurseForge-kulcs” gombra."),
                        color = WowUi.Muted)
                    c.listings.isEmpty() && c.mode == WowAddonsController.Mode.BROWSE && c.unreachable -> Text(
                        tx("Couldn't reach the addon sites. Check your connection.", "Nem érhetők el az addon-oldalak. Ellenőrizd a kapcsolatot."),
                        color = WowUi.Error)
                    c.listings.isEmpty() -> Text(
                        if (c.mode == WowAddonsController.Mode.INSTALLED) tx("No addons installed yet. Open Browse to find some.",
                            "Még nincs telepített addon. A Böngészés fülön kereshetsz.")
                        else tx("Nothing found for WoW Forever.", "Nincs találat a WoW Forever-hez."),
                        color = WowUi.Muted)
                    else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(c.listings, key = { "${it.remote.provider}:${it.remote.projectId}:${it.installed?.id}" }) { l ->
                            AddonRow(l, busy = c.busy, onInstall = { c.install(l) }, onUpdate = { c.update(l) },
                                onRemove = { l.installed?.let { removing = it } })
                        }
                    }
                }
                if (c.loading || c.listings.isEmpty()) Spacer(Modifier.weight(1f))
                AddonsCloseButton(onBack)
            }
        }
        }
        if (linkDialog) {
            var url by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { linkDialog = false },
                title = { Text(tx("Install from link", "Telepítés linkről")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tx("A GitHub repository (github.com/owner/repo) or a direct .zip link.",
                            "GitHub-repó (github.com/szerző/repó) vagy közvetlen .zip link."), fontSize = 12.sp)
                        OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = { TextButton(enabled = url.isNotBlank() && !c.busy, onClick = { linkDialog = false; c.installFromUrl(url) }) { Text(tx("Install", "Telepítés")) } },
                dismissButton = { TextButton(onClick = { linkDialog = false }) { Text(tx("Cancel", "Mégse")) } })
        }
        if (keyDialog) {
            var key by remember { mutableStateOf(CurseForgeKey.value) }
            AlertDialog(onDismissRequest = { keyDialog = false },
                title = { Text(tx("CurseForge API key", "CurseForge API-kulcs")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tx("Your own key from console.curseforge.com. It is stored only on this phone and sent only to CurseForge. Leave it empty to remove it.",
                            "A saját kulcsod a console.curseforge.com oldalról. Csak ezen a telefonon tárolódik, és csak a CurseForge kapja meg. Hagyd üresen a törléséhez."), fontSize = 12.sp)
                        OutlinedTextField(value = key, onValueChange = { key = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        // Where the free key comes from: CurseForge's developer console (sign in, then "API keys").
                        AddonAction(tx("Get a key on CurseForge", "Kulcs igénylése a CurseForge-on")) {
                            runCatching {
                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(CURSEFORGE_CONSOLE)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                        Text(tx("Sign in there, open \"API keys\" and copy your key into this field.",
                            "Jelentkezz be, nyisd meg az „API keys” részt, és másold a kulcsot ebbe a mezőbe."), fontSize = 12.sp, color = WowUi.Muted)
                    }
                },
                confirmButton = { TextButton(enabled = !c.busy, onClick = {
                    keyDialog = false
                    CurseForgeKey.save(context.applicationContext, key); hasKey = CurseForgeKey.value.isNotBlank()
                    c.search(c.query)
                }) { Text(tx("Save", "Mentés")) } },
                dismissButton = { TextButton(onClick = { keyDialog = false }) { Text(tx("Cancel", "Mégse")) } })
        }
        c.websiteQueue.firstOrNull()?.let { request ->
            WebsiteDownloadDialog(request, onDownloaded = { url, headers -> c.browserDownloaded(request, url, headers) }, onCancel = c::skipWebsite)
        }
        removing?.let { record ->
            AlertDialog(onDismissRequest = { removing = null },
                title = { Text(tx("Remove ${record.name}?", "Eltávolítod: ${record.name}?")) },
                text = { Text(tx("Its folders are deleted from Interface/AddOns: ", "Ezek a mappák törlődnek az Interface/AddOns-ból: ") +
                    record.folders.joinToString(", ")) },
                confirmButton = { TextButton(enabled = !c.busy, onClick = { removing = null; c.remove(record) }) { Text(tx("Remove", "Eltávolítás")) } },
                dismissButton = { TextButton(onClick = { removing = null }) { Text(tx("Cancel", "Mégse")) } })
        }
    }
}

/** A filter chip of the Browse tab: gold when selected. */
@Composable
private fun AddonChip(label: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = RoundedCornerShape(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) WowUi.Gold else WowUi.Muted.copy(alpha = 0.5f))) {
        Text(label, color = if (selected) WowUi.Gold else WowUi.Muted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun categoryLabel(category: AddonCategory): String = when (category) {
    AddonCategory.QUESTS -> tx("Quests & leveling", "Küldetések és szintlépés")
    AddonCategory.MAP -> tx("Map & navigation", "Térkép és navigáció")
    AddonCategory.RAIDS -> tx("Raids & bosses", "Raidek és bossok")
    AddonCategory.COMBAT -> tx("Combat & meters", "Harc és mérők")
    AddonCategory.UNIT_FRAMES -> tx("Unit frames", "Egységkeretek")
    AddonCategory.ACTION_BARS -> tx("Action bars", "Akciósávok")
    AddonCategory.BUFFS -> tx("Buffs & cooldowns", "Buffok és cooldownok")
    AddonCategory.BAGS -> tx("Bags & inventory", "Táskák és tárgyak")
    AddonCategory.AUCTION -> tx("Auction & gold", "Aukció és arany")
    AddonCategory.PROFESSIONS -> tx("Professions", "Szakmák")
    AddonCategory.CHAT -> tx("Chat & social", "Chat és közösség")
    AddonCategory.TOOLTIPS -> tx("Tooltips", "Tooltipek")
    AddonCategory.INTERFACE -> tx("Interface & media", "Felület és média")
    AddonCategory.CLASS -> tx("Class", "Kaszt")
    AddonCategory.PVP -> tx("PvP", "PvP")
    AddonCategory.PETS -> tx("Mounts & pets", "Hátasok és kedvencek")
    AddonCategory.LIBRARIES -> tx("Libraries", "Könyvtárak")
    AddonCategory.OTHER -> tx("Other", "Egyéb")
}

/** Close at the bottom right, the same button as in Settings; back to the launcher. */
@Composable
private fun AddonsCloseButton(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OutlinedButton(onClick = onBack, shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFFA0AEC0).copy(alpha = 0.6f)),
            modifier = Modifier.height(48.dp)) {
            Text(tx("Close", "Bezárás"), color = WowUi.Gold, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AddonRow(l: AddonListing, busy: Boolean, onInstall: () -> Unit, onUpdate: () -> Unit, onRemove: () -> Unit) {
    // 1.3.6: the name gets the card's full width (two lines); the buttons sit in their own row under the text.
    WowCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(36.dp).background(WowUi.GoldFill, CircleShape), contentAlignment = Alignment.Center) {
                    Text(l.remote.name.trim().take(1).uppercase(), color = WowUi.Cream, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.remote.name.trim(), color = WowUi.Cream, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val version = l.installed?.version ?: l.remote.latest[AddonFlavor.FOREVER]?.version
                    Text(listOfNotNull(l.remote.author.takeIf { it.isNotBlank() }, version).joinToString(" · "),
                        color = WowUi.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (l.remote.summary.isNotBlank()) Text(l.remote.summary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = WowUi.Muted)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                when {
                    l.installed == null -> AddonAction(tx("Install", "Telepítés"), primary = true, enabled = !busy, onClick = onInstall)
                    else -> {
                        if (l.updateAvailable) AddonAction(tx("Update", "Frissítés"), primary = true, enabled = !busy, onClick = onUpdate)
                        AddonAction(tx("Remove", "Eltávolítás"), enabled = !busy, onClick = onRemove)
                    }
                }
            }
        }
    }
}

/** CurseForge's developer console, where a player gets the free API key. */
private const val CURSEFORGE_CONSOLE = "https://console.curseforge.com/"

/** A compact action button of the addon page (one line, small padding). */
@Composable
private fun AddonAction(label: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp)
    if (primary) Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp), contentPadding = padding,
        modifier = Modifier.height(36.dp)) { Text(label, fontSize = 13.sp, maxLines = 1) }
    else OutlinedButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp), contentPadding = padding,
        modifier = Modifier.height(36.dp)) { Text(label, fontSize = 13.sp, maxLines = 1, color = WowUi.Gold) }
}

/**
 * 1.3.6: an addon whose only release is on CurseForge and which its author keeps off third-party apps: its download page opens
 * here, in the app, and the player taps Download like in a browser. The file the page hands over is installed by the app.
 */
@Composable
private fun WebsiteDownloadDialog(request: NeedsWebsiteDownload, onDownloaded: (String, Map<String, String>) -> Unit, onCancel: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(WowUi.background).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(request.remote.name.trim(), color = WowUi.Gold, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tx("Its author allows downloading only on the CurseForge website. Tap Download on the page (it may also start by itself); the app installs the file.",
                "A szerzője csak a CurseForge weboldaláról engedi letölteni. Koppints az oldalon a Download gombra (magától is elindulhat); az app telepíti a fájlt."),
                color = WowUi.Muted, fontSize = 12.sp)
            var handed by remember(request) { mutableStateOf(false) }
            androidx.compose.ui.viewinterop.AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { ctx ->
                android.webkit.WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: android.webkit.WebView, req: android.webkit.WebResourceRequest) =
                            req.url.scheme != "https"
                    }
                    setDownloadListener { url, userAgent, _, _, _ ->
                        if (handed || !url.startsWith("https://")) return@setDownloadListener
                        handed = true
                        val headers = buildMap {
                            put("User-Agent", userAgent)
                            put("Referer", request.pageUrl)
                            android.webkit.CookieManager.getInstance().getCookie(url)?.let { put("Cookie", it) }
                        }
                        onDownloaded(url, headers)
                    }
                    loadUrl(request.pageUrl)
                }
            }, onRelease = { it.destroy() })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AddonAction(tx("Cancel", "Mégse"), onClick = onCancel)
            }
        }
    }
}
