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

    val background                = Color(0xFF111113)
    val onBackground              = Color(0xFFE4E2E6)
    val surface                   = Color(0xFF141316)
    val onSurface                 = Color(0xFFE4E2E6)
    val surfaceDim                = Color(0xFF0E0D10)
    val surfaceBright             = Color(0xFF2C2B2F)
    val surfaceContainerLowest    = Color(0xFF0B0B0D)
    val surfaceContainerLow       = Color(0xFF19181B)
    val surfaceContainer          = Color(0xFF1E1D21)
    val surfaceContainerHigh      = Color(0xFF28272B)
    val surfaceContainerHighest   = Color(0xFF333236)
    val surfaceVariant            = Color(0xFF46454A)
    val onSurfaceVariant          = Color(0xFFC7C5CA)

    val outline        = Color(0xFF918F94)
    val outlineVariant = Color(0xFF46454A)

    val error            = Color(0xFFFFB4AB)
    val onError          = Color(0xFF690005)
    val errorContainer   = Color(0xFF4A1014)
    val onErrorContainer = Color(0xFFFFDAD6)

    val inverseSurface   = Color(0xFFE4E2E6)
    val inverseOnSurface = Color(0xFF1E1216)
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

    val background                = Color(0xFFFCFCFE)
    val onBackground              = Color(0xFF1B1B1E)
    val surface                   = Color(0xFFFCFCFE)
    val onSurface                 = Color(0xFF1B1B1E)
    val surfaceDim                = Color(0xFFDBDBDE)
    val surfaceBright             = Color(0xFFFCFCFE)
    val surfaceContainerLowest    = Color(0xFFFFFFFF)
    val surfaceContainerLow       = Color(0xFFF5F5F7)
    val surfaceContainer          = Color(0xFFEFEFF1)
    val surfaceContainerHigh      = Color(0xFFE9E9EC)
    val surfaceContainerHighest   = Color(0xFFE4E3E6)
    val surfaceVariant            = Color(0xFFE2E1E6)
    val onSurfaceVariant          = Color(0xFF46454A)

    val outline        = Color(0xFF76757A)
    val outlineVariant = Color(0xFFC7C5CA)

    val error            = Color(0xFFBA1A1A)
    val onError          = Color(0xFFFFFFFF)
    val errorContainer   = Color(0xFFFFDAD6)
    val onErrorContainer = Color(0xFF410002)

    val inverseSurface   = Color(0xFF333236)
    val inverseOnSurface = Color(0xFFF4EFF8)
    val inversePrimary   = Color(0xFFB49AFF)
    val scrim            = Color(0xFF000000)
    val surfaceTint      = Color(0xFF6B4FC0)
}

// ══════════════════════════════════════════════════════════════════════
//  GREEN — Dark
// ══════════════════════════════════════════════════════════════════════
private object GreenDark {
    val primary              = Color(0xFF66DDAA)
    val onPrimary            = Color(0xFF003D20)
    val primaryContainer     = Color(0xFF006B3A)
    val onPrimaryContainer   = Color(0xFFB8F5D8)
    val secondary            = Color(0xFF80C8A8)
    val onSecondary          = Color(0xFF0A3020)
    val secondaryContainer   = Color(0xFF1E4A36)
    val onSecondaryContainer = Color(0xFFC0E8D4)
    val tertiary             = Color(0xFF88E8C4)
    val onTertiary           = Color(0xFF003D20)
    val tertiaryContainer    = Color(0xFF0C5840)
    val onTertiaryContainer  = Color(0xFFB0F0DA)

    val background                = Color(0xFF111113)
    val onBackground              = Color(0xFFE4E2E6)
    val surface                   = Color(0xFF141316)
    val onSurface                 = Color(0xFFE4E2E6)
    val surfaceDim                = Color(0xFF0E0D10)
    val surfaceBright             = Color(0xFF2C2B2F)
    val surfaceContainerLowest    = Color(0xFF0B0B0D)
    val surfaceContainerLow       = Color(0xFF19181B)
    val surfaceContainer          = Color(0xFF1E1D21)
    val surfaceContainerHigh      = Color(0xFF28272B)
    val surfaceContainerHighest   = Color(0xFF333236)
    val surfaceVariant            = Color(0xFF46454A)
    val onSurfaceVariant          = Color(0xFFC7C5CA)

    val outline        = Color(0xFF918F94)
    val outlineVariant = Color(0xFF46454A)

    val error            = Color(0xFFFFB4AB)
    val onError          = Color(0xFF690005)
    val errorContainer   = Color(0xFF4A1014)
    val onErrorContainer = Color(0xFFFFDAD6)

    val inverseSurface   = Color(0xFFE4E2E6)
    val inverseOnSurface = Color(0xFF121C16)
    val inversePrimary   = Color(0xFF1E8A5E)
    val scrim            = Color(0xFF000000)
    val surfaceTint      = Color(0xFF66DDAA)
}

