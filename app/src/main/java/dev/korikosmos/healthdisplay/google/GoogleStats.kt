package dev.korikosmos.healthdisplay.google

import dev.korikosmos.healthdisplay.AppLog
import dev.korikosmos.healthdisplay.health.HealthStats.Companion.hm
import dev.korikosmos.healthdisplay.health.Ranker
import dev.korikosmos.healthdisplay.presence.Config
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** The two Google Health API calls the stats need (HTTP in [GoogleHealthApi], fakes in tests). */
interface GoogleApi {
    /** dataTypes/{type}/dataPoints with a filter, all pages. */
    suspend fun list(type: String, filter: String, pageSize: Int): List<JSONObject>

    /** dataTypes/{type}/dataPoints:dailyRollUp for one day; the matching rollup point or null. */
    suspend fun rollupDay(type: String, family: String, day: LocalDate): JSONObject?
}

/**
 * Daily stats from the Google Health API, ported from the server's HealthStats.
 *
 * Today's totals are Google's own daily totals for the GOOGLE_TOTALS source family
 * (default all-sources: watch and phone merged and de-duplicated by Google, the numbers
 * the Google Health app shows). Overnight stats and sleep use the most recent day/night
 * any source recorded, SOURCE_* order breaking ties, as on the server.
 */
class GoogleStats(private val api: GoogleApi, private val config: Config, private val zone: ZoneId = ZoneId.systemDefault()) {
    private val ranker = Ranker(config)
    private val family get() = config["GOOGLE_TOTALS"]

    suspend fun refresh(now: Instant = Instant.now()): Map<String, String> {
        val v = mutableMapOf<String, String>()
        val today = LocalDate.ofInstant(now, zone)

        suspend fun step(name: String, block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: GoogleAuthException) {
                throw e
            } catch (e: Exception) {
                AppLog.log("google", "$name: $e")
            }
        }

        // --- today's totals: Google's merged daily rollups
        suspend fun rollup(type: String) = api.rollupDay(type, family, today)
        step("steps") {
            rollup("steps")?.optJSONObject("steps")?.let {
                v["steps"] = thousands(it.num("countSum")); v["steps_src"] = "google"
            }
        }
        step("distance") {
            rollup("distance")?.optJSONObject("distance")?.let { v["distance"] = "%.1f".format(Locale.US, it.num("millimetersSum") / 1e6) }
        }
        step("calories") {
            rollup("total-calories")?.optJSONObject("totalCalories")?.let { v["calories"] = thousands(it.num("kcalSum")) }
        }
        step("active_kcal") {
            rollup("active-energy-burned")?.optJSONObject("activeEnergyBurned")?.let { v["active_kcal"] = thousands(it.num("kcalSum")) }
        }
        step("active") {
            rollup("active-minutes")?.optJSONObject("activeMinutes")?.let { am ->
                val levels = am.optJSONArray("activeMinutesRollupByActivityLevel").objects()
                    .associate { it.optString("activityLevel") to it.num("activeMinutesSum") }
                v["active"] = levels.values.sum().roundToInt().toString()
                v["active_mv"] = ((levels["MODERATE"] ?: 0.0) + (levels["VIGOROUS"] ?: 0.0)).roundToInt().toString()
            }
        }
        step("azm") {
            rollup("active-zone-minutes")?.optJSONObject("activeZoneMinutes")?.let {
                // Fitbit's definition: cardio and peak minutes count double.
                val azm = it.num("sumInFatBurnHeartZone") + 2 * (it.num("sumInCardioHeartZone") + it.num("sumInPeakHeartZone"))
                v["azm"] = azm.roundToInt().toString()
            }
        }
        step("floors") {
            rollup("floors")?.optJSONObject("floors")?.let { v["floors"] = it.num("countSum").roundToInt().toString() }
        }

