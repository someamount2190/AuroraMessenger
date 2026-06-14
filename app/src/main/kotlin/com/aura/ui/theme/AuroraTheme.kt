package com.aura.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.aura.settings.ThemePalette

// ── Aurora palette: the aurora borealis — neon green → cyan → cobalt over a
// midnight-navy sky, with magenta/red as the warm accents. ───────────────────
private val Lime      = Color(0xFF00C875) // bright lime / neon green
private val Emerald   = Color(0xFF057859) // deep forest / emerald
private val Turquoise = Color(0xFF5CE1E6) // vibrant cyan / turquoise
private val Cobalt    = Color(0xFF1D4ED8) // royal / cobalt blue
private val Midnight  = Color(0xFF0F172A) // deep navy / midnight blue
private val RichRed   = Color(0xFFB91C1C) // rich red
private val RichMag   = Color(0xFFA21CAF) // rich magenta

// Aurora streak colours used by [AuroraBackground] for the background glow.
val AuroraGreen   = Lime
val AuroraCyan    = Turquoise
val AuroraBlue    = Cobalt
val AuroraMagenta = RichMag

/** Status-dot green for "server running" — reuses the brand neon green. */
val StatusGreen = Lime

private val AuroraLight = lightColorScheme(
    primary              = Emerald,
    onPrimary            = Color.White,
    primaryContainer     = Color(0xFFBDF3DE),
    onPrimaryContainer   = Color(0xFF00382A),
    secondary            = Cobalt,
    onSecondary          = Color.White,
    secondaryContainer   = Color(0xFFDCE5FF),
    onSecondaryContainer = Color(0xFF0A1F5C),
    tertiary             = RichMag,
    onTertiary           = Color.White,
    tertiaryContainer    = Color(0xFFFCD9FF),
    onTertiaryContainer  = Color(0xFF36003D),
    background           = Color(0xFFF2FAF7),
    onBackground         = Midnight,
    surface              = Color(0xFFF2FAF7),
    onSurface            = Midnight,
    surfaceVariant       = Color(0xFFD8E8E1),
    onSurfaceVariant     = Color(0xFF3E5750),
    outline              = Color(0xFF6E8079),
    error                = RichRed,
    onError              = Color.White,
    errorContainer       = Color(0xFFFFDAD6),
    onErrorContainer     = Color(0xFF410002)
)

private val AuroraDark = darkColorScheme(
    primary              = Turquoise,
    onPrimary            = Color(0xFF00363D),
    primaryContainer     = Color(0xFF0B4A50),
    onPrimaryContainer   = Color(0xFFA6F0F5),
    secondary            = Lime,
    onSecondary          = Color(0xFF003824),
    secondaryContainer   = Color(0xFF065F46),
    onSecondaryContainer = Color(0xFF8FF8CD),
    tertiary             = Color(0xFFE879F9),
    onTertiary           = Color(0xFF3B0A40),
    tertiaryContainer    = Color(0xFF6D1B72),
    onTertiaryContainer  = Color(0xFFFAD7FF),
    background           = Midnight,
    onBackground         = Color(0xFFE2E8F0),
    surface              = Color(0xFF111B2E),
    onSurface            = Color(0xFFE2E8F0),
    surfaceVariant       = Color(0xFF1E293B),
    onSurfaceVariant     = Color(0xFF97A4B8),
    outline              = Color(0xFF35455A),
    error                = Color(0xFFFFB4AB),
    onError              = Color(0xFF690005),
    errorContainer       = Color(0xFF7F1D1D),
    onErrorContainer     = Color(0xFFFFDAD6)
)

// ── Cherish palette: the classic deep-plum / warm-violet original theme. ──────
// Sits between love (red) and trust (blue) — intimate, premium, calm.
private val Plum        = Color(0xFF6B2D5E) // primary — deep plum
private val PlumDeep    = Color(0xFF4A1F40) // darker plum for dark-mode containers
private val Rose        = Color(0xFFC084A0) // accent — soft rose
private val RoseLight   = Color(0xFFD9A8C8) // brighter rose for dark-mode primary
private val CreamBg     = Color(0xFFFAF5F8) // light background — warm white
private val PlumNearBlk = Color(0xFF1A0F17) // dark background — near black with warmth
private val InkText     = Color(0xFF2D1A28) // text — soft near-black, not harsh
private val Lavender    = Color(0xFFECDDE6) // dark-mode text — soft lavender
private val PlumTintCard = Color(0xFFEFE3EC) // light card / surfaceVariant
private val PlumMutedTxt = Color(0xFF7A6072) // light muted plum-grey
private val PlumDarkCard  = Color(0xFF2A1A25) // dark card / surfaceVariant
private val LavenderMute  = Color(0xFFB89FAE) // dark muted lavender

private val CherishLight = lightColorScheme(
    primary              = Plum,
    onPrimary            = CreamBg,
    primaryContainer     = PlumTintCard,
    onPrimaryContainer   = InkText,
    secondary            = Rose,
    onSecondary          = Color.White,
    secondaryContainer   = Color(0xFFF3DEEB),
    onSecondaryContainer = InkText,
    tertiary             = Rose,
    background           = CreamBg,
    onBackground         = InkText,
    surface              = CreamBg,
    onSurface            = InkText,
    surfaceVariant       = PlumTintCard,
    onSurfaceVariant     = PlumMutedTxt,
    outline              = Color(0xFFC9B3C2),
    error                = Color(0xFFB3261E),
    onError              = Color.White,
    errorContainer       = Color(0xFFF9DEDC),
    onErrorContainer     = Color(0xFF410E0B)
)

private val CherishDark = darkColorScheme(
    primary              = RoseLight,
    onPrimary            = PlumNearBlk,
    primaryContainer     = PlumDeep,
    onPrimaryContainer   = Lavender,
    secondary            = Rose,
    onSecondary          = PlumNearBlk,
    secondaryContainer   = PlumDeep,
    onSecondaryContainer = Lavender,
    tertiary             = RoseLight,
    background           = PlumNearBlk,
    onBackground         = Lavender,
    surface              = PlumNearBlk,
    onSurface            = Lavender,
    surfaceVariant       = PlumDarkCard,
    onSurfaceVariant     = LavenderMute,
    outline              = Color(0xFF5A4452),
    error                = Color(0xFFF2B8B5),
    onError              = PlumNearBlk,
    errorContainer       = Color(0xFF8C1D18),
    onErrorContainer     = Color(0xFFF9DEDC)
)

/**
 * The active palette, exposed to background/decoration composables so they can
 * adapt (e.g. [AuroraBackground] only paints streaks for [ThemePalette.AURORA]).
 */
val LocalThemePalette = compositionLocalOf { ThemePalette.AURORA }

@Composable
fun AuroraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    palette: ThemePalette = ThemePalette.AURORA,
    content: @Composable () -> Unit
) {
    val colors = when (palette) {
        ThemePalette.AURORA  -> if (darkTheme) AuroraDark  else AuroraLight
        ThemePalette.CHERISH -> if (darkTheme) CherishDark else CherishLight
    }
    CompositionLocalProvider(LocalThemePalette provides palette) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
