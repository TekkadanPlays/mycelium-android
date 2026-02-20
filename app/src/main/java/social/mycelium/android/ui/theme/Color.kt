package social.mycelium.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ══════════════════════════════════════════════════════════════════════
// Blazecn-inspired color system — full Material3 coverage
//
// Every accent gets its own tinted surface ladder, opaque containers,
// and all 29+ ColorScheme slots explicitly defined. No alpha hacks.
//
// Surface tonal ladder (dark):
//   surfaceDim < surface < surfaceBright
//   surfaceContainerLowest < Low < Container < High < Highest
//
// Each accent tints the neutral base toward its hue so the whole UI
// feels cohesive, not just the primary color.
// ══════════════════════════════════════════════════════════════════════

// ── OP highlight (always violet, independent of accent) ────────────
val OpHighlightPurple = Color(0xFF8E30EB)

// ══════════════════════════════════════════════════════════════════════
//  VIOLET — Dark
// ══════════════════════════════════════════════════════════════════════
private object VioletDark {
    val primary              = Color(0xFFB49AFF)
    val onPrimary            = Color(0xFF1E0A4B)
    val primaryContainer     = Color(0xFF3D2878)
    val onPrimaryContainer   = Color(0xFFE0D4FF)
    val secondary            = Color(0xFF9B8EC8)
    val onSecondary          = Color(0xFF1A1530)
    val secondaryContainer   = Color(0xFF332D4D)
    val onSecondaryContainer = Color(0xFFD8D0F0)
    val tertiary             = Color(0xFFD4C4FF)
    val onTertiary           = Color(0xFF251548)
    val tertiaryContainer    = Color(0xFF3F2E6A)
    val onTertiaryContainer  = Color(0xFFEDE4FF)

    val background                = Color(0xFF121018)
    val onBackground              = Color(0xFFE6E1F0)
    val surface                   = Color(0xFF1A1722)
    val onSurface                 = Color(0xFFE6E1F0)
    val surfaceDim                = Color(0xFF0E0C14)
    val surfaceBright             = Color(0xFF2E2A3A)
    val surfaceContainerLowest    = Color(0xFF0C0A12)
    val surfaceContainerLow       = Color(0xFF16141E)
    val surfaceContainer          = Color(0xFF1E1B28)
    val surfaceContainerHigh      = Color(0xFF262332)
    val surfaceContainerHighest   = Color(0xFF302C3E)
    val surfaceVariant            = Color(0xFF2A2638)
    val onSurfaceVariant          = Color(0xFF9A94AE)

    val outline        = Color(0xFF44405A)
    val outlineVariant = Color(0xFF332F48)

    val error            = Color(0xFFFFB4AB)
    val onError          = Color(0xFF690005)
    val errorContainer   = Color(0xFF4A1014)
    val onErrorContainer = Color(0xFFFFDAD6)

    val inverseSurface   = Color(0xFFE6E1F0)
    val inverseOnSurface = Color(0xFF1A1722)
    val inversePrimary   = Color(0xFF6B4FC0)
    val scrim            = Color(0xFF000000)
    val surfaceTint      = Color(0xFFB49AFF)
}

// ══════════════════════════════════════════════════════════════════════
//  VIOLET — Light
// ══════════════════════════════════════════════════════════════════════
private object VioletLight {
    val primary              = Color(0xFF6B4FC0)
    val onPrimary            = Color(0xFFFFFFFF)
    val primaryContainer     = Color(0xFFE8DEFF)
    val onPrimaryContainer   = Color(0xFF3D2878)
    val secondary            = Color(0xFF5E5480)
    val onSecondary          = Color(0xFFFFFFFF)
    val secondaryContainer   = Color(0xFFE6DEFF)
    val onSecondaryContainer = Color(0xFF3A3258)
    val tertiary             = Color(0xFF7B5EA7)
    val onTertiary           = Color(0xFFFFFFFF)
    val tertiaryContainer    = Color(0xFFF0E4FF)
    val onTertiaryContainer  = Color(0xFF4A3270)

