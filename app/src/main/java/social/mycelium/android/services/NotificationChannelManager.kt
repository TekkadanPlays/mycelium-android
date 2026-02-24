package social.mycelium.android.services

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
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
    const val CHANNEL_REACTIONS = "mycelium_reactions"
    const val CHANNEL_ZAPS = "mycelium_zaps"
    const val CHANNEL_REPOSTS = "mycelium_reposts"
    const val CHANNEL_DMS = "mycelium_dms"

    // ── Channel Group IDs ──
    private const val GROUP_SOCIAL = "mycelium_social"
    private const val GROUP_SERVICE = "mycelium_service"

    // ── Notification IDs ──
    const val NOTIFICATION_ID_RELAY_SERVICE = 1001
    /** Base ID for social notifications — offset by notification type ordinal + hash. */
    const val NOTIFICATION_ID_SOCIAL_BASE = 2000

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

        val reactionsChannel = NotificationChannel(
            CHANNEL_REACTIONS,
            "Reactions",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            group = GROUP_SOCIAL
            description = "Likes and emoji reactions on your notes"
            setShowBadge(false)
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
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            group = GROUP_SOCIAL
            description = "When someone reposts your note"
            setShowBadge(false)
        }

        val dmsChannel = NotificationChannel(
            CHANNEL_DMS,
            "Direct Messages",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            group = GROUP_SOCIAL
            description = "Private messages from other users"
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
                dmsChannel
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
        return NotificationCompat.Builder(context, CHANNEL_RELAY_SERVICE)
            .setContentTitle("Mycelium is running")
            .setContentText(contentText)
            .setSmallIcon(social.mycelium.android.R.drawable.ic_notification_network)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Post a social notification (reply, mention, zap, etc.).
     * Respects the channel — user can mute individual channels in system settings.
     */
    fun postSocialNotification(
        context: Context,
        channelId: String,
        notificationId: Int,
        title: String,
        body: String,
        autoCancel: Boolean = true
    ) {
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
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted
            android.util.Log.w("NotificationChannelMgr", "Cannot post notification: ${e.message}")
        }
    }
}
