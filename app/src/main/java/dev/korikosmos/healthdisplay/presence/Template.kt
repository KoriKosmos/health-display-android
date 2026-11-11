package dev.korikosmos.healthdisplay.presence

object Template {
    private val placeholder = Regex("""\{(\w+)\}""")
    const val SEPARATOR = " · "

    /**
     * Fill {placeholders}; drop any " · "-separated segment that references a missing value.
     * Same rules as the server's render(): result capped at 128 characters, and anything
     * shorter than 2 characters becomes "" (Discord rejects 1-character fields).
     */
    fun render(template: String, values: Map<String, String>): String {
        val kept = template.split(SEPARATOR).mapNotNull { seg ->
            val names = placeholder.findAll(seg).map { it.groupValues[1] }
            if (names.all { it in values }) placeholder.replace(seg) { values.getValue(it.groupValues[1]) }
            else null
        }
        val text = kept.filter { it.isNotBlank() }.joinToString(SEPARATOR)
        val cps = text.codePointCount(0, text.length)
        return when {
            cps < 2 -> ""
            cps > 128 -> text.substring(0, text.offsetByCodePoints(0, 128))
            else -> text
        }
    }

    /** Placeholder names used in a template. */
    fun names(template: String): Set<String> =
        placeholder.findAll(template).map { it.groupValues[1] }.toSet()
}
