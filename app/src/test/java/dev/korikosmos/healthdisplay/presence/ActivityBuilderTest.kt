package dev.korikosmos.healthdisplay.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityBuilderTest {
    private val values = mapOf("bpm" to "72", "min" to "60", "max" to "90", "avg" to "70", "rhr" to "55", "steps" to "1,234")

    @Test fun clearedWithoutLiveHeartRate() = assertNull(ActivityBuilder.build(false, 72, values, Config(), 0))

    @Test fun defaultLayout() {
        val a = ActivityBuilder.build(true, 72, values, Config(), 5)!!
        assertEquals("❤️ 72 bpm · RHR 55", a.details)
        assertEquals("👟 1,234", a.state)
        assertEquals("min 60 · avg 70 · max 90", a.largeText)
        assertEquals("https://placehold.co/512x512/ED4245/FFFFFF/png?text=72&font=montserrat", a.largeImage)
    }

    @Test fun imageOff() {
        val a = ActivityBuilder.build(true, 72, values, Config(mapOf("IMAGE" to "off")), 5)!!
        assertEquals("", a.largeImage); assertEquals("", a.largeText)
    }

    @Test fun quoteMatchesPython() {
        // urllib.parse.quote("❤ 72/x") == '%E2%9D%A4%2072/x'
        assertEquals("%E2%9D%A4%2072/x", ActivityBuilder.quote("❤ 72/x"))
    }
}