// ══════════════════════════════════════════════════════════════════════
//  GREEN — Light
// ══════════════════════════════════════════════════════════════════════
private object GreenLight {
    val primary              = Color(0xFF1E8A5E)
    val onPrimary            = Color(0xFFFFFFFF)
    val primaryContainer     = Color(0xFFB8F5D8)
    val onPrimaryContainer   = Color(0xFF006B3A)
    val secondary            = Color(0xFF3A6E54)
    val onSecondary          = Color(0xFFFFFFFF)
    val secondaryContainer   = Color(0xFFC0E8D4)
    val onSecondaryContainer = Color(0xFF1E4A36)
    val tertiary             = Color(0xFF2A8868)
    val onTertiary           = Color(0xFFFFFFFF)
    val tertiaryContainer    = Color(0xFFB0F0DA)
    val onTertiaryContainer  = Color(0xFF0C5840)

    val background                = Color(0xFFFCFCFE)
    val onBackground              = Color(0xFF1B1B1E)
    val surface                   = Color(0xFFFCFCFE)
    val onSurface                 = Color(0xFF1B1B1E)
    val surfaceDim                = Color(0xFFDBDBDE)
    val surfaceBright             = Color(0xFFFCFCFE)
    val surfaceContainerLowest    = Color(0xFFFFFFFF)
    val surfaceContainerLow       = Color(0xFFF5F5F7)
    val surfaceContainer          = Color(0xFFEFEFF1)
    val surfaceContainerHigh      = Color(0xFFE9E9EC)
    val surfaceContainerHighest   = Color(0xFFE4E3E6)
    val surfaceVariant            = Color(0xFFE2E1E6)
    val onSurfaceVariant          = Color(0xFF46454A)

    val outline        = Color(0xFF76757A)
    val outlineVariant = Color(0xFFC7C5CA)

    val error            = Color(0xFFBA1A1A)
    val onError          = Color(0xFFFFFFFF)
    val errorContainer   = Color(0xFFFFDAD6)
    val onErrorContainer = Color(0xFF410002)

    val inverseSurface   = Color(0xFF333236)
    val inverseOnSurface = Color(0xFFECF4EE)
    val inversePrimary   = Color(0xFF66DDAA)
    val scrim            = Color(0xFF000000)
    val surfaceTint      = Color(0xFF1E8A5E)
}

// ══════════════════════════════════════════════════════════════════════
//  BLUE — Dark
// ══════════════════════════════════════════════════════════════════════
private object BlueDark {
    val primary              = Color(0xFF8AB4F8)
    val onPrimary            = Color(0xFF0A2A5E)
    val primaryContainer     = Color(0xFF1A4080)
    val onPrimaryContainer   = Color(0xFFD4E4FF)
    val secondary            = Color(0xFF8CA8D0)
    val onSecondary          = Color(0xFF0E1E34)
    val secondaryContainer   = Color(0xFF283850)
    val onSecondaryContainer = Color(0xFFD0DCF0)
    val tertiary             = Color(0xFFB0CCFF)
    val onTertiary           = Color(0xFF0C2450)
    val tertiaryContainer    = Color(0xFF1C3870)
    val onTertiaryContainer  = Color(0xFFDCE8FF)

    val background                = Color(0xFF111113)
    val onBackground              = Color(0xFFE4E2E6)
    val surface                   = Color(0xFF141316)
    val onSurface                 = Color(0xFFE4E2E6)
    val surfaceDim                = Color(0xFF0E0D10)
    val surfaceBright             = Color(0xFF2C2B2F)
    val surfaceContainerLowest    = Color(0xFF0B0B0D)
    val surfaceContainerLow       = Color(0xFF19181B)
    val surfaceContainer          = Color(0xFF1E1D21)
    val surfaceContainerHigh      = Color(0xFF28272B)
    val surfaceContainerHighest   = Color(0xFF333236)
    val surfaceVariant            = Color(0xFF46454A)
    val onSurfaceVariant          = Color(0xFFC7C5CA)

    val outline        = Color(0xFF918F94)
    val outlineVariant = Color(0xFF46454A)

    val error            = Color(0xFFFFB4AB)
    val onError          = Color(0xFF690005)
    val errorContainer   = Color(0xFF4A1014)
    val onErrorContainer = Color(0xFFFFDAD6)

    val inverseSurface   = Color(0xFFE4E2E6)
    val inverseOnSurface = Color(0xFF14181E)
    val inversePrimary   = Color(0xFF3070C0)
    val scrim            = Color(0xFF000000)
    val surfaceTint      = Color(0xFF8AB4F8)
}

private object BlueLight {
    val primary              = Color(0xFF3070C0)
    val onPrimary            = Color(0xFFFFFFFF)
    val primaryContainer     = Color(0xFFD4E4FF)
    val onPrimaryContainer   = Color(0xFF1A4080)
    val secondary            = Color(0xFF4A6488)
    val onSecondary          = Color(0xFFFFFFFF)
    val secondaryContainer   = Color(0xFFD0DCF0)
    val onSecondaryContainer = Color(0xFF283850)
    val tertiary             = Color(0xFF4060A0)
    val onTertiary           = Color(0xFFFFFFFF)
    val tertiaryContainer    = Color(0xFFDCE8FF)
    val onTertiaryContainer  = Color(0xFF1C3870)

