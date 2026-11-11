package dev.korikosmos.healthdisplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.korikosmos.healthdisplay.discord.DiscordPresence
import dev.korikosmos.healthdisplay.discord.PresenceActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Long-running foreground service (type specialUse) that owns the presence loop.
 * SPIKE: pushes a fixed test presence with a counter every 15 s, to verify that the
 * Social SDK keeps working while the app is in the background and the screen is off.
 */
class PresenceService : LifecycleService() {
    private var loop: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf(); return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        if (loop == null) loop = lifecycleScope.launch { spikeLoop() }
        return START_STICKY
    }

    private suspend fun spikeLoop() {
        val appId = Prefs.appId(this)
        if (appId == null) {
            AppLog.log("service", "no Discord application ID set"); stopSelf(); return
        }
        DiscordPresence.start(appId)
        val started = System.currentTimeMillis()
        val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
        var n = 0
        while (true) {
            n++
            val now = LocalTime.now().format(fmt)
            DiscordPresence.update(
                PresenceActivity(
                    details = "Spike test #$n",
                    state = "updated $now",
                    largeImage = "https://placehold.co/512x512/ED4245/FFFFFF/png?text=$n&font=montserrat",
                    largeText = "Health Display spike",
                    startMs = started,
                ),
            )
            delay(2_000)
            updateNotification("#$n at $now: ${DiscordPresence.lastResult}")
            delay(13_000)
        }
    }

    override fun onDestroy() {
        loop?.cancel()
        DiscordPresence.stop()
        super.onDestroy()
    }

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Presence", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PresenceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Health Display")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "presence"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "stop"

        fun start(ctx: Context) =
            ctx.startForegroundService(Intent(ctx, PresenceService::class.java))

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, PresenceService::class.java))
    }
}
