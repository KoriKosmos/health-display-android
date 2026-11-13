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
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.korikosmos.healthdisplay.config.ConfigStore
import dev.korikosmos.healthdisplay.discord.DiscordPresence
import dev.korikosmos.healthdisplay.discord.PresenceActivity
import dev.korikosmos.healthdisplay.google.GoogleAuth
import dev.korikosmos.healthdisplay.google.GoogleAuthException
import dev.korikosmos.healthdisplay.google.GoogleHealthApi
import dev.korikosmos.healthdisplay.google.GoogleStats
import dev.korikosmos.healthdisplay.health.HealthStats
import dev.korikosmos.healthdisplay.presence.ActivityBuilder
import dev.korikosmos.healthdisplay.presence.Config
import dev.korikosmos.healthdisplay.presence.StatsMerge
import dev.korikosmos.healthdisplay.pulsoid.HeartRate
import dev.korikosmos.healthdisplay.pulsoid.Pulsoid
import dev.korikosmos.healthdisplay.push.ServerPush
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** What the app screen shows about the running service. */
data class Status(
    val running: Boolean = false,
    val problem: String = "",
    val values: Map<String, String> = emptyMap(),
    val presence: PresenceActivity? = null,
    val lastPush: String = "",
    val pulsoid: String = "",
    val server: String = "",
)

/**
 * Long-running foreground service (type specialUse) that owns the presence loop, ported
 * from the server's main(): Pulsoid readings in, Discord presence pushed at most every
 * UPDATE_INTERVAL when it changed, cleared after STALE_AFTER without data. Health Connect
 * stats are refreshed every STATS_INTERVAL. The loop restarts when settings change.
 */