    val background                = Color(0xFFFCFCFE)
    val onBackground              = Color(0xFF1B1B1E)
    val surface                   = Color(0xFFFCFCFE)
    val onSurface                 = Color(0xFF1B1B1E)
    val surfaceDim                = Color(0xFFDBDBDE)
    val surfaceBright             = Color(0xFFFCFCFE)
    val surfaceContainerLowest    = Color(0xFFFFFFFF)
    val surfaceContainerLow       = Color(0xFFF5F5F7)
    val surfaceContainer          = Color(0xFFEFEFF1)
    val surfaceContainerHigh      = Color(0xFFE9E9EC)
    val surfaceContainerHighest   = Color(0xFFE4E3E6)
    val surfaceVariant            = Color(0xFFE2E1E6)
    val onSurfaceVariant          = Color(0xFF46454A)

    val outline        = Color(0xFF76757A)
    val outlineVariant = Color(0xFFC7C5CA)

    val error            = Color(0xFFBA1A1A)
    val onError          = Color(0xFFFFFFFF)
    val errorContainer   = Color(0xFFFFDAD6)
    val onErrorContainer = Color(0xFF410002)

    val inverseSurface   = Color(0xFF333236)
    val inverseOnSurface = Color(0xFFEEF0F8)
    val inversePrimary   = Color(0xFF8AB4F8)
    val scrim            = Color(0xFF000000)
    val surfaceTint      = Color(0xFF3070C0)
}

// ══════════════════════════════════════════════════════════════════════
//  TEAL — Dark
// ══════════════════════════════════════════════════════════════════════
private object TealDark {
    val primary              = Color(0xFF5EC6C6)
    val onPrimary            = Color(0xFF003838)
    val primaryContainer     = Color(0xFF006060)
    val onPrimaryContainer   = Color(0xFFB0F0F0)
    val secondary            = Color(0xFF80B8B8)
    val onSecondary          = Color(0xFF0A2828)
    val secondaryContainer   = Color(0xFF1E4444)
    val onSecondaryContainer = Color(0xFFC0E4E4)
    val tertiary             = Color(0xFF78D8D8)
    val onTertiary           = Color(0xFF003838)
    val tertiaryContainer    = Color(0xFF0C5454)
    val onTertiaryContainer  = Color(0xFFB8F0F0)

    val background                = Color(0xFF111113)
    val onBackground              = Color(0xFFE4E2E6)
    val surface                   = Color(0xFF141316)
    val onSurface                 = Color(0xFFE4E2E6)
    val surfaceDim                = Color(0xFF0E0D10)
    val surfaceBright             = Color(0xFF2C2B2F)
    val surfaceContainerLowest    = Color(0xFF0B0B0D)
    val surfaceContainerLow       = Color(0xFF19181B)
    val surfaceContainer          = Color(0xFF1E1D21)
    val surfaceContainerHigh      = Color(0xFF28272B)
    val surfaceContainerHighest   = Color(0xFF333236)
    val surfaceVariant            = Color(0xFF46454A)
    val onSurfaceVariant          = Color(0xFFC7C5CA)

    val outline        = Color(0xFF918F94)
    val outlineVariant = Color(0xFF46454A)

    val error            = Color(0xFFFFB4AB)
    val onError          = Color(0xFF690005)
    val errorContainer   = Color(0xFF4A1014)
    val onErrorContainer = Color(0xFFFFDAD6)

    val inverseSurface   = Color(0xFFE4E2E6)
    val inverseOnSurface = Color(0xFF121A1A)
    val inversePrimary   = Color(0xFF1E8888)
    val scrim            = Color(0xFF000000)
    val surfaceTint      = Color(0xFF5EC6C6)
}

private object TealLight {
    val primary              = Color(0xFF1E8888)
    val onPrimary            = Color(0xFFFFFFFF)
    val primaryContainer     = Color(0xFFB0F0F0)
    val onPrimaryContainer   = Color(0xFF006060)
    val secondary            = Color(0xFF3A6868)
    val onSecondary          = Color(0xFFFFFFFF)
    val secondaryContainer   = Color(0xFFC0E4E4)
    val onSecondaryContainer = Color(0xFF1E4444)
    val tertiary             = Color(0xFF2A8080)
    val onTertiary           = Color(0xFFFFFFFF)
    val tertiaryContainer    = Color(0xFFB8F0F0)
    val onTertiaryContainer  = Color(0xFF0C5454)

    val background                = Color(0xFFFCFCFE)
    val onBackground              = Color(0xFF1B1B1E)
    val surface                   = Color(0xFFFCFCFE)
    val onSurface                 = Color(0xFF1B1B1E)
    val surfaceDim                = Color(0xFFDBDBDE)
    val surfaceBright             = Color(0xFFFCFCFE)
    val surfaceContainerLowest    = Color(0xFFFFFFFF)
    val surfaceContainerLow       = Color(0xFFF5F5F7)
    val surfaceContainer          = Color(0xFFEFEFF1)
    val surfaceContainerHigh      = Color(0xFFE9E9EC)
    val surfaceContainerHighest   = Color(0xFFE4E3E6)
    val surfaceVariant            = Color(0xFFE2E1E6)
    val onSurfaceVariant          = Color(0xFF46454A)

    val outline        = Color(0xFF76757A)
    val outlineVariant = Color(0xFFC7C5CA)

    val error            = Color(0xFFBA1A1A)
    val onError          = Color(0xFFFFFFFF)
    val errorContainer   = Color(0xFFFFDAD6)
    val onErrorContainer = Color(0xFF410002)

