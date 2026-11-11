package dev.korikosmos.healthdisplay.presence

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigTest {
    @Test fun parsesEnvLikeServer() {
        val v = Config.parseEnv(
            """
            # comment
            PULSOID_TOKEN="abc"
            DETAILS=❤️ {bpm} bpm · RHR {rhr}
            SOURCE_SLEEP = oura,watch,*
            junk line
            """.trimIndent(),
        )
        assertEquals("abc", v["PULSOID_TOKEN"])
        assertEquals("❤️ {bpm} bpm · RHR {rhr}", v["DETAILS"])
        assertEquals(listOf("oura", "watch", "*"), Config(v).sourceOrder("SLEEP"))
        assertEquals(listOf("watch", "oura", "ultrahuman", "*", "samsung", "phone"), Config(v).sourceOrder("STEPS"))
    }

    @Test fun defaultsAndNumbers() {
        val c = Config(mapOf("POLL_INTERVAL" to "x"))
        assertEquals(3.0, c.pollInterval, 0.0)
        assertEquals(1.5, c.statMaxAgeDays, 0.0)
    }
}
