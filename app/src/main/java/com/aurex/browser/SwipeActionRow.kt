package com.aurex.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Horizontal-first 120 FPS zero-allocation swipe row (Downloads, Bookmarks, History, Menu):
 *  - right -> red action panel (delete / hide); releasing past threshold slides the row out
 *  - left  -> accent action panel (share); releasing past threshold triggers [onShare] and springs back
 *
 * Uses strict directional slop locking (`1.3x slop`, horizontal > vertical * 1.45) and synchronous
 * draw-phase tracking (0 coroutine churn while dragging) so taps & vertical scrolling stay 100% silky.
 */
@Composable
fun SwipeActionRow(
    enabled: Boolean,
    deleteOn: Boolean,
    shareOn: Boolean,
    canShare: Boolean,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onShareBlocked: () -> Unit,
    modifier: Modifier = Modifier,
    cornerDp: Dp = 16.dp,
    deleteIcon: ImageVector = AppleIcons.Delete,
    deleteDesc: String = "Delete",
    content: @Composable () -> Unit
) {
    val style = LocalGlassStyle.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var liveX by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    val active = enabled && (deleteOn || shareOn)

    val onDeleteNow by rememberUpdatedState(onDelete)
    val onShareNow by rememberUpdatedState(onShare)
    val onBlockedNow by rememberUpdatedState(onShareBlocked)

    LaunchedEffect(active) {
        if (!active && (isDragging || offset.value != 0f)) {
            isDragging = false
            liveX = 0f
            offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f))
        }
    }

    val dir by remember {
        derivedStateOf {
            val x = if (isDragging) liveX else offset.value
            if (x > 0.5f) 1 else if (x < -0.5f) -1 else 0
        }
    }
    val popState = animateFloatAsState(
        if (armed) 1.20f else 1f,
        spring(dampingRatio = 0.48f, stiffness = 520f),
        label = "swipePop"
    )

    Box(
        modifier
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(active, deleteOn, shareOn, canShare) {
                if (!active) return@pointerInput
                val slop = viewConfiguration.touchSlop * 1.30f
                val tracker = VelocityTracker()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    tracker.resetTracking()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var accX = 0f
                    var accY = 0f
                    var dragging = false
                    armed = false
                    val threshold = (size.width * 0.28f).coerceAtLeast(1f)

                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val delta = ch.positionChange()
                        tracker.addPosition(ch.uptimeMillis, ch.position)

                        if (!dragging) {
                            if (ch.isConsumed) break
                            accX += delta.x
                            accY += delta.y
                            if (abs(accY) > slop && abs(accY) >= abs(accX)) break
                            if (abs(accX) > slop && abs(accX) > abs(accY) * 1.45f) {
                                if ((accX > 0f && !deleteOn) || (accX < 0f && !shareOn)) break
                                dragging = true
                                isDragging = true
                                ch.consume()
                                scope.launch { offset.stop() }
                                liveX = if (accX < 0f && !canShare) (accX * 0.3f).coerceIn(-threshold * 0.6f, 0f) else accX
                            }
                        } else {
                            ch.consume()
                            val cur = liveX
                            var next = cur + delta.x
                            if (next > 0f && !deleteOn) next = 0f
                            if (next < 0f && !shareOn) next = 0f
                            if (next < 0f && !canShare) next = (cur + delta.x * 0.3f).coerceIn(-threshold * 0.6f, 0f)
                            next = next.coerceIn(-size.width.toFloat(), size.width.toFloat())
                            val nowArmed = (deleteOn && next >= threshold) || (shareOn && canShare && next <= -threshold)
                            if (nowArmed && !armed) Haptics.fire(view, Hx.THRESHOLD)
                            armed = nowArmed
                            liveX = next
                        }
                    }

                    if (dragging) {
                        val x = liveX
                        val vx = tracker.calculateVelocity().x
                        armed = false
                        val flickRight = deleteOn && x > threshold * 0.45f && vx > 650f
                        val flickLeft = shareOn && canShare && x < -threshold * 0.45f && vx < -650f
                        scope.launch {
                            offset.snapTo(x)
                            isDragging = false
                            liveX = 0f
                            when {
                                (deleteOn && x >= threshold) || flickRight -> {
                                    Haptics.fire(view, Hx.WARN)
                                    offset.animateTo(size.width * 1.08f, tween(185, easing = FastOutLinearInEasing))
                                    onDeleteNow()
                                }
                                (shareOn && canShare && x <= -threshold) || flickLeft -> {
                                    Haptics.fire(view, Hx.SUCCESS)
                                    onShareNow()
                                    offset.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 440f))
                                }
                                shareOn && !canShare && x <= -threshold * 0.5f -> {
                                    onBlockedNow()
                                    offset.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 440f))
                                }
                                else -> offset.animateTo(0f, spring(dampingRatio = 0.74f, stiffness = 480f))
                            }
                        }
                    }
                }
            }
    ) {
        if (dir != 0) {
            val deleting = dir > 0
            val panel = if (deleting) Bad else if (canShare) style.accent else TextTertiary
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        val x = if (isDragging) liveX else offset.value
                        if (x != 0f) {
                            val w = (abs(x) + 18.dp.toPx()).coerceAtMost(size.width)
                            drawRoundRect(
                                color = panel,
                                topLeft = if (x > 0f) Offset(0f, 0f) else Offset(size.width - w, 0f),
                                size = Size(w, size.height),
                                cornerRadius = CornerRadius(cornerDp.toPx())
                            )
                        }
                    }
            ) {
                Icon(
                    if (deleting) deleteIcon else AppleIcons.Share,
                    if (deleting) deleteDesc else "Share",
                    tint = Color.White,
                    modifier = Modifier
                        .align(if (deleting) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = 22.dp)
                        .size(23.dp)
                        .graphicsLayer {
                            val x = if (isDragging) liveX else offset.value
                            val a = ((abs(x) - 14.dp.toPx()) / 48.dp.toPx()).coerceIn(0f, 1f)
                            alpha = a
                            val sc = (0.65f + 0.35f * a) * popState.value
                            scaleX = sc; scaleY = sc
                        }
                )
            }
        }
        Box(
            Modifier.graphicsLayer {
                val x = if (isDragging) liveX else offset.value
                if (x != 0f) {
                    translationX = x
                    if (x > 0f && widthPx > 0f) alpha = 1f - 0.65f * (x / widthPx).coerceIn(0f, 1f)
                }
            }
        ) { content() }
    }
}

