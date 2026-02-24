package social.mycelium.android.ui.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists notification preferences using SharedPreferences.
 * Singleton — call init(context) once from Application/Activity.
 */
object NotificationPreferences {
    private const val PREFS_NAME = "Mycelium_notification_prefs"
    private const val KEY_PUSH_ENABLED = "push_enabled"
    private const val KEY_BACKGROUND_SERVICE = "background_service_enabled"
    private const val KEY_NOTIFY_REACTIONS = "notify_reactions"
    private const val KEY_NOTIFY_ZAPS = "notify_zaps"
    private const val KEY_NOTIFY_REPOSTS = "notify_reposts"
    private const val KEY_NOTIFY_MENTIONS = "notify_mentions"
    private const val KEY_NOTIFY_REPLIES = "notify_replies"
    private const val KEY_NOTIFY_DMS = "notify_dms"
    private const val KEY_MUTE_STRANGERS = "mute_strangers"

    private lateinit var prefs: SharedPreferences

    private val _pushEnabled = MutableStateFlow(true)
    val pushEnabled: StateFlow<Boolean> = _pushEnabled.asStateFlow()

    private val _backgroundServiceEnabled = MutableStateFlow(true)
    val backgroundServiceEnabled: StateFlow<Boolean> = _backgroundServiceEnabled.asStateFlow()

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

    private val _muteStrangers = MutableStateFlow(false)
    val muteStrangers: StateFlow<Boolean> = _muteStrangers.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _pushEnabled.value = prefs.getBoolean(KEY_PUSH_ENABLED, true)
        _backgroundServiceEnabled.value = prefs.getBoolean(KEY_BACKGROUND_SERVICE, true)
        _notifyReactions.value = prefs.getBoolean(KEY_NOTIFY_REACTIONS, true)
        _notifyZaps.value = prefs.getBoolean(KEY_NOTIFY_ZAPS, true)
        _notifyReposts.value = prefs.getBoolean(KEY_NOTIFY_REPOSTS, true)
        _notifyMentions.value = prefs.getBoolean(KEY_NOTIFY_MENTIONS, true)
        _notifyReplies.value = prefs.getBoolean(KEY_NOTIFY_REPLIES, true)
        _notifyDMs.value = prefs.getBoolean(KEY_NOTIFY_DMS, true)
        _muteStrangers.value = prefs.getBoolean(KEY_MUTE_STRANGERS, false)
    }

    fun setPushEnabled(enabled: Boolean) {
        _pushEnabled.value = enabled
        prefs.edit().putBoolean(KEY_PUSH_ENABLED, enabled).apply()
    }

    fun setBackgroundServiceEnabled(enabled: Boolean) {
        _backgroundServiceEnabled.value = enabled
        prefs.edit().putBoolean(KEY_BACKGROUND_SERVICE, enabled).apply()
    }

    fun setNotifyReactions(enabled: Boolean) {
        _notifyReactions.value = enabled
        prefs.edit().putBoolean(KEY_NOTIFY_REACTIONS, enabled).apply()
    }

    fun setNotifyZaps(enabled: Boolean) {
        _notifyZaps.value = enabled
        prefs.edit().putBoolean(KEY_NOTIFY_ZAPS, enabled).apply()
    }

    fun setNotifyReposts(enabled: Boolean) {
        _notifyReposts.value = enabled
        prefs.edit().putBoolean(KEY_NOTIFY_REPOSTS, enabled).apply()
    }

    fun setNotifyMentions(enabled: Boolean) {
        _notifyMentions.value = enabled
        prefs.edit().putBoolean(KEY_NOTIFY_MENTIONS, enabled).apply()
    }

    fun setNotifyReplies(enabled: Boolean) {
        _notifyReplies.value = enabled
        prefs.edit().putBoolean(KEY_NOTIFY_REPLIES, enabled).apply()
    }

    fun setNotifyDMs(enabled: Boolean) {
        _notifyDMs.value = enabled
        prefs.edit().putBoolean(KEY_NOTIFY_DMS, enabled).apply()
    }

    fun setMuteStrangers(enabled: Boolean) {
        _muteStrangers.value = enabled
        prefs.edit().putBoolean(KEY_MUTE_STRANGERS, enabled).apply()
    }
}
