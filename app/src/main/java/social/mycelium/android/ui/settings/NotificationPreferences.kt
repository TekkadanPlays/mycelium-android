package social.mycelium.android.ui.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Controls how relay connections behave when the app is not in the foreground.
 *
 * - [ALWAYS_ON]: Foreground service keeps WebSockets open. Real-time notifications. Highest battery usage.
 * - [ADAPTIVE]: WorkManager periodic inbox check (configurable interval). No persistent connections. Moderate battery.
 * - [WHEN_ACTIVE]: Connections only while app is visible. Zero background activity. Best battery life.
 */
enum class ConnectionMode {
    ALWAYS_ON,
    ADAPTIVE,
    WHEN_ACTIVE;

    companion object {
        fun fromString(value: String?): ConnectionMode = when (value) {
            "ALWAYS_ON" -> ALWAYS_ON
            "ADAPTIVE" -> ADAPTIVE
            "WHEN_ACTIVE" -> WHEN_ACTIVE
            else -> ADAPTIVE
        }
    }
}

/**
 * Persists notification preferences using SharedPreferences.
 * Singleton — call init(context) once from Application/Activity.
 */
object NotificationPreferences {
    private const val PREFS_NAME = "Mycelium_notification_prefs"
    // ── Global (device-level) keys — NOT account-bound ──
    private const val KEY_PUSH_ENABLED = "push_enabled"
    private const val KEY_BACKGROUND_SERVICE = "background_service_enabled" // legacy, migrated to connection_mode
    private const val KEY_CONNECTION_MODE = "connection_mode"
    private const val KEY_ADAPTIVE_CHECK_INTERVAL = "adaptive_check_interval_minutes"
    // ── Per-account keys (prefixed with hex pubkey at runtime) ──
    private const val KEY_NOTIFY_REACTIONS = "notify_reactions"
    private const val KEY_NOTIFY_ZAPS = "notify_zaps"
    private const val KEY_NOTIFY_REPOSTS = "notify_reposts"
    private const val KEY_NOTIFY_MENTIONS = "notify_mentions"
    private const val KEY_NOTIFY_REPLIES = "notify_replies"
    private const val KEY_NOTIFY_DMS = "notify_dms"
    private const val KEY_NOTIFY_POLLS = "notify_polls"
    private const val KEY_NOTIFY_QUOTES = "notify_quotes"
    private const val KEY_MUTE_STRANGERS = "mute_strangers"
    private const val KEY_SHOW_DM_CONTENT = "show_dm_content"

    /** Active account hex pubkey (lowercase). Null = guest / no account. */
    @Volatile private var activeAccountHex: String? = null

    /** Returns the per-account key by prefixing with the active account hex pubkey. */
    private fun acctKey(base: String): String {
        val hex = activeAccountHex
        return if (hex.isNullOrBlank()) base else "${hex}:${base}"
    }

    /** Default adaptive check interval in minutes. */
    const val DEFAULT_ADAPTIVE_INTERVAL_MINUTES = 15L

    private lateinit var prefs: SharedPreferences

    private val _pushEnabled = MutableStateFlow(true)
    val pushEnabled: StateFlow<Boolean> = _pushEnabled.asStateFlow()

    private val _connectionMode = MutableStateFlow(ConnectionMode.ADAPTIVE)
    val connectionMode: StateFlow<ConnectionMode> = _connectionMode.asStateFlow()

    /** Backward-compat: true only when mode is ALWAYS_ON. */
    val backgroundServiceEnabled: Boolean get() = _connectionMode.value == ConnectionMode.ALWAYS_ON

    /** Adaptive mode: how often to check inbox relays (minutes). */
    private val _adaptiveCheckIntervalMinutes = MutableStateFlow(DEFAULT_ADAPTIVE_INTERVAL_MINUTES)
    val adaptiveCheckIntervalMinutes: StateFlow<Long> = _adaptiveCheckIntervalMinutes.asStateFlow()

    private val _notifyReactions = MutableStateFlow(true)
    val notifyReactions: StateFlow<Boolean> = _notifyReactions.asStateFlow()

    private val _notifyZaps = MutableStateFlow(true)
    val notifyZaps: StateFlow<Boolean> = _notifyZaps.asStateFlow()

    private val _notifyReposts = MutableStateFlow(true)
    val notifyReposts: StateFlow<Boolean> = _notifyReposts.asStateFlow()

    private val _notifyMentions = MutableStateFlow(true)
    val notifyMentions: StateFlow<Boolean> = _notifyMentions.asStateFlow()

    private val _notifyReplies = MutableStateFlow(true)
    val notifyReplies: StateFlow<Boolean> = _notifyReplies.asStateFlow()

    private val _notifyDMs = MutableStateFlow(true)
    val notifyDMs: StateFlow<Boolean> = _notifyDMs.asStateFlow()

    private val _notifyPolls = MutableStateFlow(true)
    val notifyPolls: StateFlow<Boolean> = _notifyPolls.asStateFlow()

