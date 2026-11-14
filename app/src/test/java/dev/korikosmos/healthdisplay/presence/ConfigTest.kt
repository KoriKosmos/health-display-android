package dev.korikosmos.healthdisplay.presence

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigTest {
    @Test fun parsesEnvLikeServer() {
        val v = Config.parseEnv(
            """
            # comment
            PULSOID_TOKEN="abc"
            STAT_MAX_AGE_DAYS=2
            SOURCE_SLEEP = oura,watch,*
            junk line
            """.trimIndent(),
        )
        assertEquals("abc", v["PULSOID_TOKEN"])
        assertEquals(2.0, Config(v).statMaxAgeDays, 0.0)
        assertEquals(listOf("oura", "watch", "*"), Config(v).sourceOrder("SLEEP"))
        assertEquals(listOf("watch", "oura", "ultrahuman", "*", "samsung", "phone"), Config(v).sourceOrder("STEPS"))
    }

    @Test fun defaultsAndNumbers() {
        val c = Config(mapOf("POLL_INTERVAL" to "x"))
        assertEquals(3.0, c.pollInterval, 0.0)
        assertEquals(1.5, c.statMaxAgeDays, 0.0)
    }

    @Test fun equalValuesAreEqualConfigs() {
        // The service restarts its loop when the config changes; identical values must not count.
        assertEquals(Config(mapOf("A" to "1")), Config(mapOf("A" to "1")))
    }
}