    val inverseSurface   = Color(0xFF333236)
    val inverseOnSurface = Color(0xFFECF4F4)
    val inversePrimary   = Color(0xFF5EC6C6)
    val scrim            = Color(0xFF000000)
    val surfaceTint      = Color(0xFF1E8888)
}

// ══════════════════════════════════════════════════════════════════════
//  GOLD — Dark
// ══════════════════════════════════════════════════════════════════════
private object GoldDark {
    val primary              = Color(0xFFFFD54F)
    val onPrimary            = Color(0xFF3E2E00)
    val primaryContainer     = Color(0xFF6A5000)
    val onPrimaryContainer   = Color(0xFFFFE8A0)
    val secondary            = Color(0xFFD4C080)
    val onSecondary          = Color(0xFF2E2408)
    val secondaryContainer   = Color(0xFF4A3C18)
    val onSecondaryContainer = Color(0xFFF0E0C0)
    val tertiary             = Color(0xFFFFE082)
    val onTertiary           = Color(0xFF3A2C00)
    val tertiaryContainer    = Color(0xFF5E4800)
    val onTertiaryContainer  = Color(0xFFFFECC0)

    val background                = Color(0xFF111113)
    val onBackground              = Color(0xFFE4E2E6)
    val surface                   = Color(0xFF141316)
    val onSurface                 = Color(0xFFE4E2E6)
    val surfaceDim                = Color(0xFF0E0D10)
    val surfaceBright             = Color(0xFF2C2B2F)
    val surfaceContainerLowest    = Color(0xFF0B0B0D)
    val surfaceContainerLow       = Color(0xFF19181B)
    val surfaceContainer          = Color(0xFF1E1D21)
    val surfaceContainerHigh      = Color(0xFF28272B)
    val surfaceContainerHighest   = Color(0xFF333236)
    val surfaceVariant            = Color(0xFF46454A)
    val onSurfaceVariant          = Color(0xFFC7C5CA)

    val outline        = Color(0xFF918F94)
    val outlineVariant = Color(0xFF46454A)

    val error            = Color(0xFFFFB4AB)
    val onError          = Color(0xFF690005)
    val errorContainer   = Color(0xFF4A1014)
    val onErrorContainer = Color(0xFFFFDAD6)

    val inverseSurface   = Color(0xFFE4E2E6)
    val inverseOnSurface = Color(0xFF1C1A12)
    val inversePrimary   = Color(0xFFB08C00)
    val scrim            = Color(0xFF000000)
    val surfaceTint      = Color(0xFFFFD54F)
}

private object GoldLight {
    val primary              = Color(0xFFB08C00)
    val onPrimary            = Color(0xFFFFFFFF)
    val primaryContainer     = Color(0xFFFFE8A0)
    val onPrimaryContainer   = Color(0xFF6A5000)
    val secondary            = Color(0xFF7A6830)
    val onSecondary          = Color(0xFFFFFFFF)
    val secondaryContainer   = Color(0xFFF0E0C0)
    val onSecondaryContainer = Color(0xFF4A3C18)
    val tertiary             = Color(0xFFA08000)
    val onTertiary           = Color(0xFFFFFFFF)
    val tertiaryContainer    = Color(0xFFFFECC0)
    val onTertiaryContainer  = Color(0xFF5E4800)

    val background                = Color(0xFFFCFCFE)
    val onBackground              = Color(0xFF1B1B1E)
    val surface                   = Color(0xFFFCFCFE)
    val onSurface                 = Color(0xFF1B1B1E)
    val surfaceDim                = Color(0xFFDBDBDE)
    val surfaceBright             = Color(0xFFFCFCFE)
    val surfaceContainerLowest    = Color(0xFFFFFFFF)
    val surfaceContainerLow       = Color(0xFFF5F5F7)
    val surfaceContainer          = Color(0xFFEFEFF1)
    val surfaceContainerHigh      = Color(0xFFE9E9EC)
    val surfaceContainerHighest   = Color(0xFFE4E3E6)
    val surfaceVariant            = Color(0xFFE2E1E6)
    val onSurfaceVariant          = Color(0xFF46454A)

    val outline        = Color(0xFF76757A)
    val outlineVariant = Color(0xFFC7C5CA)

    val error            = Color(0xFFBA1A1A)
    val onError          = Color(0xFFFFFFFF)
    val errorContainer   = Color(0xFFFFDAD6)
    val onErrorContainer = Color(0xFF410002)

    val inverseSurface   = Color(0xFF333236)
    val inverseOnSurface = Color(0xFFF8F4E8)
    val inversePrimary   = Color(0xFFFFD54F)
    val scrim            = Color(0xFF000000)
    val surfaceTint      = Color(0xFFB08C00)
}

// ══════════════════════════════════════════════════════════════════════
//  PINK — Dark
// ══════════════════════════════════════════════════════════════════════
private object PinkDark {
    val primary              = Color(0xFFFF8AB4)
    val onPrimary            = Color(0xFF4A0828)
    val primaryContainer     = Color(0xFF7A1848)
    val onPrimaryContainer   = Color(0xFFFFD0E0)
    val secondary            = Color(0xFFD09098)
    val onSecondary          = Color(0xFF301018)
    val secondaryContainer   = Color(0xFF4C2430)
    val onSecondaryContainer = Color(0xFFF0D0D8)
    val tertiary             = Color(0xFFFFAAC8)
    val onTertiary           = Color(0xFF401020)
    val tertiaryContainer    = Color(0xFF6A2040)
    val onTertiaryContainer  = Color(0xFFFFDCE8)

