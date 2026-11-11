package dev.korikosmos.healthdisplay.presence

import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateTest {
    @Test fun fillsAllPlaceholders() =
        assertEquals("❤️ 72 bpm · RHR 55", Template.render("❤️ {bpm} bpm · RHR {rhr}", mapOf("bpm" to "72", "rhr" to "55")))

    @Test fun dropsSegmentWithMissingValue() =
        assertEquals("❤️ 72 bpm · HRV 40ms",
            Template.render("❤️ {bpm} bpm · RHR {rhr} · HRV {hrv}ms", mapOf("bpm" to "72", "hrv" to "40")))

    @Test fun segmentWithoutPlaceholdersIsKept() =
        assertEquals("hi · 72", Template.render("hi · {bpm} · {x}", mapOf("bpm" to "72")))

    @Test fun tooShortBecomesEmpty() = assertEquals("", Template.render("{a}", mapOf("a" to "7")))

    @Test fun nothingLeftBecomesEmpty() = assertEquals("", Template.render("RHR {rhr}", emptyMap()))

    @Test fun capsAt128CodePoints() {
        val out = Template.render("{a}", mapOf("a" to "😀".repeat(200)))
        assertEquals(128, out.codePointCount(0, out.length))
    }
}
