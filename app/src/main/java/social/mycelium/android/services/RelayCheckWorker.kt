package social.mycelium.android.services

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import social.mycelium.android.data.AccountInfo
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.repository.RelayStorageManager
import social.mycelium.android.ui.settings.ConnectionMode
import social.mycelium.android.ui.settings.NotificationPreferences
import java.util.concurrent.TimeUnit

/**
 * WorkManager periodic worker for **Adaptive** connection mode.
 *
 * Opens temporary relay connections, fetches new notification events
 * (replies, mentions, zaps, DMs) since the last check, dispatches
 * Android notifications, then closes.
 *
 * Battery-efficient: runs only when network is available, at the
 * user-configured interval (15 min – 6 hours). Android batches
 * wakeups with other jobs.
 */
class RelayCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "RelayCheckWorker"
        private const val UNIQUE_WORK_NAME = "relay_inbox_check"
        private const val PREFS_NAME = "relay_check_worker"
        private const val KEY_LAST_CHECK_TIMESTAMP = "last_check_timestamp"
        private const val ACCOUNT_PREFS_NAME = "Mycelium_accounts"
        private const val PREF_CURRENT_ACCOUNT = "current_account_npub"
        private const val PREF_ALL_ACCOUNTS = "all_accounts_json"

        /** Max time to wait for relay events before giving up. */
        private const val FETCH_TIMEOUT_MS = 15_000L

        /**
         * Schedule the periodic inbox check. Call when [ConnectionMode.ADAPTIVE] is active.
         * Uses [ExistingPeriodicWorkPolicy.UPDATE] so interval changes take effect immediately.
         */
        fun schedule(context: Context, intervalMinutes: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<RelayCheckWorker>(
                intervalMinutes, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInitialDelay(intervalMinutes, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.d(TAG, "Scheduled adaptive inbox check every ${intervalMinutes}min")
        }

        /** Cancel the periodic inbox check. Call when switching away from [ConnectionMode.ADAPTIVE]. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            Log.d(TAG, "Cancelled adaptive inbox check")
        }
    }

    override suspend fun doWork(): Result {
        // Only run if still in Adaptive mode (user may have changed while job was queued)
        if (NotificationPreferences.connectionMode.value != ConnectionMode.ADAPTIVE) {
            Log.d(TAG, "No longer in Adaptive mode, skipping")
            return Result.success()
        }

        val currentNpub = getCurrentAccountNpub() ?: run {
            Log.d(TAG, "No current account, skipping")
            return Result.success()
        }

        val hexPubkey = try {
            val nip19 = com.example.cybin.nip19.Nip19Parser.uriToRoute(currentNpub)
            (nip19?.entity as? com.example.cybin.nip19.NPub)?.hex
        } catch (e: Exception) { null }

        if (hexPubkey == null) {
            Log.d(TAG, "Could not derive hex pubkey, skipping")
            return Result.success()
        }

        val storageManager = RelayStorageManager(applicationContext)
        // Use inbox + outbox relays for notification check
        val inboxRelays = storageManager.loadInboxRelays(hexPubkey).map { it.url }
        val outboxRelays = storageManager.loadOutboxRelays(hexPubkey).map { it.url }
        val categoryRelays = storageManager.loadCategories(hexPubkey)
            .flatMap { it.relays }
            .map { it.url }
        val allRelayUrls = (inboxRelays + outboxRelays + categoryRelays)
            .map { social.mycelium.android.utils.normalizeRelayUrl(it) }
            .distinct()

        if (allRelayUrls.isEmpty()) {
            Log.d(TAG, "No relays configured, skipping")
            return Result.success()
        }

        val lastCheckTimestamp = getLastCheckTimestamp()
        val sinceTimestamp = if (lastCheckTimestamp > 0) lastCheckTimestamp else {
            // First run: only check last 30 minutes
            (System.currentTimeMillis() / 1000) - 1800
        }

        Log.d(TAG, "Checking ${allRelayUrls.size} relays for events since $sinceTimestamp (${hexPubkey.take(8)}...)")

        try {
            val events = fetchNotificationEvents(allRelayUrls, hexPubkey, sinceTimestamp)
            if (events.isNotEmpty()) {
                Log.d(TAG, "Found ${events.size} new notification events")
                dispatchNotifications(events, hexPubkey)
            } else {
                Log.d(TAG, "No new notification events")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inbox check failed: ${e.message}", e)
            return Result.retry()
        }

        // Update last check timestamp
        saveLastCheckTimestamp(System.currentTimeMillis() / 1000)
        return Result.success()
    }

    /**
     * Fetch notification-relevant events from relays using a temporary subscription.
     * Returns events that are replies, mentions, zaps, reactions, or DMs addressed to [hexPubkey].
     */
    private suspend fun fetchNotificationEvents(
        relayUrls: List<String>,
        hexPubkey: String,
        sinceTimestamp: Long
    ): List<com.example.cybin.core.Event> {
        val rcsm = RelayConnectionStateMachine.getInstance()
        val collectedEvents = mutableListOf<com.example.cybin.core.Event>()
        val eoseReceived = CompletableDeferred<Unit>()

        // Filter for events that tag this user (replies, mentions, zaps, DMs)
        val relayFilters = relayUrls.associate { url ->
            url to listOf(
                Filter(
                    kinds = listOf(1, 7, 9735, 4, 1111),
                    tags = mapOf("p" to listOf(hexPubkey)),
                    since = sinceTimestamp,
                    limit = 50
                )
            )
        }

        val handle = rcsm.relayPool.subscribe(relayFilters, SubscriptionPriority.BACKGROUND) { event, _ ->
            // Filter out our own events
            if (event.pubKey != hexPubkey) {
                collectedEvents.add(event)
            }
        }
        rcsm.relayPool.connect()

        // Wait for EOSE or timeout
        withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            // Simple time-based wait since we can't easily hook EOSE on the pool
            kotlinx.coroutines.delay(FETCH_TIMEOUT_MS / 2)
        }

        handle.close()
        return collectedEvents
    }

    /**
     * Dispatch Android notifications for the fetched events.
     * Groups by type and sends via [NotificationChannelManager].
     */
    private fun dispatchNotifications(
        events: List<com.example.cybin.core.Event>,
        hexPubkey: String
    ) {
        val notifyReactions = NotificationPreferences.notifyReactions.value
        val notifyZaps = NotificationPreferences.notifyZaps.value
        val notifyReposts = NotificationPreferences.notifyReposts.value
        val notifyMentions = NotificationPreferences.notifyMentions.value
        val notifyReplies = NotificationPreferences.notifyReplies.value
        val notifyDMs = NotificationPreferences.notifyDMs.value

        var replyCount = 0
        var mentionCount = 0
        var reactionCount = 0
        var zapCount = 0
        var dmCount = 0

        for (event in events) {
            when (event.kind) {
                1, 1111 -> {
                    // Check if it's a reply (has 'e' tag) or a mention (tags 'p' without 'e')
                    val hasETag = event.tags.any { it.size >= 2 && it[0] == "e" }
                    if (hasETag && notifyReplies) replyCount++
                    else if (notifyMentions) mentionCount++
                }
                7 -> if (notifyReactions) reactionCount++
                9735 -> if (notifyZaps) zapCount++
                4 -> if (notifyDMs) dmCount++
            }
        }

        val ctx = applicationContext

        if (replyCount > 0) {
            NotificationChannelManager.sendSummaryNotification(
                ctx,
                NotificationChannelManager.CHANNEL_REPLIES,
                "New replies",
                "$replyCount new ${if (replyCount == 1) "reply" else "replies"}",
                NotificationChannelManager.NOTIFICATION_ID_REPLY_SUMMARY
            )
        }
        if (reactionCount > 0) {
            NotificationChannelManager.sendSummaryNotification(
                ctx,
                NotificationChannelManager.CHANNEL_REACTIONS,
                "New reactions",
                "$reactionCount new ${if (reactionCount == 1) "reaction" else "reactions"}",
                NotificationChannelManager.NOTIFICATION_ID_REACTION_SUMMARY
            )
        }
        if (zapCount > 0) {
            NotificationChannelManager.sendSummaryNotification(
                ctx,
                NotificationChannelManager.CHANNEL_ZAPS,
                "New zaps",
                "$zapCount new ${if (zapCount == 1) "zap" else "zaps"}",
                NotificationChannelManager.NOTIFICATION_ID_ZAP_SUMMARY
            )
        }
        if (dmCount > 0) {
            NotificationChannelManager.sendSummaryNotification(
                ctx,
                NotificationChannelManager.CHANNEL_DMS,
                "New messages",
                "$dmCount new ${if (dmCount == 1) "message" else "messages"}",
                NotificationChannelManager.NOTIFICATION_ID_DM_SUMMARY
            )
        }
        if (mentionCount > 0) {
            NotificationChannelManager.sendSummaryNotification(
                ctx,
                NotificationChannelManager.CHANNEL_MENTIONS,
                "New mentions",
                "$mentionCount new ${if (mentionCount == 1) "mention" else "mentions"}",
                NotificationChannelManager.NOTIFICATION_ID_MENTION_SUMMARY
            )
        }
    }

    private fun getCurrentAccountNpub(): String? {
        val prefs = applicationContext.getSharedPreferences(ACCOUNT_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_CURRENT_ACCOUNT, null)
    }

    private fun getLastCheckTimestamp(): Long {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_LAST_CHECK_TIMESTAMP, 0L)
    }

    private fun saveLastCheckTimestamp(timestamp: Long) {
        applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_CHECK_TIMESTAMP, timestamp)
            .apply()
    }
}