    val background                = Color(0xFFFCF8FF)
    val onBackground              = Color(0xFF1A1722)
    val surface                   = Color(0xFFFCF8FF)
    val onSurface                 = Color(0xFF1A1722)
    val surfaceDim                = Color(0xFFDDD8E8)
    val surfaceBright             = Color(0xFFFCF8FF)
    val surfaceContainerLowest    = Color(0xFFFFFFFF)
    val surfaceContainerLow       = Color(0xFFF6F2FC)
    val surfaceContainer          = Color(0xFFF0ECF6)
    val surfaceContainerHigh      = Color(0xFFEBE6F2)
    val surfaceContainerHighest   = Color(0xFFE5E0EC)
    val surfaceVariant            = Color(0xFFE8E0F0)
    val onSurfaceVariant          = Color(0xFF4A4458)

    val outline        = Color(0xFF7B7490)
    val outlineVariant = Color(0xFFCBC4DA)

    val error            = Color(0xFFBA1A1A)
    val onError          = Color(0xFFFFFFFF)
    val errorContainer   = Color(0xFFFFDAD6)
    val onErrorContainer = Color(0xFF410002)

    val inverseSurface   = Color(0xFF302C3E)
    val inverseOnSurface = Color(0xFFF4EFF8)
    val inversePrimary   = Color(0xFFB49AFF)
    val scrim            = Color(0xFF000000)
    val surfaceTint      = Color(0xFF6B4FC0)
}

// ══════════════════════════════════════════════════════════════════════
//  GREEN — Dark
// ══════════════════════════════════════════════════════════════════════
private object GreenDark {
    val primary              = Color(0xFF5AD8A6)
    val onPrimary            = Color(0xFF003824)
    val primaryContainer     = Color(0xFF0A5E3E)
    val onPrimaryContainer   = Color(0xFFA8F5D4)
    val secondary            = Color(0xFF80C8A8)
    val onSecondary          = Color(0xFF0A3020)
    val secondaryContainer   = Color(0xFF1E4A36)
    val onSecondaryContainer = Color(0xFFC0E8D4)
    val tertiary             = Color(0xFF88E8C4)
    val onTertiary           = Color(0xFF003828)
    val tertiaryContainer    = Color(0xFF0C5840)
    val onTertiaryContainer  = Color(0xFFB0F0DA)

    val background                = Color(0xFF0C1610)
    val onBackground              = Color(0xFFDCEDE4)
    val surface                   = Color(0xFF121C16)
    val onSurface                 = Color(0xFFDCEDE4)
    val surfaceDim                = Color(0xFF08100C)
    val surfaceBright             = Color(0xFF263830)
    val surfaceContainerLowest    = Color(0xFF060E0A)
    val surfaceContainerLow       = Color(0xFF101A14)
    val surfaceContainer          = Color(0xFF18221C)
    val surfaceContainerHigh      = Color(0xFF202C26)
    val surfaceContainerHighest   = Color(0xFF2A3832)
    val surfaceVariant            = Color(0xFF1E2E26)
    val onSurfaceVariant          = Color(0xFF8AA898)

    val outline        = Color(0xFF3E5A4C)
    val outlineVariant = Color(0xFF2C4438)

    val error            = Color(0xFFFFB4AB)
    val onError          = Color(0xFF690005)
    val errorContainer   = Color(0xFF4A1014)
    val onErrorContainer = Color(0xFFFFDAD6)

    val inverseSurface   = Color(0xFFDCEDE4)
    val inverseOnSurface = Color(0xFF121C16)
    val inversePrimary   = Color(0xFF1E8A5E)
    val scrim            = Color(0xFF000000)
    val surfaceTint      = Color(0xFF5AD8A6)
}