    val background                = Color(0xFF111113)
    val onBackground              = Color(0xFFE4E2E6)
    val surface                   = Color(0xFF141316)
    val onSurface                 = Color(0xFFE4E2E6)
    val surfaceDim                = Color(0xFF0E0D10)
    val surfaceBright             = Color(0xFF2C2B2F)
    val surfaceContainerLowest    = Color(0xFF0B0B0D)
    val surfaceContainerLow       = Color(0xFF19181B)
    val surfaceContainer          = Color(0xFF1E1D21)
    val surfaceContainerHigh      = Color(0xFF28272B)
    val surfaceContainerHighest   = Color(0xFF333236)
    val surfaceVariant            = Color(0xFF46454A)
    val onSurfaceVariant          = Color(0xFFC7C5CA)

    val outline        = Color(0xFF918F94)
    val outlineVariant = Color(0xFF46454A)

    val error            = Color(0xFFFFB4AB)
    val onError          = Color(0xFF690005)
    val errorContainer   = Color(0xFF4A1014)
    val onErrorContainer = Color(0xFFFFDAD6)

    val inverseSurface   = Color(0xFFE4E2E6)
    val inverseOnSurface = Color(0xFF1E1216)
    val inversePrimary   = Color(0xFFC03060)
    val scrim            = Color(0xFF000000)
    val surfaceTint      = Color(0xFFFF8AB4)
}

private object PinkLight {
    val primary              = Color(0xFFC03060)
    val onPrimary            = Color(0xFFFFFFFF)
    val primaryContainer     = Color(0xFFFFD0E0)
    val onPrimaryContainer   = Color(0xFF7A1848)
    val secondary            = Color(0xFF7A4A54)
    val onSecondary          = Color(0xFFFFFFFF)
    val secondaryContainer   = Color(0xFFF0D0D8)
    val onSecondaryContainer = Color(0xFF4C2430)
    val tertiary             = Color(0xFFAA4060)
    val onTertiary           = Color(0xFFFFFFFF)
    val tertiaryContainer    = Color(0xFFFFDCE8)
    val onTertiaryContainer  = Color(0xFF6A2040)

    val background                = Color(0xFFFCFCFE)
    val onBackground              = Color(0xFF1B1B1E)
    val surface                   = Color(0xFFFCFCFE)
    val onSurface                 = Color(0xFF1B1B1E)
    val surfaceDim                = Color(0xFFDBDBDE)
    val surfaceBright             = Color(0xFFFCFCFE)
    val surfaceContainerLowest    = Color(0xFFFFFFFF)
    val surfaceContainerLow       = Color(0xFFF5F5F7)
    val surfaceContainer          = Color(0xFFEFEFF1)
    val surfaceContainerHigh      = Color(0xFFE9E9EC)
    val surfaceContainerHighest   = Color(0xFFE4E3E6)
    val surfaceVariant            = Color(0xFFE2E1E6)
    val onSurfaceVariant          = Color(0xFF46454A)

    val outline        = Color(0xFF76757A)
    val outlineVariant = Color(0xFFC7C5CA)

    val error            = Color(0xFFBA1A1A)
    val onError          = Color(0xFFFFFFFF)
    val errorContainer   = Color(0xFFFFDAD6)
    val onErrorContainer = Color(0xFF410002)

    val inverseSurface   = Color(0xFF333236)
    val inverseOnSurface = Color(0xFFF8EEF0)
    val inversePrimary   = Color(0xFFFF8AB4)
    val scrim            = Color(0xFF000000)
    val surfaceTint      = Color(0xFFC03060)
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

    val background                = Color(0xFF111113)
    val onBackground              = Color(0xFFE4E2E6)
    val surface                   = Color(0xFF141316)
    val onSurface                 = Color(0xFFE4E2E6)
    val surfaceDim                = Color(0xFF0E0D10)
    val surfaceBright             = Color(0xFF2C2B2F)
    val surfaceContainerLowest    = Color(0xFF0B0B0D)
    val surfaceContainerLow       = Color(0xFF19181B)
    val surfaceContainer          = Color(0xFF1E1D21)
    val surfaceContainerHigh      = Color(0xFF28272B)
    val surfaceContainerHighest   = Color(0xFF333236)
    val surfaceVariant            = Color(0xFF46454A)
    val onSurfaceVariant          = Color(0xFFC7C5CA)

    val outline        = Color(0xFF918F94)
    val outlineVariant = Color(0xFF46454A)

    val error            = Color(0xFFFFB4AB)
    val onError          = Color(0xFF690005)
    val errorContainer   = Color(0xFF4A1014)
    val onErrorContainer = Color(0xFFFFDAD6)

    val inverseSurface   = Color(0xFFE4E2E6)
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

