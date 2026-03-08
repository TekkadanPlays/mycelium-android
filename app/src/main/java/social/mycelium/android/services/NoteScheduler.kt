package social.mycelium.android.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import social.mycelium.android.data.Draft
import java.util.concurrent.TimeUnit

/**
 * Manages scheduling of pre-signed Nostr events for future publication.
 * Uses AlarmManager for precise timing and WorkManager for reliable execution.
 *
 * Flow:
 * 1. User composes a kind-1 note or kind-11 topic and chooses "Schedule"
 * 2. Event is signed immediately and stored in the Draft (signedEventJson)
 * 3. AlarmManager fires at the scheduled time → ScheduleReceiver
 * 4. ScheduleReceiver enqueues an expedited WorkManager task
 * 5. ScheduledNoteWorker reads the draft, sends the signed event to relays
 *
 * Based on Prism by hardran3.
 * @see <a href="https://github.com/hardran3/Prism">Prism</a>
 */
object NoteScheduler {

    private const val TAG = "NoteScheduler"

    /**
     * Schedule a draft for future publication using AlarmManager.
     * The draft must already have signedEventJson and scheduledAt set.
     */
    fun schedule(context: Context, draft: Draft) {
        val scheduledAt = draft.scheduledAt ?: return
        if (draft.signedEventJson.isNullOrBlank()) {
            Log.w(TAG, "Cannot schedule draft without signed event: ${draft.id.take(8)}")
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            action = ScheduleReceiver.ACTION_PUBLISH_SCHEDULED
            putExtra(ScheduleReceiver.EXTRA_DRAFT_ID, draft.id)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            draft.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    scheduledAt,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    scheduledAt,
                    pendingIntent
                )
            }
            Log.d(TAG, "Alarm set for draft ${draft.id.take(8)} at $scheduledAt")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set alarm: ${e.message}")
            // If the scheduled time is in the past, publish immediately
            if (scheduledAt <= System.currentTimeMillis()) {
                enqueueImmediate(context, draft.id)
            }
        }
    }

    /**
     * Enqueue an expedited WorkManager task to publish a draft immediately.
     * Called by ScheduleReceiver when the alarm fires.
     */
    fun enqueueImmediate(context: Context, draftId: String) {
        val workRequest = OneTimeWorkRequestBuilder<ScheduledNoteWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30, TimeUnit.SECONDS
            )
            .setInputData(workDataOf("draft_id" to draftId))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "scheduled_post_$draftId",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
        Log.d(TAG, "Immediate work enqueued for draft ${draftId.take(8)}")
    }

    /**
     * Enqueue a network-constrained retry for a draft that failed due to connectivity.
     */
    fun enqueueOfflineRetry(context: Context, draftId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<ScheduledNoteWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30, TimeUnit.SECONDS
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf("draft_id" to draftId))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "offline_retry_$draftId",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
        Log.d(TAG, "Offline retry enqueued for draft ${draftId.take(8)}")
    }

    /**
     * Cancel a scheduled draft's alarm and any pending WorkManager tasks.
     */
    fun cancel(context: Context, draftId: String) {
        // Cancel AlarmManager
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            action = ScheduleReceiver.ACTION_PUBLISH_SCHEDULED
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            draftId.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }

        // Cancel WorkManager
        WorkManager.getInstance(context).cancelUniqueWork("scheduled_post_$draftId")
        WorkManager.getInstance(context).cancelUniqueWork("offline_retry_$draftId")

        Log.d(TAG, "Cancelled all scheduling for draft ${draftId.take(8)}")
    }

    /**
     * Re-register alarms for all pending scheduled drafts.
     * Called after device boot or app start to ensure alarms survive restarts.
     */
    fun verifyAllScheduled(context: Context) {
        val pending = social.mycelium.android.repository.DraftsRepository.getPendingScheduledDrafts()
        Log.d(TAG, "Verifying ${pending.size} scheduled drafts")

        val now = System.currentTimeMillis()
        pending.forEach { draft ->
            val scheduledAt = draft.scheduledAt ?: return@forEach
            if (scheduledAt <= now) {
                // Past due — publish immediately
                enqueueImmediate(context, draft.id)
            } else {
                // Re-register alarm
                schedule(context, draft)
            }
        }
    }
}