class PresenceService : LifecycleService() {
    private var loop: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            lifecycleScope.launch { ConfigStore(this@PresenceService).setRunning(false); stopSelf() }
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        val test = intent?.action == ACTION_TEST
        if (!test) lifecycleScope.launch { ConfigStore(this@PresenceService).setRunning(true) }
        loop?.cancel()
        loop = lifecycleScope.launch {
            if (test) testLoop()
            else ConfigStore(this@PresenceService).config.distinctUntilChanged().collectLatest { run(it) }
        }
        _status.update { it.copy(running = true) }
        return START_STICKY
    }

    private suspend fun run(cfg: Config) = coroutineScope {
        val token = cfg["PULSOID_TOKEN"]
        val appId = cfg["DISCORD_APP_ID"].toLongOrNull()
        if (token.isEmpty() || appId == null) {
            problem("Set the Pulsoid token and Discord application ID in Settings"); return@coroutineScope
        }
        _status.update { it.copy(problem = "") }
        DiscordPresence.start(this@PresenceService, appId)

        val hr = HeartRate()
        val pulsoid = Pulsoid(token, cfg.pollInterval) { bpm, ms -> hr.add(bpm, ms) }
        pulsoid.start(this)
        var stats: Map<String, String> = emptyMap()
        if (HealthConnectClient.getSdkStatus(this@PresenceService) == HealthConnectClient.SDK_AVAILABLE) {
            val hs = HealthStats(this@PresenceService, cfg)
            launch {
                while (true) {
                    try {
                        stats = hs.refresh()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.log("health", "$e")
                    }
                    delay((cfg.statsInterval * 1000).toLong())
                }
            }
        }

        var google: Map<String, String> = emptyMap()
        var googleOkAt = 0L
        if (cfg.googleOn) {
            val gs = GoogleStats(GoogleHealthApi { GoogleAuth.token(this@PresenceService) }, cfg)
            launch {
                while (true) {
                    try {
                        google = gs.refresh(); googleOkAt = System.currentTimeMillis()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: GoogleAuthException) {
                        AppLog.log("google", e.message ?: "$e"); google = emptyMap()
                    } catch (e: Exception) {
                        AppLog.log("google", "$e")
                    }
                    // Offline for a while: let Health Connect take over rather than show stale totals.
                    if (System.currentTimeMillis() - googleOkAt > 3 * cfg.googleInterval * 1000) google = emptyMap()
                    delay((cfg.googleInterval * 1000).toLong())
                }
            }
        }

        val push = cfg["PUSH_URL"].takeIf { it.isNotBlank() && cfg["PUSH_TOKEN"].isNotBlank() }
            ?.let { ServerPush(it, cfg["PUSH_TOKEN"]) }
        var lastServerPush = 0L; var lastServerLive: Boolean? = null

        try {
            val started = System.currentTimeMillis()
            var lastKey = "init"; var lastPush = 0L
            while (true) {
                val now = System.currentTimeMillis()
                val values = StatsMerge.merge(google, stats) + hr.values()
                val act = ActivityBuilder.build(hr.bpm?.toString()?.takeIf { hr.live(now, cfg.staleAfter) }, values, cfg, started)
                val key = ActivityBuilder.key(act)
                // Re-send unchanged presence every 10 min in case the Discord app restarted.
                val refresh = act != null && now - lastPush >= REFRESH_MS
                if ((key != lastKey && (now - lastPush >= cfg.updateInterval * 1000 || lastKey == "init" || act == null)) || refresh) {
                    if (act == null) DiscordPresence.clear() else DiscordPresence.update(act)
                    val text = act?.let { listOf(it.details, it.state).filter(String::isNotEmpty).joinToString(" | ") }
                        ?: "cleared (no live heart rate)"
                    AppLog.log("presence", text)
                    lastKey = key; lastPush = now
                    updateNotification(text)
                    _status.update { it.copy(lastPush = "${LocalTime.now().format(TIME)} $text") }
                }
                // Server push: every UPDATE_INTERVAL as a heartbeat, and at once when live changes.
                val live = act != null
                if (push != null && (now - lastServerPush >= cfg.updateInterval * 1000 || live != lastServerLive)) {
                    lastServerPush = now; lastServerLive = live
                    val payload = ServerPush.payload(live, hr.measuredMs, values, now)
                    launch {
                        val err = push.send(payload)
                        val text = "${LocalTime.now().format(TIME)} " + (err?.let { "failed: $it" } ?: "ok")
                        _status.update { it.copy(server = text) }
                    }
                }
                _status.update {
                    it.copy(
                        values = values, presence = act,
                        pulsoid = if (pulsoid.socketUp) "WebSocket connected" else "WebSocket down, polling",
                    )
                }
                delay(1000)
            }
        } finally {
            pulsoid.stop()
        }
    }

    /** Presence test without Pulsoid: a counter every 15 s, to check background behaviour. */
    private suspend fun testLoop() {
        val appId = ConfigStore(this).load()["DISCORD_APP_ID"].toLongOrNull()
            ?: return problem("Set the Discord application ID in Settings")
        DiscordPresence.start(this@PresenceService, appId)
        val started = System.currentTimeMillis()
        var n = 0
        while (true) {
            n++
            val now = LocalTime.now().format(TIME)
            DiscordPresence.update(
                PresenceActivity(
                    details = "Presence test #$n", state = "updated $now",
                    largeImage = "https://placehold.co/512x512/ED4245/FFFFFF/png?text=$n&font=montserrat",
                    largeText = "Health Display test", startMs = started,
                ),
            )
            delay(2_000)
            updateNotification("Test #$n at $now: ${DiscordPresence.lastResult}")
            delay(13_000)
        }
    }

    private fun problem(msg: String) {
        AppLog.log("service", msg)
        updateNotification(msg)
        _status.update { it.copy(problem = msg) }
    }

    override fun onDestroy() {
        loop?.cancel()
        DiscordPresence.stop()
        _status.value = Status()
        super.onDestroy()
    }

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Presence", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PresenceService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
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
        private const val ACTION_TEST = "test"
        private const val REFRESH_MS = 10 * 60_000L
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

        private val _status = MutableStateFlow(Status())
        val status: StateFlow<Status> = _status

        fun start(ctx: Context, test: Boolean = false) = ctx.startForegroundService(
            Intent(ctx, PresenceService::class.java).apply { if (test) action = ACTION_TEST },
        )

        fun stop(ctx: Context) = ctx.startService(Intent(ctx, PresenceService::class.java).setAction(ACTION_STOP))
    }
}