/**
 * Bidirectional Apple Safari iOS 27 120 FPS swipe-to-close wrapper for Tab Grid cards.
 * Zero coroutine churn during active finger drag + strict directional slop locking.
 */
@Composable
fun SwipeTabCard(
    enabled: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    cornerDp: Dp = 26.dp,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var liveX by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    var armed by remember { mutableStateOf(false) }
    val onCloseNow by rememberUpdatedState(onClose)

    val dir by remember {
        derivedStateOf {
            val x = if (isDragging) liveX else offset.value
            if (x > 0.5f) 1 else if (x < -0.5f) -1 else 0
        }
    }
    val popState = animateFloatAsState(
        if (armed) 1.22f else 1f,
        spring(dampingRatio = 0.46f, stiffness = 520f),
        label = "tabSwipePop"
    )

    Box(
        modifier
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val slop = viewConfiguration.touchSlop * 1.30f
                val tracker = VelocityTracker()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    tracker.resetTracking()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var accX = 0f
                    var accY = 0f
                    var dragging = false
                    armed = false
                    val threshold = (size.width * 0.36f).coerceAtLeast(1f)

                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val delta = ch.positionChange()
                        tracker.addPosition(ch.uptimeMillis, ch.position)

                        if (!dragging) {
                            if (ch.isConsumed) break
                            accX += delta.x
                            accY += delta.y
                            if (abs(accY) > slop && abs(accY) >= abs(accX)) break
                            if (abs(accX) > slop && abs(accX) > abs(accY) * 1.45f) {
                                dragging = true
                                isDragging = true
                                ch.consume()
                                scope.launch { offset.stop() }
                                liveX = accX
                            }
                        } else {
                            ch.consume()
                            val next = (liveX + delta.x).coerceIn(-size.width * 1.3f, size.width * 1.3f)
                            val nowArmed = abs(next) >= threshold
                            if (nowArmed && !armed) Haptics.fire(view, Hx.THRESHOLD)
                            armed = nowArmed
                            liveX = next
                        }
                    }

                    if (dragging) {
                        val x = liveX
                        val vx = tracker.calculateVelocity().x
                        val direction = if (x >= 0f) 1f else -1f
                        val flick = abs(x) > threshold * 0.42f && vx * direction > 600f
                        armed = false
                        scope.launch {
                            offset.snapTo(x)
                            isDragging = false
                            liveX = 0f
                            if (abs(x) >= threshold || flick) {
                                Haptics.fire(view, Hx.WARN)
                                offset.animateTo(direction * size.width * 1.25f, tween(170, easing = FastOutLinearInEasing))
                                onCloseNow()
                            } else {
                                offset.animateTo(0f, spring(dampingRatio = 0.68f, stiffness = 480f))
                            }
                        }
                    }
                }
            }
    ) {
        if (dir != 0) {
            val closeBg = Bad
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        val x = if (isDragging) liveX else offset.value
                        val p = (abs(x) / widthPx).coerceIn(0f, 1f)
                        drawRoundRect(
                            color = closeBg.copy(alpha = (0.28f + 0.64f * p).coerceIn(0f, 0.92f)),
                            cornerRadius = CornerRadius(cornerDp.toPx())
                        )
                    }
            ) {
                Icon(
                    AppleIcons.Delete,
                    "Close tab",
                    tint = Color.White,
                    modifier = Modifier
                        .align(if (dir > 0) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = 22.dp)
                        .size(26.dp)
                        .graphicsLayer {
                            val x = if (isDragging) liveX else offset.value
                            val a = ((abs(x) - 14.dp.toPx()) / 42.dp.toPx()).coerceIn(0f, 1f)
                            alpha = a
                            val sc = (0.65f + 0.35f * a) * popState.value
                            scaleX = sc; scaleY = sc
                        }
                )
            }
        }
        Box(
            Modifier.graphicsLayer {
                val x = if (isDragging) liveX else offset.value
                if (x != 0f) {
                    val frac = (abs(x) / widthPx).coerceIn(0f, 1f)
                    translationX = x
                    rotationZ = (x / widthPx).coerceIn(-1.2f, 1.2f) * 6.5f
                    val sc = 1f - 0.045f * frac
                    scaleX = sc
                    scaleY = sc
                    alpha = (1f - 0.58f * frac).coerceIn(0.25f, 1f)
                }
            }
        ) { content() }
    }
}

