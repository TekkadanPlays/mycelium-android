package social.mycelium.android.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import social.mycelium.android.debug.MLog

/**
 * BroadcastReceiver triggered by AlarmManager when a scheduled note is due.
 * Delegates to NoteScheduler to enqueue an expedited WorkManager task.
 */
class ScheduleReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PUBLISH_SCHEDULED = "social.mycelium.android.PUBLISH_SCHEDULED"
        const val EXTRA_DRAFT_ID = "draft_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val draftId = intent.getStringExtra(EXTRA_DRAFT_ID)

        MLog.d("ScheduleReceiver", "Received broadcast: $action for draft ${draftId?.take(8)}")

        if (ACTION_PUBLISH_SCHEDULED == action && !draftId.isNullOrBlank()) {
            NoteScheduler.enqueueImmediate(context, draftId)
        }
    }
}
