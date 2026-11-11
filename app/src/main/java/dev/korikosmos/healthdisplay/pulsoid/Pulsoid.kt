package dev.korikosmos.healthdisplay.pulsoid

import dev.korikosmos.healthdisplay.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Heart rate from Pulsoid. Primary: real-time WebSocket v2 (pushes each reading).
 * Fallback: while the socket is down, poll the REST "latest" endpoint every pollSec.
 */
class Pulsoid(
    private val token: String,
    private val pollSec: Double,
    private val onReading: (bpm: Int, measuredMs: Long) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS) // detects dead sockets after network switches
        .build()

    @Volatile var socketUp = false; private set
    private var socket: WebSocket? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            launch { socketLoop() }
            launch { pollLoop() }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        socket?.close(1000, null); socket = null
    }

    private suspend fun socketLoop() {
        var backoff = 1_000L
        while (currentCoroutineContext().isActive) {
            val closed = CompletableDeferred<String>()
            val req = Request.Builder()
                .url("wss://dev.pulsoid.net/api/v2/data/real_time?types=heart_rate")
                .header("Authorization", "Bearer $token")
                .header("User-Agent", "Health-Display-Android")
                .build()
            socket = http.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    socketUp = true; backoff = 1_000L
                    AppLog.log("pulsoid", "websocket connected")
                }

                override fun onMessage(ws: WebSocket, text: String) = handle(text)

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    closed.complete("closed $code $reason")
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    val why = response?.let { "HTTP ${it.code}" } ?: t.toString()
                    closed.complete("failed: $why")
                }
            })
            val why = closed.await()
            socketUp = false
            AppLog.log("pulsoid", "websocket $why; retrying in ${backoff / 1000}s (polling meanwhile)")
            delay(backoff)
            backoff = min(backoff * 2, 60_000L)
        }
    }

    private fun handle(text: String) {
        runCatching {
            val o = JSONObject(text)
            when (o.optString("type")) {
                "heart_rate" -> {
                    val bpm = o.getJSONObject("heartRate").getInt("bpm")
                    onReading(bpm, Instant.parse(o.getString("timestamp")).toEpochMilli())
                }
                "ingestion_pause" -> AppLog.log("pulsoid", "ingestion paused: $text")
            }
        }.onFailure { AppLog.log("pulsoid", "bad message: ${text.take(200)}") }
    }

    private suspend fun pollLoop() {
        var warned412 = false
        while (currentCoroutineContext().isActive) {
            delay((pollSec * 1000).toLong())
            if (socketUp) continue
            val req = Request.Builder()
                .url("https://dev.pulsoid.net/api/v1/data/heart_rate/latest")
                .header("Authorization", "Bearer $token")
                .header("User-Agent", "Health-Display-Android")
                .build()
            runCatching {
                http.newCall(req).execute().use { r ->
                    when {
                        r.code == 412 -> if (!warned412) {
                            AppLog.log("pulsoid", "account has no heart-rate data yet"); warned412 = true
                        }
                        !r.isSuccessful -> AppLog.log("pulsoid", "HTTP ${r.code} ${r.body.string().take(200)}")
                        else -> {
                            val o = JSONObject(r.body.string())
                            val bpm = o.optJSONObject("data")?.optInt("heart_rate") ?: 0
                            onReading(bpm, o.optLong("measured_at"))
                        }
                    }
                }
            }.onFailure { AppLog.log("pulsoid", "poll: $it") }
        }
    }
}