/**
 * Interactive Apple iOS left-edge swipe-right back gesture (0-alloc synchronous drag,
 * narrow 18dp edge strip + 1.35x slop + 1.5x horizontal dominance so left-side taps NEVER miss!).
 */
fun Modifier.edgeSwipeBack(
    enabled: Boolean,
    edgeWidthDp: Dp = 18.dp,
    onBack: () -> Unit
): Modifier = composed {
    if (!enabled) return@composed this
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val localDensity = LocalDensity.current
    val edgePx = with(localDensity) { edgeWidthDp.toPx() }
    val dragX = remember { Animatable(0f) }
    var liveX by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val currentBack by rememberUpdatedState(onBack)

    this
        .pointerInput(enabled, edgePx) {
            val slop = viewConfiguration.touchSlop * 1.35f
            val tracker = VelocityTracker()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (down.position.x > edgePx) return@awaitEachGesture
                tracker.resetTracking()
                tracker.addPosition(down.uptimeMillis, down.position)
                var accX = 0f
                var accY = 0f
                var dragging = false
                var armed = false
                val threshold = (size.width * 0.24f).coerceAtLeast(1f)

                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Main)
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    val delta = ch.positionChange()
                    tracker.addPosition(ch.uptimeMillis, ch.position)

                    if (!dragging) {
                        if (ch.isConsumed) break
                        accX += delta.x
                        accY += delta.y
                        if (abs(accY) > slop && abs(accY) >= abs(accX)) break
                        if (accX < -slop) break
                        if (accX > slop && accX > abs(accY) * 1.5f) {
                            dragging = true
                            isDragging = true
                            ch.consume()
                            scope.launch { dragX.stop() }
                            liveX = accX
                        }
                    } else {
                        ch.consume()
                        val next = (liveX + delta.x).coerceIn(0f, size.width.toFloat())
                        val nowArmed = next >= threshold
                        if (nowArmed && !armed) Haptics.fire(view, Hx.THRESHOLD)
                        armed = nowArmed
                        liveX = next
                    }
                }

                if (dragging) {
                    val x = liveX
                    val vx = tracker.calculateVelocity().x
                    val commit = x >= threshold || (x > threshold * 0.40f && vx > 650f)
                    scope.launch {
                        dragX.snapTo(x)
                        isDragging = false
                        liveX = 0f
                        if (commit) {
                            dragX.animateTo(size.width.toFloat(), tween(160, easing = FastOutLinearInEasing))
                            currentBack()
                            dragX.snapTo(0f)
                        } else {
                            dragX.animateTo(0f, spring(dampingRatio = 0.74f, stiffness = 500f))
                        }
                    }
                }
            }
        }
        .graphicsLayer {
            val x = if (isDragging) liveX else dragX.value
            if (x > 0f) {
                translationX = x
                val f = (x / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
                alpha = (1f - 0.18f * f).coerceIn(0.6f, 1f)
            }
        }
}
