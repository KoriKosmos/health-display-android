package dev.korikosmos.healthdisplay.pulsoid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateTest {
    @Test fun tracksMinAvgMaxAndIgnoresRepeats() {
        val hr = HeartRate()
        assertTrue(hr.add(70, 1000))
        assertFalse(hr.add(99, 1000)) // same timestamp
        assertFalse(hr.add(0, 2000))
        assertTrue(hr.add(81, 3000))
        assertEquals(mapOf("bpm" to "81", "min" to "70", "max" to "81", "avg" to "76"), hr.values())
    }

    @Test fun goesStale() {
        val hr = HeartRate()
        assertFalse(hr.live(0, 60.0))
        hr.add(70, 10_000)
        assertTrue(hr.live(70_000, 60.0))
        assertFalse(hr.live(70_001, 60.0))
    }
}
