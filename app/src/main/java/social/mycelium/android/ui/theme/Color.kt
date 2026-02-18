package social.mycelium.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ── Frog Green (sage) ──────────────────────────────────────────────
val SageGreen80 = Color(0xFF8FBC8F)
val SageGreenGrey80 = Color(0xFFA8C8A8)
val SageGreenAccent80 = Color(0xFF6B8E6B)

val SageGreen40 = Color(0xFF4E8A4E)
val SageGreenGrey40 = Color(0xFF7BA67B)
val SageGreenAccent40 = Color(0xFF3D6E3D)

// ── Nostr Purple ───────────────────────────────────────────────────
val NostrPurple80 = Color(0xFFB39DDB)
val NostrPurpleGrey80 = Color(0xFFC5B8D9)
val NostrPurpleAccent80 = Color(0xFF9575CD)

val NostrPurple40 = Color(0xFF7E57C2)
val NostrPurpleGrey40 = Color(0xFF6A4FA0)
val NostrPurpleAccent40 = Color(0xFF5E35B1)

// ── Bitcoin Orange ─────────────────────────────────────────────────
val BitcoinOrange80 = Color(0xFFFFB74D)
val BitcoinOrangeGrey80 = Color(0xFFFFCC80)
val BitcoinOrangeAccent80 = Color(0xFFFFA726)

val BitcoinOrange40 = Color(0xFFEF8C00)
val BitcoinOrangeGrey40 = Color(0xFFD07B00)
val BitcoinOrangeAccent40 = Color(0xFFE65100)

// ── Love Red ───────────────────────────────────────────────────────
val LoveRed80 = Color(0xFFEF9A9A)
val LoveRedGrey80 = Color(0xFFF0B0B0)
val LoveRedAccent80 = Color(0xFFE57373)

val LoveRed40 = Color(0xFFD32F2F)
val LoveRedGrey40 = Color(0xFFC62828)
val LoveRedAccent40 = Color(0xFFB71C1C)

// ── OP highlight (always purple, independent of accent) ────────────
val OpHighlightPurple = Color(0xFF8E30EB)

// ── Palette builder ────────────────────────────────────────────────

fun accentDarkScheme(accent: AccentColor): ColorScheme = when (accent) {
    AccentColor.GREEN -> darkColorScheme(
        primary = SageGreen80,
        secondary = SageGreenGrey80,
        tertiary = SageGreenAccent80,
        background = Color(0xFF121212),
        surface = Color(0xFF1E1E1E),
        surfaceVariant = Color(0xFF2A2A2A),
        onPrimary = Color.White,
        onSecondary = Color.White,
        onBackground = Color(0xFFE0E0E0),
        onSurface = Color(0xFFE0E0E0),
        onSurfaceVariant = Color(0xFFB0B0B0),
        outline = Color(0xFF444444),
        outlineVariant = Color(0xFF333333)
    )
    AccentColor.PURPLE -> darkColorScheme(
        primary = NostrPurple80,
        secondary = NostrPurpleGrey80,
        tertiary = NostrPurpleAccent80,
        background = Color(0xFF121212),
        surface = Color(0xFF1E1E1E),
        surfaceVariant = Color(0xFF2A2A2A),
        onPrimary = Color.White,
        onSecondary = Color.White,
        onBackground = Color(0xFFE0E0E0),
        onSurface = Color(0xFFE0E0E0),
        onSurfaceVariant = Color(0xFFB0B0B0),
        outline = Color(0xFF444444),
        outlineVariant = Color(0xFF333333)
    )
    AccentColor.ORANGE -> darkColorScheme(
        primary = BitcoinOrange80,
        secondary = BitcoinOrangeGrey80,
        tertiary = BitcoinOrangeAccent80,
        background = Color(0xFF121212),
        surface = Color(0xFF1E1E1E),
        surfaceVariant = Color(0xFF2A2A2A),
        onPrimary = Color(0xFF1A1A1A),
        onSecondary = Color(0xFF1A1A1A),
        onBackground = Color(0xFFE0E0E0),
        onSurface = Color(0xFFE0E0E0),
        onSurfaceVariant = Color(0xFFB0B0B0),
        outline = Color(0xFF444444),
        outlineVariant = Color(0xFF333333)
    )
    AccentColor.RED -> darkColorScheme(
        primary = LoveRed80,
        secondary = LoveRedGrey80,
        tertiary = LoveRedAccent80,
        background = Color(0xFF121212),
        surface = Color(0xFF1E1E1E),
        surfaceVariant = Color(0xFF2A2A2A),
        onPrimary = Color(0xFF1A1A1A),
        onSecondary = Color(0xFF1A1A1A),
        onBackground = Color(0xFFE0E0E0),
        onSurface = Color(0xFFE0E0E0),
        onSurfaceVariant = Color(0xFFB0B0B0),
        outline = Color(0xFF444444),
        outlineVariant = Color(0xFF333333)
    )
}

fun accentLightScheme(accent: AccentColor): ColorScheme = when (accent) {
    AccentColor.GREEN -> lightColorScheme(
        primary = SageGreen40,
        secondary = SageGreenGrey40,
        tertiary = SageGreenAccent40,
        background = Color(0xFFFAFAFA),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF0F0F0),
        onPrimary = Color.White,
        onSecondary = Color.White,
        onBackground = Color(0xFF1C1B1F),
        onSurface = Color(0xFF1C1B1F),
        onSurfaceVariant = Color(0xFF555555),
        outline = Color(0xFFCCCCCC),
        outlineVariant = Color(0xFFDDDDDD)
    )
    AccentColor.PURPLE -> lightColorScheme(
        primary = NostrPurple40,
        secondary = NostrPurpleGrey40,
        tertiary = NostrPurpleAccent40,
        background = Color(0xFFFAFAFA),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF0F0F0),
        onPrimary = Color.White,
        onSecondary = Color.White,
        onBackground = Color(0xFF1C1B1F),
        onSurface = Color(0xFF1C1B1F),
        onSurfaceVariant = Color(0xFF555555),
        outline = Color(0xFFCCCCCC),
        outlineVariant = Color(0xFFDDDDDD)
    )
    AccentColor.ORANGE -> lightColorScheme(
        primary = BitcoinOrange40,
        secondary = BitcoinOrangeGrey40,
        tertiary = BitcoinOrangeAccent40,
        background = Color(0xFFFAFAFA),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF0F0F0),
        onPrimary = Color.White,
        onSecondary = Color.White,
        onBackground = Color(0xFF1C1B1F),
        onSurface = Color(0xFF1C1B1F),
        onSurfaceVariant = Color(0xFF555555),
        outline = Color(0xFFCCCCCC),
        outlineVariant = Color(0xFFDDDDDD)
    )
    AccentColor.RED -> lightColorScheme(
        primary = LoveRed40,
        secondary = LoveRedGrey40,
        tertiary = LoveRedAccent40,
        background = Color(0xFFFAFAFA),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF0F0F0),
        onPrimary = Color.White,
        onSecondary = Color.White,
        onBackground = Color(0xFF1C1B1F),
        onSurface = Color(0xFF1C1B1F),
        onSurfaceVariant = Color(0xFF555555),
        outline = Color(0xFFCCCCCC),
        outlineVariant = Color(0xFFDDDDDD)
    )
}