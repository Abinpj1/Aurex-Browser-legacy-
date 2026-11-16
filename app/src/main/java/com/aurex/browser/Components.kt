package com.aurex.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.math.max
import kotlin.math.sin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

// ── Drag handle + drag-to-reorder row (YouTube-style card drag) ──────────────

/**
 * A card row that is picked up after a long press and reorders vertically.
 * The parent owns the list: this composable only reports the move, so a single
 * reorder can never be applied twice. One shared [ReorderState] lets every
 * sibling card shift out of the way while a card is held, so the list feels
 * like physical cards being swapped instead of a text row being dragged.
 */
class ReorderState {
    var activeIndex by mutableIntStateOf(-1)
    var targetIndex by mutableIntStateOf(-1)
    var dragOffset by mutableFloatStateOf(0f)
    internal var rowHeight by mutableFloatStateOf(58f)

    internal fun reset() {
        activeIndex = -1
        targetIndex = -1
        dragOffset = 0f
    }
}

@Composable
fun <T> DragReorderRow(
    item: T,
    index: Int,
    items: List<T>,
    onReorder: (from: Int, to: Int) -> Unit,
    state: ReorderState = remember { ReorderState() },
    content: @Composable (T, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val step = state.rowHeight + with(density) { 4.dp.toPx() }
    val dragging = state.activeIndex == index
    val active = state.activeIndex
    val target = state.targetIndex
    val shiftTarget = when {
        active < 0 || dragging -> 0f
        index > active && index <= target -> -step
        index < active && index >= target -> step
        else -> 0f
    }
    val shift by androidx.compose.animation.core.animateFloatAsState(
        shiftTarget,
        spring(dampingRatio = 0.78f, stiffness = 620f),
        label = "cardShift"
    )
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (dragging) 1.035f else 1f,
        spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "cardPickup"
    )

    val dragModifier = Modifier
        .pointerInput(index, items.size) {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    state.activeIndex = index
                    state.targetIndex = index
                    state.dragOffset = 0f
                    performDragStart(view)
                },
                onDragCancel = { state.reset() },
                onDragEnd = {
                    val from = state.activeIndex
                    val to = state.targetIndex
                    state.reset()
                    Haptics.fire(view, Hx.DRAG_END)
                    if (from >= 0 && to >= 0 && from != to && items.isNotEmpty()) {
                        onReorder(from, to)
                    }
                },
                onDrag = { change, _ ->
                    state.dragOffset += change.position.y - change.previousPosition.y
                    val moved = (state.dragOffset / step.coerceAtLeast(1f)).roundToInt()
                    val newTarget = (index + moved).coerceIn(0, items.lastIndex.coerceAtLeast(0))
                    if (newTarget != state.targetIndex) performPress(view)   // a tick for every slot passed
                    state.targetIndex = newTarget
                    change.consume()
                }
            )
        }

    val dragLayer = Modifier
        .graphicsLayer {
            translationY = if (dragging) state.dragOffset else shift
            alpha = if (dragging) 0.97f else 1f
            scaleX = scale
            scaleY = scale
            shadowElevation = if (dragging) 22.dp.toPx() else 0f
        }

    Box(
        // Gesture sits outermost (stable coordinates); the layer wraps the glass
        // background so the whole card -- not just its content -- follows the finger.
        modifier = Modifier
            .then(dragModifier)
            .then(dragLayer)
            .then(modifier)
            .onGloballyPositioned { state.rowHeight = it.size.height.toFloat().coerceAtLeast(1f) }
    ) {
        content(item, dragging)
    }
}

// ── Buttons ── Neumorphism + Liquid Glass mix ────────────────────────────────

@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    iconSize: Dp = 20.dp,
    tint: Color = TextPrimary,
    filled: Boolean = true,
    accent: Boolean = false,
    enabled: Boolean = true,
    badge: String? = null
) {
    val style = LocalGlassStyle.current
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        val bg = when {
            accent -> Modifier.background(
                Brush.linearGradient(
                    listOf(style.accent, style.accent)
                ),
                CircleShape
            )
            filled -> Modifier.glassTile(CircleShape, 0.075f)
            else -> Modifier
        }
        GlowPress(
            onClick = onClick,
            modifier = Modifier.fillMaxSize().then(bg),
            shape = CircleShape,
            enabled = enabled
        ) {
            Icon(
                icon, contentDescription,
                tint = if (accent) onAccent(style.accent) else tint.copy(alpha = if (enabled) 1f else 0.38f),
                modifier = Modifier.size(iconSize)
            )
        }
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .heightIn(min = 16.dp)
                    .widthIn(min = 16.dp)
                    .background(style.accent, Capsule)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    badge, color = onAccent(style.accent), fontSize = 9.sp,
                    fontWeight = FontWeight.Bold, lineHeight = 10.sp
                )
            }
        }
    }
}