// ══════════════════════════════════════════════════════════════════════
//  GREEN — Light
// ══════════════════════════════════════════════════════════════════════
private object GreenLight {
    val primary              = Color(0xFF1E8A5E)
    val onPrimary            = Color(0xFFFFFFFF)
    val primaryContainer     = Color(0xFFA8F5D4)
    val onPrimaryContainer   = Color(0xFF0A5E3E)
    val secondary            = Color(0xFF3A6E54)
    val onSecondary          = Color(0xFFFFFFFF)
    val secondaryContainer   = Color(0xFFBCECD4)
    val onSecondaryContainer = Color(0xFF1E4A36)
    val tertiary             = Color(0xFF2A8868)
    val onTertiary           = Color(0xFFFFFFFF)
    val tertiaryContainer    = Color(0xFFB0F0DA)
    val onTertiaryContainer  = Color(0xFF0C5840)

    val background                = Color(0xFFF5FBF8)
    val onBackground              = Color(0xFF121C16)
    val surface                   = Color(0xFFF5FBF8)
    val onSurface                 = Color(0xFF121C16)
    val surfaceDim                = Color(0xFFD4E4DC)
    val surfaceBright             = Color(0xFFF5FBF8)
    val surfaceContainerLowest    = Color(0xFFFFFFFF)
    val surfaceContainerLow       = Color(0xFFEFF6F2)
    val surfaceContainer          = Color(0xFFE8F0EC)
    val surfaceContainerHigh      = Color(0xFFE0EAE4)
    val surfaceContainerHighest   = Color(0xFFDAE4DE)
    val surfaceVariant            = Color(0xFFDCEDE4)
    val onSurfaceVariant          = Color(0xFF3A4E44)

    val outline        = Color(0xFF6A8478)
    val outlineVariant = Color(0xFFBED0C6)

    val error            = Color(0xFFBA1A1A)
    val onError          = Color(0xFFFFFFFF)
    val errorContainer   = Color(0xFFFFDAD6)
    val onErrorContainer = Color(0xFF410002)

    val inverseSurface   = Color(0xFF2A3832)
    val inverseOnSurface = Color(0xFFECF4EE)
    val inversePrimary   = Color(0xFF5AD8A6)
    val scrim            = Color(0xFF000000)
    val surfaceTint      = Color(0xFF1E8A5E)
}

// ══════════════════════════════════════════════════════════════════════
//  ORANGE — Dark
// ══════════════════════════════════════════════════════════════════════
private object OrangeDark {
    val primary              = Color(0xFFFFAA5C)
    val onPrimary            = Color(0xFF3E1E00)
    val primaryContainer     = Color(0xFF6A3800)
    val onPrimaryContainer   = Color(0xFFFFDCC2)
    val secondary            = Color(0xFFD4A878)
    val onSecondary          = Color(0xFF2E1A06)
    val secondaryContainer   = Color(0xFF4A3418)
    val onSecondaryContainer = Color(0xFFF0D8C0)
    val tertiary             = Color(0xFFFFCC88)
    val onTertiary           = Color(0xFF3A2200)
    val tertiaryContainer    = Color(0xFF5E3C08)
    val onTertiaryContainer  = Color(0xFFFFE4C0)

    val background                = Color(0xFF18120C)
    val onBackground              = Color(0xFFF0E4DA)
    val surface                   = Color(0xFF1E1812)
    val onSurface                 = Color(0xFFF0E4DA)
    val surfaceDim                = Color(0xFF120E08)
    val surfaceBright             = Color(0xFF383028)
    val surfaceContainerLowest    = Color(0xFF100C06)
    val surfaceContainerLow       = Color(0xFF1A1610)
    val surfaceContainer          = Color(0xFF241E18)
    val surfaceContainerHigh      = Color(0xFF2E2820)
    val surfaceContainerHighest   = Color(0xFF3A322A)
    val surfaceVariant            = Color(0xFF302820)
    val onSurfaceVariant          = Color(0xFFAA9E90)

    val outline        = Color(0xFF5C5248)
    val outlineVariant = Color(0xFF463E36)

    val error            = Color(0xFFFFB4AB)
    val onError          = Color(0xFF690005)
    val errorContainer   = Color(0xFF4A1014)
    val onErrorContainer = Color(0xFFFFDAD6)

    val inverseSurface   = Color(0xFFF0E4DA)
    val inverseOnSurface = Color(0xFF1E1812)
    val inversePrimary   = Color(0xFFC07020)
    val scrim            = Color(0xFF000000)
    val surfaceTint      = Color(0xFFFFAA5C)
}

