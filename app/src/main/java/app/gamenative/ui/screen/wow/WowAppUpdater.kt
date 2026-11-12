package app.gamenative.ui.screen.wow

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.gamenative.ui.theme.WowTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app update from the project's GitHub releases: the latest release's APK, when its version is newer than this app's.
 * Only the published package (forever.launcher) checks; the beta package and other forks never do. The APK is signed with the
 * same key as the installed app, so Android installs it as an update.
 */
internal object WowAppUpdater {
    const val REPO = "DeadmanDog/forever-launcher-on-android"
    private const val PACKAGE = "forever.launcher"
    private const val PREFS = "wow_forever_update"
    private const val CHECK_EVERY_MS = 6L * 60 * 60 * 1000

    data class Release(val version: String, val apkUrl: String, val apkSize: Long, val notes: String, val page: String)

    fun enabled(context: Context) = context.packageName == PACKAGE

    fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    /** "v1.0.2", "1.0.2-beta" -> [1, 0, 2]; anything that is not a version gives an empty list. */
    fun parts(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore(' ').split('.')
            .map { it.toIntOrNull() ?: return emptyList() }

    fun isNewer(remote: String, local: String): Boolean {
        val r = parts(remote); val l = parts(local)
        if (r.isEmpty() || l.isEmpty()) return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** The release JSON of GitHub's "latest release" API, reduced to what the card needs; null when it has no APK. */
    fun parse(json: String): Release? {
        val o = JSONObject(json)
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", ignoreCase = true))
                return Release(o.optString("tag_name").ifBlank { o.optString("name") }, a.getString("browser_download_url"),
                    a.optLong("size"), o.optString("body").trim(), o.optString("html_url"))
        }
        return null
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "ForeverLauncher")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    /** Network; call on IO. */
    fun latest(): Release? {
        val c = open("https://api.github.com/repos/$REPO/releases/latest")
        try {
            if (c.responseCode != 200) return null
            return parse(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    /** A newer release found by an earlier check that the player has not put off. */
    fun pending(context: Context): Release? {
        if (!enabled(context)) return null
        val p = context.getSharedPreferences(PREFS, 0)
        val version = p.getString("version", null) ?: return null
        if (!isNewer(version, installedVersion(context)) || p.getString("dismissed", null) == version) return null
        return Release(version, p.getString("url", "").orEmpty(), p.getLong("size", 0), p.getString("notes", "").orEmpty(), p.getString("page", "").orEmpty())
    }

    /** Checks GitHub at most every 6 hours (or now when [force]); remembers a newer release. Call on IO. */
    fun check(context: Context, force: Boolean = false): Release? {
        if (!enabled(context)) return null
        val p = context.getSharedPreferences(PREFS, 0)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong("checked", 0) < CHECK_EVERY_MS) return pending(context)
        val release = runCatching { latest() }.getOrNull()
        p.edit().putLong("checked", now).apply()
        if (release == null || !isNewer(release.version, installedVersion(context))) return null
        p.edit().putString("version", release.version).putString("url", release.apkUrl).putLong("size", release.apkSize)
            .putString("notes", release.notes.take(2000)).putString("page", release.page).apply()
        return pending(context)
    }

    fun dismiss(context: Context, release: Release) {
        context.getSharedPreferences(PREFS, 0).edit().putString("dismissed", release.version).apply()
    }

    /** Downloads the APK into the cache (the FileProvider's cache path). Call on IO. */
    fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "Forever-Launcher-${release.version.removePrefix("v")}.apk")
        val c = open(release.apkUrl)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            var done = 0L
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) onProgress((done.toDouble() / total).toFloat().coerceIn(0f, 1f))
                    }
                }
            }
            if (release.apkSize > 0 && done != release.apkSize) throw IOException("incomplete download")
        } finally { c.disconnect() }
        return file
    }

    /** Opens Android's installer; asks for the "install unknown apps" permission first when it is missing. */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}

/** Home-screen card for a newer GitHub release: version, the first lines of its notes, Update / Later. */
@Composable
internal fun WowAppUpdateCard(release: WowAppUpdater.Release, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = WowTheme.palette
    var progress by remember { mutableStateOf<Float?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val failed = tx("The download failed. Check the connection and try again.", "A letöltés nem sikerült. Ellenőrizd a kapcsolatot, és próbáld újra.")
    val allow = tx("Allow \"Install unknown apps\" for Forever Launcher, then tap Install again.",
        "Engedélyezd az „Ismeretlen alkalmazások telepítése” lehetőséget a Forever Launchernek, aztán koppints újra a Telepítésre.")

    Card(
        modifier = Modifier.fillMaxWidth(0.9f).border(1.dp, palette.accent, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = palette.card.copy(alpha = 0.85f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tx("NEW VERSION: ${release.version}", "ÚJ VERZIÓ: ${release.version}"), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp, color = palette.accent)
            }
            if (release.notes.isNotBlank()) Text(release.notes.lines().filter { it.isNotBlank() }.take(6).joinToString("\n"),
                fontSize = 11.sp, lineHeight = 15.sp, color = palette.muted)
            progress?.let {
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = palette.accent, trackColor = palette.divider)
            }
            message?.let { Text(it, fontSize = 11.sp, color = Color(0xFFE57373)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        message = null
                        val ready = apk
                        if (ready != null) { if (!WowAppUpdater.install(context, ready)) message = allow; return@Button }
                        progress = 0f
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                runCatching { WowAppUpdater.download(context.applicationContext, release) { p -> progress = p } }.getOrNull()
                            }
                            progress = null
                            if (file == null) { message = failed; return@launch }
                            apk = file
                            if (!WowAppUpdater.install(context, file)) message = allow
                        }
                    },
                    enabled = progress == null, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accentFill, contentColor = Color.White),
                ) { Text(if (apk != null) tx("INSTALL", "TELEPÍTÉS") else tx("UPDATE", "FRISSÍTÉS"), fontWeight = FontWeight.Bold) }
                TextButton(onClick = { WowAppUpdater.dismiss(context, release); onDismiss() }, enabled = progress == null) {
                    Text(tx("Later", "Később"), color = palette.mutedLight)
                }
            }
        }
    }
}