@Composable
fun GlassPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Boolean = false,
    accentColor: Color? = null,
    enabled: Boolean = true
) {
    val style = LocalGlassStyle.current
    val color = accentColor ?: style.accent
    val filledAccent = accent || accentColor != null
    val fg = if (filledAccent) onAccent(color) else TextPrimary
    val bg = if (filledAccent) {
        Modifier.background(color, Capsule)
    } else {
        Modifier.glassTile(Capsule, 0.10f)
    }
    GlowPress(
        onClick = onClick,
        modifier = modifier.then(bg),
        shape = Capsule,
        enabled = enabled
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(17.dp))
            Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── Toggle ── Neumorphic Apple-style Switch ──────────────────────────────────

@Composable
fun GlassSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null
) {
    val style = LocalGlassStyle.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickableHaptic(kind = if (checked) Hx.TOGGLE_OFF else Hx.TOGGLE_ON) { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(36.dp)
                    .glassTile(SmallShape, if (checked) 0.13f else 0.06f, if (checked) style.accent else Color.White),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon, null,
                    tint = if (checked) style.accent else TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, color = TextTertiary, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        GlassToggle(checked)
    }
}

// Liquid-glass switch (iOS 26 style): 51x31 track, system green when on, a white pill thumb that
// turns into a bigger clear glass lens while it is pressed and while it travels.
private val SwitchGreen = Color(0xFF34C759)
private val SwitchGreenDark = Color(0xFF30D158)

/**
 * The shared glass "lens" of the switch and the slider: a clear capsule with a soft depth shadow outside,
 * a faint body gradient, a top specular streak, a two-tone rim and a thin warm / cool colour fringe on the
 * edges (the cheap stand-in for real refraction). [amount] 0..1 fades and strengthens it.
 */
private fun DrawScope.drawGlassLens(cx: Float, cy: Float, w: Float, h: Float, amount: Float, dark: Boolean, low: Boolean) {
    val a = amount.coerceIn(0f, 1f)
    if (a < 0.01f) return
    val l = cx - w / 2f
    val t = cy - h / 2f
    val cr = CornerRadius(h / 2f)
    val lens = Path().apply { addRoundRect(RoundRect(l, t, l + w, t + h, cr)) }
    // depth shadow, only outside the lens so the glass stays clear
    if (!low) clipPath(lens, ClipOp.Difference) {
        val base = if (dark) 0.20f else 0.085f
        for (i in 1..3) {
            val g = i * 1.6.dp.toPx()
            drawRoundRect(
                Color.Black.copy(alpha = base / i * a),
                Offset(l - g, t - g + 2.2.dp.toPx()), Size(w + g * 2f, h + g * 2f), CornerRadius(h / 2f + g)
            )
        }
    }
    // body: clear glass, a little frosted at the top and bottom
    drawRoundRect(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = (if (dark) 0.20f else 0.55f) * a),
            0.45f to Color(0xFF8E8E93).copy(alpha = (if (dark) 0.02f else 0.05f) * a),
            1f to Color.White.copy(alpha = (if (dark) 0.14f else 0.32f) * a)
        ),
        Offset(l, t), Size(w, h), cr
    )
    // top specular streak
    drawRoundRect(
        Color.White.copy(alpha = (if (dark) 0.42f else 0.85f) * a),
        Offset(l + h * 0.46f, t + 1.6.dp.toPx()), Size((w - h * 0.92f).coerceAtLeast(1f), 1.4.dp.toPx()),
        CornerRadius(1.dp.toPx())
    )
    // colour fringe: warm on the right edge, cool on the left (low-end phones skip it)
    if (!low) {
        val f = 1.1.dp.toPx()
        drawRoundRect(
            Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFFFFB02E).copy(alpha = 0.55f * a), Color(0xFFFF4D7D).copy(alpha = 0.45f * a)), startX = l, endX = l + w),
            Offset(l + f * 0.7f, t - f * 0.4f), Size(w, h), cr, style = Stroke(1.2.dp.toPx())
        )
        drawRoundRect(
            Brush.horizontalGradient(listOf(Color(0xFF2EC8FF).copy(alpha = 0.50f * a), Color(0xFF7A6CFF).copy(alpha = 0.30f * a), Color.Transparent), startX = l, endX = l + w),
            Offset(l - f * 0.7f, t + f * 0.4f), Size(w, h), cr, style = Stroke(1.2.dp.toPx())
        )
    }
    // rim
    drawRoundRect(
        Brush.verticalGradient(
            0f to (if (dark) Color.White.copy(alpha = 0.60f * a) else Color.White.copy(alpha = 0.98f * a)),
            1f to (if (dark) Color.White.copy(alpha = 0.14f * a) else Color(0xFF8E8E93).copy(alpha = 0.42f * a))
        ),
        Offset(l, t), Size(w, h), cr, style = Stroke(0.9.dp.toPx())
    )
}

