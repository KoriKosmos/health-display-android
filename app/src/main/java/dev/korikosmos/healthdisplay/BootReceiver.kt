package dev.korikosmos.healthdisplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.korikosmos.healthdisplay.config.ConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Restarts the presence service after a reboot if it was running before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (ConfigStore(context).running.first()) {
                    AppLog.log("boot", "restarting presence service")
                    PresenceService.start(context)
                }
            } catch (e: Exception) {
                AppLog.log("boot", "could not start: $e")
            } finally {
                pending.finish()
            }
        }
    }
}
