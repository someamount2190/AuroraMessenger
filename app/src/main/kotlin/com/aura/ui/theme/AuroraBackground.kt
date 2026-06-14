package com.aura.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * Fills the area with the theme background and paints soft diagonal aurora
 * streaks over it — neon-green → cyan → cobalt bands plus a couple of magenta /
 * green corner glows. Kept low-alpha so text stays readable; a touch stronger on
 * the dark (midnight) theme where the streaks read as a real aurora sky.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val a = if (dark) 0.20f else 0.11f   // streak strength
    // The classic Cherish palette predates the aurora streaks — plain background.
    // NOTE: keep a single `content()` call site (one Box) so switching palette only
    // changes the painting, never the composition structure — otherwise the whole
    // subtree (incl. the NavController) would be torn down and re-created.
    val cherish = LocalThemePalette.current == com.aura.settings.ThemePalette.CHERISH

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(scheme.background)
                if (cherish) return@drawBehind   // plain background, no streaks
                val w = size.width
                val h = size.height

                // Diagonal aurora streaks (bottom-left → top-right).
                drawRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.18f to AuroraGreen.copy(alpha = a),
                            0.33f to Color.Transparent,
                            0.50f to AuroraCyan.copy(alpha = a),
                            0.66f to Color.Transparent,
                            0.82f to AuroraBlue.copy(alpha = a * 0.85f),
                            1.00f to Color.Transparent
                        ),
                        start = Offset(0f, h * 1.1f),
                        end = Offset(w, -h * 0.2f)
                    )
                )
                // A second, fainter set crossing the other way for depth.
                drawRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.45f to AuroraCyan.copy(alpha = a * 0.5f),
                            0.70f to Color.Transparent,
                            1.00f to AuroraGreen.copy(alpha = a * 0.4f)
                        ),
                        start = Offset(w, h),
                        end = Offset(0f, 0f)
                    )
                )
                // Soft corner glows.
                drawRect(
                    Brush.radialGradient(
                        listOf(AuroraMagenta.copy(alpha = a * 0.6f), Color.Transparent),
                        center = Offset(w * 0.95f, h * 0.06f),
                        radius = w * 0.75f
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(AuroraGreen.copy(alpha = a * 0.55f), Color.Transparent),
                        center = Offset(w * 0.05f, h * 0.96f),
                        radius = w * 0.75f
                    )
                )
            },
        content = content
    )
}

/**
 * Frosted-glass panel for list/card elements — a translucent light sheen plus a
 * faint light border, so elements lift off the aurora background and stay legible
 * without covering it. Dark mode only (light mode already reads fine on its pale
 * background); a no-op there.
 */
fun Modifier.auroraGlass(shape: Shape = RoundedCornerShape(18.dp)): Modifier = composed {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cherish = LocalThemePalette.current == com.aura.settings.ThemePalette.CHERISH
    if (!dark || cherish) {
        this
    } else {
        this
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.15f), Color.White.copy(alpha = 0.06f))
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
    }
}
