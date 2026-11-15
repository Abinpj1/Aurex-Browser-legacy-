package com.aurex.browser

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * State for "hold, then drag to select a range" in the Downloads list.
 *
 *  - Hold a card: it is selected (or, when it was already selected, un-selected) and the drag starts.
 *  - Keep the finger down and move DOWN: every card you pass is selected. Move UP: the cards above are selected.
 *    Moving back toward the card you started on gives the far ones up again.
 *  - Near the top / bottom edge the list scrolls by itself, faster the closer the finger is to the edge.
 */
@Stable
class DragSelect {
    /** Cards on screen: item id -> (top, bottom) in window coordinates. */
    internal val rows = HashMap<Int, FloatArray>()
    internal var list: LayoutCoordinates? = null

    /** True from the hold until the finger lifts. */
    var active by mutableStateOf(false)
        internal set

    internal var pointerY = 0f
    internal var anchor = -1
    internal var lastIndex = -1
    internal var adding = true
    internal var base: Set<Int> = emptySet()
}

/** Reports where this card is on screen, so the drag knows which card is under the finger. */
fun Modifier.trackRow(id: Int, ds: DragSelect): Modifier = composed {
    DisposableEffect(id, ds) { onDispose { ds.rows.remove(id) } }
    onGloballyPositioned { c ->
        if (c.isAttached) {
            val r = c.boundsInWindow()
            ds.rows[id] = floatArrayOf(r.top, r.bottom)
        }
    }
}

/**
 * Hold + drag range selection for a vertical list, with edge auto-scroll and haptics:
 * a firm tick on the hold, a light tick for every card the finger crosses, a stronger one when the
 * auto-scroll kicks in, and a soft one when the finger lifts.
 *
 * [ids] is the list's ids in display order, [selection] the current selection and [onSelection] receives the new one.
 * Put this on the LazyColumn itself; scrolling and taps keep working because nothing is taken over before the hold.
 */
fun Modifier.dragSelect(
    ds: DragSelect,
    listState: LazyListState,
    ids: () -> List<Int>,
    selection: () -> Set<Int>,
    onSelection: (Set<Int>) -> Unit
): Modifier = composed {
    val view = LocalView.current
    val idsNow by rememberUpdatedState(ids)
    val selNow by rememberUpdatedState(selection)
    val setNow by rememberUpdatedState(onSelection)

    this
        .onGloballyPositioned { ds.list = it }
        .pointerInput(ds, listState) {
            // Index (in display order) of the card under window-y, or the nearest one when the finger is past the ends.
            fun indexAt(order: List<Int>, y: Float): Int {
                var nearest = -1
                var nearestDist = Float.MAX_VALUE
                for (i in order.indices) {
                    val r = ds.rows[order[i]] ?: continue
                    if (y >= r[0] && y < r[1]) return i
                    val d = if (y < r[0]) r[0] - y else y - r[1]
                    if (d < nearestDist) { nearestDist = d; nearest = i }
                }
                return nearest
            }

            // Selection = what was selected at the hold, plus (or minus) every card from the start card to this one.
            fun updateRange() {
                val order = idsNow()
                val cur = indexAt(order, ds.pointerY)
                if (cur < 0 || cur == ds.lastIndex || ds.anchor !in order.indices) return
                ds.lastIndex = cur
                Haptics.fire(view, Hx.TICK)
                val range = order.subList(minOf(ds.anchor, cur), maxOf(ds.anchor, cur) + 1).toSet()
                setNow(if (ds.adding) ds.base + range else ds.base - range)
            }

            // px per second the list should scroll by: negative = up, 0 = finger is away from the edges.
            fun edgeSpeed(): Float {
                val c = ds.list ?: return 0f
                if (!c.isAttached) return 0f
                val top = c.localToWindow(Offset.Zero).y
                val bottom = c.localToWindow(Offset(0f, c.size.height.toFloat())).y
                val zone = minOf(96.dp.toPx(), (bottom - top) / 4f)
                if (zone <= 0f) return 0f
                val max = 1000.dp.toPx()
                val y = ds.pointerY
                return when {
                    y < top + zone -> {
                        val t = ((top + zone - y) / zone).coerceIn(0f, 1f)
                        -max * (0.12f + 0.88f * t)
                    }
                    y > bottom - zone -> {
                        val t = ((y - (bottom - zone)) / zone).coerceIn(0f, 1f)
                        max * (0.12f + 0.88f * t)
                    }
                    else -> 0f
                }
            }

            coroutineScope {
                // Auto-scroll: runs one frame at a time while a drag is active and the finger sits near an edge.
                launch {
                    snapshotFlow { ds.active }.collectLatest { on ->
                        if (!on) return@collectLatest
                        var last = withFrameNanos { it }
                        var scrolling = false
                        while (true) {
                            val now = withFrameNanos { it }
                            val dt = (now - last) / 1_000_000_000f
                            last = now
                            val v = edgeSpeed()
                            if (v != 0f) {
                                if (!scrolling) {
                                    scrolling = true
                                    Haptics.fire(view, Hx.THRESHOLD)
                                }
                                listState.scrollBy(v * dt)
                                updateRange() // the cards moved under the finger
                            } else {
                                scrolling = false
                            }
                        }
                    }
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Cancelled by a scroll, a sideways swipe on a card, or letting go early: those keep working as before.
                    val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    val c = ds.list
                    if (c == null || !c.isAttached) return@awaitEachGesture
                    val order = idsNow()
                    ds.pointerY = c.localToWindow(held.position).y
                    val first = indexAt(order, ds.pointerY)
                    if (first < 0) return@awaitEachGesture

                    ds.anchor = first
                    ds.lastIndex = first
                    ds.base = selNow()
                    ds.adding = order[first] !in ds.base
                    ds.active = true
                    Haptics.fire(view, Hx.HOLD)
                    setNow(if (ds.adding) ds.base + order[first] else ds.base - order[first])

                    try {
                        while (true) {
                            // Initial pass: the list and the cards underneath see the moves as already used,
                            // so the list does not scroll under the finger and the card does not get a tap.
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            ch.consume()
                            if (!ch.pressed) break
                            ds.pointerY = c.localToWindow(ch.position).y
                            updateRange()
                        }
                        Haptics.fire(view, Hx.DRAG_END)
                    } finally {
                        ds.active = false
                        ds.anchor = -1
                        ds.lastIndex = -1
                    }
                }
            }
        }
}