/** Visual only - the parent (GlassSwitchRow / a menu row) handles the tap. */
@Composable
fun GlassToggle(checked: Boolean) {
    val dark = LocalPalette.current.dark
    val low = PerfTier.low
    // Watches the finger without consuming it, so the parent's tap handling is untouched.
    var pressed by remember { mutableStateOf(false) }
    val p by animateFloatAsState(
        if (checked) 1f else 0f,
        spring(dampingRatio = 0.8f, stiffness = 700f),
        label = "switchP"
    )
    val press by animateFloatAsState(
        if (pressed) 1f else 0f,
        spring(dampingRatio = 0.62f, stiffness = 520f),
        label = "switchPress"
    )
    val pc = p.coerceIn(0f, 1f)
    // the lens also appears while the thumb is travelling, then melts back into the white pill
    val lensAmt = max(press, sin(Math.PI * pc).toFloat().coerceIn(0f, 1f) * 0.9f)
    val onColor = if (dark) SwitchGreenDark else SwitchGreen
    val offTrack = Color(0xFF787880).copy(alpha = if (dark) 0.36f else 0.16f)
    val track = androidx.compose.ui.graphics.lerp(offTrack, onColor, pc)
    Box(
        Modifier
            .size(width = 51.dp, height = 31.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        pressed = e.changes.any { it.pressed }
                    }
                }
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            drawRoundRect(track, Offset.Zero, Size(w, h), CornerRadius(h / 2f))
            val pillW = 33.dp.toPx()
            val pillH = 27.dp.toPx()
            val inset = 2.dp.toPx()
            val cx = inset + pillW / 2f + (w - inset * 2f - pillW) * p
            val cy = h / 2f
            // white pill thumb (soft shadow fades while the lens is out)
            val sh = (1f - lensAmt * 0.6f)
            if (!low) drawRoundRect(
                Color.Black.copy(alpha = 0.13f * sh), Offset(cx - pillW / 2f - 0.5.dp.toPx(), cy - pillH / 2f + 1.4.dp.toPx()),
                Size(pillW + 1.dp.toPx(), pillH + 0.5.dp.toPx()), CornerRadius(pillH / 2f)
            )
            drawRoundRect(Color.Black.copy(alpha = 0.06f * sh), Offset(cx - pillW / 2f - 0.5.dp.toPx(), cy - pillH / 2f - 0.5.dp.toPx()), Size(pillW + 1.dp.toPx(), pillH + 1.dp.toPx()), CornerRadius(pillH / 2f))
            drawRoundRect(Color.White, Offset(cx - pillW / 2f, cy - pillH / 2f), Size(pillW, pillH), CornerRadius(pillH / 2f))
            // glass lens, bigger than the pill, kept inside the track horizontally
            val lw = pillW + 11.dp.toPx() * lensAmt
            val lh = pillH + 7.dp.toPx() * lensAmt
            val lcx = cx.coerceIn(lw / 2f - 2.dp.toPx(), w - lw / 2f + 2.dp.toPx())
            drawGlassLens(lcx, cy, lw, lh, lensAmt, dark, low)
        }
    }
}

// ── Slider ── Liquid-glass slider: blue fill, grey rest, clear glass lens thumb ──────────────


/** Horizontal room kept free at both ends so the grown lens never leaves the slider's bounds. */
private val LensPad = 21.dp