// ══════════════════════════════════════════════════════════════════════
//  ORANGE — Light
// ══════════════════════════════════════════════════════════════════════
private object OrangeLight {
    val primary              = Color(0xFFC07020)
    val onPrimary            = Color(0xFFFFFFFF)
    val primaryContainer     = Color(0xFFFFDCC2)
    val onPrimaryContainer   = Color(0xFF6A3800)
    val secondary            = Color(0xFF7A5C3A)
    val onSecondary          = Color(0xFFFFFFFF)
    val secondaryContainer   = Color(0xFFF0D8C0)
    val onSecondaryContainer = Color(0xFF4A3418)
    val tertiary             = Color(0xFFAA7830)
    val onTertiary           = Color(0xFFFFFFFF)
    val tertiaryContainer    = Color(0xFFFFE4C0)
    val onTertiaryContainer  = Color(0xFF5E3C08)

    val background                = Color(0xFFFFF8F4)
    val onBackground              = Color(0xFF1E1812)
    val surface                   = Color(0xFFFFF8F4)
    val onSurface                 = Color(0xFF1E1812)
    val surfaceDim                = Color(0xFFE0D6CC)
    val surfaceBright             = Color(0xFFFFF8F4)
    val surfaceContainerLowest    = Color(0xFFFFFFFF)
    val surfaceContainerLow       = Color(0xFFFAF2EA)
    val surfaceContainer          = Color(0xFFF4ECE4)
    val surfaceContainerHigh      = Color(0xFFEEE6DE)
    val surfaceContainerHighest   = Color(0xFFE8E0D6)
    val surfaceVariant            = Color(0xFFF0E4DA)
    val onSurfaceVariant          = Color(0xFF4E4438)

    val outline        = Color(0xFF807466)
    val outlineVariant = Color(0xFFD0C4B8)

    val error            = Color(0xFFBA1A1A)
    val onError          = Color(0xFFFFFFFF)
    val errorContainer   = Color(0xFFFFDAD6)
    val onErrorContainer = Color(0xFF410002)

    val inverseSurface   = Color(0xFF3A322A)
    val inverseOnSurface = Color(0xFFF8F0E8)
    val inversePrimary   = Color(0xFFFFAA5C)
    val scrim            = Color(0xFF000000)
    val surfaceTint      = Color(0xFFC07020)
}

// ══════════════════════════════════════════════════════════════════════
//  RED — Dark
// ══════════════════════════════════════════════════════════════════════
private object RedDark {
    val primary              = Color(0xFFFF7B7B)
    val onPrimary            = Color(0xFF4A0808)
    val primaryContainer     = Color(0xFF7A1818)
    val onPrimaryContainer   = Color(0xFFFFD0D0)
    val secondary            = Color(0xFFD09090)
    val onSecondary          = Color(0xFF301010)
    val secondaryContainer   = Color(0xFF4C2424)
    val onSecondaryContainer = Color(0xFFF0D0D0)
    val tertiary             = Color(0xFFFFAAAA)
    val onTertiary           = Color(0xFF401010)
    val tertiaryContainer    = Color(0xFF6A2020)
    val onTertiaryContainer  = Color(0xFFFFDCDC)

    val background                = Color(0xFF180C0C)
    val onBackground              = Color(0xFFF0E0E0)
    val surface                   = Color(0xFF1E1212)
    val onSurface                 = Color(0xFFF0E0E0)
    val surfaceDim                = Color(0xFF120808)
    val surfaceBright             = Color(0xFF382828)
    val surfaceContainerLowest    = Color(0xFF100606)
    val surfaceContainerLow       = Color(0xFF1A1010)
    val surfaceContainer          = Color(0xFF241818)
    val surfaceContainerHigh      = Color(0xFF2E2020)
    val surfaceContainerHighest   = Color(0xFF3A2A2A)
    val surfaceVariant            = Color(0xFF302020)
    val onSurfaceVariant          = Color(0xFFAA9494)

