package dev.korikosmos.healthdisplay.discord

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * The Social SDK's Android glue (DiscordSocialSdkInit.setEngineActivity) expects a game's
 * Activity, and its RPC client binds to the Discord app through that Activity. A binding
 * made through a real Activity is torn down when the Activity is destroyed (app swiped
 * away), which would end presence even though our foreground service is still running.
 *
 * This stand-in is never shown: its base context is the application context, so the SDK's
 * bindService/unbindService/getPackageManager calls are owned by the app process (kept
 * alive by PresenceService) and it also works when the service starts at boot without any
 * UI. Only methods the SDK uses are relevant; startActivity (OAuth only, unused) is
 * redirected to a new task.
 */
internal class EngineContext(app: Context) : Activity() {
    init {
        attachBaseContext(app.applicationContext)
    }

    override fun getApplicationContext(): Context = baseContext.applicationContext
    override fun getPackageManager(): PackageManager = baseContext.packageManager
    override fun getPackageName(): String = baseContext.packageName
    override fun startActivity(intent: Intent) =
        baseContext.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
