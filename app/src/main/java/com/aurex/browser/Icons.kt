package com.aurex.browser

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

enum class SafariGlyph {
    Back, Forward, Tabs, Download, More, Home, Lock, Search, Close, Share, Bookmark
}

/** Hand-drawn Safari-style glyphs: thin, rounded, perfectly geometric. */
@Composable
fun SafariGlyph(
    glyph: SafariGlyph,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    tint: Color = TextPrimary,
    strokeWidth: Dp = 1.9.dp
) {
    Canvas(modifier.size(size)) {
        val s = min(this.size.width, this.size.height)
        val w = strokeWidth.toPx()
        val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(vararg points: Pair<Float, Float>): Path = Path().apply {
            points.forEachIndexed { i, (x, y) ->
                if (i == 0) moveTo(x * s, y * s) else lineTo(x * s, y * s)
            }
        }

        when (glyph) {
            SafariGlyph.Back -> {
                drawPath(p(0.63f to 0.20f, 0.36f to 0.50f, 0.63f to 0.80f), tint, style = stroke)
            }
            SafariGlyph.Forward -> {
                drawPath(p(0.37f to 0.20f, 0.64f to 0.50f, 0.37f to 0.80f), tint, style = stroke)
            }
            SafariGlyph.Tabs -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(0.11f * s, 0.20f * s),
                    size = Size(0.78f * s, 0.60f * s),
                    cornerRadius = CornerRadius(0.15f * s),
                    style = stroke
                )
            }
            SafariGlyph.Download -> {
                drawPath(p(0.50f to 0.15f, 0.50f to 0.61f), tint, style = stroke)
                drawPath(p(0.34f to 0.46f, 0.50f to 0.63f, 0.66f to 0.46f), tint, style = stroke)
                drawPath(p(0.20f to 0.72f, 0.20f to 0.82f, 0.80f to 0.82f, 0.80f to 0.72f), tint, style = stroke)
            }
            SafariGlyph.More -> {
                val r = 0.065f * s
                listOf(0.24f, 0.50f, 0.76f).forEach { x ->
                    drawCircle(tint, radius = r, center = Offset(x * s, 0.50f * s))
                }
            }
            SafariGlyph.Home -> {
                drawPath(p(0.18f to 0.48f, 0.50f to 0.18f, 0.82f to 0.48f), tint, style = stroke)
                drawPath(
                    p(0.27f to 0.44f, 0.27f to 0.82f, 0.73f to 0.82f, 0.73f to 0.44f),
                    tint, style = stroke
                )
            }
            SafariGlyph.Lock -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(0.24f * s, 0.44f * s),
                    size = Size(0.52f * s, 0.38f * s),
                    cornerRadius = CornerRadius(0.10f * s),
                    style = stroke
                )
                val arcSize = Size(0.36f * s, 0.38f * s)
                drawArc(
                    color = tint,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(0.32f * s, 0.16f * s),
                    size = arcSize,
                    style = stroke
                )
            }
            SafariGlyph.Search -> {
                drawCircle(tint, radius = 0.24f * s, center = Offset(0.44f * s, 0.44f * s), style = stroke)
                drawPath(p(0.62f to 0.62f, 0.82f to 0.82f), tint, style = stroke)
            }
            SafariGlyph.Close -> {
                drawPath(p(0.28f to 0.28f, 0.72f to 0.72f), tint, style = stroke)
                drawPath(p(0.72f to 0.28f, 0.28f to 0.72f), tint, style = stroke)
            }
            SafariGlyph.Share -> {
                drawPath(p(0.50f to 0.72f, 0.50f to 0.20f), tint, style = stroke)
                drawPath(p(0.34f to 0.35f, 0.50f to 0.19f, 0.66f to 0.35f), tint, style = stroke)
                drawPath(
                    p(0.24f to 0.46f, 0.24f to 0.82f, 0.76f to 0.82f, 0.76f to 0.46f),
                    tint, style = stroke
                )
            }
            SafariGlyph.Bookmark -> {
                drawPath(
                    p(0.30f to 0.16f, 0.70f to 0.16f, 0.70f to 0.84f, 0.50f to 0.66f, 0.30f to 0.84f, 0.30f to 0.16f),
                    tint, style = stroke
                )
            }
        }
    }
}

