package dev.korikosmos.healthdisplay.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ActivityIntensityRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dev.korikosmos.healthdisplay.AppLog
import dev.korikosmos.healthdisplay.presence.Config
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.reflect.KClass

/**
 * Daily stats from Health Connect, mapped to the server's placeholder names.
 *
 * Every device/app is a fallback for the others: today's totals come from the feed that
 * counted the most; overnight stats and sleep from the most recent night any source
 * recorded; SOURCE_* order only breaks ties. Missing or stale stats are simply absent.
 *
 * Not available in Health Connect (no record type): {azm} {fitness} {ecg}. Health Connect
 * only has moderate/vigorous activity minutes, so {active} equals {active_mv}.
 */
class HealthStats(context: Context, private val config: Config) {
    private val client = HealthConnectClient.getOrCreate(context)
    private val ranker = Ranker(config)
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private fun feedOf(r: Record): Feed {
        val m = r.metadata
        val pkg = m.dataOrigin.packageName
        val d = m.device
        return Feed(
            Sources.sourceName(pkg, d?.type),
            pkg,
            listOfNotNull(d?.manufacturer, d?.model).joinToString(" "),
        )
    }

    private suspend fun <T : Record> read(type: KClass<T>, start: Instant, end: Instant = Instant.now()): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        repeat(20) {
            val resp = client.readRecords(
                ReadRecordsRequest(type, TimeRangeFilter.between(start, end), pageToken = token),
            )
            out += resp.records
            token = resp.pageToken
            if (token == null) return out
        }
        return out
    }

    suspend fun refresh(): Map<String, String> {
        val v = mutableMapOf<String, String>()
        val now = Instant.now()
        val midnight = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val tomorrow = midnight.plus(Duration.ofDays(1))
        val maxAge = Duration.ofMinutes((config.statMaxAgeDays * 24 * 60).toLong())

        suspend fun step(name: String, block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                AppLog.log("health", "$name: permission not granted")
            } catch (e: Exception) {
                AppLog.log("health", "$name: $e")
            }
        }

        /** Today's total per feed; records must start today (rings write one whole-day record). */
        suspend fun <T : Record> today(type: KClass<T>, start: (T) -> Instant, value: (T) -> Double): Map<Feed, Double> =
            read(type, midnight, tomorrow).filter { !start(it).isBefore(midnight) }
                .groupBy(::feedOf).mapValues { (_, rs) -> rs.sumOf(value) }

        // --- today's totals: the feed with the highest count wins (never summed)
        step("steps") {
            ranker.pickTotal("STEPS", today(StepsRecord::class, { it.startTime }) { it.count.toDouble() })
                ?.let { (f, n) -> v["steps"] = thousands(n); v["steps_src"] = f.source }
        }
        step("distance") {
            ranker.pickTotal("DISTANCE", today(DistanceRecord::class, { it.startTime }) { it.distance.inKilometers })
                ?.let { (_, n) -> v["distance"] = "%.1f".format(Locale.US, n) }
        }
        step("active") {
            val t = ranker.pickTotal("ACTIVE", today(ActivityIntensityRecord::class, { it.startTime }) {
                Duration.between(it.startTime, it.endTime).toMinutes().toDouble()
            })
            t?.let { (_, n) -> v["active"] = n.roundToInt().toString() }
            v["active_mv"] = (t?.second ?: 0.0).roundToInt().toString()
        }
        step("active_kcal") {
            ranker.pickTotal("CALORIES", today(ActiveCaloriesBurnedRecord::class, { it.startTime }) { it.energy.inKilocalories })
                ?.let { (_, n) -> v["active_kcal"] = thousands(n) }
        }
        step("calories") {
            ranker.pickTotal("CALORIES", today(TotalCaloriesBurnedRecord::class, { it.startTime }) { it.energy.inKilocalories })
                ?.let { (_, n) -> v["calories"] = thousands(n) }
        }
        step("floors") {
            ranker.pickTotal("FLOORS", today(FloorsClimbedRecord::class, { it.startTime }) { it.floors })
                ?.let { (_, n) -> v["floors"] = n.roundToInt().toString() }
        }
        step("kcal_in") {
            ranker.pickTotal("NUTRITION", today(NutritionRecord::class, { it.startTime }) { it.energy?.inKilocalories ?: 0.0 })
                ?.let { (_, n) -> v["kcal_in"] = thousands(n) }
        }

        // --- sleep: most recent night from any source; sessions ending within 4h = same night
        val since = now.minus(maxAge)
        var sleeps: List<SleepSessionRecord> = emptyList()
        step("sleep") {
            sleeps = read(SleepSessionRecord::class, since)
            val s = ranker.best(sleeps, "SLEEP", { feedOf(it).source }, { it.endTime.toEpochMilli() }, 4 * 3600_000L)
                ?: return@step
            val mins = { types: Set<Int> ->
                s.stages.filter { it.stage in types }.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
            }
            val period = Duration.between(s.startTime, s.endTime).toMinutes()
            val asleep = if (s.stages.isEmpty()) period else mins(ASLEEP)
            v["sleep"] = hm(asleep); v["sleep_src"] = feedOf(s).source
            if (s.stages.isNotEmpty() && period > 0) v["sleep_eff"] = (100.0 * asleep / period).roundToInt().toString()
            mapOf(
                "deep" to setOf(SleepSessionRecord.STAGE_TYPE_DEEP),
                "rem" to setOf(SleepSessionRecord.STAGE_TYPE_REM),
                "light" to setOf(SleepSessionRecord.STAGE_TYPE_LIGHT),
                "awake" to setOf(SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED),
            ).forEach { (k, types) -> if (s.stages.any { it.stage in types }) v[k] = hm(mins(types)) }
        }

        // --- overnight metrics: per feed and night, then the most recent night wins.
        // Sample-based metrics only count samples taken during a recorded sleep (any source),
        // so a daytime spot reading doesn't beat last night's ring data.
        fun duringSleep(t: Instant) =
            sleeps.isEmpty() || sleeps.any { !t.isBefore(it.startTime.minusSeconds(1800)) && !t.isAfter(it.endTime.plusSeconds(1800)) }

        data class Night(val feed: Feed, val night: LocalDate, val value: Double)

        fun <T> nightly(group: String, rs: List<T>, feed: (T) -> Feed, time: (T) -> Instant, value: (T) -> Double): Night? {
            val nights = rs.groupBy { feed(it) to Sources.nightOf(time(it), zone) }
                .map { (k, xs) -> Night(k.first, k.second, xs.map(value).average()) }
            return ranker.best(nights, group, { it.feed.source }, { it.night.toEpochDay() })
        }

        step("rhr") {
            nightly("RHR", read(RestingHeartRateRecord::class, since), ::feedOf, { it.time }) { it.beatsPerMinute.toDouble() }
                ?.let { v["rhr"] = it.value.roundToInt().toString(); v["rhr_src"] = it.feed.source }
        }
        step("hrv") {
            nightly("HRV", read(HeartRateVariabilityRmssdRecord::class, since).filter { duringSleep(it.time) },
                ::feedOf, { it.time }) { it.heartRateVariabilityMillis }
                ?.let { v["hrv"] = it.value.roundToInt().toString(); v["hrv_src"] = it.feed.source }
        }
        step("spo2") {
            nightly("SPO2", read(OxygenSaturationRecord::class, since).filter { duringSleep(it.time) },
                ::feedOf, { it.time }) { it.percentage.value }
                ?.let { v["spo2"] = "%.0f".format(Locale.US, it.value) }
        }
        step("resp") {
            nightly("RESP", read(RespiratoryRateRecord::class, since).filter { duringSleep(it.time) },
                ::feedOf, { it.time }) { it.rate }
                ?.let { v["resp"] = "%.1f".format(Locale.US, it.value) }
        }
        step("temp") {
            nightly("TEMP", read(SkinTemperatureRecord::class, since).filter { it.deltas.isNotEmpty() },
                ::feedOf, { it.endTime }) { r -> r.deltas.map { it.delta.inCelsius }.average() }
                ?.let { v["temp_delta"] = "%+.1f".format(Locale.US, it.value) }
        }
        step("vo2max") {
            nightly("VO2", read(Vo2MaxRecord::class, now.minus(Duration.ofDays(29))), ::feedOf, { it.time }) {
                it.vo2MillilitersPerMinuteKilogram
            }?.let { v["vo2max"] = "%.0f".format(Locale.US, it.value) }
        }

        // --- other records
        step("workout") {
            read(ExerciseSessionRecord::class, now.minus(Duration.ofDays(1))).maxByOrNull { it.startTime }?.let { e ->
                v["workout"] = e.title?.takeIf { it.isNotBlank() } ?: exerciseName(e.exerciseType)
                v["workout_time"] = hm(Duration.between(e.startTime, e.endTime).toMinutes())
            }
        }
        step("weight") {
            read(WeightRecord::class, now.minus(Duration.ofDays(29))).maxByOrNull { it.time }
                ?.let { v["weight"] = "%.1f".format(Locale.US, it.weight.inKilograms) }
        }
        step("glucose") {
            read(BloodGlucoseRecord::class, now.minus(Duration.ofDays(1))).maxByOrNull { it.time }?.let {
                v["glucose"] = "%.1f".format(Locale.US, it.level.inMillimolesPerLiter)
                v["glucose_mgdl"] = "%.0f".format(Locale.US, it.level.inMilligramsPerDeciliter)
            }
        }

        AppLog.log("health", v.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" }.ifEmpty { "no stats" })
        return v
    }

    /**
     * How today's totals were chosen: Health Connect's own de-duplicated total across all
     * apps, then each feed's total, record count and newest record. Records that started
     * before midnight are not counted (as on the server) and are listed separately.
     */
    suspend fun todayBreakdown(): String = buildString {
        val midnight = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val now = Instant.now()
        val hhmm = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(zone)

        suspend fun <T : Record> section(
            label: String, type: KClass<T>, metric: AggregateMetric<*>,
            start: (T) -> Instant, end: (T) -> Instant, value: (T) -> Double, fmt: (Double) -> String,
        ) {
            append("== $label\n")
            try {
                val all = when (val x = client.aggregate(AggregateRequest(setOf(metric), TimeRangeFilter.between(midnight, now)))[metric]) {
                    is Long -> x.toDouble()
                    is Double -> x
                    is androidx.health.connect.client.units.Length -> x.inKilometers
                    is androidx.health.connect.client.units.Energy -> x.inKilocalories
                    else -> null
                }
                append("   Health Connect total (all apps, de-duplicated): ${all?.let(fmt) ?: "none"}\n")
                val rs = read(type, midnight.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)))
                    .filter { end(it).isAfter(midnight) }
                val (today, before) = rs.partition { !start(it).isBefore(midnight) }
                today.groupBy(::feedOf).entries.sortedByDescending { e -> e.value.sumOf(value) }.forEach { (f, xs) ->
                    append("   ${f.source} [${f.pkg}${if (f.device.isNotEmpty()) " · ${f.device}" else " · no device"}]: " +
                        "${fmt(xs.sumOf(value))} in ${xs.size} records, newest ends ${hhmm.format(xs.maxOf(end))}\n")
                }
                if (before.isNotEmpty()) append("   (not counted: ${before.size} records starting before midnight, ${fmt(before.sumOf(value))})\n")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                append("   $e\n")
            }
        }

        section("steps", StepsRecord::class, StepsRecord.COUNT_TOTAL, { it.startTime }, { it.endTime },
            { it.count.toDouble() }, ::thousands)
        section("distance (km)", DistanceRecord::class, DistanceRecord.DISTANCE_TOTAL, { it.startTime }, { it.endTime },
            { it.distance.inKilometers }) { "%.2f".format(Locale.US, it) }
        section("active kcal", ActiveCaloriesBurnedRecord::class, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
            { it.startTime }, { it.endTime }, { it.energy.inKilocalories }, ::thousands)
        section("total kcal", TotalCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord.ENERGY_TOTAL,
            { it.startTime }, { it.endTime }, { it.energy.inKilocalories }, ::thousands)
    }

    /** What each record type holds over the last 7 days, per app and device (like the server's probe). */
    suspend fun probe(): String = buildString {
        val since = Instant.now().minus(Duration.ofDays(7))
        for (type in PROBE_TYPES) {
            append("== ${type.simpleName}: ")
            try {
                val rs = read(type, since)
                if (rs.isEmpty()) { append("none\n"); continue }
                append("${rs.size}\n")
                rs.groupBy(::feedOf).forEach { (f, xs) ->
                    val newest = xs.maxOf { r -> recordTime(r) }
                    append("   ${f.source} [${f.pkg}${if (f.device.isNotEmpty()) " · ${f.device}" else ""}]: ${xs.size}, newest $newest\n")
                }
            } catch (e: SecurityException) {
                append("no permission\n")
            } catch (e: Exception) {
                append("$e\n")
            }
        }
    }

    companion object {
        private val ASLEEP = setOf(
            SleepSessionRecord.STAGE_TYPE_DEEP, SleepSessionRecord.STAGE_TYPE_REM,
            SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_SLEEPING,
        )

        val PROBE_TYPES: List<KClass<out Record>> = listOf(
            StepsRecord::class, DistanceRecord::class, ActivityIntensityRecord::class,
            ActiveCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord::class, FloorsClimbedRecord::class,
            NutritionRecord::class, RestingHeartRateRecord::class, HeartRateVariabilityRmssdRecord::class,
            OxygenSaturationRecord::class, RespiratoryRateRecord::class, SkinTemperatureRecord::class,
            Vo2MaxRecord::class, SleepSessionRecord::class, ExerciseSessionRecord::class,
            WeightRecord::class, BloodGlucoseRecord::class, MindfulnessSessionRecord::class,
        )

        /** Read permissions requested: every probed type plus background reading. */
        val PERMISSIONS: Set<String> =
            PROBE_TYPES.map { HealthPermission.getReadPermission(it) }.toSet() +
                HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

        /** End time of an interval record, or the time of an instantaneous one (their interfaces are internal). */
        private fun recordTime(r: Record): Instant =
            listOf("getEndTime", "getTime").firstNotNullOfOrNull { name ->
                runCatching { r.javaClass.getMethod(name).invoke(r) as Instant }.getOrNull()
            } ?: Instant.EPOCH

        fun hm(minutes: Long): String = if (minutes >= 60) "%dh%02d".format(minutes / 60, minutes % 60) else "${minutes}m"

        private fun thousands(n: Double) = "%,d".format(Locale.US, n.roundToInt())

        private fun exerciseName(type: Int): String =
            (ExerciseSessionRecord.EXERCISE_TYPE_INT_TO_STRING_MAP[type] ?: "workout")
                .replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
