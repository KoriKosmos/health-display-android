package dev.korikosmos.healthdisplay.presence

import dev.korikosmos.healthdisplay.health.Ranker
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong

/** One heart-rate sample from Health Connect (watch, Oura, Ultrahuman, anything else). */
data class HrReading(val bpm: Long, val time: Instant, val source: String)

/**
 * Heart-rate fallback for when Pulsoid has no live reading, as on the server (refresh_hr /
 * build_activity): the newest sample from any source wins, SOURCE_HR (else SOURCE_DEFAULT)
 * breaks ties within 60 s, and it's only used while at most HR_FALLBACK_MAX_AGE old.
 */
object HrFallback {
    private const val TIE_WINDOW_MS = 60_000L

    fun pick(readings: List<HrReading>, ranker: Ranker): HrReading? =
        ranker.best(readings, "HR", { it.source }, { it.time.toEpochMilli() }, TIE_WINDOW_MS)

    /** The reading if it's still recent enough to show (HR_FALLBACK_MAX_AGE; 0 = off). */
    fun usable(r: HrReading?, now: Instant, config: Config): HrReading? {
        val maxAge = config.hrFallbackMaxAge
        if (r == null || maxAge <= 0) return null
        return r.takeIf { Duration.between(it.time, now).seconds <= maxAge }
    }

    /** {bpm} = "~72" (not live), {bpm_age}, {bpm_src}. */
    fun values(r: HrReading, now: Instant): Map<String, String> {
        val mins = (Duration.between(r.time, now).seconds.coerceAtLeast(0) / 60.0).roundToLong()
        return mapOf(
            "bpm" to "~${r.bpm}",
            "bpm_age" to if (mins < 1) "just now" else "$mins min ago",
            "bpm_src" to r.source,
        )
    }
}