    private val _notifyQuotes = MutableStateFlow(true)
    val notifyQuotes: StateFlow<Boolean> = _notifyQuotes.asStateFlow()

    private val _muteStrangers = MutableStateFlow(false)
    val muteStrangers: StateFlow<Boolean> = _muteStrangers.asStateFlow()

    /** When true, DM notifications show sender name and message preview (requires decryption).
     *  When false (default), DM notifications show "New private message" — no content revealed. */
    private val _showDmContent = MutableStateFlow(false)
    val showDmContent: StateFlow<Boolean> = _showDmContent.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // ── Global (device-level) settings ──
        _pushEnabled.value = prefs.getBoolean(KEY_PUSH_ENABLED, true)

        // Migration: old boolean toggle → new ConnectionMode enum
        if (prefs.contains(KEY_CONNECTION_MODE)) {
            _connectionMode.value = ConnectionMode.fromString(prefs.getString(KEY_CONNECTION_MODE, null))
        } else if (prefs.contains(KEY_BACKGROUND_SERVICE)) {
            // Migrate: old true → ALWAYS_ON, old false → ADAPTIVE (upgrade to new default)
            val wasEnabled = prefs.getBoolean(KEY_BACKGROUND_SERVICE, true)
            val migrated = if (wasEnabled) ConnectionMode.ALWAYS_ON else ConnectionMode.ADAPTIVE
            _connectionMode.value = migrated
            prefs.edit()
                .putString(KEY_CONNECTION_MODE, migrated.name)
                .remove(KEY_BACKGROUND_SERVICE)
                .apply()
        } else {
            _connectionMode.value = ConnectionMode.ADAPTIVE
        }