    val outline        = Color(0xFF5C4848)
    val outlineVariant = Color(0xFF463636)

    val error            = Color(0xFFFFB4AB)
    val onError          = Color(0xFF690005)
    val errorContainer   = Color(0xFF4A1014)
    val onErrorContainer = Color(0xFFFFDAD6)

    val inverseSurface   = Color(0xFFF0E0E0)
    val inverseOnSurface = Color(0xFF1E1212)
    val inversePrimary   = Color(0xFFC03030)
    val scrim            = Color(0xFF000000)
    val surfaceTint      = Color(0xFFFF7B7B)
}

// ══════════════════════════════════════════════════════════════════════
//  RED — Light
// ══════════════════════════════════════════════════════════════════════
private object RedLight {
    val primary              = Color(0xFFC03030)
    val onPrimary            = Color(0xFFFFFFFF)
    val primaryContainer     = Color(0xFFFFD0D0)
    val onPrimaryContainer   = Color(0xFF7A1818)
    val secondary            = Color(0xFF7A4A4A)
    val onSecondary          = Color(0xFFFFFFFF)
    val secondaryContainer   = Color(0xFFF0D0D0)
    val onSecondaryContainer = Color(0xFF4C2424)
    val tertiary             = Color(0xFFAA4040)
    val onTertiary           = Color(0xFFFFFFFF)
    val tertiaryContainer    = Color(0xFFFFDCDC)
    val onTertiaryContainer  = Color(0xFF6A2020)

    val background                = Color(0xFFFFF6F6)
    val onBackground              = Color(0xFF1E1212)
    val surface                   = Color(0xFFFFF6F6)
    val onSurface                 = Color(0xFF1E1212)
    val surfaceDim                = Color(0xFFE0D2D2)
    val surfaceBright             = Color(0xFFFFF6F6)
    val surfaceContainerLowest    = Color(0xFFFFFFFF)
    val surfaceContainerLow       = Color(0xFFFAF0F0)
    val surfaceContainer          = Color(0xFFF4EAEA)
    val surfaceContainerHigh      = Color(0xFFEEE4E4)
    val surfaceContainerHighest   = Color(0xFFE8DCDC)
    val surfaceVariant            = Color(0xFFF0E0E0)
    val onSurfaceVariant          = Color(0xFF4E3C3C)

    val outline        = Color(0xFF806868)
    val outlineVariant = Color(0xFFD0C0C0)

    val error            = Color(0xFFBA1A1A)
    val onError          = Color(0xFFFFFFFF)
    val errorContainer   = Color(0xFFFFDAD6)
    val onErrorContainer = Color(0xFF410002)

    val inverseSurface   = Color(0xFF3A2A2A)
    val inverseOnSurface = Color(0xFFF8EEEE)
    val inversePrimary   = Color(0xFFFF7B7B)
    val scrim            = Color(0xFF000000)
    val surfaceTint      = Color(0xFFC03030)
}

// ── Palette builders ───────────────────────────────────────────────

