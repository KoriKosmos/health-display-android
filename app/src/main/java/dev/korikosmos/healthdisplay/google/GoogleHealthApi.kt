package dev.korikosmos.healthdisplay.google

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Google Health API v4 over HTTP; [token] supplies a fresh access token per call. */
class GoogleHealthApi(private val token: suspend () -> String) : GoogleApi {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    private suspend fun call(build: Request.Builder): JSONObject {
        val req = build.header("Authorization", "Bearer ${token()}")
            .header("Accept", "application/json")
            .header("User-Agent", "Health-Display-Android")
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { r ->
                val body = r.body.string()
                if (r.code == 401 || r.code == 403) throw GoogleAuthException("HTTP ${r.code} ${body.take(300)}")
                if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code} ${body.take(300)}")
                JSONObject(body)
            }
        }
    }

    override suspend fun list(type: String, filter: String, pageSize: Int): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var page: String? = null
        repeat(20) {
            val url = "$API/dataTypes/$type/dataPoints".toHttpUrl().newBuilder()
                .addQueryParameter("filter", filter)
                .addQueryParameter("pageSize", pageSize.toString())
                .apply { page?.let { addQueryParameter("pageToken", it) } }
                .build()
            val d = call(Request.Builder().url(url))
            d.optJSONArray("dataPoints")?.let { a -> for (i in 0 until a.length()) out += a.getJSONObject(i) }
            page = d.optString("nextPageToken").takeIf { it.isNotEmpty() }
            if (page == null) return out
        }
        return out
    }

    override suspend fun rollupDay(type: String, family: String, day: LocalDate): JSONObject? {
        fun date(d: LocalDate) = JSONObject().put("date", JSONObject().put("year", d.year).put("month", d.monthValue).put("day", d.dayOfMonth))
        val body = JSONObject()
            .put("range", JSONObject().put("start", date(day)).put("end", date(day.plusDays(1))))
            .put("windowSizeDays", 1)
            .put("dataSourceFamily", "users/me/dataSourceFamilies/$family")
        val d = call(
            Request.Builder().url("$API/dataTypes/$type/dataPoints:dailyRollUp")
                .post(body.toString().toRequestBody("application/json".toMediaType())),
        )
        val points = d.optJSONArray("rollupDataPoints") ?: return null
        for (i in 0 until points.length()) {
            val p = points.getJSONObject(i)
            val s = p.getJSONObject("civilStartTime").getJSONObject("date")
            if (LocalDate.of(s.getInt("year"), s.getInt("month"), s.getInt("day")) == day) return p
        }
        return null
    }

    companion object {
        const val API = "https://health.googleapis.com/v4/users/me"
    }
}
