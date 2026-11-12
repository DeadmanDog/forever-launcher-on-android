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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.gamenative.R

/**
 * Runs while a game session is alive, so leaving the app for a while does not cost the game its server connection. Without a
 * foreground service Android's cached-app freezer stops the app and the Wine processes it started about ten seconds after the
 * player switches away, and WoW drops the connection when it wakes up. Started and stopped with the session ([sessionChanged]).
 */
class WowSessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, WowI18n.pick(this, "Running game", "Futó játék"), NotificationManager.IMPORTANCE_LOW))
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_forever)
            .setContentTitle(WowI18n.pick(this, "Forever Launcher is running", "A Forever Launcher fut"))
            .setContentText(WowI18n.pick(this, "The game stays connected while you use another app.", "A játék kapcsolata megmarad, amíg másik appot használsz."))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        /** True while the service runs in the foreground (set in onStartCommand, cleared in onDestroy). */
        @Volatile @JvmStatic var running = false
            private set

        /**
         * 1.2.3 (multi-agent review): the service was started once, when Wine started; Android refuses that start while the app is in
         * the background (targetSdk 36), and nothing retried: the whole session then ran without it and WoW disconnected the next
         * time the player left the app. Called when the app comes to the foreground: starts it if the game runs without it.
         */
        fun needsStart(sessionAlive: Boolean, serviceRunning: Boolean) = sessionAlive && !serviceRunning

        @JvmStatic
        fun ensureRunning(context: Context, sessionAlive: Boolean) {
            if (needsStart(sessionAlive, running)) sessionChanged(context, true)
        }

        private const val CHANNEL = "wow_session"
        private const val NOTIFICATION_ID = 4101

        /** Starts the service when a session begins and stops it when it ends; a refused start never affects the game. */
        @JvmStatic
        fun sessionChanged(context: Context, alive: Boolean) {
            val intent = Intent(context, WowSessionService::class.java)
            try {
                if (alive) context.startForegroundService(intent) else context.stopService(intent)
            } catch (e: Exception) {
                // A start while the app is already in the background is refused: the game simply runs without the service.
                android.util.Log.w("WowSession", "Session service ${if (alive) "start" else "stop"} failed", e)
            }
        }
    }
}
