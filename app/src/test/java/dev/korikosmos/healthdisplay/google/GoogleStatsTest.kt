package dev.korikosmos.healthdisplay.google

import dev.korikosmos.healthdisplay.presence.Config
import dev.korikosmos.healthdisplay.presence.StatsMerge
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class GoogleStatsTest {
    private val zone = ZoneId.of("Europe/London")
    private val now = Instant.parse("2026-11-11T19:30:00Z")

    // Rollup bodies as returned by the API for this account on 2026-11-11 (all-sources).
    private val rollups = mapOf(
        "steps" to """{"steps": {"countSum": "2800"}}""",
        "distance" to """{"distance": {"millimetersSum": "1524413"}}""",
        "total-calories" to """{"totalCalories": {"kcalSum": 2000.304428122271}}""",
        "active-energy-burned" to """{"activeEnergyBurned": {"kcalSum": 238.51509255555555}}""",
        "active-minutes" to """{"activeMinutes": {"activeMinutesRollupByActivityLevel": [{"activityLevel": "LIGHT", "activeMinutesSum": "123"}, {"activityLevel": "MODERATE", "activeMinutesSum": "19"}, {"activityLevel": "VIGOROUS", "activeMinutesSum": "4"}]}}""",
        "active-zone-minutes" to """{"activeZoneMinutes": {"sumInCardioHeartZone": "2", "sumInPeakHeartZone": "0", "sumInFatBurnHeartZone": "9"}}""",
        "floors" to """{"floors": {"countSum": "7"}}""",
    )

    private fun point(source: String, body: String) = JSONObject("""{"dataSource": $source, "name": "x", $body}""")
    private val watch = """{"platform": "FITBIT", "device": {"displayName": "Pixel Watch 4"}}"""
    private val oura = """{"platform": "HEALTH_CONNECT", "application": {"packageName": "com.ouraring.oura"}}"""

    private val lists = mapOf(
        "daily-resting-heart-rate" to listOf(
            point(watch, """"dailyRestingHeartRate": {"date": {"year": 2026, "month": 11, "day": 10}, "beatsPerMinute": "63"}"""),
            point(watch, """"dailyRestingHeartRate": {"date": {"year": 2026, "month": 11, "day": 11}, "beatsPerMinute": "64"}"""),
            point(oura, """"dailyRestingHeartRate": {"date": {"year": 2026, "month": 11, "day": 11}, "beatsPerMinute": 58}"""),
        ),
        "sleep" to listOf(
            point(oura, """"sleep": {"interval": {"endTime": "2026-11-11T07:10:00Z"}, "summary": {"minutesAsleep": "430", "minutesInSleepPeriod": "480", "stagesSummary": [{"type": "DEEP", "minutes": "70"}, {"type": "REM", "minutes": "95"}]}}"""),
            point(watch, """"sleep": {"interval": {"endTime": "2026-11-11T07:30:00Z"}, "summary": {"minutesAsleep": "400", "minutesInSleepPeriod": "470"}}"""),
        ),
    )

    private val fake = object : GoogleApi {
        val families = mutableSetOf<String>()
        override suspend fun list(type: String, filter: String, pageSize: Int) = lists[type].orEmpty()
        override suspend fun rollupDay(type: String, family: String, day: LocalDate): JSONObject? {
            families += family
            assertEquals(LocalDate.of(2026, 11, 11), day)
            return rollups[type]?.let(::JSONObject)
        }
    }

    @Test fun todayTotalsFromMergedRollups() = runBlocking {
        val v = GoogleStats(fake, Config(), zone).refresh(now)
        assertEquals(setOf("all-sources"), fake.families)
        assertEquals("2,800", v["steps"]); assertEquals("google", v["steps_src"])
        assertEquals("1.5", v["distance"])
        assertEquals("2,000", v["calories"]); assertEquals("239", v["active_kcal"])
        assertEquals("146", v["active"]); assertEquals("23", v["active_mv"])
        assertEquals("13", v["azm"]) // 9 + 2*(2+0)
        assertEquals("7", v["floors"])
    }

    @Test fun overnightMostRecentDayThenPreference() = runBlocking {
        val v = GoogleStats(fake, Config(), zone).refresh(now)
        assertEquals("64", v["rhr"]); assertEquals("watch", v["rhr_src"]) // same day: watch preferred over oura
        val sleepFirst = GoogleStats(fake, Config(mapOf("SOURCE_SLEEP" to "oura,watch,*")), zone).refresh(now)
        assertEquals("7h10", sleepFirst["sleep"]); assertEquals("oura", sleepFirst["sleep_src"])
        assertEquals("90", sleepFirst["sleep_eff"]); assertEquals("1h10", sleepFirst["deep"])
        assertEquals("6h40", v["sleep"]) // default order: watch wins the same night
        assertFalse("hrv" in v)
    }

    @Test fun sourceNames() {
        assertEquals("watch", GoogleStats.sourceOf(JSONObject("""{"dataSource": $watch}""")))
        assertEquals("phone", GoogleStats.sourceOf(JSONObject("""{"dataSource": {"platform": "FITBIT", "device": {"displayName": "MobileTrack"}}}""")))
        assertEquals("oura", GoogleStats.sourceOf(JSONObject("""{"dataSource": $oura}""")))
    }

    @Test fun mergePrefersGoogleAndKeepsSourceWithItsStat() {
        val m = StatsMerge.merge(
            mapOf("steps" to "2,800", "steps_src" to "google"),
            mapOf("steps" to "2,039", "steps_src" to "watch", "hrv" to "41", "hrv_src" to "oura", "mindful" to "10"),
        )
        assertEquals(mapOf("steps" to "2,800", "steps_src" to "google", "hrv" to "41", "hrv_src" to "oura", "mindful" to "10"), m)
    }
}
