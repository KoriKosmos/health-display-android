package dev.korikosmos.healthdisplay.health

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ManifestPermissionsTest {
    @Test fun manifestDeclaresEveryRequestedHealthPermission() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val declared = Regex("""android\.permission\.health\.[A-Z0-9_]+""").findAll(manifest).map { it.value }.toSet()
        assertEquals(HealthStats.PERMISSIONS.sorted(), declared.sorted())
    }

    @Test fun hm() {
        assertEquals("45m", HealthStats.hm(45))
        assertEquals("7h05", HealthStats.hm(425))
    }
}
