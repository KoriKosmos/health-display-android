package dev.korikosmos.healthdisplay.push

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerPushTest {
    @Test fun payloadFormat() {
        val p = ServerPush.payload(true, 1000, mapOf("bpm" to "72", "steps" to "2,800", "steps_src" to "google"), 2000)
        assertEquals(1, p.getInt("v"))
        assertEquals(2000L, p.getLong("sent_at"))
        assertEquals(true, p.getBoolean("live"))
        assertEquals(1000L, p.getLong("measured_at"))
        assertEquals("2,800", p.getJSONObject("values").getString("steps"))
        assertEquals(setOf("v", "sent_at", "live", "measured_at", "values"), p.keys().asSequence().toSet())
    }
}
