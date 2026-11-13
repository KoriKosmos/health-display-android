package dev.korikosmos.healthdisplay.presence

import dev.korikosmos.healthdisplay.discord.PresenceActivity

/** Builds the Discord activity exactly like the server's build_activity(). */
object ActivityBuilder {
    /**
     * [bpm] is the text for the BPM tile: the live Pulsoid value, "~72" for a fallback
     * reading, or null when there is neither (presence is cleared).
     */
    fun build(bpm: String?, values: Map<String, String>, config: Config, startedMs: Long): PresenceActivity? {
        if (bpm.isNullOrEmpty()) return null
        val image = config.imageOn
        return PresenceActivity(
            details = Template.render(config["DETAILS"], values),
            state = Template.render(config["STATE"], values),
            largeImage = if (image) tileUrl(config, bpm) else "",
            largeText = if (image) Template.render(config["IMG_HOVER"], values) else "",
            startMs = startedMs,
        )
    }

    fun tileUrl(config: Config, bpm: String): String {
        val text = quote(config["IMG_TEXT"].replace("{bpm}", bpm))
        return "https://placehold.co/512x512/${config["IMG_BG"]}/${config["IMG_FG"]}/png?text=$text&font=montserrat"
    }

    /** Percent-encode like Python's urllib.parse.quote (unreserved characters and '/' kept). */
    fun quote(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if (c.toChar().let { it.isLetterOrDigit() && c < 128 || it in "_.-~/" }) append(c.toChar())
            else append("%%%02X".format(c))
        }
    }

    /** Identity of what's shown, ignoring the timestamp (to skip pushing unchanged presence). */
    fun key(a: PresenceActivity?): String =
        if (a == null) "" else listOf(a.details, a.state, a.largeImage, a.largeText).joinToString("\u0000")
}
