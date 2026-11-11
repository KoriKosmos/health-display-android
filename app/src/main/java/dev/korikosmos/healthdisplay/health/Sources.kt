package dev.korikosmos.healthdisplay.health

import androidx.health.connect.client.records.metadata.Device
import dev.korikosmos.healthdisplay.presence.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One data feed: the app that wrote a record plus the device it came from. Totals are
 * computed per feed and never added across feeds (two apps can count the same steps).
 */
data class Feed(val source: String, val pkg: String, val device: String)

object Sources {
    /** Apps with a short name; anything else is identified by its package name. */
    val APP_ALIASES = mapOf(
        "com.ouraring.oura" to "oura",
        "com.ultrahuman.android" to "ultrahuman",
        "com.sec.android.app.shealth" to "samsung",
        "android" to "phone", // on-device step counting (pre-June-2026 records)
        "com.android.healthconnect.phone" to "phone",
    )

    /** Google/Fitbit apps sync the Pixel Watch; their data counts as the watch unless the device says phone. */
    private val WATCH_APPS = setOf("com.fitbit.FitbitMobile", "com.google.android.apps.fitness")

    /** Short name for where a record came from: watch, oura, ultrahuman, samsung, phone or <package>. */
    fun sourceName(pkg: String, deviceType: Int?): String {
        APP_ALIASES[pkg]?.let { return it }
        return when (deviceType) {
            Device.TYPE_WATCH -> "watch"
            Device.TYPE_PHONE -> "phone"
            else -> if (pkg in WATCH_APPS) "watch" else pkg
        }
    }

    /**
     * The "night" a sample belongs to: noon-to-noon, labelled with the morning's date, so
     * readings from 23:00 and 03:00 count as the same night.
     */
    fun nightOf(t: Instant, zone: ZoneId): LocalDate = t.plusSeconds(12 * 3600).atZone(zone).toLocalDate()
}

/** Source preference from SOURCE_<GROUP> / SOURCE_DEFAULT; only used to break ties. */
class Ranker(private val config: Config) {
    fun rank(group: String, source: String): Int {
        val order = config.sourceOrder(group)
        val i = order.indexOf(source)
        if (i >= 0) return i
        val star = order.indexOf("*")
        return if (star >= 0) star else order.size
    }

    /** The feed that counted the most (the device actually worn); ties go to the preferred source. */
    fun pickTotal(group: String, totals: Map<Feed, Double>): Pair<Feed, Double>? =
        totals.filterValues { it > 0 }.entries
            .minWithOrNull(compareBy({ -it.value }, { rank(group, it.key.source) }))
            ?.let { it.key to it.value }

    /**
     * The most recent item; items within [windowMs] of the newest compete on source
     * preference (then recency).
     */
    fun <T> best(items: List<T>, group: String, source: (T) -> String, recency: (T) -> Long, windowMs: Long = 0): T? {
        if (items.isEmpty()) return null
        val newest = items.maxOf(recency)
        return items.filter { newest - recency(it) <= windowMs }
            .minWithOrNull(compareBy({ rank(group, source(it)) }, { -recency(it) }))
    }
}
