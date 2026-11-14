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
import dev.korikosmos.healthdisplay.google.GoogleAuth
import dev.korikosmos.healthdisplay.google.GoogleAuthException
import dev.korikosmos.healthdisplay.google.GoogleHealthApi
import dev.korikosmos.healthdisplay.google.GoogleStats
import dev.korikosmos.healthdisplay.health.HealthStats
import dev.korikosmos.healthdisplay.presence.Config
import dev.korikosmos.healthdisplay.presence.HrFallback
import dev.korikosmos.healthdisplay.presence.HrReading
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
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** What the app screen shows about the running service. */
data class Status(
    val running: Boolean = false,
    val problem: String = "",
    val values: Map<String, String> = emptyMap(),
    val heartRate: String = "",
    val pulsoid: String = "",
    val server: String = "",
)

/**
 * Long-running foreground service (type specialUse) that collects the phone's data and
 * sends it to the Health Display server, which owns the Discord presence
 * (docs/server-push.md). Pulsoid heart rate (live), Health Connect / Google Health stats
 * and the heart-rate fallback are merged into placeholder values and pushed every
 * UPDATE_INTERVAL, and at once when "live" changes. The loop restarts when settings change.
 */
class PushService : LifecycleService() {
    private var loop: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            lifecycleScope.launch { ConfigStore(this@PushService).setRunning(false); stopSelf() }
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        lifecycleScope.launch { ConfigStore(this@PushService).setRunning(true) }
        loop?.cancel()
        loop = lifecycleScope.launch {
            ConfigStore(this@PushService).config.distinctUntilChanged().collectLatest { run(it) }
        }
        _status.update { it.copy(running = true) }
        return START_STICKY
    }

    private suspend fun run(cfg: Config) = coroutineScope {
        val url = cfg["PUSH_URL"]; val pushToken = cfg["PUSH_TOKEN"]
        if (url.isBlank() || pushToken.isBlank()) {
            problem("Nothing to do: set the server push URL and token in Settings (or import the server's config.env)")
            return@coroutineScope
        }
        _status.update { it.copy(problem = "") }
        val push = ServerPush(url, pushToken)

        val hr = HeartRate()
        val pulsoid = cfg["PULSOID_TOKEN"].takeIf { it.isNotBlank() }
            ?.let { Pulsoid(it, cfg.pollInterval) { bpm, ms -> hr.add(bpm, ms) } }
        if (pulsoid == null) AppLog.log("pulsoid", "no token set: pushing stats without live heart rate")
        pulsoid?.start(this)

        var stats: Map<String, String> = emptyMap()
        var fallback: HrReading? = null
        if (HealthConnectClient.getSdkStatus(this@PushService) == HealthConnectClient.SDK_AVAILABLE) {
            val hs = HealthStats(this@PushService, cfg)
            // Heart-rate fallback: only looked up while Pulsoid isn't live.
            if (cfg.hrFallbackMaxAge > 0) launch {
                while (true) {
                    if (!hr.live(System.currentTimeMillis(), cfg.staleAfter)) {
                        try {
                            fallback = hs.recentHeartRate(cfg.hrFallbackMaxAge)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLog.log("health", "heart rate: $e")
                        }
                    }
                    delay((cfg.hrFallbackInterval * 1000).toLong())
                }
            }
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
            val gs = GoogleStats(GoogleHealthApi { GoogleAuth.token(this@PushService) }, cfg)
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
                    // Offline for a while: let Health Connect take over rather than send stale totals.
                    if (System.currentTimeMillis() - googleOkAt > 3 * cfg.googleInterval * 1000) google = emptyMap()
                    delay((cfg.googleInterval * 1000).toLong())
                }
            }
        }

        try {
            var lastPush = 0L; var lastLive: Boolean? = null
            var lastMode: String? = "init"
            while (true) {
                val now = System.currentTimeMillis()
                val live = hr.live(now, cfg.staleAfter)
                // No live Pulsoid reading: a recent sample from any device, sent as "~72".
                val fb = if (live) null else HrFallback.usable(fallback, Instant.ofEpochMilli(now), cfg)
                val mode = if (live) "live" else fb?.source
                val hrText = when {
                    live -> "live from Pulsoid"
                    fb != null -> "no live reading; sending ~${fb.bpm} from ${fb.source}"
                    else -> "no live or recent reading"
                }
                if (mode != lastMode) { AppLog.log("heart rate", hrText); lastMode = mode }
                val values = StatsMerge.merge(google, stats) + hr.values() +
                    (fb?.let { HrFallback.values(it, Instant.ofEpochMilli(now)) } ?: emptyMap())

                // "live" = a heart rate worth showing: live Pulsoid, or the fallback reading.
                val shown = live || fb != null
                if (now - lastPush >= cfg.updateInterval * 1000 || shown != lastLive) {
                    lastPush = now; lastLive = shown
                    val measured = if (live) hr.measuredMs else fb?.time?.toEpochMilli() ?: hr.measuredMs
                    val payload = ServerPush.payload(shown, measured, values, now)
                    launch {
                        val r = push.send(payload)
                        val text = "${r.describe()} at ${LocalTime.now().format(TIME)}"
                        _status.update { it.copy(server = text) }
                        updateNotification("Server: $text · ${values["bpm"]?.let { "$it bpm" } ?: "no heart rate"}")
                    }
                }
                _status.update {
                    it.copy(
                        values = values, heartRate = hrText,
                        pulsoid = when {
                            pulsoid == null -> "no token set"
                            pulsoid.socketUp -> "WebSocket connected"
                            else -> "WebSocket down, polling"
                        },
                    )
                }
                delay(1000)
            }
        } finally {
            pulsoid?.stop()
        }
    }

    private fun problem(msg: String) {
        AppLog.log("service", msg)
        updateNotification(msg)
        _status.update { it.copy(problem = msg) }
    }

    override fun onDestroy() {
        loop?.cancel()
        _status.value = Status()
        super.onDestroy()
    }

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sending to server", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PushService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
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
        private const val CHANNEL = "push"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "stop"
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

        private val _status = MutableStateFlow(Status())
        val status: StateFlow<Status> = _status

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, PushService::class.java))

        fun stop(ctx: Context) = ctx.startService(Intent(ctx, PushService::class.java).setAction(ACTION_STOP))
    }
}
