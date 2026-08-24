package `in`.setu.relay.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings the relay back after a reboot, but only if the user had it running.
 *
 * A disaster app that silently restarts itself on every boot without consent is
 * exactly the creepy behaviour docs/07-ui-spec.md is trying to avoid, so the
 * flag is set by the user's own start/stop action.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        if (!Prefs(context).relayWanted) return
        Log.i("SetuBoot", "restarting relay after boot")
        runCatching { RelayService.start(context) }
    }
}
