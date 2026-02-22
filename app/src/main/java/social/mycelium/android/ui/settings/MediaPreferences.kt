package social.mycelium.android.ui.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists media playback preferences using SharedPreferences.
 * Singleton — call init(context) once from Application/Activity.
 */
object MediaPreferences {
    private const val PREFS_NAME = "Mycelium_media_prefs"
    private const val KEY_AUTOPLAY_VIDEOS = "autoplay_videos"
    private const val KEY_AUTOPLAY_SOUND = "autoplay_sound"
    private const val KEY_SHOW_SENSITIVE = "show_sensitive_content"

    private lateinit var prefs: SharedPreferences

    private val _autoplayVideos = MutableStateFlow(true)
    val autoplayVideos: StateFlow<Boolean> = _autoplayVideos.asStateFlow()

    private val _autoplaySound = MutableStateFlow(false)
    val autoplaySound: StateFlow<Boolean> = _autoplaySound.asStateFlow()

    /** When false, notes with content-warning tag or #nsfw hashtag show a blur overlay. */
    private val _showSensitiveContent = MutableStateFlow(false)
    val showSensitiveContent: StateFlow<Boolean> = _showSensitiveContent.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _autoplayVideos.value = prefs.getBoolean(KEY_AUTOPLAY_VIDEOS, true)
        _autoplaySound.value = prefs.getBoolean(KEY_AUTOPLAY_SOUND, false)
        _showSensitiveContent.value = prefs.getBoolean(KEY_SHOW_SENSITIVE, false)
    }

    fun setAutoplayVideos(enabled: Boolean) {
        _autoplayVideos.value = enabled
        prefs.edit().putBoolean(KEY_AUTOPLAY_VIDEOS, enabled).apply()
    }

    fun setAutoplaySound(enabled: Boolean) {
        _autoplaySound.value = enabled
        prefs.edit().putBoolean(KEY_AUTOPLAY_SOUND, enabled).apply()
    }

    fun setShowSensitiveContent(enabled: Boolean) {
        _showSensitiveContent.value = enabled
        prefs.edit().putBoolean(KEY_SHOW_SENSITIVE, enabled).apply()
    }
}