    val background                = Color(0xFFFCFCFE)
    val onBackground              = Color(0xFF1B1B1E)
    val surface                   = Color(0xFFFCFCFE)
    val onSurface                 = Color(0xFF1B1B1E)
    val surfaceDim                = Color(0xFFDBDBDE)
    val surfaceBright             = Color(0xFFFCFCFE)
    val surfaceContainerLowest    = Color(0xFFFFFFFF)
    val surfaceContainerLow       = Color(0xFFF5F5F7)
    val surfaceContainer          = Color(0xFFEFEFF1)
    val surfaceContainerHigh      = Color(0xFFE9E9EC)
    val surfaceContainerHighest   = Color(0xFFE4E3E6)
    val surfaceVariant            = Color(0xFFE2E1E6)
    val onSurfaceVariant          = Color(0xFF46454A)

    val outline        = Color(0xFF76757A)
    val outlineVariant = Color(0xFFC7C5CA)

    val error            = Color(0xFFBA1A1A)
    val onError          = Color(0xFFFFFFFF)
    val errorContainer   = Color(0xFFFFDAD6)
    val onErrorContainer = Color(0xFF410002)

    val inverseSurface   = Color(0xFF333236)
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

    val background                = Color(0xFF111113)
    val onBackground              = Color(0xFFE4E2E6)
    val surface                   = Color(0xFF141316)
    val onSurface                 = Color(0xFFE4E2E6)
    val surfaceDim                = Color(0xFF0E0D10)
    val surfaceBright             = Color(0xFF2C2B2F)
    val surfaceContainerLowest    = Color(0xFF0B0B0D)
    val surfaceContainerLow       = Color(0xFF19181B)
    val surfaceContainer          = Color(0xFF1E1D21)
    val surfaceContainerHigh      = Color(0xFF28272B)
    val surfaceContainerHighest   = Color(0xFF333236)
    val surfaceVariant            = Color(0xFF46454A)
    val onSurfaceVariant          = Color(0xFFC7C5CA)

    val outline        = Color(0xFF918F94)
    val outlineVariant = Color(0xFF46454A)

    val error            = Color(0xFFFFB4AB)
    val onError          = Color(0xFF690005)
    val errorContainer   = Color(0xFF4A1014)
    val onErrorContainer = Color(0xFFFFDAD6)

    val inverseSurface   = Color(0xFFE4E2E6)
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

    val background                = Color(0xFFFCFCFE)
    val onBackground              = Color(0xFF1B1B1E)
    val surface                   = Color(0xFFFCFCFE)
    val onSurface                 = Color(0xFF1B1B1E)
    val surfaceDim                = Color(0xFFDBDBDE)
    val surfaceBright             = Color(0xFFFCFCFE)
    val surfaceContainerLowest    = Color(0xFFFFFFFF)
    val surfaceContainerLow       = Color(0xFFF5F5F7)
    val surfaceContainer          = Color(0xFFEFEFF1)
    val surfaceContainerHigh      = Color(0xFFE9E9EC)
    val surfaceContainerHighest   = Color(0xFFE4E3E6)
    val surfaceVariant            = Color(0xFFE2E1E6)
    val onSurfaceVariant          = Color(0xFF46454A)

    val outline        = Color(0xFF76757A)
    val outlineVariant = Color(0xFFC7C5CA)

    val error            = Color(0xFFBA1A1A)
    val onError          = Color(0xFFFFFFFF)
    val errorContainer   = Color(0xFFFFDAD6)
    val onErrorContainer = Color(0xFF410002)

    val inverseSurface   = Color(0xFF333236)
    val inverseOnSurface = Color(0xFFF4F0F0)
    val inversePrimary   = Color(0xFFFF7B7B)
    val scrim            = Color(0xFF000000)
    val surfaceTint      = Color(0xFFC03030)
}

// ── Palette builders ───────────────────────────────────────────────

private inline fun <T> buildScheme(
    obj: T,
    builder: T.() -> ColorScheme
): ColorScheme = obj.builder()

private fun darkFrom(
    primary: Color, onPrimary: Color, primaryContainer: Color, onPrimaryContainer: Color,
    secondary: Color, onSecondary: Color, secondaryContainer: Color, onSecondaryContainer: Color,
    tertiary: Color, onTertiary: Color, tertiaryContainer: Color, onTertiaryContainer: Color,
    background: Color, onBackground: Color, surface: Color, onSurface: Color,
    surfaceVariant: Color, onSurfaceVariant: Color, outline: Color, outlineVariant: Color,
    error: Color, onError: Color, errorContainer: Color, onErrorContainer: Color,
    inverseSurface: Color, inverseOnSurface: Color, inversePrimary: Color,
    scrim: Color, surfaceTint: Color,
    surfaceDim: Color, surfaceBright: Color,
    surfaceContainerLowest: Color, surfaceContainerLow: Color,
    surfaceContainer: Color, surfaceContainerHigh: Color, surfaceContainerHighest: Color
) = darkColorScheme(
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
)