/**
 * Liquid-glass slider (iOS 26 style): a thick bar with a blue fill, a white pill thumb with a small blue
 * dot at rest, and - while held - a clear glass lens that grows out of the pill, magnifies the bar inside
 * it, has a coloured edge fringe, and pulls the blue fill into a small liquid bulge at its edge.
 *
 * @param bufferedFraction  Optional 0..1 secondary fill drawn behind the main fill - used in the
 *                          video player to show how much of the stream is buffered. Pass 0 (the
 *                          default) for a plain slider without a buffer bar.
 */
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    bufferedFraction: Float = 0f,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val view = LocalView.current
    val dark = LocalPalette.current.dark
    val SliderBlue = if (dark) Color(0xFF0A84FF) else Color(0xFF007AFF)
    val low = PerfTier.low
    val span = remember(valueRange) {
        (valueRange.endInclusive - valueRange.start).takeIf { it != 0f } ?: 1f
    }
    val fraction by remember(value, valueRange, span) {
        derivedStateOf { ((value - valueRange.start) / span).coerceIn(0f, 1f) }
    }
    val bufFrac = bufferedFraction.coerceIn(0f, 1f)

    // While dragging the lens is glued to the finger; after release a spring catches up.
    var dragging by remember { mutableStateOf(false) }
    val thumbFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = if (dragging)
            spring(dampingRatio = 1f, stiffness = 6000f)
        else
            spring(dampingRatio = 0.75f, stiffness = 600f),
        label = "sliderThumb"
    )
    // 0 = resting, 1 = held (lens gets bigger)
    val grow by animateFloatAsState(
        if (dragging) 1f else 0f,
        spring(dampingRatio = 0.62f, stiffness = 520f),
        label = "sliderGrow"
    )

    val trackGrey = if (dark) Color.White.copy(alpha = 0.22f) else Color(0xFFD1D1D6)
    val bufferGrey = if (dark) Color.White.copy(alpha = 0.36f) else Color(0xFFB4B9C0)
    val ringColor = if (dark) Color.White.copy(alpha = 0.30f) else Color(0xFF7A828C).copy(alpha = 0.35f)

    // Map a touch x to a value. The lens centre travels between [pad, width - pad].
    var lastBucket by remember { mutableStateOf(Int.MIN_VALUE) }
    var atEnd by remember { mutableStateOf(false) }
    fun update(x: Float, width: Float, pad: Float) {
        val usable = (width - pad * 2f).coerceAtLeast(1f)
        val f = ((x - pad) / usable).coerceIn(0f, 1f)
        val snapped = if (steps > 0) (f * (steps + 1)).roundToInt().toFloat() / (steps + 1) else f
        // haptics: a tick per step (or per 5 % on a smooth slider), a firmer click at either end
        val bucket = if (steps > 0) (f * (steps + 1)).roundToInt() else (f * 20f).roundToInt()
        val end = f <= 0f || f >= 1f
        if (end && !atEnd) performThreshold(view)
        else if (bucket != lastBucket && lastBucket != Int.MIN_VALUE && !end) performPress(view)
        lastBucket = bucket
        atEnd = end
        onValueChange(valueRange.start + snapped * span)
    }

    // 44 dp tall hit area around the 34 dp visual
    Box(
        modifier
            .height(44.dp)
            .pointerInput(valueRange, steps) {
                val pad = LensPad.toPx()
                detectTapGestures { off ->
                    update(off.x, size.width.toFloat(), pad)
                    onValueChangeFinished?.invoke()
                }
            }
            .pointerInput(valueRange, steps) {
                val pad = LensPad.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { off ->
                        dragging = true
                        update(off.x, size.width.toFloat(), pad)
                        performPress(view)
                    },
                    onDragEnd = {
                        dragging = false
                        onValueChangeFinished?.invoke()
                    },
                    onDragCancel = {
                        dragging = false
                        onValueChangeFinished?.invoke()
                    }
                ) { change: PointerInputChange, _ ->
                    update(change.position.x, size.width.toFloat(), pad)
                    change.consume()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxWidth().height(44.dp)) {
            // Liquid-glass slider: thick bar, white pill thumb at rest that grows into a clear glass lens
            // while held (the bar is magnified inside it) and the blue fill bulges into the lens edge.
            val g = grow.coerceIn(0f, 1.12f)
            val ga = grow.coerceIn(0f, 1f)
            val pad = LensPad.toPx()
            val usable = (size.width - pad * 2f).coerceAtLeast(1f)
            val cy = size.height / 2f
            val thumbX = pad + usable * thumbFraction
            val barH = 6.dp.toPx()
            val trackL = pad
            val trackR = (size.width - pad).coerceAtLeast(trackL + 1f)
            val barR = CornerRadius(barH / 2f)
            drawRoundRect(trackGrey, Offset(trackL, cy - barH / 2f), Size(trackR - trackL, barH), barR)
            if (bufFrac > 0.002f) {
                val bx = trackL + (trackR - trackL) * bufFrac
                drawRoundRect(bufferGrey, Offset(trackL, cy - barH / 2f), Size(bx - trackL, barH), barR)
            }
            drawRoundRect(
                SliderBlue,
                Offset(trackL, cy - barH / 2f),
                Size((thumbX - trackL).coerceAtLeast(0f), barH),
                barR
            )

            val restW = 30.dp.toPx(); val restH = 20.dp.toPx()
            val lensW = 42.dp.toPx(); val lensH = 32.dp.toPx()
            val w = restW + (lensW - restW) * g
            val h = restH + (lensH - restH) * g
            val left = thumbX - w / 2f

            // liquid bulge where the blue fill runs into the lens
            val flareLen = 10.dp.toPx() * ga
            if (ga > 0.02f && left - trackL > flareLen) {
                val fh = barH * (1f + 0.95f * ga)
                val x0 = left - flareLen
                val bulge = Path().apply {
                    moveTo(x0, cy - barH / 2f)
                    cubicTo(x0 + flareLen * 0.55f, cy - barH / 2f, left - flareLen * 0.30f, cy - fh / 2f, left + 2.dp.toPx(), cy - fh / 2f)
                    lineTo(left + 2.dp.toPx(), cy + fh / 2f)
                    cubicTo(left - flareLen * 0.30f, cy + fh / 2f, x0 + flareLen * 0.55f, cy + barH / 2f, x0, cy + barH / 2f)
                    close()
                }
                drawPath(bulge, SliderBlue)
            }

            // resting thumb: white pill with a small blue dot
            val restA = (1f - ga).coerceIn(0f, 1f)
            if (restA > 0.01f) {
                val rl = thumbX - w / 2f; val rt = cy - h / 2f
                if (!low) drawRoundRect(Color.Black.copy(alpha = 0.14f * restA), Offset(rl - 0.5.dp.toPx(), rt + 1.5.dp.toPx()), Size(w + 1.dp.toPx(), h + 0.5.dp.toPx()), CornerRadius(h / 2f))
                drawRoundRect(Color.Black.copy(alpha = 0.06f * restA), Offset(rl - 0.5.dp.toPx(), rt - 0.5.dp.toPx()), Size(w + 1.dp.toPx(), h + 1.dp.toPx()), CornerRadius(h / 2f))
                drawRoundRect(Color.White.copy(alpha = restA), Offset(rl, rt), Size(w, h), CornerRadius(h / 2f))
                drawCircle(SliderBlue.copy(alpha = restA), 2.2.dp.toPx(), Offset(thumbX, cy))
            }

            // held: the bar is magnified inside the glass lens
            if (ga > 0.02f) {
                val lens = Path().apply { addRoundRect(RoundRect(left, cy - h / 2f, left + w, cy + h / 2f, CornerRadius(h / 2f))) }
                clipPath(lens) {
                    val mh = barH * (1f + 0.45f * ga)
                    drawRoundRect(trackGrey.copy(alpha = trackGrey.alpha * ga), Offset(left, cy - mh / 2f), Size(w, mh), CornerRadius(mh / 2f))
                    val fw = (thumbX - left).coerceAtLeast(0f)
                    drawRect(SliderBlue.copy(alpha = ga), Offset(left, cy - mh / 2f), Size(fw, mh))
                }
            }
            drawGlassLens(thumbX, cy, w, h, ga, dark, low)
        }
    }
}

