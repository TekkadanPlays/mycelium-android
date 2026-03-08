package social.mycelium.android.services

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.cybin.core.Event
import com.example.cybin.relay.RelayUrlNormalizer
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.repository.DraftsRepository

/**
 * WorkManager worker that publishes a pre-signed Nostr event from a scheduled draft.
 * Triggered by NoteScheduler (via ScheduleReceiver alarm or direct enqueue).
 *
 * The draft's signedEventJson is parsed back into an Event and sent to the stored relay URLs.
 * On success the draft is marked completed; on failure it is marked with the error
 * and retried with exponential backoff.
 */
class ScheduledNoteWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "ScheduledNoteWorker"
    }

    override suspend fun doWork(): Result {
        val draftId = inputData.getString("draft_id")
        if (draftId.isNullOrBlank()) {
            Log.w(TAG, "No draft_id in work data")
            return Result.failure()
        }

        val draft = DraftsRepository.getDraft(draftId)
        if (draft == null) {
            Log.w(TAG, "Draft not found: ${draftId.take(8)}")
            return Result.failure()
        }

        if (draft.isCompleted) {
            Log.d(TAG, "Draft already completed: ${draftId.take(8)}")
            return Result.success()
        }

        val signedJson = draft.signedEventJson
        if (signedJson.isNullOrBlank()) {
            Log.w(TAG, "Draft has no signed event: ${draftId.take(8)}")
            DraftsRepository.markFailed(draftId, "No signed event")
            return Result.failure()
        }

        val relayUrls = draft.relayUrls
        if (relayUrls.isEmpty()) {
            Log.w(TAG, "Draft has no relay URLs: ${draftId.take(8)}")
            DraftsRepository.markFailed(draftId, "No relay URLs")
            return Result.failure()
        }

        return try {
            // Parse the pre-signed event
            val event = Event.fromJson(signedJson)

            // Normalize relay URLs
            val normalized = relayUrls
                .mapNotNull { RelayUrlNormalizer.normalizeOrNull(it)?.url }
                .toSet()

            if (normalized.isEmpty()) {
                DraftsRepository.markFailed(draftId, "No valid relay URLs after normalization")
                return Result.failure()
            }

            // Send to relays via the existing relay state machine
            val relayManager = RelayConnectionStateMachine.getInstance()
            relayManager.send(event, normalized)

            Log.d(TAG, "Scheduled note published: ${event.id.take(8)} → ${normalized.size} relays")
            DraftsRepository.markCompleted(draftId)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish scheduled note: ${e.message}", e)
            DraftsRepository.markFailed(draftId, e.message ?: "Unknown error")

            // Retry — WorkManager will use exponential backoff
            if (runAttemptCount < 5) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        // Minimal foreground info for expedited work (required on older APIs)
        val notification = android.app.Notification.Builder(applicationContext, "scheduled_notes")
            .setContentTitle("Publishing scheduled note")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .build()
        return ForegroundInfo(9999, notification)
    }
}
