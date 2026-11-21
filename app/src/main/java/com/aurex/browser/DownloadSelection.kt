package com.aurex.browser

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tap + press-and-hold on one surface, with the same little press-in feel as [clickableHaptic].
 * Holding gives a firm haptic tick, which is what starts "select" mode in the Downloads list.
 */
fun Modifier.tapOrHold(
    onTap: () -> Unit,
    onHold: () -> Unit,
    tapKind: Hx = Hx.TAP,
    holdEnabled: Boolean = true
): Modifier = composed {
    val view = LocalView.current
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    var pressed by remember { mutableStateOf(false) }
    val scState = animateFloatAsState(
        if (pressed) 0.985f else 1f,
        spring(dampingRatio = 0.65f, stiffness = 1200f),
        label = "holdScale"
    )
    this
        .graphicsLayer { val s = scState.value; scaleX = s; scaleY = s }
        .pointerInput(tapKind, holdEnabled) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    try { tryAwaitRelease() } finally { pressed = false }
                },
                // When the list owns the hold (hold + drag to select a range) this surface only handles taps.
                onLongPress = if (holdEnabled) ({ _: androidx.compose.ui.geometry.Offset ->
                    performHold(view)
                    hold()
                }) else null,
                onTap = {
                    Haptics.fire(view, tapKind)
                    tap()
                }
            )
        }
}

/** Round check mark shown at the start of a card while selecting. */
@Composable
fun SelectDot(selected: Boolean, accent: Color) {
    val fill by animateColorAsState(if (selected) accent else Color.Transparent, tween(180), label = "dotFill")
    val ring by animateColorAsState(if (selected) accent else TextTertiary, tween(180), label = "dotRing")
    val tickState = animateFloatAsState(
        if (selected) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 600f), label = "dotTick"
    )
    Box(
        Modifier
            .size(24.dp)
            .background(fill, CircleShape)
            .border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            AppleIcons.Check, null, tint = Color.White,
            modifier = Modifier.size(14.dp).graphicsLayer {
                val t = tickState.value
                scaleX = t; scaleY = t; alpha = t.coerceIn(0f, 1f)
            }
        )
    }
}

/** Replaces the Downloads header while files are selected: count, select all, share, delete. */
@Composable
fun SelectionHeader(
    count: Int,
    total: Int,
    allSelected: Boolean,
    canShare: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val style = LocalGlassStyle.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.liquidGlass(CircleShape, 1.02f, 0f, reactive = false)) {
            GlassIconButton(AppleIcons.Close, "Cancel selection", onClick = onClose, size = 44.dp, iconSize = 20.dp, filled = false)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "$count selected", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 0.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier
                    .clip(Capsule)
                    .background(style.accent.copy(alpha = 0.16f), Capsule)
                    .clickableHaptic { onSelectAll() }
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text(
                    if (allSelected) "Deselect all" else "Select all ($total)",
                    color = style.accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, softWrap = false
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Row(
            Modifier.liquidGlass(Capsule, 1.02f, 0f, reactive = false).padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassIconButton(
                AppleIcons.Share, "Share", onClick = onShare,
                size = 44.dp, iconSize = 20.dp, filled = false, enabled = canShare
            )
            GlassIconButton(
                AppleIcons.Delete, "Delete", onClick = onDelete,
                size = 44.dp, iconSize = 20.dp, filled = false, tint = Bad
            )
        }
    }
}
