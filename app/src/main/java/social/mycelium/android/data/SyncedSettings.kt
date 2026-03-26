package social.mycelium.android.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * User preferences synced across devices via NIP-78 (kind 30078).
 * Published as NIP-44 encrypted JSON with d-tag "MyceliumSettings".
 *
 * Only includes settings that should follow the user across devices.
 * Device-specific settings (connection mode, push, relay lists, etc.) stay local.
 */
data class SyncedSettings(
    // Tier 1 — High value
    val zapAmounts: List<Long> = listOf(1L),
    val showSensitive: Boolean = false,
    val muteStrangers: Boolean = false,

    // Tier 2 — UX preferences
    val theme: String = "DARK",
    val accent: String = "VIOLET",
    val compactMedia: Boolean = false,
    val autoplayVideos: Boolean = true,
    val autoplaySound: Boolean = false,
    val autoPipLive: Boolean = true,

    // Tier 3 — Notification filter preferences
    val notifyReactions: Boolean = true,
    val notifyZaps: Boolean = true,
    val notifyReposts: Boolean = true,
    val notifyMentions: Boolean = true,
    val notifyReplies: Boolean = true,
    val notifyDMs: Boolean = true,

    // Tier 4 — Feed preferences (cold-start defaults)
    val defaultFeedView: String = "HOME",       // HOME or TOPICS
    val defaultSortOrder: String = "LATEST",     // LATEST or POPULAR
    val defaultListDTag: String? = null,           // NIP-51 list d-tag for cold start, null = Following

    // Tier 5 — DM preferences
    val autoDecryptDMs: Boolean = false,            // Auto-decrypt without user confirmation (off by default)
    val showDmContent: Boolean = false              // Show decrypted sender/content in DM notifications (off by default)
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("zapAmounts", JSONArray(zapAmounts))
        obj.put("showSensitive", showSensitive)
        obj.put("muteStrangers", muteStrangers)
        obj.put("theme", theme)
        obj.put("accent", accent)
        obj.put("compactMedia", compactMedia)
        obj.put("autoplayVideos", autoplayVideos)
        obj.put("autoplaySound", autoplaySound)
        obj.put("autoPipLive", autoPipLive)
        obj.put("notifyReactions", notifyReactions)
        obj.put("notifyZaps", notifyZaps)
        obj.put("notifyReposts", notifyReposts)
        obj.put("notifyMentions", notifyMentions)
        obj.put("notifyReplies", notifyReplies)
        obj.put("notifyDMs", notifyDMs)
        obj.put("defaultFeedView", defaultFeedView)
        obj.put("defaultSortOrder", defaultSortOrder)
        if (defaultListDTag != null) obj.put("defaultListDTag", defaultListDTag)
        obj.put("autoDecryptDMs", autoDecryptDMs)
        obj.put("showDmContent", showDmContent)
        return obj.toString()
    }

    companion object {
        fun fromJson(json: String): SyncedSettings {
            return try {
                val obj = JSONObject(json)
                val zapArr = obj.optJSONArray("zapAmounts")
                val zapAmounts = if (zapArr != null) {
                    List(zapArr.length()) { zapArr.getLong(it) }
                } else listOf(1L)

                SyncedSettings(
                    zapAmounts = zapAmounts,
                    showSensitive = obj.optBoolean("showSensitive", false),
                    muteStrangers = obj.optBoolean("muteStrangers", false),
                    theme = obj.optString("theme", "DARK"),
                    accent = obj.optString("accent", "VIOLET"),
                    compactMedia = obj.optBoolean("compactMedia", false),
                    autoplayVideos = obj.optBoolean("autoplayVideos", true),
                    autoplaySound = obj.optBoolean("autoplaySound", false),
                    autoPipLive = obj.optBoolean("autoPipLive", true),
                    notifyReactions = obj.optBoolean("notifyReactions", true),
                    notifyZaps = obj.optBoolean("notifyZaps", true),
                    notifyReposts = obj.optBoolean("notifyReposts", true),
                    notifyMentions = obj.optBoolean("notifyMentions", true),
                    notifyReplies = obj.optBoolean("notifyReplies", true),
                    notifyDMs = obj.optBoolean("notifyDMs", true),
                    defaultFeedView = obj.optString("defaultFeedView", "HOME"),
                    defaultSortOrder = obj.optString("defaultSortOrder", "LATEST"),
                    defaultListDTag = obj.optString("defaultListDTag", null),
                    autoDecryptDMs = obj.optBoolean("autoDecryptDMs", false),
                    showDmContent = obj.optBoolean("showDmContent", false)
                )
            } catch (e: Exception) {
                SyncedSettings() // Return defaults on parse failure
            }
        }

        /**
         * Snapshot current local preferences into a SyncedSettings instance.
         */
        fun fromLocalPreferences(): SyncedSettings {
            return SyncedSettings(
                zapAmounts = social.mycelium.android.utils.ZapAmountManager.zapAmounts.value,
                showSensitive = social.mycelium.android.ui.settings.MediaPreferences.showSensitiveContent.value,
                muteStrangers = social.mycelium.android.ui.settings.NotificationPreferences.muteStrangers.value,
                theme = social.mycelium.android.ui.theme.ThemePreferences.themeMode.value.name,
                accent = social.mycelium.android.ui.theme.ThemePreferences.accentColor.value.name,
                compactMedia = social.mycelium.android.ui.theme.ThemePreferences.compactMedia.value,
                autoplayVideos = social.mycelium.android.ui.settings.MediaPreferences.autoplayVideos.value,
                autoplaySound = social.mycelium.android.ui.settings.MediaPreferences.autoplaySound.value,
                autoPipLive = social.mycelium.android.ui.settings.MediaPreferences.autoPipLiveActivities.value,
                notifyReactions = social.mycelium.android.ui.settings.NotificationPreferences.notifyReactions.value,
                notifyZaps = social.mycelium.android.ui.settings.NotificationPreferences.notifyZaps.value,
                notifyReposts = social.mycelium.android.ui.settings.NotificationPreferences.notifyReposts.value,
                notifyMentions = social.mycelium.android.ui.settings.NotificationPreferences.notifyMentions.value,
                notifyReplies = social.mycelium.android.ui.settings.NotificationPreferences.notifyReplies.value,
                notifyDMs = social.mycelium.android.ui.settings.NotificationPreferences.notifyDMs.value,
                defaultFeedView = social.mycelium.android.ui.settings.FeedPreferences.defaultFeedView.value,
                defaultSortOrder = social.mycelium.android.ui.settings.FeedPreferences.defaultSortOrder.value,
                defaultListDTag = social.mycelium.android.ui.settings.FeedPreferences.defaultListDTag.value,
                autoDecryptDMs = social.mycelium.android.ui.settings.DmPreferences.autoDecryptDMs.value,
                showDmContent = social.mycelium.android.ui.settings.NotificationPreferences.showDmContent.value
            )
        }

        /**
         * Apply remote settings to all local preference singletons.
         * Called after fetching kind 30078 on login.
         */
        fun applyToLocalPreferences(settings: SyncedSettings) {
            // Tier 1
            social.mycelium.android.utils.ZapAmountManager.updateAmounts(settings.zapAmounts)
            social.mycelium.android.ui.settings.MediaPreferences.setShowSensitiveContent(settings.showSensitive)
            social.mycelium.android.ui.settings.NotificationPreferences.setMuteStrangers(settings.muteStrangers)

            // Tier 2
            social.mycelium.android.ui.theme.ThemePreferences.setThemeMode(
                social.mycelium.android.ui.theme.ThemeMode.fromString(settings.theme)
            )
            social.mycelium.android.ui.theme.ThemePreferences.setAccentColor(
                social.mycelium.android.ui.theme.AccentColor.fromString(settings.accent)
            )
            social.mycelium.android.ui.theme.ThemePreferences.setCompactMedia(settings.compactMedia)
            social.mycelium.android.ui.settings.MediaPreferences.setAutoplayVideos(settings.autoplayVideos)
            social.mycelium.android.ui.settings.MediaPreferences.setAutoplaySound(settings.autoplaySound)
            social.mycelium.android.ui.settings.MediaPreferences.setAutoPipLiveActivities(settings.autoPipLive)

            // Tier 3
            social.mycelium.android.ui.settings.NotificationPreferences.setNotifyReactions(settings.notifyReactions)
            social.mycelium.android.ui.settings.NotificationPreferences.setNotifyZaps(settings.notifyZaps)
            social.mycelium.android.ui.settings.NotificationPreferences.setNotifyReposts(settings.notifyReposts)
            social.mycelium.android.ui.settings.NotificationPreferences.setNotifyMentions(settings.notifyMentions)
            social.mycelium.android.ui.settings.NotificationPreferences.setNotifyReplies(settings.notifyReplies)
            social.mycelium.android.ui.settings.NotificationPreferences.setNotifyDMs(settings.notifyDMs)

            // Tier 4 — Feed defaults
            social.mycelium.android.ui.settings.FeedPreferences.setDefaultFeedView(settings.defaultFeedView)
            social.mycelium.android.ui.settings.FeedPreferences.setDefaultSortOrder(settings.defaultSortOrder)
            social.mycelium.android.ui.settings.FeedPreferences.setDefaultListDTag(settings.defaultListDTag)

            // Tier 5 — DM preferences
            social.mycelium.android.ui.settings.DmPreferences.applyAutoDecryptDMs(settings.autoDecryptDMs)
            social.mycelium.android.ui.settings.NotificationPreferences.setShowDmContent(settings.showDmContent)
        }
    }
}
