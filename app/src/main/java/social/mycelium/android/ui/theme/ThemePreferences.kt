package social.mycelium.android.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Theme mode: System follows device setting, Light forces light, Dark forces dark.
 */
enum class ThemeMode(val label: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark");

    companion object {
        fun fromString(s: String): ThemeMode = entries.firstOrNull { it.name == s } ?: SYSTEM
    }
}

/**
 * Accent color palette choices. Each maps to a blazecn-inspired semantic palette.
 */
enum class AccentColor(val label: String, val emoji: String) {
    VIOLET("Violet", "\uD83D\uDD2E"),
    BLUE("Blue", "\uD83C\uDF0A"),
    TEAL("Teal", "\uD83E\uDEB4"),
    GREEN("Emerald", "\uD83C\uDF3F"),
    GOLD("Gold", "\u2B50"),
    ORANGE("Orange", "\uD83C\uDF4A"),
    RED("Rose", "\uD83C\uDF39"),
    PINK("Pink", "\uD83C\uDF38");

    companion object {
        fun fromString(s: String): AccentColor = entries.firstOrNull { it.name == s } ?: VIOLET
    }
}

/**
 * Persists theme preferences using SharedPreferences.
 * Singleton — call init(context) once from Application/Activity.
 */
object ThemePreferences {
    private const val PREFS_NAME = "Mycelium_theme_prefs"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_ACCENT_COLOR = "accent_color"

    private lateinit var prefs: SharedPreferences

    private val _themeMode = MutableStateFlow(ThemeMode.DARK)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _accentColor = MutableStateFlow(AccentColor.VIOLET)
    val accentColor: StateFlow<AccentColor> = _accentColor.asStateFlow()


    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _themeMode.value = ThemeMode.fromString(prefs.getString(KEY_THEME_MODE, ThemeMode.DARK.name) ?: ThemeMode.DARK.name)
        _accentColor.value = AccentColor.fromString(prefs.getString(KEY_ACCENT_COLOR, AccentColor.VIOLET.name) ?: AccentColor.VIOLET.name)
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun setAccentColor(color: AccentColor) {
        _accentColor.value = color
        prefs.edit().putString(KEY_ACCENT_COLOR, color.name).apply()
    }

}
