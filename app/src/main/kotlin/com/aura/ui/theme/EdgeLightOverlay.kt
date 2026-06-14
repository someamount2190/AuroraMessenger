package com.aura.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

private const val DURATION_MS = 1500f

/**
 * "Light through glass" edge sweep, scoped to a single element's border. When
 * [trigger] changes, one bright highlight band (plus a fainter opposite band)
 * sweeps once around the element's rounded-rect outline — a soft wide glow with
 * a thin bright filament on top, riding a `sin` fade-in/out envelope. Used on the
 * home contact box to flag a fresh message from that sender.
 *
 * Driven by the frame clock (compose.runtime) rather than animation-core, which
 * isn't in the vendored maven repo. Idle cost is zero — nothing draws unless an
 * animation is mid-flight.
 */
fun Modifier.glassEdgeLight(
    trigger: Int,
    shape: Shape = RoundedCornerShape(18.dp)
): Modifier = composed {
    val bright = MaterialTheme.colorScheme.primary
    val accent = MaterialTheme.colorScheme.secondary

    var progress by remember { mutableFloatStateOf(1f) }   // start "finished": nothing drawn
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect   // skip the initial composition
        var startNanos = 0L
        while (true) {
            val frame = withFrameNanos { it }
            if (startNanos == 0L) startNanos = frame
            val t = ((frame - startNanos) / 1_000_000f / DURATION_MS).coerceIn(0f, 1f)
            progress = t
            if (t >= 1f) break
        }
    }

    drawWithContent {
        drawContent()
        if (progress <= 0f || progress >= 1f) return@drawWithContent
        val envelope = sin(progress * PI).toFloat()
        if (envelope <= 0.01f) return@drawWithContent

        val outline = shape.createOutline(size, layoutDirection, this)
        val band = Brush.sweepGradient(
            colorStops = ringStops(progress, bright, accent),
            center = Offset(size.width / 2f, size.height / 2f)
        )
        // Wide soft glow underneath (the light bleeding through glass)…
        drawOutline(outline, brush = band, alpha = 0.35f * envelope, style = Stroke(14.dp.toPx()))
        // …a mid line…
        drawOutline(outline, brush = band, alpha = 0.6f * envelope, style = Stroke(4.dp.toPx()))
        // …and a bright thin filament on top.
        drawOutline(outline, brush = band, alpha = 0.95f * envelope, style = Stroke(1.6.dp.toPx()))
    }
}

/**
 * Colour stops for a sweep gradient holding a bright highlight band centred at
 * [head] (0..1 around the ring) with a short comet tail, plus a second fainter
 * band on the opposite side. Everything else is transparent so only the moving
 * light shows.
 */
private fun ringStops(
    head: Float,
    bright: Color,
    accent: Color
): Array<Pair<Float, Color>> {
    val stops = ArrayList<Pair<Float, Color>>(12)
    val core = 0.04f
    val tail = 0.14f

    fun addBand(centerPos: Float, peak: Color, scale: Float) {
        val pts = listOf(
            -tail to Color.Transparent,
            -core to accent.copy(alpha = 0.0f),
            0f to peak.copy(alpha = scale),
            core to accent.copy(alpha = 0.35f * scale),
            tail to Color.Transparent
        )
        for ((off, c) in pts) {
            var pos = centerPos + off
            pos -= kotlin.math.floor(pos)   // wrap into [0,1)
            stops.add(pos to c)
        }
    }

    addBand(head, bright, 1.0f)
    addBand(head + 0.5f, accent, 0.45f)

    stops.sortBy { it.first }
    if (stops.first().first > 0f) stops.add(0, 0f to Color.Transparent)
    if (stops.last().first < 1f) stops.add(1f to Color.Transparent)
    return stops.toTypedArray()
}
