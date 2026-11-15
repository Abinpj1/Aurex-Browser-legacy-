package com.aurex.browser

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/**
 * The Aurex logo: a frosted-glass "A" (a lambda with a floating aqua orb instead of a crossbar) on a
 * blue -> violet liquid-glass tile with soft aurora glows.
 *
 * It is drawn from exactly the same numbers as the launcher icon (res/drawable/ic_launcher_*.xml,
 * 108 x 108 adaptive grid). The tile shows the central 72 x 72 part of that grid, the same window that
 * every launcher mask (circle / squircle / teardrop / square) shows, so the in-app logo and the home
 * screen icon always look identical.
 */
@Composable
fun AurexLogo(size: Dp, modifier: Modifier = Modifier, corner: Float = 0.2237f) {
    Canvas(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * corner))
    ) {
        drawAurexLogoArt(this.size.minDimension)
    }
}

private fun lc(rgb: Int, a: Float = 1f): Color = Color((0xFF shl 24) or rgb).copy(alpha = a)

/** Draws the full-bleed logo art into a square of [px] pixels (the 72 x 72 window of the 108 grid). */
fun DrawScope.drawAurexLogoArt(px: Float) {
    val u = px / 72f
    fun x(v: Float): Float = (v - 18f) * u
    fun p(xv: Float, yv: Float): Offset = Offset(x(xv), x(yv))

    // ── background tile ────────────────────────────────────────────────────────
    drawRect(
        Brush.linearGradient(
            0f to lc(0x66C8FF), 0.46f to lc(0x1A7DFF), 1f to lc(0x5A36E6),
            start = p(0f, 0f), end = p(108f, 108f)
        )
    )
    drawRect(   // soft light, top left
        Brush.radialGradient(
            0f to lc(0xFFFFFF, .42f), 0.55f to lc(0xFFFFFF, .10f), 1f to lc(0xFFFFFF, 0f),
            center = p(22f, 12f), radius = 74f * u
        )
    )
    drawRect(   // aurora, bottom left
        Brush.radialGradient(
            0f to lc(0x2CF0D6, .62f), 0.5f to lc(0x2CF0D6, .20f), 1f to lc(0x2CF0D6, 0f),
            center = p(6f, 104f), radius = 62f * u
        )
    )
    drawRect(   // violet, top right
        Brush.radialGradient(
            0f to lc(0xC58BFF, .38f), 1f to lc(0xC58BFF, 0f),
            center = p(100f, 22f), radius = 46f * u
        )
    )
    drawRect(   // depth at the bottom
        Brush.linearGradient(
            0f to lc(0x06103A, 0f), 0.6f to lc(0x06103A, .03f), 1f to lc(0x06103A, .30f),
            start = p(0f, 0f), end = p(0f, 108f)
        )
    )

    // ── glyph geometry (108 grid) ──────────────────────────────────────────────
    val ax = 54f;   val ay = 35f      // apex
    val lx = 37.5f; val ly = 73f      // left foot
    val rx = 70.5f; val ry = 73f      // right foot
    val sw = 10.4f                    // leg stroke
    val ox = 54f;   val oy = 63.2f    // orb centre
    val orbR = 5.7f                   // orb radius

    fun legs(dy: Float): Path = Path().apply {
        moveTo(x(lx), x(ly + dy)); lineTo(x(ax), x(ay + dy)); lineTo(x(rx), x(ry + dy))
    }
    fun stroke(w: Float) = Stroke(width = w * u, cap = StrokeCap.Round, join = StrokeJoin.Round)

    // soft, blur-free drop shadow: widening, faint copies pushed down
    for (i in 0..6) {
        val grow = 0.95f * i
        drawPath(legs(2.4f), lc(0x050E6E, .05f), style = stroke(sw + grow))
        drawCircle(lc(0x050E6E, .05f), radius = (orbR + grow * 0.5f) * u, center = p(ox, oy + 2.4f))
    }

    // frosted glass body
    val body = Brush.linearGradient(
        0f to lc(0xFFFFFF, .99f), 0.55f to lc(0xEEF8FF, .93f), 1f to lc(0xBFE0FF, .80f),
        start = p(54f, ay - sw / 2f), end = p(54f, ly + sw / 2f)
    )
    drawPath(legs(0f), body, style = stroke(sw))

    // glass orb: aqua sphere lit from the top left, with a hot specular dot and a faint halo
    drawCircle(
        Brush.radialGradient(
            0f to lc(0x3CF2DC, 0f), 0.55f to lc(0x3CF2DC, 0f), 0.7f to lc(0x3CF2DC, .22f), 1f to lc(0x3CF2DC, 0f),
            center = p(ox, oy), radius = (orbR + 2.6f) * u
        ),
        radius = (orbR + 2.6f) * u, center = p(ox, oy)
    )
    drawCircle(
        Brush.radialGradient(
            0f to lc(0xFFFFFF), 0.28f to lc(0xA6FFF0), 0.72f to lc(0x25D8D0), 1f to lc(0x0FA7C4),
            center = p(ox - 1.9f, oy - 2.3f), radius = orbR * 1.55f * u
        ),
        radius = orbR * u, center = p(ox, oy)
    )
    drawCircle(lc(0xFFFFFF, .95f), radius = 1.15f * u, center = p(ox - 1.8f, oy - 2.2f))

    // specular rim (top left edge) and cool refraction line (right edge)
    val rimL = Path().apply { moveTo(x(35.43f), x(68.61f)); lineTo(x(50.61f), x(33.64f)) }
    drawPath(
        rimL,
        Brush.linearGradient(
            0f to lc(0xFFFFFF, 0f), 0.4f to lc(0xFFFFFF, .85f), 1f to lc(0xFFFFFF, .95f),
            start = p(35.43f, 68.61f), end = p(50.61f, 33.64f)
        ),
        style = stroke(1.1f)
    )
    val rimR = Path().apply { moveTo(x(58.54f), x(36.30f)); lineTo(x(73.77f), x(71.36f)) }
    drawPath(
        rimR,
        Brush.linearGradient(
            0f to lc(0x9FEFFF, 0f), 0.5f to lc(0x9FEFFF, .35f), 1f to lc(0x9FEFFF, .70f),
            start = p(58.54f, 36.30f), end = p(73.77f, 71.36f)
        ),
        style = stroke(1.0f)
    )
}