fun accentDarkScheme(accent: AccentColor): ColorScheme = when (accent) {
    AccentColor.VIOLET -> with(VioletDark) { darkColorScheme(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
        secondary = secondary, onSecondary = onSecondary,
        secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary, onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
        background = background, onBackground = onBackground,
        surface = surface, onSurface = onSurface,
        surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
        outline = outline, outlineVariant = outlineVariant,
        error = error, onError = onError,
        errorContainer = errorContainer, onErrorContainer = onErrorContainer,
        inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface,
        inversePrimary = inversePrimary, scrim = scrim, surfaceTint = surfaceTint,
        surfaceDim = surfaceDim, surfaceBright = surfaceBright,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest
    )}
    AccentColor.GREEN -> with(GreenDark) { darkColorScheme(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
        secondary = secondary, onSecondary = onSecondary,
        secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary, onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
        background = background, onBackground = onBackground,
        surface = surface, onSurface = onSurface,
        surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
        outline = outline, outlineVariant = outlineVariant,
        error = error, onError = onError,
        errorContainer = errorContainer, onErrorContainer = onErrorContainer,
        inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface,
        inversePrimary = inversePrimary, scrim = scrim, surfaceTint = surfaceTint,
        surfaceDim = surfaceDim, surfaceBright = surfaceBright,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest
    )}
    AccentColor.ORANGE -> with(OrangeDark) { darkColorScheme(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
        secondary = secondary, onSecondary = onSecondary,
        secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary, onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
        background = background, onBackground = onBackground,
        surface = surface, onSurface = onSurface,
        surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
        outline = outline, outlineVariant = outlineVariant,
        error = error, onError = onError,
        errorContainer = errorContainer, onErrorContainer = onErrorContainer,
        inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface,
        inversePrimary = inversePrimary, scrim = scrim, surfaceTint = surfaceTint,
        surfaceDim = surfaceDim, surfaceBright = surfaceBright,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest
    )}
    AccentColor.RED -> with(RedDark) { darkColorScheme(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
        secondary = secondary, onSecondary = onSecondary,
        secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary, onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
        background = background, onBackground = onBackground,
        surface = surface, onSurface = onSurface,
        surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
        outline = outline, outlineVariant = outlineVariant,
        error = error, onError = onError,
        errorContainer = errorContainer, onErrorContainer = onErrorContainer,
        inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface,
        inversePrimary = inversePrimary, scrim = scrim, surfaceTint = surfaceTint,
        surfaceDim = surfaceDim, surfaceBright = surfaceBright,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest
    )}
}

fun accentLightScheme(accent: AccentColor): ColorScheme = when (accent) {
    AccentColor.VIOLET -> with(VioletLight) { lightColorScheme(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
        secondary = secondary, onSecondary = onSecondary,
        secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary, onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
        background = background, onBackground = onBackground,
        surface = surface, onSurface = onSurface,
        surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
        outline = outline, outlineVariant = outlineVariant,
        error = error, onError = onError,
        errorContainer = errorContainer, onErrorContainer = onErrorContainer,
        inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface,
        inversePrimary = inversePrimary, scrim = scrim, surfaceTint = surfaceTint,
        surfaceDim = surfaceDim, surfaceBright = surfaceBright,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest
    )}
    AccentColor.GREEN -> with(GreenLight) { lightColorScheme(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
        secondary = secondary, onSecondary = onSecondary,
        secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary, onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
        background = background, onBackground = onBackground,
        surface = surface, onSurface = onSurface,
        surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
        outline = outline, outlineVariant = outlineVariant,
        error = error, onError = onError,
        errorContainer = errorContainer, onErrorContainer = onErrorContainer,
        inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface,
        inversePrimary = inversePrimary, scrim = scrim, surfaceTint = surfaceTint,
        surfaceDim = surfaceDim, surfaceBright = surfaceBright,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest
    )}
    AccentColor.ORANGE -> with(OrangeLight) { lightColorScheme(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
        secondary = secondary, onSecondary = onSecondary,
        secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary, onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
        background = background, onBackground = onBackground,
        surface = surface, onSurface = onSurface,
        surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
        outline = outline, outlineVariant = outlineVariant,
        error = error, onError = onError,
        errorContainer = errorContainer, onErrorContainer = onErrorContainer,
        inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface,
        inversePrimary = inversePrimary, scrim = scrim, surfaceTint = surfaceTint,
        surfaceDim = surfaceDim, surfaceBright = surfaceBright,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest
    )}
    AccentColor.RED -> with(RedLight) { lightColorScheme(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
        secondary = secondary, onSecondary = onSecondary,
        secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary, onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
        background = background, onBackground = onBackground,
        surface = surface, onSurface = onSurface,
        surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
        outline = outline, outlineVariant = outlineVariant,
        error = error, onError = onError,
        errorContainer = errorContainer, onErrorContainer = onErrorContainer,
        inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface,
        inversePrimary = inversePrimary, scrim = scrim, surfaceTint = surfaceTint,
        surfaceDim = surfaceDim, surfaceBright = surfaceBright,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest
    )}
}