package app.gamenative.ui.screen.wow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import app.gamenative.ui.theme.WowTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The full game-data download on the home screen, in the launcher's card style: percent badge, one progress bar, size and speed
 * on one line, time left under it, then the actions (Download / Stop / Delete the downloaded data, for a later copy from a PC).
 * [data] is the on-disk progress the launcher already shows; a running download reports through [WowDataDownloadState].
 */
@Composable
internal fun WowGameDataCard(data: WowGameData.Progress?, enabled: Boolean, gameRoot: String, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val running = WowDataDownloadState.running
    val p = WowDataDownloadState.progress
    val error = WowDataDownloadState.error
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    if (!running && error == null && (data == null || data.complete)) return
    val palette = WowTheme.palette
    val preparing = running && (p == null || p.preparing || p.total <= 0)
    val done = if (running && p != null && p.total > 0) p.done else data?.downloaded ?: 0L
    val total = if (running && p != null && p.total > 0) p.total else data?.total ?: 0L
    val fraction = if (total > 0) (done.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else 0f

    Card(
        modifier = Modifier.fillMaxWidth(0.9f).border(1.dp, palette.cardBorder, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = palette.card.copy(alpha = 0.85f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Title row: icon, label, percent badge.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tx("GAME DATA", "JÁTÉKADATOK"), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    color = palette.accent, modifier = Modifier.weight(1f))
                if (!preparing && total > 0) {
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(palette.accent.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("${(fraction * 100).toInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.accent)
                    }
                }
            }

            // One progress bar for both states.
            if (preparing) LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = palette.accent, trackColor = palette.divider)
            else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = palette.accent, trackColor = palette.divider)

            // Size on the left, speed on the right; time left or the explanation under it.
            if (preparing) {
                Text(tx("Preparing the download: reading the game's file list...", "Letöltés előkészítése: a játék fájllistájának beolvasása..."),
                    fontSize = 12.sp, color = palette.text)
            } else if (total <= 0) {
                // The size comes from Blizzard's file list a few seconds later; the download can start right away.
                Text(tx("The game data is not downloaded yet. Calculating its size...", "A játékadatok még nincsenek letöltve. A méret számítása..."),
                    fontSize = 12.sp, color = palette.text)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${WowGameData.gb(done)} / ${WowGameData.gb(total)} GB", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        color = palette.text, modifier = Modifier.weight(1f))
                    if (running && p != null) Text("${String.format(java.util.Locale.ROOT, "%.1f", p.bytesPerSecond / 1048576)} MB/s",
                        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = palette.text)
                }
                if (running && p != null && p.bytesPerSecond > 100_000) {
                    val left = ((p.total - p.done) / p.bytesPerSecond / 60).toLong()
                    Text(tx("About ${left / 60} h ${left % 60} min left", "Kb. ${left / 60} ó ${left % 60} p van hátra"), fontSize = 11.sp, color = palette.muted)
                }
            }
            if (running || total > 0) Text(
                if (running) tx("Downloading at full speed, even with the screen off. You can start the game at any time: the download pauses, and while you play the game fetches only what it needs, at a lower speed.",
                    "Teljes sebességgel tölt, kikapcsolt képernyővel is. Bármikor indíthatod a játékot: a letöltés szünetel, és játék közben a játék csak a szükséges fájlokat tölti, lassabban.")
                else tx("${WowGameData.gb(total - done)} GB left. Downloading now is much faster than while playing, when the game fetches only what it needs. Wi-Fi recommended.",
                    "Még ${WowGameData.gb(total - done)} GB hiányzik. Most letöltve sokkal gyorsabb, mint játék közben, amikor a játék csak a szükséges fájlokat tölti. Wi-Fi ajánlott."),
                fontSize = 11.sp, lineHeight = 15.sp, color = palette.muted,
            )
            error?.let { Text(tx("The download stopped: ", "A letöltés megállt: ") + it, fontSize = 11.sp, color = Color(0xFFE57373)) }

            // Actions.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    OutlinedButton(onClick = { WowDataDownloadService.stop(context) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                        Text(tx("Stop", "Leállítás"), color = palette.accent, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(onClick = { WowDataDownloadState.error = null; WowDataDownloadService.start(context) }, enabled = enabled && !deleting,
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accentFill, contentColor = Color.White)) {
                        Text(if (done > 0) tx("CONTINUE", "FOLYTATÁS") else tx("DOWNLOAD", "LETÖLTÉS"), fontWeight = FontWeight.Bold)
                    }
                    if (done > 0) TextButton(onClick = { confirmDelete = true }, enabled = enabled && !deleting) {
                        Text(tx("Delete", "Törlés"), color = palette.mutedLight)
                    }
                }
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(tx("Delete the downloaded game data?", "Törlöd a letöltött játékadatokat?")) },
        text = {
            Text(tx("Removes the game's data storage (Data/data, ${WowGameData.gb(done)} GB): what this app and the game downloaded. The game itself, your settings and addons stay. Use it before you copy the game data from a PC.",
                "Törli a játék adattárolóját (Data/data, ${WowGameData.gb(done)} GB): amit az app és a játék letöltött. Maga a játék, a beállításaid és az addonjaid maradnak. Akkor hasznos, ha a játékadatokat gépről másolod fel."))
        },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false; deleting = true
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { WowGameData.deleteStorage(File(gameRoot)) } }
                    WowDataDownloadState.error = null; WowDataDownloadState.progress = null
                    deleting = false; onChanged()
                }
            }) { Text(tx("Delete", "Törlés"), color = Color(0xFFE57373)) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tx("Cancel", "Mégse")) } },
    )
}
