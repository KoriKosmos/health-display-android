package dev.korikosmos.healthdisplay.pulsoid

import kotlin.math.roundToInt

/** Live BPM plus session min/avg/max, as in the server's HeartRate class. Thread-safe. */
class HeartRate {
    var bpm: Int? = null; private set
    var measuredMs: Long = 0; private set
    private var min: Int? = null
    private var max: Int? = null
    private var total = 0L
    private var n = 0

    /** Record a reading; repeats of the same timestamp and zero readings are ignored. */
    @Synchronized
    fun add(bpm: Int, measuredMs: Long): Boolean {
        if (bpm <= 0 || measuredMs == this.measuredMs) return false
        this.bpm = bpm; this.measuredMs = measuredMs
        min = minOf(min ?: bpm, bpm); max = maxOf(max ?: bpm, bpm)
        total += bpm; n++
        return true
    }

    @Synchronized
    fun live(nowMs: Long, staleAfterSec: Double): Boolean =
        bpm != null && nowMs - measuredMs <= staleAfterSec * 1000

    @Synchronized
    fun values(): Map<String, String> = buildMap {
        bpm?.let { put("bpm", it.toString()) }
        min?.let { put("min", it.toString()) }
        max?.let { put("max", it.toString()) }
        if (n > 0) put("avg", (total.toDouble() / n).roundToInt().toString())
    }
}
