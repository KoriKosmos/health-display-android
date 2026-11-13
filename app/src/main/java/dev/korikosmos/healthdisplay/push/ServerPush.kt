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

/**
 * Sends the phone's current values to the Health Display server (docs/server-push.md), so
 * the server's presence shows the same numbers while the phone is active. The pushes are
 * also the server's "phone is active" signal: it falls back to its own sources when they stop.
 */
class ServerPush(private val url: String, private val token: String) {
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private var lastError: String? = null

    /** Returns null on success, or the error. Errors are logged once until it works again. */
    suspend fun send(payload: JSONObject): String? = withContext(Dispatchers.IO) {
        val error = runCatching {
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .header("User-Agent", "Health-Display-Android")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { r -> if (r.isSuccessful) null else "HTTP ${r.code} ${r.body.string().take(200)}" }
        }.getOrElse { it.toString() }
        if (error != lastError) AppLog.log("server", error?.let { "push failed: $it" } ?: "pushing to $url")
        lastError = error
        error
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