// ── Segmented / Chips ────────────────────────────────────────────────────────

/**
 * Adaptive segmented control — labels wrap to their text width so the pill
 * stays compact on short labels (e.g. "Tabs") and expands for longer ones.
 * The selection indicator cross-fades in with an accent bloom instead of an
 * abrupt background swap, matching the liquid glass motion language.
 */
@Composable
fun GlassSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val style = LocalGlassStyle.current
    Row(
        modifier
            .height(40.dp)
            // Adaptive: the pill breathes when option labels change length
            .animateContentSize(spring(dampingRatio = 0.80f, stiffness = 440f))
            .liquidGlass(Capsule, 0.72f, 0f)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { i, label ->
            val active = selected == i
            // Animate selection bg alpha for a smooth cross-fade, not a pop
            val bgAlpha by animateFloatAsState(
                if (active) 1f else 0f,
                spring(dampingRatio = 0.72f, stiffness = 480f),
                label = "segBg$i"
            )
            val textAlpha by animateFloatAsState(
                if (active) 1f else 0.55f,
                spring(dampingRatio = 0.72f, stiffness = 480f),
                label = "segText$i"
            )
            GlowPress(
                onClick = { onSelect(i) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (bgAlpha > 0.01f)
                            Modifier
                                .shadow(if (style.dark) 0.dp else 2.dp, Capsule, clip = false,
                                    ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.14f))
                                .background((if (style.dark) Color(0xFF636366).copy(alpha = 0.70f) else Color.White).copy(alpha = bgAlpha.coerceIn(0f, 1f)), Capsule)
                        else Modifier
                    ),
                shape = Capsule
            ) {
                Text(
                    label,
                    // The spring overshoots 1.0 (to ~1.016) a moment after a tap; Color.copy() throws
                    // "alpha outside the range for sRGB" for that, which closed the whole app.
                    color = TextPrimary.copy(alpha = textAlpha.coerceIn(0f, 1f)),
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

// ── Settings / rows ──────────────────────────────────────────────────────────

/**
 * iOS 27 inset-grouped list row. Rows of one list share a single rounded card: only the first row
 * rounds its top corners and only the last rounds its bottom ones, with a hairline between rows that
 * starts after the leading icon ([dividerInset]).
 */
@Composable
fun GroupedRow(
    index: Int,
    count: Int,
    onClick: (() -> Unit)? = null,
    dividerInset: Dp = 62.dp,
    content: @Composable RowScope.() -> Unit
) {
    val r = 26.dp
    val shape = RoundedCornerShape(
        topStart = if (index == 0) r else 0.dp,
        topEnd = if (index == 0) r else 0.dp,
        bottomStart = if (index == count - 1) r else 0.dp,
        bottomEnd = if (index == count - 1) r else 0.dp
    )
    Column(Modifier.fillMaxWidth().groupedSurface(shape)) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickableHaptic(onClick = onClick) else Modifier)
                .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
        if (index < count - 1) {
            Box(
                Modifier
                    .padding(start = dividerInset)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(Separator)
            )
        }
    }
}

// ── Visual bits ──────────────────────────────────────────────────────────────

@Composable
fun GlassProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp
) {
    val style = LocalGlassStyle.current
    // Smooth glide between updates instead of jumping; a 1 % download still shows a round dot.
    val p by animateFloatAsState(
        progress.coerceIn(0f, 1f), tween(450, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "progress"
    )
    // A white track vanishes on light surfaces: use a neutral system-fill grey there.
    val trackColor = if (LocalPalette.current.dark) Color.White.copy(alpha = 0.10f)
                     else Color(0xFF787880).copy(alpha = 0.22f)
    Canvas(modifier.fillMaxWidth().height(height)) {
        val h = size.height
        val cr = CornerRadius(h / 2)
        drawRoundRect(trackColor, cornerRadius = cr)
        if (p > 0.002f) {
            val w = maxOf(h, size.width * p)
            drawRoundRect(
                Brush.horizontalGradient(
                    listOf(style.accent.copy(alpha = 0.82f), style.accent, androidx.compose.ui.graphics.lerp(style.accent, Color.White, 0.30f)),
                    endX = w
                ),
                size = Size(w, h),
                cornerRadius = cr
            )
            // soft glass highlight along the top edge of the fill
            if (h >= 6f) drawRoundRect(
                Color.White.copy(alpha = 0.22f),
                topLeft = androidx.compose.ui.geometry.Offset(h * 0.4f, h * 0.14f),
                size = Size(maxOf(0f, w - h * 0.8f), h * 0.26f),
                cornerRadius = CornerRadius(h * 0.13f)
            )
        }
    }
}

@Composable
fun SiteBadge(host: String, size: Dp = 38.dp, letter: String? = null, bitmap: Bitmap? = null) {
    val ctx = LocalContext.current
    val colors = listOf(
        Color(0xFF0A84FF), Color(0xFF40C8E0), Color(0xFFBF5AF2),
        Color(0xFFFF375F), Color(0xFFFF9F0A), Color(0xFF30D158)
    )
    val c = colors[abs(host.hashCode()) % colors.size]
    val shape = RoundedCornerShape(size * 0.32f)
    var icon by remember(host, bitmap) {
        mutableStateOf(bitmap ?: Favicons.cached(host))
    }
    LaunchedEffect(host, bitmap) {
        if (bitmap != null) {
            icon = bitmap
        } else if (icon == null) {
            icon = Favicons.load(ctx, host)
        }
    }
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(c.copy(alpha = 0.92f), c.copy(alpha = 0.48f))))
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.16f),
                    0.55f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.10f)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        val bmp = icon
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(size * 0.72f).clip(RoundedCornerShape(size * 0.16f)),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                letter?.take(1)?.uppercase() ?: host.trim().firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontSize = (size.value * 0.40f).sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 46.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(74.dp).glassTile(CircleShape, 0.06f),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = TextTertiary, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(5.dp))
        Text(subtitle, color = TextTertiary, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

/**
 * Adaptive Liquid Glass NavBar — the same material as the main toolbar pill.
 *
 * The pill:
 *  • Springs into place from above on first compose (no jarring pop-in).
 *  • Uses animateContentSize so it breathes when the subtitle count updates
 *    or trailing controls appear/disappear — exactly like the adaptive toolbar.
 *  • Subtitle fades + expands in; fades + shrinks out without layout jumps.
 *
 * Every panel (Tabs, Bookmarks, History, Downloads, Media, Settings) shares
 * this single composable so they all look and move identically.
 */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String?,
    onClose: () -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    // iOS 27 navigation: floating glass controls on top, Large Title underneath (no big capsule).
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        enter.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 420f))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enter.value.coerceIn(0f, 1f)
                translationY = -12.dp.toPx() * (1f - enter.value)
            }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(Modifier.liquidGlass(CircleShape, 0.9f, 0.04f)) {
                GlassIconButton(
                    AppleIcons.ArrowBack, "Back",
                    onClick = onClose, size = 44.dp, iconSize = 20.dp, filled = false
                )
            }
            if (trailing != null) {
                Row(
                    Modifier
                        .animateContentSize(spring(dampingRatio = 0.80f, stiffness = 440f))
                        .liquidGlass(Capsule, 0.9f, 0.04f)
                        .padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) { trailing() }
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 10.dp)) {
            Text(
                title, color = TextPrimary, fontSize = 34.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            AnimatedVisibility(
                visible = subtitle != null,
                enter = fadeIn(tween(220)) +
                        expandVertically(spring(dampingRatio = 0.78f, stiffness = 480f)),
                exit  = fadeOut(tween(140)) + shrinkVertically(tween(160))
            ) {
                Text(
                    subtitle.orEmpty(), color = TextSecondary, fontSize = 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun GlassConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Confirm",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(scrim(0.60f))
            .clickableHaptic(haptic = false, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(max = 380.dp)
                .liquidGlass(CardShape, 1.1f)
                .clickableHaptic(haptic = false) {}
                .padding(20.dp)
        ) {
            Text(title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(message, color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                GlassPillButton("Cancel", onDismiss)
                GlassPillButton(
                    confirmLabel, onConfirm,
                    accent = !destructive,
                    accentColor = if (destructive) Bad else null
                )
            }
        }
    }
}

// ── Small utilities ──────────────────────────────────────────────────────────

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

fun formatSpeed(bps: Long): String = if (bps <= 0) "" else formatBytes(bps) + "/s"

fun hostOf(url: String): String {
    if (url.isBlank()) return ""
    return runCatching { Uri.parse(url).host ?: url }.getOrDefault(url).removePrefix("www.")
}

fun prettyUrl(url: String): String {
    if (url.isBlank()) return ""
    return url
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .trimEnd('/')
        .ifBlank { hostOf(url) }
}

fun timeAgo(time: Long): String {
    val diff = (System.currentTimeMillis() - time).coerceAtLeast(0)
    val m = TimeUnit.MILLISECONDS.toMinutes(diff)
    return when {
        m < 1 -> "Just now"
        m < 60 -> "${m}m ago"
        m < 1440 -> "${m / 60}h ago"
        m < 10080 -> "${m / 1440}d ago"
        else -> "${m / 10080}w ago"
    }
}

private fun decodedUrl(url: String): String =
    runCatching { java.net.URLDecoder.decode(url, "UTF-8") }.getOrDefault(url).lowercase()

/**
 * Direct media detection. Sites like YouTube serve streams from
 * googlevideo.com/videoplayback with no file extension at all, so checking the
 * path alone missed every one of them.
 */
private val MEDIA_EXT_RE =
    Regex("""\.(m3u8|mpd|mp4|webm|mkv|m4v|mov|ts|mp3|m4a|aac|ogg|oga|opus|flac|wav)(\?|#|$)""")
private val MEDIA_MIME_RE = Regex("""[?&](mime|type)=(video|audio)/""")
private val MEDIA_FMT_RE = Regex("""[?&](format|type)=(m3u8|mpd|mp4|webm|mpegurl)""")

fun isMediaUrl(url: String): Boolean {
    if (url.isBlank()) return false
    val lower = url.lowercase()
    if (lower.startsWith("blob:") || lower.startsWith("data:")) return false
    if (MEDIA_EXT_RE.containsMatchIn(lower)) return true
    if (lower.contains("googlevideo.com/videoplayback") ||
        lower.contains("manifest.googlevideo.com") ||
        lower.contains("googlevideo.com/api/manifest")) return true
    if (lower.contains("m3u8") || lower.contains("format=mpd")) return true
    // Instagram / Facebook CDN — fbcdn.net and cdninstagram.com deliver .mp4 files
    // with long signed-URL paths that contain no extension hint in the last path segment.
    if ((lower.contains(".fbcdn.net") || lower.contains("cdninstagram.com")) &&
        (lower.contains(".mp4") || lower.contains(".m4v") || lower.contains("video"))) return true
    // Only URLs that actually carry a mime/format hint pay for decoding.
    if (!lower.contains("mime") && !lower.contains("type=") && !lower.contains("format=")) return false
    val decoded = if (lower.contains('%')) decodedUrl(url) else lower
    return MEDIA_MIME_RE.containsMatchIn(decoded) || MEDIA_FMT_RE.containsMatchIn(decoded)
}

private val AUDIO_EXT_RE = Regex("""\.(mp3|m4a|aac|ogg|oga|opus|flac|wav)(\?|#|$)""")
private val AUDIO_MIME_RE = Regex("""[?&]mime=audio/""")
private val ITAG_RE = Regex("""[?&]itag=\d+""")
private val TS_EXT_RE = Regex("""\.ts(\?|#|$)""")

fun mediaKind(url: String): String {
    val lower = decodedUrl(url)
    val path = url.lowercase()
    return when {
        lower.contains(".m3u8") || lower.contains("mpegurl") -> "HLS"
        lower.contains(".mpd") || lower.contains("dash+xml") -> "DASH"
        AUDIO_EXT_RE.containsMatchIn(path) -> "AUDIO"
        AUDIO_MIME_RE.containsMatchIn(lower) -> "AUDIO"
        else -> "VIDEO"
    }
}

/** Larger score = better candidate for the built-in player. */
fun mediaScore(url: String): Int {
    val lower = url.lowercase()
    if (lower.startsWith("blob:") || lower.startsWith("data:")) return -1000
    if (lower.contains("thumb") || lower.contains("poster") || lower.contains("sprite") ||
        lower.contains("storyboard") || lower.contains("preview")) return -300
    val decoded = decodedUrl(url)
    var score = when {
        lower.contains(".m3u8") || lower.contains("mpegurl") -> 130
        lower.contains(".mpd") -> 125
        lower.contains(".mp4") || lower.contains(".m4v") || lower.contains(".mov") -> 115
        lower.contains(".webm") -> 110
        lower.contains(".mkv") -> 105
        lower.contains("videoplayback") -> 100
        else -> 70
    }
    if (decoded.contains("mime=video/")) score += 14
    if (decoded.contains("mime=audio/")) score -= 26
    if (ITAG_RE.containsMatchIn(decoded)) score += 6
    if (lower.contains("&range=") || lower.contains("?range=")) score -= 20
    if (TS_EXT_RE.containsMatchIn(lower)) score -= 45
    if (mediaKind(url) == "AUDIO") score -= 18
    return score
}
