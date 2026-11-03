package app.gamenative.ui.screen.wow

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A small line at the top of the game while WoW still streams its data: how much is on the phone and how fast it comes in.
 * Speed = the app's received bytes (the game runs in the app's own process group, so its CDN traffic counts here). It takes no
 * touches and disappears once the data is complete. Not drawn when the experimental full-screen scanout bypasses the compositor.
 */
@Composable
fun WowDownloadOverlay() {
    val context = LocalContext.current.applicationContext
    val root = remember { File(GamePath.load(context)) }
    var progress by remember { mutableStateOf<WowGameData.Progress?>(null) }
    var speed by remember { mutableStateOf(0.0) }
    LaunchedEffect(root) {
        val uid = Process.myUid()
        var lastBytes = TrafficStats.getUidRxBytes(uid)
        var lastTime = SystemClock.elapsedRealtime()
        while (true) {
            progress = withContext(Dispatchers.IO) { runCatching { WowGameData.progress(context, root) }.getOrNull() }
            if (progress?.complete != false) { delay(30_000); continue }
            delay(2_000)
            val bytes = TrafficStats.getUidRxBytes(uid)
            val now = SystemClock.elapsedRealtime()
            if (bytes >= 0 && lastBytes >= 0 && now > lastTime) speed = (bytes - lastBytes) / 1048576.0 / ((now - lastTime) / 1000.0)
            lastBytes = bytes; lastTime = now
        }
    }
    val p = progress ?: return
    if (p.complete) return
    val pct = p.percent
    val rate = String.format(java.util.Locale.ROOT, "%.1f", speed)
    val label = if (speed >= 0.1) tx("Game data $pct% · $rate MB/s", "Játékadatok $pct% · $rate MB/s")
        else tx("Game data $pct%", "Játékadatok $pct%")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.padding(top = 6.dp).background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(label, color = Color(0xFFE8E2D4), fontSize = 11.sp)
            LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.width(120.dp).height(2.dp),
                color = Color(0xFFD9B26A), trackColor = Color(0x33FFFFFF))
        }
    }
}
