package dev.korikosmos.healthdisplay.presence

import dev.korikosmos.healthdisplay.health.Ranker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class HrFallbackTest {
    private val now = Instant.parse("2026-11-13T18:00:00Z")
    private fun r(bpm: Long, secondsAgo: Long, src: String) = HrReading(bpm, now.minusSeconds(secondsAgo), src)

    @Test fun newestWinsAcrossSources() {
        val p = HrFallback.pick(listOf(r(70, 600, "watch"), r(66, 120, "oura"), r(80, 900, "ultrahuman")), Ranker(Config()))
        assertEquals(66L, p!!.bpm)
    }

    @Test fun preferenceBreaksTiesWithin60s() {
        val xs = listOf(r(70, 100, "watch"), r(66, 60, "oura"))
        assertEquals("watch", HrFallback.pick(xs, Ranker(Config()))!!.source)
        assertEquals("oura", HrFallback.pick(xs, Ranker(Config(mapOf("SOURCE_HR" to "oura,*"))))!!.source)
        assertEquals("oura", HrFallback.pick(listOf(r(70, 200, "watch"), r(66, 60, "oura")), Ranker(Config()))!!.source)
    }

    @Test fun maxAgeAndOff() {
        val c = Config()
        assertEquals(66L, HrFallback.usable(r(66, 900, "oura"), now, c)!!.bpm)
        assertNull(HrFallback.usable(r(66, 901, "oura"), now, c))
        assertNull(HrFallback.usable(r(66, 10, "oura"), now, Config(mapOf("HR_FALLBACK_MAX_AGE" to "0"))))
    }

    @Test fun valuesAndPresence() {
        assertEquals(mapOf("bpm" to "~72", "bpm_age" to "just now", "bpm_src" to "oura"), HrFallback.values(r(72, 20, "oura"), now))
        assertEquals("8 min ago", HrFallback.values(r(72, 480, "watch"), now)["bpm_age"])
        val values = mapOf("min" to "60", "avg" to "70", "max" to "90", "rhr" to "55") + HrFallback.values(r(72, 480, "watch"), now)
        val a = ActivityBuilder.build("~72", values, Config(), 0)!!
        assertEquals("❤️ ~72 bpm · 8 min ago · RHR 55", a.details)
        assertEquals("https://placehold.co/512x512/ED4245/FFFFFF/png?text=~72&font=montserrat", a.largeImage)
        assertEquals("min 60 · avg 70 · max 90", a.largeText)
    }
}
