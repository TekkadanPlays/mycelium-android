package social.mycelium.android.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import social.mycelium.android.debug.MLog

/**
 * Re-registers scheduled note alarms after device boot.
 * AlarmManager alarms are cleared on reboot, so we need to re-schedule
 * any pending drafts that were waiting to be published.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            MLog.d("BootReceiver", "Device booted — verifying scheduled notes")
            social.mycelium.android.repository.DraftsRepository.init(context)
            // DraftsRepository needs a pubkey to load drafts — we defer to app startup
            // which will call verifyAllScheduled after restoring the session.
            // For now, just ensure the repository is initialized.
        }
    }
}
