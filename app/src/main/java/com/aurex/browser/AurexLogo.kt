package com.aurex.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
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
 * All gradients, paths, and strokes are cached per size in [drawWithCache] (0 allocations per frame).
 */
@Composable
fun AurexLogo(size: Dp, modifier: Modifier = Modifier, corner: Float = 0.2237f) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * corner))
            .drawWithCache {
                val px = this.size.minDimension
                val u = px / 72f
                fun x(v: Float): Float = (v - 18f) * u
                fun p(xv: Float, yv: Float): Offset = Offset(x(xv), x(yv))

                val bgMain = Brush.linearGradient(
                    0f to lc(0x66C8FF), 0.46f to lc(0x1A7DFF), 1f to lc(0x5A36E6),
                    start = p(0f, 0f), end = p(108f, 108f)
                )
                val bgTopLeft = Brush.radialGradient(
                    0f to lc(0xFFFFFF, .42f), 0.55f to lc(0xFFFFFF, .10f), 1f to lc(0xFFFFFF, 0f),
                    center = p(22f, 12f), radius = (74f * u).coerceAtLeast(1f)
                )
                val bgAurora = Brush.radialGradient(
                    0f to lc(0x2CF0D6, .62f), 0.5f to lc(0x2CF0D6, .20f), 1f to lc(0x2CF0D6, 0f),
                    center = p(6f, 104f), radius = (62f * u).coerceAtLeast(1f)
                )
                val bgViolet = Brush.radialGradient(
                    0f to lc(0xC58BFF, .38f), 1f to lc(0xC58BFF, 0f),
                    center = p(100f, 22f), radius = (46f * u).coerceAtLeast(1f)
                )
                val bgDepth = Brush.linearGradient(
                    0f to lc(0x06103A, 0f), 0.6f to lc(0x06103A, .03f), 1f to lc(0x06103A, .30f),
                    start = p(0f, 0f), end = p(0f, 108f)
                )

                val ax = 54f;   val ay = 35f
                val lx = 37.5f; val ly = 73f
                val rx = 70.5f; val ry = 73f
                val sw = 10.4f
                val ox = 54f;   val oy = 63.2f
                val orbR = 5.7f

                val shadowPath = Path().apply {
                    moveTo(x(lx), x(ly + 2.4f)); lineTo(x(ax), x(ay + 2.4f)); lineTo(x(rx), x(ry + 2.4f))
                }
                val mainPath = Path().apply {
                    moveTo(x(lx), x(ly)); lineTo(x(ax), x(ay)); lineTo(x(rx), x(ry))
                }
                val shadowColor = lc(0x050E6E, .05f)
                val shadowCenter = p(ox, oy + 2.4f)
                val shadowStrokes = Array(7) { i ->
                    Stroke(width = (sw + 0.95f * i) * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
                }
                val shadowRadii = FloatArray(7) { i -> (orbR + 0.95f * i * 0.5f) * u }
                val mainStroke = Stroke(width = sw * u, cap = StrokeCap.Round, join = StrokeJoin.Round)

                val body = Brush.linearGradient(
                    0f to lc(0xFFFFFF, .99f), 0.55f to lc(0xEEF8FF, .93f), 1f to lc(0xBFE0FF, .80f),
                    start = p(54f, ay - sw / 2f), end = p(54f, ly + sw / 2f)
                )
                val orbCenter = p(ox, oy)
                val orbHaloR = (orbR + 2.6f) * u
                val orbHalo = Brush.radialGradient(
                    0f to lc(0x3CF2DC, 0f), 0.55f to lc(0x3CF2DC, 0f), 0.7f to lc(0x3CF2DC, .22f), 1f to lc(0x3CF2DC, 0f),
                    center = orbCenter, radius = orbHaloR.coerceAtLeast(1f)
                )
                val orbFill = Brush.radialGradient(
                    0f to lc(0xFFFFFF), 0.28f to lc(0xA6FFF0), 0.72f to lc(0x25D8D0), 1f to lc(0x0FA7C4),
                    center = p(ox - 1.9f, oy - 2.3f), radius = (orbR * 1.55f * u).coerceAtLeast(1f)
                )
                val orbSpecCenter = p(ox - 1.8f, oy - 2.2f)
                val orbSpecR = 1.15f * u
                val orbSpecColor = lc(0xFFFFFF, .95f)

                val rimL = Path().apply { moveTo(x(35.43f), x(68.61f)); lineTo(x(50.61f), x(33.64f)) }
                val rimLBrush = Brush.linearGradient(
                    0f to lc(0xFFFFFF, 0f), 0.4f to lc(0xFFFFFF, .85f), 1f to lc(0xFFFFFF, .95f),
                    start = p(35.43f, 68.61f), end = p(50.61f, 33.64f)
                )
                val rimLStroke = Stroke(width = 1.1f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)

                val rimR = Path().apply { moveTo(x(58.54f), x(36.30f)); lineTo(x(73.77f), x(71.36f)) }
                val rimRBrush = Brush.linearGradient(
                    0f to lc(0x9FEFFF, 0f), 0.5f to lc(0x9FEFFF, .35f), 1f to lc(0x9FEFFF, .70f),
                    start = p(58.54f, 36.30f), end = p(73.77f, 71.36f)
                )
                val rimRStroke = Stroke(width = 1.0f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)

                onDrawBehind {
                    drawRect(bgMain)
                    drawRect(bgTopLeft)
                    drawRect(bgAurora)
                    drawRect(bgViolet)
                    drawRect(bgDepth)
                    for (i in 0..6) {
                        drawPath(shadowPath, shadowColor, style = shadowStrokes[i])
                        drawCircle(shadowColor, radius = shadowRadii[i], center = shadowCenter)
                    }
                    drawPath(mainPath, body, style = mainStroke)
                    drawCircle(orbHalo, radius = orbHaloR, center = orbCenter)
                    drawCircle(orbFill, radius = orbR * u, center = orbCenter)
                    drawCircle(orbSpecColor, radius = orbSpecR, center = orbSpecCenter)
                    drawPath(rimL, rimLBrush, style = rimLStroke)
                    drawPath(rimR, rimRBrush, style = rimRStroke)
                }
            }
    )
}

