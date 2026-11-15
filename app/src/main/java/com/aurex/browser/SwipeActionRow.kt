package com.aurex.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A row you can swipe sideways, like a mail list:
 *  - right  -> red "delete" panel; letting go past the threshold slides the row out and calls [onDelete] (no confirmation)
 *  - left   -> accent "share" panel; letting go past the threshold calls [onShare] and the row springs back
 *
 * A direction that is switched off ([deleteOn] / [shareOn]) simply does not move. When sharing is on but the
 * item cannot be shared yet ([canShare] = false) the row only gives a little, rubber-band resistance and
 * [onShareBlocked] runs instead. Everything is disabled while [enabled] is false (e.g. while selecting).
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
    content: @Composable () -> Unit
) {
    val style = LocalGlassStyle.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    val active = enabled && (deleteOn || shareOn)

    val onDeleteNow by rememberUpdatedState(onDelete)
    val onShareNow by rememberUpdatedState(onShare)
    val onBlockedNow by rememberUpdatedState(onShareBlocked)

    // switched off (or selecting started) in the middle of a swipe: put the row back
    LaunchedEffect(active) { if (!active && offset.value != 0f) offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f)) }

    // -1 = swiping left (share), 0 = at rest, 1 = swiping right (delete); only recomposes when the side changes
    val dir by remember { derivedStateOf { if (offset.value > 0f) 1 else if (offset.value < 0f) -1 else 0 } }
    val pop by animateFloatAsState(if (armed) 1.18f else 1f, spring(dampingRatio = 0.5f, stiffness = 520f), label = "swipePop")

    Box(
        modifier
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(active, deleteOn, shareOn, canShare) {
                if (!active) return@pointerInput
                val threshold = size.width * 0.30f
                detectHorizontalDragGestures(
                    onDragStart = { armed = false },
                    onDragEnd = {
                        val x = offset.value
                        armed = false
                        scope.launch {
                            when {
                                deleteOn && x >= threshold -> {
                                    // slide the row out, then remove it: the list closes the gap with its own animation
                                    offset.animateTo(size.width * 1.08f, tween(210, easing = FastOutLinearInEasing))
                                    onDeleteNow()
                                }
                                shareOn && canShare && x <= -threshold -> {
                                    onShareNow()
                                    offset.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 420f))
                                }
                                shareOn && !canShare && x <= -threshold * 0.5f -> {
                                    onBlockedNow()
                                    offset.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 420f))
                                }
                                else -> offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f))
                            }
                        }
                    },
                    onDragCancel = {
                        armed = false
                        scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f)) }
                    }
                ) { change, dx ->
                    change.consume()
                    val cur = offset.value
                    var next = cur + dx
                    if (next > 0f && !deleteOn) next = 0f
                    if (next < 0f && !shareOn) next = 0f
                    // nothing to share yet: rubber band, never past half the threshold
                    if (next < 0f && !canShare) next = (cur + dx * 0.3f).coerceIn(-threshold * 0.6f, 0f)
                    next = next.coerceIn(-size.width.toFloat(), size.width.toFloat())
                    val nowArmed = (deleteOn && next >= threshold) || (shareOn && canShare && next <= -threshold)
                    if (nowArmed && !armed) Haptics.fire(view, Hx.THRESHOLD)
                    armed = nowArmed
                    scope.launch { offset.snapTo(next) }
                }
            }
    ) {
        if (dir != 0) {
            val deleting = dir > 0
            val panel = if (deleting) Bad else if (canShare) style.accent else TextTertiary
            // the coloured panel grows from the edge the row is dragged away from
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        val x = offset.value
                        if (x != 0f) {
                            val w = (abs(x) + 16.dp.toPx()).coerceAtMost(size.width)
                            drawRoundRect(
                                color = panel,
                                topLeft = if (x > 0f) Offset(0f, 0f) else Offset(size.width - w, 0f),
                                size = Size(w, size.height),
                                cornerRadius = CornerRadius(16.dp.toPx())
                            )
                        }
                    }
            ) {
                Icon(
                    if (deleting) AppleIcons.Delete else AppleIcons.Share,
                    if (deleting) "Delete" else "Share",
                    tint = Color.White,
                    modifier = Modifier
                        .align(if (deleting) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = 26.dp)
                        .size(26.dp)
                        .graphicsLayer {
                            // fades in as the row clears the icon, pops when the action is armed
                            val a = ((abs(offset.value) - 18.dp.toPx()) / 56.dp.toPx()).coerceIn(0f, 1f)
                            alpha = a
                            val sc = (0.65f + 0.35f * a) * pop
                            scaleX = sc; scaleY = sc
                        }
                )
            }
        }
        Box(
            Modifier.graphicsLayer {
                val x = offset.value
                translationX = x
                // a row being deleted fades while it leaves; sharing keeps it fully visible
                if (x > 0f && widthPx > 0f) alpha = 1f - 0.7f * (x / widthPx).coerceIn(0f, 1f)
            }
        ) { content() }
    }
}
