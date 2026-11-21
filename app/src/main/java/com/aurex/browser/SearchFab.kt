package com.aurex.browser

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Open / closed state of a search button, hoisted so the screen around it can close it with a tap on empty space
 * (see [closeSearchOnEmptyTap]).
 */
class SearchFabState {
    var expanded by mutableStateOf(false)
    internal var closeTick by mutableIntStateOf(0)

    /** Collapse the bar and clear its filter (the button does the clearing). */
    fun requestClose() { if (expanded) closeTick++ }
}

@Composable
fun rememberSearchFabState(): SearchFabState = remember { SearchFabState() }

/**
 * One tap on EMPTY space closes the open search bar. Taps that a button, switch, row or the bar itself takes
 * (consumed by the time the event reaches the final pass) and scroll drags are left alone, so every option
 * keeps working while the bar is open. Put it on the Box that holds both the content and the search button.
 */
fun Modifier.closeSearchOnEmptyTap(state: SearchFabState): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        if (!state.expanded || down.isConsumed) return@awaitEachGesture
        val up = waitForUpOrCancellation(PointerEventPass.Final)
        if (up != null && !up.isConsumed) state.requestClose()
    }
}

/**
 * Search button that rests in the bottom-right corner. Tapping it morphs the round button into a
 * full-width search bar (the width springs open to the left, the field fades in), the keyboard
 * comes up and the field is focused. The close button, the system back gesture or an empty
 * query collapses it again and clears the search. The keyboard's search key only hides the
 * keyboard and keeps the typed filter.
 *
 * Place it in a Box with `Modifier.align(BottomEnd).navigationBarsPadding().imePadding()` so it rides
 * above the keyboard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpandingSearchFab(
    query: String,
    onQuery: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    state: SearchFabState = rememberSearchFabState()
) {
    val style = LocalGlassStyle.current
    var expanded by state::expanded
    var everOpened by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    fun close() { onQuery(""); expanded = false }

    BackHandler(enabled = expanded) { close() }

    // The keyboard's own back press only hides the keyboard (the system gives it that press first), which used to
    // leave the bar open until a SECOND back. Now the bar folds the moment the keyboard goes away - unless the
    // keyboard's search key hid it on purpose (the typed filter stays then).
    val imeVisible = WindowInsets.isImeVisible
    var imeSeen by remember { mutableStateOf(false) }
    var hiddenByKey by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible, expanded) {
        if (!expanded) { imeSeen = false; hiddenByKey = false; return@LaunchedEffect }
        if (imeVisible) { imeSeen = true; hiddenByKey = false }
        else if (imeSeen && !hiddenByKey) close()
    }
    // A tap on empty space somewhere else on the screen (see closeSearchOnEmptyTap).
    LaunchedEffect(state.closeTick) { if (state.closeTick > 0 && expanded) close() }

    LaunchedEffect(expanded) {
        if (expanded) {
            everOpened = true
            delay(90)                                   // let the field be composed before asking for focus
            runCatching { focus.requestFocus() }
            keyboard?.show()
        } else if (everOpened) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    BoxWithConstraints(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        val fab = 54.dp
        val width by animateDpAsState(
            if (expanded) maxWidth else fab,
            spring(dampingRatio = 0.84f, stiffness = 380f),
            label = "searchWidth"
        )
        val iconTint by animateColorAsState(
            if (expanded) TextSecondary else style.accent,
            tween(220), label = "searchIconTint"
        )
        Row(
            Modifier
                .width(width)
                .height(fab)
                .liquidWindowDrag(
                    maxPullDp = 30.dp,
                    dismissDir = if (expanded) LiquidDismissDir.DOWN else LiquidDismissDir.NONE,
                    dismissThresholdDp = 46.dp,
                    onDismiss = if (expanded) ({ close() }) else null
                )
                .liquidGlass(Capsule, 1.22f, 0f, reactive = false)
                // The open bar takes its own taps, so they never count as "empty space".
                .then(if (expanded) Modifier.pointerInput(Unit) { detectTapGestures { } } else Modifier),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(fab)
                    .then(if (!expanded) Modifier.clickableHaptic { expanded = true } else Modifier),
                contentAlignment = Alignment.Center
            ) {
                Icon(AppleIcons.Search, "Search", tint = iconTint, modifier = Modifier.size(22.dp))
            }
            AnimatedVisibility(
                visible = expanded,
                modifier = Modifier.weight(1f),
                enter = fadeIn(tween(170, delayMillis = 90)),
                exit = fadeOut(tween(80))
            ) {
                Row(Modifier.fillMaxWidth().height(fab), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                placeholder, color = TextTertiary, fontSize = 16.sp,
                                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = onQuery,
                            singleLine = true,
                            textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                            cursorBrush = SolidColor(style.accent),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                hiddenByKey = true
                                keyboard?.hide()
                                focusManager.clearFocus()
                            }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus)
                        )
                    }
                    GlassIconButton(
                        AppleIcons.Close, "Close search",
                        onClick = { close() },
                        size = 36.dp, iconSize = 16.dp, filled = false
                    )
                    Spacer(Modifier.width(9.dp))
                }
            }
        }
    }
}

/**
 * The one standard search button of the app: bottom-right, above the navigation bar and the keyboard.
 * Every screen with a search (Bookmarks, History, Downloads, Settings) places it with this, so it always
 * sits and behaves the same. Call it from inside a `Box(Modifier.fillMaxSize())` that holds the content.
 */
@Composable
fun BoxScope.StandardSearchFab(
    query: String,
    onQuery: (String) -> Unit,
    placeholder: String,
    state: SearchFabState = rememberSearchFabState()
) {
    ExpandingSearchFab(
        query = query,
        onQuery = onQuery,
        placeholder = placeholder,
        state = state,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .navigationBarsPadding()
            .imePadding()
            .padding(bottom = 14.dp)
    )
}
