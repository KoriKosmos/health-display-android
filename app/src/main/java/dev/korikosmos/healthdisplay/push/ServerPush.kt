package dev.korikosmos.healthdisplay.push

import dev.korikosmos.healthdisplay.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Outcome of one push: the HTTP status (null if no response) and the error, if any. */
data class PushResult(val code: Int?, val error: String?) {
    val ok get() = error == null
    fun describe() = if (ok) "ok (HTTP $code)" else "failed: $error"
}

/**
 * Sends the phone's current values to the Health Display server (docs/server-push.md),
 * which owns the Discord presence. The pushes are also the server's "phone is active"
 * signal: it falls back to its own sources when they stop.
 */
class ServerPush(private val url: String, private val token: String) {
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private var lastError: String? = "init"

    /** Errors are logged once until it works again. */
    suspend fun send(payload: JSONObject): PushResult = withContext(Dispatchers.IO) {
        val result = runCatching {
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .header("User-Agent", "Health-Display-Android")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { r ->
                PushResult(r.code, if (r.isSuccessful) null else "HTTP ${r.code} ${r.body.string().take(200)}")
            }
        }.getOrElse { PushResult(null, it.toString()) }
        if (result.error != lastError) AppLog.log("server", result.error?.let { "push failed: $it" } ?: "pushing to $url")
        lastError = result.error
        result
    }

    companion object {
        const val VERSION = 1

        /** Wire format, see docs/server-push.md. */
        fun payload(live: Boolean, measuredAtMs: Long, values: Map<String, String>, nowMs: Long): JSONObject =
            JSONObject()
                .put("v", VERSION)
                .put("sent_at", nowMs)
                .put("live", live)
                .put("measured_at", measuredAtMs)
                .put("values", JSONObject(values))
    }
}
