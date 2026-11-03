package app.gamenative.ui.screen.wow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.gamenative.R
import java.io.File

/** What the launcher shows of the full game-data download (snapshot state, set from the service's thread). */
internal object WowDataDownloadState {
    var running by mutableStateOf(false)
    var progress by mutableStateOf<WowGameDataDownloader.Progress?>(null)
    var error by mutableStateOf<String?>(null)
    @Volatile var cancel = false
}

/**
 * Runs [WowGameDataDownloader] as a foreground service (data sync), so the download goes on with the screen off or another app
 * in front. The notification shows the percent and the speed and has a Stop action; a stop keeps everything downloaded so far.
 */
class WowDataDownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { WowDataDownloadState.cancel = true; return START_NOT_STICKY }
        if (WowDataDownloadState.running) return START_NOT_STICKY
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, WowI18n.pick(this, "Game data download", "Játékadatok letöltése"),
            NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(null),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        val root = File(GamePath.load(this))
        WowDataDownloadState.running = true; WowDataDownloadState.error = null; WowDataDownloadState.cancel = false
        val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forever:gamedata")
        Thread {
            wake.acquire(6 * 60 * 60 * 1000L)
            var lastNotify = 0L
            try {
                WowGameDataDownloader.run(applicationContext, root, { WowDataDownloadState.cancel }) { p ->
                    WowDataDownloadState.progress = p
                    val now = System.currentTimeMillis()
                    if (now - lastNotify > 2000) { lastNotify = now; manager.notify(NOTIFICATION_ID, notification(p)) }
                }
            } catch (e: Throwable) {
                WowDataDownloadState.error = e.message ?: e.javaClass.simpleName
            } finally {
                if (wake.isHeld) wake.release()
                WowDataDownloadState.running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.apply { name = "WowGameData"; start() }
        return START_NOT_STICKY
    }

    private fun notification(p: WowGameDataDownloader.Progress?): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, WowDataDownloadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val pct = if (p != null && p.total > 0) (p.done * 100 / p.total).toInt() else 0
        val text = if (p == null || p.preparing) WowI18n.pick(this, "Preparing...", "Előkészítés...")
            else "${WowGameData.gb(p.done)} / ${WowGameData.gb(p.total)} GB ($pct%) · ${String.format(java.util.Locale.ROOT, "%.1f", p.bytesPerSecond / 1048576)} MB/s"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_forever)
            .setContentTitle(WowI18n.pick(this, "Downloading the game data", "Játékadatok letöltése"))
            .setContentText(text)
            .setProgress(100, pct, p == null || p.preparing)
            .setContentIntent(open)
            .addAction(0, WowI18n.pick(this, "Stop", "Leállítás"), stop)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()
    }

    companion object {
        private const val CHANNEL = "wow_gamedata"
        private const val NOTIFICATION_ID = 4102
        private const val ACTION_STOP = "forever.launcher.GAMEDATA_STOP"

        fun start(context: Context) { context.startForegroundService(Intent(context, WowDataDownloadService::class.java)) }
        fun stop(context: Context) {
            WowDataDownloadState.cancel = true
        }
    }
}