fun accentDarkScheme(accent: AccentColor): ColorScheme = when (accent) {
    AccentColor.VIOLET -> with(VioletDark) { darkFrom(primary, onPrimary, primaryContainer, onPrimaryContainer, secondary, onSecondary, secondaryContainer, onSecondaryContainer, tertiary, onTertiary, tertiaryContainer, onTertiaryContainer, background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant, outline, outlineVariant, error, onError, errorContainer, onErrorContainer, inverseSurface, inverseOnSurface, inversePrimary, scrim, surfaceTint, surfaceDim, surfaceBright, surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest) }
    AccentColor.BLUE   -> with(BlueDark) { darkFrom(primary, onPrimary, primaryContainer, onPrimaryContainer, secondary, onSecondary, secondaryContainer, onSecondaryContainer, tertiary, onTertiary, tertiaryContainer, onTertiaryContainer, background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant, outline, outlineVariant, error, onError, errorContainer, onErrorContainer, inverseSurface, inverseOnSurface, inversePrimary, scrim, surfaceTint, surfaceDim, surfaceBright, surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest) }
    AccentColor.TEAL   -> with(TealDark) { darkFrom(primary, onPrimary, primaryContainer, onPrimaryContainer, secondary, onSecondary, secondaryContainer, onSecondaryContainer, tertiary, onTertiary, tertiaryContainer, onTertiaryContainer, background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant, outline, outlineVariant, error, onError, errorContainer, onErrorContainer, inverseSurface, inverseOnSurface, inversePrimary, scrim, surfaceTint, surfaceDim, surfaceBright, surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest) }
    AccentColor.GREEN  -> with(GreenDark) { darkFrom(primary, onPrimary, primaryContainer, onPrimaryContainer, secondary, onSecondary, secondaryContainer, onSecondaryContainer, tertiary, onTertiary, tertiaryContainer, onTertiaryContainer, background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant, outline, outlineVariant, error, onError, errorContainer, onErrorContainer, inverseSurface, inverseOnSurface, inversePrimary, scrim, surfaceTint, surfaceDim, surfaceBright, surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest) }
    AccentColor.GOLD   -> with(GoldDark) { darkFrom(primary, onPrimary, primaryContainer, onPrimaryContainer, secondary, onSecondary, secondaryContainer, onSecondaryContainer, tertiary, onTertiary, tertiaryContainer, onTertiaryContainer, background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant, outline, outlineVariant, error, onError, errorContainer, onErrorContainer, inverseSurface, inverseOnSurface, inversePrimary, scrim, surfaceTint, surfaceDim, surfaceBright, surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest) }
    AccentColor.ORANGE -> with(OrangeDark) { darkFrom(primary, onPrimary, primaryContainer, onPrimaryContainer, secondary, onSecondary, secondaryContainer, onSecondaryContainer, tertiary, onTertiary, tertiaryContainer, onTertiaryContainer, background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant, outline, outlineVariant, error, onError, errorContainer, onErrorContainer, inverseSurface, inverseOnSurface, inversePrimary, scrim, surfaceTint, surfaceDim, surfaceBright, surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest) }
    AccentColor.RED    -> with(RedDark) { darkFrom(primary, onPrimary, primaryContainer, onPrimaryContainer, secondary, onSecondary, secondaryContainer, onSecondaryContainer, tertiary, onTertiary, tertiaryContainer, onTertiaryContainer, background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant, outline, outlineVariant, error, onError, errorContainer, onErrorContainer, inverseSurface, inverseOnSurface, inversePrimary, scrim, surfaceTint, surfaceDim, surfaceBright, surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest) }
    AccentColor.PINK   -> with(PinkDark) { darkFrom(primary, onPrimary, primaryContainer, onPrimaryContainer, secondary, onSecondary, secondaryContainer, onSecondaryContainer, tertiary, onTertiary, tertiaryContainer, onTertiaryContainer, background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant, outline, outlineVariant, error, onError, errorContainer, onErrorContainer, inverseSurface, inverseOnSurface, inversePrimary, scrim, surfaceTint, surfaceDim, surfaceBright, surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest) }
}

