package dev.korikosmos.healthdisplay.presence

object StatsMerge {
    /**
     * Google Health values first; Health Connect fills whatever Google doesn't have (offline,
     * not signed in, or stats Google doesn't record, e.g. {mindful}). A "<stat>_src" key
     * always comes from the same source as its stat.
     */
    fun merge(primary: Map<String, String>, fallback: Map<String, String>): Map<String, String> {
        val out = primary.toMutableMap()
        for ((k, v) in fallback) {
            val base = k.removeSuffix("_src")
            if (k !in primary && base !in primary) out[k] = v
        }
        return out
    }
}