private fun lc(rgb: Int, a: Float = 1f): Color = Color((0xFF shl 24) or rgb).copy(alpha = a)

/** Draws the full-bleed logo art into a square of [px] pixels (kept for external callers). */
fun DrawScope.drawAurexLogoArt(px: Float) {
    val u = px / 72f
    fun x(v: Float): Float = (v - 18f) * u
    fun p(xv: Float, yv: Float): Offset = Offset(x(xv), x(yv))

    drawRect(Brush.linearGradient(0f to lc(0x66C8FF), 0.46f to lc(0x1A7DFF), 1f to lc(0x5A36E6), start = p(0f, 0f), end = p(108f, 108f)))
    drawRect(Brush.radialGradient(0f to lc(0xFFFFFF, .42f), 0.55f to lc(0xFFFFFF, .10f), 1f to lc(0xFFFFFF, 0f), center = p(22f, 12f), radius = (74f * u).coerceAtLeast(1f)))
    drawRect(Brush.radialGradient(0f to lc(0x2CF0D6, .62f), 0.5f to lc(0x2CF0D6, .20f), 1f to lc(0x2CF0D6, 0f), center = p(6f, 104f), radius = (62f * u).coerceAtLeast(1f)))
    drawRect(Brush.radialGradient(0f to lc(0xC58BFF, .38f), 1f to lc(0xC58BFF, 0f), center = p(100f, 22f), radius = (46f * u).coerceAtLeast(1f)))
    drawRect(Brush.linearGradient(0f to lc(0x06103A, 0f), 0.6f to lc(0x06103A, .03f), 1f to lc(0x06103A, .30f), start = p(0f, 0f), end = p(0f, 108f)))

    val ax = 54f;   val ay = 35f
    val lx = 37.5f; val ly = 73f
    val rx = 70.5f; val ry = 73f
    val sw = 10.4f
    val ox = 54f;   val oy = 63.2f
    val orbR = 5.7f
    val shadowPath = Path().apply { moveTo(x(lx), x(ly + 2.4f)); lineTo(x(ax), x(ay + 2.4f)); lineTo(x(rx), x(ry + 2.4f)) }
    val mainPath = Path().apply { moveTo(x(lx), x(ly)); lineTo(x(ax), x(ay)); lineTo(x(rx), x(ry)) }
    fun stroke(w: Float) = Stroke(width = w * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    for (i in 0..6) {
        val grow = 0.95f * i
        drawPath(shadowPath, lc(0x050E6E, .05f), style = stroke(sw + grow))
        drawCircle(lc(0x050E6E, .05f), radius = (orbR + grow * 0.5f) * u, center = p(ox, oy + 2.4f))
    }
    drawPath(mainPath, Brush.linearGradient(0f to lc(0xFFFFFF, .99f), 0.55f to lc(0xEEF8FF, .93f), 1f to lc(0xBFE0FF, .80f), start = p(54f, ay - sw / 2f), end = p(54f, ly + sw / 2f)), style = stroke(sw))
    drawCircle(Brush.radialGradient(0f to lc(0x3CF2DC, 0f), 0.55f to lc(0x3CF2DC, 0f), 0.7f to lc(0x3CF2DC, .22f), 1f to lc(0x3CF2DC, 0f), center = p(ox, oy), radius = ((orbR + 2.6f) * u).coerceAtLeast(1f)), radius = (orbR + 2.6f) * u, center = p(ox, oy))
    drawCircle(Brush.radialGradient(0f to lc(0xFFFFFF), 0.28f to lc(0xA6FFF0), 0.72f to lc(0x25D8D0), 1f to lc(0x0FA7C4), center = p(ox - 1.9f, oy - 2.3f), radius = (orbR * 1.55f * u).coerceAtLeast(1f)), radius = orbR * u, center = p(ox, oy))
    drawCircle(lc(0xFFFFFF, .95f), radius = 1.15f * u, center = p(ox - 1.8f, oy - 2.2f))
    val rimL = Path().apply { moveTo(x(35.43f), x(68.61f)); lineTo(x(50.61f), x(33.64f)) }
    drawPath(rimL, Brush.linearGradient(0f to lc(0xFFFFFF, 0f), 0.4f to lc(0xFFFFFF, .85f), 1f to lc(0xFFFFFF, .95f), start = p(35.43f, 68.61f), end = p(50.61f, 33.64f)), style = stroke(1.1f))
    val rimR = Path().apply { moveTo(x(58.54f), x(36.30f)); lineTo(x(73.77f), x(71.36f)) }
    drawPath(rimR, Brush.linearGradient(0f to lc(0x9FEFFF, 0f), 0.5f to lc(0x9FEFFF, .35f), 1f to lc(0x9FEFFF, .70f), start = p(58.54f, 36.30f), end = p(73.77f, 71.36f)), style = stroke(1.0f))
}
