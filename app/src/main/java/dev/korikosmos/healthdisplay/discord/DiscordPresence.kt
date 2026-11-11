package dev.korikosmos.healthdisplay.discord

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import dev.korikosmos.healthdisplay.AppLog
import dev.korikosmos.healthdisplay.BuildConfig

/** What Discord shows. Empty strings are left out. */
data class PresenceActivity(
    val details: String,
    val state: String,
    val largeImage: String,
    val largeText: String,
    val startMs: Long,
)

/**
 * Rich Presence through the official Discord Social SDK (no auth: needs the Discord app
 * installed and signed in). All native calls run on one worker thread, which also pumps
 * the SDK's callbacks.
 */
object DiscordPresence {
    @Volatile var available = false; private set
    @Volatile var lastResult: String = "not started"; private set

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val pump = object : Runnable {
        override fun run() {
            nativeRunCallbacks()
            handler?.postDelayed(this, 100)
        }
    }

    init {
        available = BuildConfig.HAS_DISCORD_SDK && runCatching { System.loadLibrary("discord_bridge") }
            .onFailure { AppLog.log("discord", "native bridge failed to load: $it") }.isSuccess
    }

    /** The SDK's Android glue wants an Activity once; call from MainActivity.onCreate. */
    fun attachActivity(activity: Activity) {
        if (!available) return
        runCatching {
            Class.forName("com.discord.socialsdk.DiscordSocialSdkInit")
                .getMethod("setEngineActivity", Activity::class.java)
                .invoke(null, activity)
        }.onFailure { AppLog.log("discord", "setEngineActivity failed: $it") }
    }

    @Synchronized
    fun start(appId: Long) {
        if (!available) {
            lastResult = "Discord Social SDK missing from this build"
            AppLog.log("discord", lastResult); return
        }
        if (thread != null) return
        val t = HandlerThread("discord").also { it.start() }
        thread = t
        handler = Handler(t.looper).also {
            it.post { nativeInit(appId) }
            it.post(pump)
        }
        AppLog.log("discord", "started for application $appId")
    }

    fun update(a: PresenceActivity) {
        handler?.post { nativeUpdate(a.details, a.state, a.largeImage, a.largeText, a.startMs) }
    }

    fun clear() {
        handler?.post { nativeClear() }
    }

    @Synchronized
    fun stop() {
        val h = handler ?: return
        h.removeCallbacks(pump)
        h.post { nativeShutdown(); nativeRunCallbacks() }
        thread?.quitSafely()
        thread = null; handler = null
        AppLog.log("discord", "stopped")
    }

    @JvmStatic private fun onNativeLog(msg: String) = AppLog.log("discord-sdk", msg.trim())

    @JvmStatic private fun onNativeResult(ok: Boolean, error: String) {
        lastResult = if (ok) "presence set" else "presence failed: $error"
        AppLog.log("discord", lastResult)
    }

    @JvmStatic private external fun nativeInit(appId: Long)
    @JvmStatic private external fun nativeUpdate(
        details: String, state: String, largeImage: String, largeText: String, startMs: Long,
    )
    @JvmStatic private external fun nativeClear()
    @JvmStatic private external fun nativeRunCallbacks()
    @JvmStatic private external fun nativeShutdown()
}
