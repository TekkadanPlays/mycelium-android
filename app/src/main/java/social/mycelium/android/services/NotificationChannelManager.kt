package social.mycelium.android.services


import social.mycelium.android.debug.MLog
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Centralized notification channel management for Mycelium.
 *
 * Android notification channels (API 26+) are user-visible categories in system settings.
 * Each channel has its own importance, sound, vibration, and badge settings that the user
 * can configure independently via the system notification settings.
 *
 * Channels:
 * - **Relay Service** (low importance, ongoing) — foreground service keepalive
 * - **Replies** (high importance) — kind-1 replies to your notes
 * - **Comments** (high importance) — kind-1111 thread comments
 * - **Mentions** (default importance) — when someone mentions you
 * - **Reactions** (low importance) — likes and emoji reactions
 * - **Zaps** (high importance) — lightning zaps on your notes
 * - **Reposts** (low importance) — when someone reposts your note
 * - **Direct Messages** (high importance) — NIP-17 DMs
 *
 * Call [createChannels] once from Application/Activity onCreate.
 */
object NotificationChannelManager {

    // ── Channel IDs ──
    const val CHANNEL_RELAY_SERVICE = "mycelium_relay_service"
    const val CHANNEL_REPLIES = "mycelium_replies"
    const val CHANNEL_COMMENTS = "mycelium_comments"
    const val CHANNEL_MENTIONS = "mycelium_mentions"
    const val CHANNEL_REACTIONS = "mycelium_reactions_v2"
    const val CHANNEL_ZAPS = "mycelium_zaps"
    const val CHANNEL_REPOSTS = "mycelium_reposts_v2"
    const val CHANNEL_DMS = "mycelium_dms"
    const val CHANNEL_POLLS = "mycelium_polls"

    // ── Channel Group IDs ──
    private const val GROUP_SOCIAL = "mycelium_social"
    private const val GROUP_SERVICE = "mycelium_service"

    // ── Notification IDs ──
    const val NOTIFICATION_ID_RELAY_SERVICE = 1001
    /** Base ID for social notifications — offset by notification type ordinal + hash. */
    const val NOTIFICATION_ID_SOCIAL_BASE = 2000
    // Summary notification IDs for Adaptive mode periodic checks
    const val NOTIFICATION_ID_REPLY_SUMMARY = 3001
    const val NOTIFICATION_ID_REACTION_SUMMARY = 3002
    const val NOTIFICATION_ID_ZAP_SUMMARY = 3003
    const val NOTIFICATION_ID_DM_SUMMARY = 3004
    const val NOTIFICATION_ID_MENTION_SUMMARY = 3005