        // --- overnight metrics: most recent day from any source (preference breaks same-day ties)
        val maxAge = config.statMaxAgeDays
        suspend fun daily(type: String, group: String, days: Double = maxAge): Pair<JSONObject, String>? {
            val from = LocalDate.ofInstant(now.minus(Duration.ofMinutes((days * 24 * 60).toLong())), zone)
            val key = type.replace('-', '_')
            val points = api.list(type, "$key.date >= \"$from\"", 100)
            val best = ranker.best(points, group, ::sourceOf, { p ->
                val d = bodyOf(p).getJSONObject("date")
                LocalDate.of(d.getInt("year"), d.getInt("month"), d.getInt("day")).toEpochDay()
            }) ?: return null
            return bodyOf(best) to sourceOf(best)
        }
        step("rhr") {
            daily("daily-resting-heart-rate", "RHR")?.let { (b, src) ->
                v["rhr"] = b.num("beatsPerMinute").roundToInt().toString(); v["rhr_src"] = src
            }
        }
        step("hrv") {
            daily("daily-heart-rate-variability", "HRV")?.let { (b, src) ->
                v["hrv"] = b.num("averageHeartRateVariabilityMilliseconds").roundToInt().toString(); v["hrv_src"] = src
            }
        }
        step("spo2") {
            daily("daily-oxygen-saturation", "SPO2")?.let { (b, _) -> v["spo2"] = "%.0f".format(Locale.US, b.num("averagePercentage")) }
        }
        step("resp") {
            daily("daily-respiratory-rate", "RESP")?.let { (b, _) -> v["resp"] = "%.1f".format(Locale.US, b.num("breathsPerMinute")) }
        }
        step("temp") {
            daily("daily-sleep-temperature-derivations", "TEMP")?.let { (b, _) ->
                v["temp_delta"] = "%+.1f".format(Locale.US, b.num("nightlyTemperatureCelsius") - b.num("baselineTemperatureCelsius"))
            }
        }
        step("vo2max") {
            daily("daily-vo2-max", "VO2", days = 30.0)?.let { (b, _) ->
                v["vo2max"] = "%.0f".format(Locale.US, b.num("vo2Max"))
                b.optString("cardioFitnessLevel").takeIf { it.isNotEmpty() }?.let { v["fitness"] = title(it) }
            }
        }

        // --- sleep: most recent night; sessions ending within 4 h of each other = same night
        step("sleep") {
            val since = now.minus(Duration.ofMinutes((maxAge * 24 * 60).toLong()))
            val pts = api.list("sleep", "sleep.interval.end_time >= \"${utc(since)}\"", 25)
            val p = ranker.best(pts, "SLEEP", ::sourceOf, { instant(it.getJSONObject("sleep").getJSONObject("interval").getString("endTime")).toEpochMilli() }, 4 * 3600_000L)
                ?: return@step
            val s = p.getJSONObject("sleep").getJSONObject("summary")
            val stages = s.optJSONArray("stagesSummary").objects().associate { it.optString("type") to it.num("minutes").toLong() }
            val asleep = s.num("minutesAsleep").toLong()
            val period = s.num("minutesInSleepPeriod").toLong()
            v["sleep"] = hm(asleep); v["sleep_src"] = sourceOf(p)
            if (period > 0) v["sleep_eff"] = (100.0 * asleep / period).roundToInt().toString()
            for (k in listOf("DEEP", "REM", "LIGHT", "AWAKE")) stages[k]?.let { v[k.lowercase()] = hm(it) }
        }