/** Safari-style glass button used across the chrome and panels. */
@Composable
fun SafariButton(
    glyph: SafariGlyph,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    glyphSize: Dp = 20.dp,
    enabled: Boolean = true,
    accent: Boolean = false,
    badge: String? = null,
    tint: Color? = null
) {
    val style = LocalGlassStyle.current
    val description = contentDescription
    Box(
        modifier
            .size(size)
            .semantics {
                this.contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        val bg = if (accent) {
            Modifier.background(
                Brush.linearGradient(listOf(style.accent, style.accent)),
                CircleShape
            )
        } else {
            Modifier.glassTile(CircleShape, 0.075f)
        }
        GlowPress(
            onClick = onClick,
            modifier = Modifier.fillMaxSize().then(bg),
            shape = CircleShape,
            enabled = enabled
        ) {
            SafariGlyph(
                glyph,
                size = glyphSize,
                tint = if (accent) onAccent(style.accent) else (tint ?: TextPrimary),
                strokeWidth = if (size < 34.dp) 1.7.dp else 1.9.dp
            )
        }
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .heightIn(min = 15.dp)
                    .widthIn(min = 15.dp)
                    .background(style.accent, Capsule)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    badge, color = onAccent(style.accent), fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold, lineHeight = 10.sp
                )
            }
        }
    }
}

/**
 * The tab control is a rounded rectangle with the live tab count inside,
 * exactly like the reference the user asked for.
 */
@Composable
fun TabCounterButton(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    tint: Color = TextPrimary,
    onLongClick: (() -> Unit)? = null
) {
    Box(
        modifier
            .size(size)
            .semantics {
                contentDescription = "Tabs, $count open"
                role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        GlowPress(
            onClick = onClick,
            modifier = Modifier.fillMaxSize().glassTile(CircleShape, 0.075f),
            shape = CircleShape,
            onLongClick = onLongClick
        ) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(size * 0.54f)) {
                    val s = this.size.minDimension
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(0.06f * s, 0.13f * s),
                        size = Size(0.88f * s, 0.74f * s),
                        cornerRadius = CornerRadius(0.20f * s),
                        style = Stroke(
                            width = (if (size < 34.dp) 1.6f else 1.8f) * density,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
                Text(
                    if (count > 99) "99+" else "$count",
                    color = tint,
                    fontSize = if (count > 9) (size.value * 0.19f).sp else (size.value * 0.22f).sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = (size.value * 0.22f).sp,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Aurex logo mark used on the start page (and anywhere a small brand mark is needed).
 * Same artwork as the launcher icon, see [AurexLogo].
 */
@Composable
fun AurexMark(size: Dp = 44.dp, modifier: Modifier = Modifier) {
    AurexLogo(size, modifier)
}

/**
 * Menu (three dots) button, iOS 27 style: a glossy accent bubble with a light top-to-bottom gradient,
 * a thin specular rim, a soft accent glow under it and a rounder, slightly larger ellipsis.
 */
@Composable
fun MenuDotsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    onLongClick: (() -> Unit)? = null
) {
    val style = LocalGlassStyle.current
    val top = androidx.compose.ui.graphics.lerp(style.accent, Color.White, 0.24f)
    Box(
        modifier
            .size(size)
            .semantics {
                contentDescription = "Menu"
                role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        // soft accent glow under the bubble
        Box(
            Modifier
                .fillMaxSize()
                .padding(1.5.dp)
                .offset(y = 1.5.dp)
                .background(style.accent.copy(alpha = 0.30f), CircleShape)
        )
        GlowPress(
            onClick = onClick,
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(top, style.accent)), CircleShape)
                .border(
                    0.75.dp,
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.60f), Color.White.copy(alpha = 0.06f))),
                    CircleShape
                ),
            shape = CircleShape,
            onLongClick = onLongClick
        ) {
            Canvas(Modifier.size(size * 0.52f)) {
                val s = this.size.minDimension
                val r = 0.085f * s
                listOf(0.20f, 0.50f, 0.80f).forEach { x ->
                    drawCircle(Color.White, radius = r, center = Offset(x * s, 0.5f * s))
                }
            }
        }
    }
}
