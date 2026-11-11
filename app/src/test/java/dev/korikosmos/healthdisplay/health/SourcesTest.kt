package dev.korikosmos.healthdisplay.health

import androidx.health.connect.client.records.metadata.Device
import dev.korikosmos.healthdisplay.presence.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SourcesTest {
    private val ranker = Ranker(Config())
    private fun feed(src: String) = Feed(src, "pkg.$src", "")

    @Test fun names() {
        assertEquals("oura", Sources.sourceName("com.ouraring.oura", Device.TYPE_RING))
        assertEquals("watch", Sources.sourceName("com.fitbit.FitbitMobile", null))
        assertEquals("phone", Sources.sourceName("com.fitbit.FitbitMobile", Device.TYPE_PHONE))
        assertEquals("phone", Sources.sourceName("android", null))
        assertEquals("com.new.app", Sources.sourceName("com.new.app", null))
    }

    @Test fun rankUsesStarForUnknownApps() {
        assertEquals(0, ranker.rank("STEPS", "watch"))
        assertEquals(3, ranker.rank("STEPS", "com.new.app"))
        assertEquals(5, ranker.rank("STEPS", "phone"))
        val r = Ranker(Config(mapOf("SOURCE_SLEEP" to "oura,watch")))
        assertEquals(2, r.rank("SLEEP", "phone")) // no '*': after the list
    }

    @Test fun highestCountWinsNeverSummed() {
        val (f, n) = ranker.pickTotal("STEPS", mapOf(feed("watch") to 4000.0, feed("oura") to 9000.0, feed("phone") to 3000.0))!!
        assertEquals("oura", f.source); assertEquals(9000.0, n, 0.0)
    }

    @Test fun equalCountsGoToPreference() {
        val (f, _) = ranker.pickTotal("STEPS", mapOf(feed("phone") to 500.0, feed("oura") to 500.0))!!
        assertEquals("oura", f.source)
    }

    @Test fun zeroTotalsIgnored() = assertNull(ranker.pickTotal("STEPS", mapOf(feed("watch") to 0.0)))

    data class Night(val src: String, val endH: Long)

    @Test fun mostRecentNightWinsPreferenceWithin4h() {
        val h = 3600_000L
        val pick = { xs: List<Night> -> ranker.best(xs, "SLEEP", { it.src }, { it.endH * h }, 4 * h)!!.src }
        // watch ended 2h after oura: same night, watch preferred by default
        assertEquals("watch", pick(listOf(Night("oura", 100), Night("watch", 102))))
        // only the ring was worn last night; the watch's night is a day older
        assertEquals("oura", pick(listOf(Night("watch", 80), Night("oura", 104))))
        val r = Ranker(Config(mapOf("SOURCE_SLEEP" to "oura,watch,*")))
        assertEquals("oura", r.best(listOf(Night("oura", 100), Night("watch", 102)), "SLEEP", { it.src }, { it.endH * h }, 4 * h)!!.src)
    }

    @Test fun nightSpansMidnight() {
        val z = ZoneId.of("Europe/London")
        val eve = Instant.parse("2026-11-10T23:00:00Z"); val morn = Instant.parse("2026-11-11T06:00:00Z")
        assertEquals(LocalDate.of(2026, 11, 11), Sources.nightOf(eve, z))
        assertEquals(LocalDate.of(2026, 11, 11), Sources.nightOf(morn, z))
    }
}
