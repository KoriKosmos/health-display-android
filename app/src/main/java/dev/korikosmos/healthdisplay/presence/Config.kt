package dev.korikosmos.healthdisplay.presence

/**
 * Settings using the same keys and defaults as the server's config.env, so a pasted
 * config.env behaves the same on the phone. Unknown keys are kept (e.g. SOURCE_<GROUP>).
 */
data class Config(val values: Map<String, String> = emptyMap()) {
    operator fun get(key: String): String = values[key] ?: DEFAULTS[key] ?: ""

    fun double(key: String): Double = this[key].toDoubleOrNull() ?: DEFAULTS.getValue(key).toDouble()

    val pollInterval get() = double("POLL_INTERVAL")
    val updateInterval get() = double("UPDATE_INTERVAL")
    val staleAfter get() = double("STALE_AFTER")
    val statsInterval get() = double("STATS_INTERVAL")
    val statMaxAgeDays get() = double("STAT_MAX_AGE_DAYS")
    val googleInterval get() = double("GOOGLE_INTERVAL")
    val googleOn get() = this["GOOGLE_HEALTH"].equals("on", ignoreCase = true)
    val imageOn get() = !this["IMAGE"].equals("off", ignoreCase = true)

    /** Source preference for a stat group: SOURCE_<GROUP>, else SOURCE_DEFAULT. */
    fun sourceOrder(group: String): List<String> =
        (values["SOURCE_$group"]?.takeIf { it.isNotBlank() } ?: this["SOURCE_DEFAULT"])
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun with(changes: Map<String, String>) = Config(values + changes)

    companion object {
        val DEFAULTS = mapOf(
            "PULSOID_TOKEN" to "", "DISCORD_APP_ID" to "",
            "DETAILS" to "❤️ {bpm} bpm · RHR {rhr} · HRV {hrv}ms",
            "STATE" to "👟 {steps} · ⏱ {active} min active · 😴 {sleep}",
            "IMG_HOVER" to "min {min} · avg {avg} · max {max}",
            "SOURCE_DEFAULT" to "watch,oura,ultrahuman,*,samsung,phone",
            "STAT_MAX_AGE_DAYS" to "1.5",
            "IMAGE" to "on", "IMG_TEXT" to "{bpm}", "IMG_BG" to "ED4245", "IMG_FG" to "FFFFFF",
            "POLL_INTERVAL" to "3", "UPDATE_INTERVAL" to "15", "STALE_AFTER" to "60",
            // Phone-only: how often Health Connect is read.
            "STATS_INTERVAL" to "900",
            // Google Health API: main source when signed in (on/off), refresh interval (s), and
            // which source family gives today's totals: all-sources = watch + phone merged by
            // Google (what the Google Health app shows), google-wearables = watch only.
            "GOOGLE_HEALTH" to "off",
            "GOOGLE_INTERVAL" to "300",
            "GOOGLE_TOTALS" to "all-sources",
            // Send the phone's values to the Health Display server (docs/server-push.md);
            // off while PUSH_URL is empty. Same keys in the server's config.env.
            "PUSH_URL" to "", "PUSH_TOKEN" to "",
        )

        /** Parse KEY=VALUE lines like the server does (comments and blank lines skipped). */
        fun parseEnv(text: String): Map<String, String> = buildMap {
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || '=' !in line) continue
                val k = line.substringBefore('=').trim()
                val v = line.substringAfter('=').trim().trim('"').trim('\'')
                put(k, v)
            }
        }

        /** Serialize back to config.env text (for export / editing as text). */
        fun toEnv(values: Map<String, String>): String =
            values.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }
    }
}