    // ── Old channel to delete ──
    private const val OLD_CHANNEL_ID = "Mycelium_relay_channel"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Delete the old monolithic channel
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)

        // Create groups
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_SERVICE, "Background Service")
        )
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_SOCIAL, "Social Notifications")
        )

        // ── Relay service channel (ongoing, low importance — no sound/vibration) ──
        val relayChannel = NotificationChannel(
            CHANNEL_RELAY_SERVICE,
            "Relay connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            group = GROUP_SERVICE
            description = "Persistent notification while relay connections are active"
            setShowBadge(false)
        }

        // ── Social channels ──
        val repliesChannel = NotificationChannel(
            CHANNEL_REPLIES,
            "Replies",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            group = GROUP_SOCIAL
            description = "When someone replies to your notes"
        }

        val commentsChannel = NotificationChannel(
            CHANNEL_COMMENTS,
            "Comments",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            group = GROUP_SOCIAL
            description = "When someone comments on your threads"
        }

        val mentionsChannel = NotificationChannel(
            CHANNEL_MENTIONS,
            "Mentions",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            group = GROUP_SOCIAL
            description = "When someone mentions you in a note"
        }

        // Delete old LOW-importance v1 channels (Android caches importance per channel ID)
        manager.deleteNotificationChannel("mycelium_reactions")
        manager.deleteNotificationChannel("mycelium_reposts")

        val reactionsChannel = NotificationChannel(
            CHANNEL_REACTIONS,
            "Reactions",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            group = GROUP_SOCIAL
            description = "Likes and emoji reactions on your notes"
        }

        val zapsChannel = NotificationChannel(
            CHANNEL_ZAPS,
            "Zaps",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            group = GROUP_SOCIAL
            description = "Lightning zaps on your notes"
        }

        val repostsChannel = NotificationChannel(
            CHANNEL_REPOSTS,
            "Reposts",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            group = GROUP_SOCIAL
            description = "When someone reposts your note"
        }

        val dmsChannel = NotificationChannel(
            CHANNEL_DMS,
            "Direct Messages",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            group = GROUP_SOCIAL
            description = "Private messages from other users"
        }

        val pollsChannel = NotificationChannel(
            CHANNEL_POLLS,
            "Poll Votes",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            group = GROUP_SOCIAL
            description = "When someone votes on your polls"
        }

        manager.createNotificationChannels(
            listOf(
                relayChannel,
                repliesChannel,
                commentsChannel,
                mentionsChannel,
                reactionsChannel,
                zapsChannel,
                repostsChannel,
                dmsChannel,
                pollsChannel
            )
        )
    }

    /**
     * Build the relay service foreground notification.
     */
    fun buildRelayServiceNotification(context: Context, followingCount: Int): android.app.Notification {
        val contentText = if (followingCount > 0) {
            "$followingCount new note${if (followingCount != 1) "s" else ""} from people you follow"
        } else {
            "Keeping relay connections active"
        }
        // PendingIntent to open the app when the notification is tapped.
        // Also signals to the OS that this is a legitimate user-facing foreground service,
        // preventing the process from being reclassified as oom_cached and frozen.
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        // "Stop Mycelium" action — kills the process so the user can restart fresh
        val killIntent = Intent(KillAppReceiver.ACTION_KILL_APP).apply {
            setPackage(context.packageName)
        }
        val killPendingIntent = PendingIntent.getBroadcast(
            context, 0, killIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_RELAY_SERVICE)
            .setContentTitle("Mycelium is running")
            .setContentText(contentText)
            .setSmallIcon(social.mycelium.android.R.drawable.ic_notification_network)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop Mycelium", killPendingIntent)
            .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
            .build()
    }

    /** Intent extra keys for deep-linking from notification tap. */
    const val EXTRA_NOTE_ID = "mycelium_note_id"
    const val EXTRA_ROOT_NOTE_ID = "mycelium_root_note_id"
    const val EXTRA_NOTIF_TYPE = "mycelium_notif_type"
    /** Hex pubkey of the account that received this notification.
     *  Used to switch accounts when the user taps a notification from a background account. */
    const val EXTRA_ACCOUNT_PUBKEY = "mycelium_account_pubkey"

    /**
     * Post a social notification (reply, mention, zap, etc.).
     * Respects the channel — user can mute individual channels in system settings.
     *
     * @param noteId The event ID that triggered this notification (used for highlight)
     * @param rootNoteId The root note ID for thread navigation (null = noteId is the root)
     * @param notifType Notification type string for routing (e.g. "REPLY", "ZAP")
     */
    fun postSocialNotification(
        context: Context,
        channelId: String,
        notificationId: Int,
        title: String,
        body: String,
        noteId: String? = null,
        rootNoteId: String? = null,
        notifType: String? = null,
        accountPubkey: String? = null,
        autoCancel: Boolean = true
    ) {
        // Build a deep-link PendingIntent so tapping opens the specific thread/note
        val tapIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (noteId != null) putExtra(EXTRA_NOTE_ID, noteId)
            if (rootNoteId != null) putExtra(EXTRA_ROOT_NOTE_ID, rootNoteId)
            if (notifType != null) putExtra(EXTRA_NOTIF_TYPE, notifType)
            if (accountPubkey != null) putExtra(EXTRA_ACCOUNT_PUBKEY, accountPubkey)
        }
        val pendingIntent = if (tapIntent != null) {
            PendingIntent.getActivity(
                context, notificationId, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(social.mycelium.android.R.drawable.ic_notification_network)
            .setAutoCancel(autoCancel)
            .setPriority(
                when (channelId) {
                    CHANNEL_REPLIES, CHANNEL_COMMENTS, CHANNEL_ZAPS, CHANNEL_DMS ->
                        NotificationCompat.PRIORITY_HIGH
                    CHANNEL_MENTIONS -> NotificationCompat.PRIORITY_DEFAULT
                    else -> NotificationCompat.PRIORITY_LOW
                }
            )
            .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted
            MLog.w("NotificationChannelMgr", "Cannot post notification: ${e.message}")
        }
    }

    /**
     * Post a summary notification from the Adaptive mode background check.
     * Uses a fixed notification ID per type so repeated checks update rather than stack.
     */
    fun sendSummaryNotification(
        context: Context,
        channelId: String,
        title: String,
        body: String,
        notificationId: Int
    ) {
        postSocialNotification(context, channelId, notificationId, title, body, autoCancel = true)
    }
}
