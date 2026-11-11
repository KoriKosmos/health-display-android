package dev.korikosmos.healthdisplay.discord

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import dev.korikosmos.healthdisplay.AppLog
import dev.korikosmos.healthdisplay.BuildConfig
import kotlin.concurrent.thread

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

    private var appId: Long? = null
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

    private var contextSet = false

    /** Hands the SDK's Android glue its context (see [EngineContext]); once per process. */
    private fun setContext(context: Context) {
        if (contextSet) return
        runCatching {
            Class.forName("com.discord.socialsdk.DiscordSocialSdkInit")
                .getMethod("setEngineActivity", Activity::class.java)
                .invoke(null, EngineContext(context))
            contextSet = true
        }.onFailure { AppLog.log("discord", "setEngineActivity failed: $it") }
    }

    @Synchronized
    fun start(context: Context, appId: Long) {
        if (!available) {
            lastResult = "Discord Social SDK missing from this build"
            AppLog.log("discord", lastResult); return
        }
        if (thread != null && this.appId == appId) return
        if (thread != null) stop()
        setContext(context)
        startSdkLogcat()
        AppLog.log("discord", discordAppStatus(context))
        selectProvider(context)
        this.appId = appId
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
        thread = null; handler = null; appId = null
        AppLog.log("discord", "stopped")
    }

    /** Packages offering the Social SDK's presence (RPC) service, official Discord first. */
    fun rpcProviders(context: Context): List<String> =
        context.packageManager.queryIntentServices(Intent(RPC_ACTION), 0)
            .map { it.serviceInfo.packageName }.distinct()
            .sortedBy { if (it == DISCORD_PACKAGE) 0 else 1 }

    /** Which Discord app(s) offer the presence service, for the status screen and log. */
    fun discordAppStatus(context: Context): String {
        val pm = context.packageManager
        fun version(pkg: String) = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
            ?.let { "${it.versionName} (${it.longVersionCode})" } ?: "?"
        val providers = rpcProviders(context)
        val official = runCatching { pm.getPackageInfo(DISCORD_PACKAGE, 0) }.isSuccess
        return when {
            providers.isNotEmpty() -> "Presence service offered by: " +
                providers.joinToString { "$it ${version(it)}" } +
                if (providers.first() != DISCORD_PACKAGE) " (official $DISCORD_PACKAGE ${if (official) "installed without it" else "not installed"})" else ""
            official -> "Discord app ${version(DISCORD_PACKAGE)} installed, but it has no presence service (too old?)"
            else -> "No installed app offers Discord's presence service ($DISCORD_PACKAGE not installed or not visible)"
        }
    }

    /**
     * The SDK only looks for the official package (DiscordRpcClient.DISCORD_PACKAGES =
     * ["com.discord"]). If that isn't installed but another Discord build (e.g. a client
     * mod under its own package name) offers the same presence service, point the SDK at it.
     */
    private fun selectProvider(context: Context) {
        val target = rpcProviders(context).firstOrNull() ?: return
        if (target == DISCORD_PACKAGE) return
        runCatching {
            val f = Class.forName("com.discord.socialsdk.DiscordRpcClient").getDeclaredField("DISCORD_PACKAGES")
            f.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val packages = f.get(null) as Array<String>
            packages[0] = target
            AppLog.log("discord", "using presence service from $target")
        }.onFailure { AppLog.log("discord", "could not select $target: $it") }
    }

    private var logcatStarted = false

    /**
     * The SDK's Java glue logs connection failures (bind denied, handshake failed…) to logcat
     * under "DiscordSocialSdk"; mirror our own process's lines into the app log.
     */
    private fun startSdkLogcat() {
        if (logcatStarted) return
        logcatStarted = true
        thread(isDaemon = true, name = "sdk-logcat") {
            runCatching {
                val p = ProcessBuilder(
                    "logcat", "-v", "brief", "-T", "1", "--pid=${android.os.Process.myPid()}",
                    "DiscordSocialSdk:V", "*:S",
                ).redirectErrorStream(true).start()
                p.inputStream.bufferedReader().forEachLine {
                    if (it.isNotBlank() && !it.startsWith("-----")) AppLog.log("discord-java", it.trim())
                }
            }.onFailure { AppLog.log("discord", "logcat reader failed: $it") }
        }
    }

    private const val DISCORD_PACKAGE = "com.discord"
    private const val RPC_ACTION = "com.discord.socialsdk.rpc.IDiscordRpcService"

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