        // --- other records
        step("workout") {
            val pts = api.list("exercise", "exercise.interval.civil_start_time >= \"${civil(now.minus(Duration.ofDays(1)))}\"", 25)
            pts.maxByOrNull { it.getJSONObject("exercise").getJSONObject("interval").getString("startTime") }?.getJSONObject("exercise")?.let { e ->
                v["workout"] = e.optString("displayName").ifEmpty { title(e.optString("exerciseType")) }
                e.optString("activeDuration").removeSuffix("s").toDoubleOrNull()?.let { v["workout_time"] = hm((it / 60).roundToInt().toLong()) }
            }
        }
        step("weight") {
            val pts = api.list("weight", "weight.sample_time.physical_time >= \"${utc(now.minus(Duration.ofDays(30)))}\"", 50)
            pts.maxByOrNull { it.getJSONObject("weight").getJSONObject("sampleTime").getString("physicalTime") }
                ?.let { v["weight"] = "%.1f".format(Locale.US, it.getJSONObject("weight").num("weightGrams") / 1000) }
        }
        step("kcal_in") {
            val pts = api.list("nutrition-log", "nutrition_log.interval.civil_start_time >= \"${today}T00:00:00\"", 500)
            if (pts.isNotEmpty()) v["kcal_in"] = thousands(pts.sumOf { it.getJSONObject("nutritionLog").optJSONObject("energy")?.num("kcal") ?: 0.0 })
        }
        step("glucose") {
            val pts = api.list("blood-glucose", "blood_glucose.sample_time.physical_time >= \"${utc(now.minus(Duration.ofDays(1)))}\"", 50)
            pts.maxByOrNull { it.getJSONObject("bloodGlucose").getJSONObject("sampleTime").getString("physicalTime") }?.let {
                val mg = it.getJSONObject("bloodGlucose").num("bloodGlucoseMilligramsPerDeciliter")
                v["glucose"] = "%.1f".format(Locale.US, mg / 18.016); v["glucose_mgdl"] = "%.0f".format(Locale.US, mg)
            }
        }
        step("ecg") {
            val pts = api.list("electrocardiogram", "electrocardiogram.interval.start_time >= \"${utc(now.minus(Duration.ofDays(1)))}\"", 10)
            pts.maxByOrNull { it.getJSONObject("electrocardiogram").getJSONObject("interval").getString("startTime") }?.let {
                it.getJSONObject("electrocardiogram").optString("resultClassification").takeIf(String::isNotEmpty)
                    ?.let { c -> v["ecg"] = c.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase) }
            }
        }

        AppLog.log("google", v.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" }.ifEmpty { "no stats" })
        return v
    }

    private fun utc(t: Instant) = DateTimeFormatter.ISO_INSTANT.format(t.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
    private fun civil(t: Instant) = LocalDateTime.ofInstant(t, zone).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()

    companion object {
        /** Apps with a short name; any other app is identified by its package name (as on the server). */
        val APP_ALIASES = mapOf(
            "com.ouraring.oura" to "oura", "com.ultrahuman.android" to "ultrahuman",
            "com.sec.android.app.shealth" to "samsung", "com.google.android.apps.fitness" to "phone",
            "com.android.healthconnect.phone" to "phone",
        )

        /** Short name for where a data point came from (watch, oura, phone, <package>...). */
        fun sourceOf(p: JSONObject): String {
            val ds = p.optJSONObject("dataSource") ?: JSONObject()
            if (ds.optString("platform") == "FITBIT") {
                return if (ds.optJSONObject("device")?.optString("displayName") == "MobileTrack") "phone" else "watch"
            }
            val pkg = ds.optJSONObject("application")?.optString("packageName").orEmpty()
            if (pkg.startsWith("com.android.healthconnect.phone")) return "phone"
            return APP_ALIASES[pkg] ?: pkg.ifEmpty { ds.optString("platform").ifEmpty { "unknown" }.lowercase() }
        }

        /** The data point's body: its one key besides dataSource and name. */
        fun bodyOf(p: JSONObject): JSONObject =
            p.keys().asSequence().first { it != "dataSource" && it != "name" }.let { p.getJSONObject(it) }

        /** Numbers arrive as JSON numbers or as strings (int64). */
        fun JSONObject.num(key: String): Double = when (val x = opt(key)) {
            is Number -> x.toDouble()
            is String -> x.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }

        fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

        fun instant(s: String): Instant = runCatching { Instant.parse(s) }
            .getOrElse { java.time.OffsetDateTime.parse(s).toInstant() }

        private fun thousands(n: Double) = "%,d".format(Locale.US, n.roundToInt())
        private fun title(s: String) = s.replace('_', ' ').lowercase().split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }
}

/** Google sign-in is missing or needs the user (consent, account choice): only the app can fix it. */
class GoogleAuthException(message: String) : Exception(message)