fun accentLightScheme(accent: AccentColor): ColorScheme = when (accent) {
    AccentColor.VIOLET -> with(VioletLight) { lightColorScheme(primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer, secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer, tertiary = tertiary, onTertiary = onTertiary, tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer, background = background, onBackground = onBackground, surface = surface, onSurface = onSurface, surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant, outline = outline, outlineVariant = outlineVariant, error = error, onError = onError, errorContainer = errorContainer, onErrorContainer = onErrorContainer, inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface, inversePrimary = inversePrimary, scrim = scrim, surfaceTint = surfaceTint, surfaceDim = surfaceDim, surfaceBright = surfaceBright, surfaceContainerLowest = surfaceContainerLowest, surfaceContainerLow = surfaceContainerLow, surfaceContainer = surfaceContainer, surfaceContainerHigh = surfaceContainerHigh, surfaceContainerHighest = surfaceContainerHighest) }
    AccentColor.BLUE   -> with(BlueLight) { lightColorScheme(primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer, secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer, tertiary = tertiary, onTertiary = onTertiary, tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer, background = background, onBackground = onBackground, surface = surface, onSurface = onSurface, surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant, outline = outline, outlineVariant = outlineVariant, error = error, onError = onError, errorContainer = errorContainer, onErrorContainer = onErrorContainer, inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface, inversePrimary = inversePrimary, scrim = scrim, surfaceTint = surfaceTint, surfaceDim = surfaceDim, surfaceBright = surfaceBright, surfaceContainerLowest = surfaceContainerLowest, surfaceContainerLow = surfaceContainerLow, surfaceContainer = surfaceContainer, surfaceContainerHigh = surfaceContainerHigh, surfaceContainerHighest = surfaceContainerHighest) }
    AccentColor.TEAL   -> with(TealLight) { lightColorScheme(primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer, secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer, tertiary = tertiary, onTertiary = onTertiary, tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer, background = background, onBackground = onBackground, surface = surface, onSurface = onSurface, surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant, outline = outline, outlineVariant = outlineVariant, error = error, onError = onError, errorContainer = errorContainer, onErrorContainer = onErrorContainer, inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface, inversePrimary = inversePrimary, scrim = scrim, surfaceTint = surfaceTint, surfaceDim = surfaceDim, surfaceBright = surfaceBright, surfaceContainerLowest = surfaceContainerLowest, surfaceContainerLow = surfaceContainerLow, surfaceContainer = surfaceContainer, surfaceContainerHigh = surfaceContainerHigh, surfaceContainerHighest = surfaceContainerHighest) }
    AccentColor.GREEN  -> with(GreenLight) { lightColorScheme(primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer, secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer, tertiary = tertiary, onTertiary = onTertiary, tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer, background = background, onBackground = onBackground, surface = surface, onSurface = onSurface, surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant, outline = outline, outlineVariant = outlineVariant, error = error, onError = onError, errorContainer = errorContainer, onErrorContainer = onErrorContainer, inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface, inversePrimary = inversePrimary, scrim = scrim, surfaceTint = surfaceTint, surfaceDim = surfaceDim, surfaceBright = surfaceBright, surfaceContainerLowest = surfaceContainerLowest, surfaceContainerLow = surfaceContainerLow, surfaceContainer = surfaceContainer, surfaceContainerHigh = surfaceContainerHigh, surfaceContainerHighest = surfaceContainerHighest) }
    AccentColor.GOLD   -> with(GoldLight) { lightColorScheme(primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer, secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer, tertiary = tertiary, onTertiary = onTertiary, tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer, background = background, onBackground = onBackground, surface = surface, onSurface = onSurface, surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant, outline = outline, outlineVariant = outlineVariant, error = error, onError = onError, errorContainer = errorContainer, onErrorContainer = onErrorContainer, inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface, inversePrimary = inversePrimary, scrim = scrim, surfaceTint = surfaceTint, surfaceDim = surfaceDim, surfaceBright = surfaceBright, surfaceContainerLowest = surfaceContainerLowest, surfaceContainerLow = surfaceContainerLow, surfaceContainer = surfaceContainer, surfaceContainerHigh = surfaceContainerHigh, surfaceContainerHighest = surfaceContainerHighest) }
    AccentColor.ORANGE -> with(OrangeLight) { lightColorScheme(primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer, secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer, tertiary = tertiary, onTertiary = onTertiary, tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer, background = background, onBackground = onBackground, surface = surface, onSurface = onSurface, surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant, outline = outline, outlineVariant = outlineVariant, error = error, onError = onError, errorContainer = errorContainer, onErrorContainer = onErrorContainer, inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface, inversePrimary = inversePrimary, scrim = scrim, surfaceTint = surfaceTint, surfaceDim = surfaceDim, surfaceBright = surfaceBright, surfaceContainerLowest = surfaceContainerLowest, surfaceContainerLow = surfaceContainerLow, surfaceContainer = surfaceContainer, surfaceContainerHigh = surfaceContainerHigh, surfaceContainerHighest = surfaceContainerHighest) }
    AccentColor.RED    -> with(RedLight) { lightColorScheme(primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer, secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer, tertiary = tertiary, onTertiary = onTertiary, tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer, background = background, onBackground = onBackground, surface = surface, onSurface = onSurface, surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant, outline = outline, outlineVariant = outlineVariant, error = error, onError = onError, errorContainer = errorContainer, onErrorContainer = onErrorContainer, inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface, inversePrimary = inversePrimary, scrim = scrim, surfaceTint = surfaceTint, surfaceDim = surfaceDim, surfaceBright = surfaceBright, surfaceContainerLowest = surfaceContainerLowest, surfaceContainerLow = surfaceContainerLow, surfaceContainer = surfaceContainer, surfaceContainerHigh = surfaceContainerHigh, surfaceContainerHighest = surfaceContainerHighest) }
    AccentColor.PINK   -> with(PinkLight) { lightColorScheme(primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer, secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer, tertiary = tertiary, onTertiary = onTertiary, tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer, background = background, onBackground = onBackground, surface = surface, onSurface = onSurface, surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant, outline = outline, outlineVariant = outlineVariant, error = error, onError = onError, errorContainer = errorContainer, onErrorContainer = onErrorContainer, inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface, inversePrimary = inversePrimary, scrim = scrim, surfaceTint = surfaceTint, surfaceDim = surfaceDim, surfaceBright = surfaceBright, surfaceContainerLowest = surfaceContainerLowest, surfaceContainerLow = surfaceContainerLow, surfaceContainer = surfaceContainer, surfaceContainerHigh = surfaceContainerHigh, surfaceContainerHighest = surfaceContainerHighest) }
}