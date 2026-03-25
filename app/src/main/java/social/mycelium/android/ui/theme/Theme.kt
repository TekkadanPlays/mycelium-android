package social.mycelium.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyceliumTheme(
    content: @Composable () -> Unit
) {
    val themeMode by ThemePreferences.themeMode.collectAsState()
    val accent by ThemePreferences.accentColor.collectAsState()

    val isDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = remember(isDark, accent) {
        if (isDark) accentDarkScheme(accent) else accentLightScheme(accent)
    }

    // Shape scale: small interactive elements (chips, buttons, menus) keep rounded corners
    // for visual polish; larger surfaces (cards, sheets, dialogs) stay square for the
    // edge-to-edge design language used in the home feed and note cards.
    val appShapes = remember {
        val square = RoundedCornerShape(0.dp)
        Shapes(
            extraSmall = RoundedCornerShape(8.dp),   // Chips, menus, tooltips
            small      = RoundedCornerShape(8.dp),   // Buttons, small cards, text fields
            medium     = square,                      // Cards, elevated cards — edge-to-edge
            large      = square,                      // Sheets, navigation drawers
            extraLarge = square                       // Full-screen dialogs
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = appShapes,
    ) {
        CompositionLocalProvider(LocalRippleConfiguration provides null) {
            content()
        }
    }
}