        _adaptiveCheckIntervalMinutes.value = prefs.getLong(KEY_ADAPTIVE_CHECK_INTERVAL, DEFAULT_ADAPTIVE_INTERVAL_MINUTES)
        // ── Per-account settings (loaded with default account — no prefix until setActiveAccount) ──
        loadAccountSettings()
    }

    /**
     * Switch to a different account's notification preferences.
     * Call when the active account changes (login, account switch).
     * Power settings (connectionMode, pushEnabled, adaptiveInterval) are NOT affected.
     */
    fun setActiveAccount(hexPubkey: String?) {
        activeAccountHex = hexPubkey?.lowercase()?.takeIf { it.isNotBlank() }
        if (::prefs.isInitialized) loadAccountSettings()
    }

    /** Reload per-account notification toggle settings from SharedPreferences. */
    private fun loadAccountSettings() {
        // Migrate: if per-account keys don't exist yet, fall back to legacy global keys
        fun getAccountBool(base: String, default: Boolean): Boolean {
            val key = acctKey(base)
            return if (prefs.contains(key)) prefs.getBoolean(key, default)
            else prefs.getBoolean(base, default) // legacy global fallback
        }
        _notifyReactions.value = getAccountBool(KEY_NOTIFY_REACTIONS, true)
        _notifyZaps.value = getAccountBool(KEY_NOTIFY_ZAPS, true)
        _notifyReposts.value = getAccountBool(KEY_NOTIFY_REPOSTS, true)
        _notifyMentions.value = getAccountBool(KEY_NOTIFY_MENTIONS, true)
        _notifyReplies.value = getAccountBool(KEY_NOTIFY_REPLIES, true)
        _notifyDMs.value = getAccountBool(KEY_NOTIFY_DMS, true)
        _notifyPolls.value = getAccountBool(KEY_NOTIFY_POLLS, true)
        _notifyQuotes.value = getAccountBool(KEY_NOTIFY_QUOTES, true)
        _muteStrangers.value = getAccountBool(KEY_MUTE_STRANGERS, false)
        _showDmContent.value = getAccountBool(KEY_SHOW_DM_CONTENT, false)
    }

    fun setPushEnabled(enabled: Boolean) {
        _pushEnabled.value = enabled
        prefs.edit().putBoolean(KEY_PUSH_ENABLED, enabled).apply()
    }

    fun setConnectionMode(mode: ConnectionMode) {
        _connectionMode.value = mode
        prefs.edit().putString(KEY_CONNECTION_MODE, mode.name).apply()
    }

    fun setAdaptiveCheckIntervalMinutes(minutes: Long) {
        _adaptiveCheckIntervalMinutes.value = minutes
        prefs.edit().putLong(KEY_ADAPTIVE_CHECK_INTERVAL, minutes).apply()
    }

    fun setNotifyReactions(enabled: Boolean) {
        _notifyReactions.value = enabled
        prefs.edit().putBoolean(acctKey(KEY_NOTIFY_REACTIONS), enabled).apply()
        social.mycelium.android.repository.sync.SettingsSyncManager.notifySettingChanged()
    }

    fun setNotifyZaps(enabled: Boolean) {
        _notifyZaps.value = enabled
        prefs.edit().putBoolean(acctKey(KEY_NOTIFY_ZAPS), enabled).apply()
        social.mycelium.android.repository.sync.SettingsSyncManager.notifySettingChanged()
    }

    fun setNotifyReposts(enabled: Boolean) {
        _notifyReposts.value = enabled
        prefs.edit().putBoolean(acctKey(KEY_NOTIFY_REPOSTS), enabled).apply()
        social.mycelium.android.repository.sync.SettingsSyncManager.notifySettingChanged()
    }

    fun setNotifyMentions(enabled: Boolean) {
        _notifyMentions.value = enabled
        prefs.edit().putBoolean(acctKey(KEY_NOTIFY_MENTIONS), enabled).apply()
        social.mycelium.android.repository.sync.SettingsSyncManager.notifySettingChanged()
    }

    fun setNotifyReplies(enabled: Boolean) {
        _notifyReplies.value = enabled
        prefs.edit().putBoolean(acctKey(KEY_NOTIFY_REPLIES), enabled).apply()
        social.mycelium.android.repository.sync.SettingsSyncManager.notifySettingChanged()
    }

    fun setNotifyDMs(enabled: Boolean) {
        _notifyDMs.value = enabled
        prefs.edit().putBoolean(acctKey(KEY_NOTIFY_DMS), enabled).apply()
        social.mycelium.android.repository.sync.SettingsSyncManager.notifySettingChanged()
    }

    fun setNotifyPolls(enabled: Boolean) {
        _notifyPolls.value = enabled
        prefs.edit().putBoolean(acctKey(KEY_NOTIFY_POLLS), enabled).apply()
        social.mycelium.android.repository.sync.SettingsSyncManager.notifySettingChanged()
    }

    fun setNotifyQuotes(enabled: Boolean) {
        _notifyQuotes.value = enabled
        prefs.edit().putBoolean(acctKey(KEY_NOTIFY_QUOTES), enabled).apply()
        social.mycelium.android.repository.sync.SettingsSyncManager.notifySettingChanged()
    }

    fun setMuteStrangers(enabled: Boolean) {
        _muteStrangers.value = enabled
        prefs.edit().putBoolean(acctKey(KEY_MUTE_STRANGERS), enabled).apply()
        social.mycelium.android.repository.sync.SettingsSyncManager.notifySettingChanged()
    }

    fun setShowDmContent(enabled: Boolean) {
        _showDmContent.value = enabled
        prefs.edit().putBoolean(acctKey(KEY_SHOW_DM_CONTENT), enabled).apply()
        social.mycelium.android.repository.sync.SettingsSyncManager.notifySettingChanged()
    }

    // ── Per-account preference lookup (for background accounts) ─────────────
    // These read directly from SharedPreferences for a specific pubkey without
    // switching the active account. Used by background account notification gating.

    /** Read a per-account boolean preference for a specific pubkey. */
    private fun getAccountBoolForPubkey(pubkey: String, base: String, default: Boolean): Boolean {
        if (!::prefs.isInitialized) return default
        val key = "${pubkey.lowercase()}:${base}"
        return if (prefs.contains(key)) prefs.getBoolean(key, default)
        else prefs.getBoolean(base, default) // legacy global fallback
    }

    /**
     * Check whether a notification type is allowed for a specific account.
     * Reads directly from SharedPreferences — safe to call for any account
     * without affecting the active account's in-memory state flows.
     */
    fun isNotificationAllowedForAccount(pubkey: String, type: social.mycelium.android.data.NotificationType): Boolean {
        if (!pushEnabled.value) return false
        return when (type) {
            social.mycelium.android.data.NotificationType.REPLY,
            social.mycelium.android.data.NotificationType.COMMENT ->
                getAccountBoolForPubkey(pubkey, KEY_NOTIFY_REPLIES, true)
            social.mycelium.android.data.NotificationType.LIKE,
            social.mycelium.android.data.NotificationType.BADGE_AWARD ->
                getAccountBoolForPubkey(pubkey, KEY_NOTIFY_REACTIONS, true)
            social.mycelium.android.data.NotificationType.ZAP ->
                getAccountBoolForPubkey(pubkey, KEY_NOTIFY_ZAPS, true)
            social.mycelium.android.data.NotificationType.REPOST ->
                getAccountBoolForPubkey(pubkey, KEY_NOTIFY_REPOSTS, true)
            social.mycelium.android.data.NotificationType.MENTION,
            social.mycelium.android.data.NotificationType.HIGHLIGHT,
            social.mycelium.android.data.NotificationType.REPORT ->
                getAccountBoolForPubkey(pubkey, KEY_NOTIFY_MENTIONS, true)
            social.mycelium.android.data.NotificationType.QUOTE ->
                getAccountBoolForPubkey(pubkey, KEY_NOTIFY_QUOTES, true)
            social.mycelium.android.data.NotificationType.DM ->
                getAccountBoolForPubkey(pubkey, KEY_NOTIFY_DMS, true)
            social.mycelium.android.data.NotificationType.POLL_VOTE ->
                getAccountBoolForPubkey(pubkey, KEY_NOTIFY_POLLS, true)
        }
    }
}
