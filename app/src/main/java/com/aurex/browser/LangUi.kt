package com.aurex.browser

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * App-language UI (the logic is AppLang in Lang.kt):
 *  - AppLangRow           one row of the app-language list: tap = use this language, the button on its right = download only,
 *                         and the progress of a download is drawn right there (a ring around the cancel cross)
 *  - WebLangRow           one row of the website-language list: the same row and the same download button (the pack is
 *                         shared with the app language), tap = ask websites for this language
 *  - LangOverlayHost      full-screen layer above the whole app (MainActivity):
 *                           * the veil of a language change: the screen fades to the theme colour, THEN the texts change,
 *                             then it fades back, so the person never sees half-changed texts
 *                           * the setup page shown at start when the language is not on the phone yet
 *
 * Haptics follow the rest of the app (Hx): touching the download button = PRESS (very light), start of a download =
 * TOGGLE_ON, cancel = TOGGLE_OFF, row tap = TAP,
 * the moment the language flips = THRESHOLD (SUCCESS when it came after a download), failure = ERROR.
 */

// ── progress ring ────────────────────────────────────────────────────────────

/**
 * Ring for a language pack. The model download (DOWNLOADING) is a turning arc: ML Kit does not report how many bytes
 * came in, so no percentage is made up. Translating the app strings (TRANSLATING) is a real, filling arc.
 */
@Composable
private fun LangRing(phase: LangPhase, progress: Float, color: Color, stroke: Dp, modifier: Modifier = Modifier) {
    val spin = rememberInfiniteTransition(label = "langSpin")
    val angle by spin.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (phase == LangPhase.QUEUED) 2400 else 1000, easing = LinearEasing)),
        label = "langAngle"
    )
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(380, easing = FastOutSlowInEasing), label = "langP")
    Canvas(modifier) {
        val sw = stroke.toPx()
        val tl = Offset(sw / 2f, sw / 2f)
        val sz = Size(size.width - sw, size.height - sw)
        drawArc(color.copy(alpha = 0.18f), 0f, 360f, false, tl, sz, style = Stroke(sw))
        when (phase) {
            LangPhase.TRANSLATING ->
                drawArc(color, -90f, 360f * p.coerceAtLeast(0.03f), false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            LangPhase.READY ->
                drawArc(color, -90f, 360f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            LangPhase.DOWNLOADING ->
                drawArc(color, angle - 90f, 100f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            LangPhase.QUEUED ->
                drawArc(color.copy(alpha = 0.5f), angle - 90f, 60f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            else -> Unit
        }
    }
}

// ── the trailing slot of a row ───────────────────────────────────────────────

private enum class BtnKind { NONE, SELECTED, DOWNLOAD, ACTIVE, FAILED, DONE }

/** Touch target of the trailing slot, and the width the row's text keeps free for it. */
private val SlotSize = 44.dp

/** Gap between the slot and the right edge of the list card. */
private val SlotEdge = 6.dp

/**
 * Press feel of the round download / cancel / retry button: it sinks under the finger, springs back with a small
 * overshoot, a very light tick on touch and the real haptic ([kind]) when the finger lifts on the button.
 */
private fun Modifier.pressButton(kind: Hx, onClick: () -> Unit): Modifier = composed {
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val sc by animateFloatAsState(
        targetValue = if (pressed) 0.84f else 1f,
        animationSpec = if (pressed) spring<Float>(dampingRatio = 0.9f, stiffness = 1100f)
        else spring<Float>(dampingRatio = 0.42f, stiffness = 480f),
        label = "langBtnPress"
    )
    this
        .graphicsLayer { scaleX = sc; scaleY = sc }
        .pointerInput(kind) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                pressed = true
                Haptics.fire(view, Hx.PRESS)
                val up = waitForUpOrCancellation()
                pressed = false
                if (up != null) {
                    up.consume()
                    Haptics.fire(view, kind)
                    currentOnClick()
                }
            }
        }
}

/**
 * The one column on the right of every row (download icon, ring, tick and the "selected" check all sit in it):
 *  - not on the phone: a download arrow; tapping it only downloads, it never changes the language
 *  - waiting / downloading / preparing: a ring with the cancel cross inside
 *  - failed: a retry arrow (it shakes once)
 *  - just finished: a green tick with a soft pulse, for a moment
 *  - this row is the chosen one: the accent check
 * [code] null = the row is not a language of its own ("Auto" of the website list): no pack, no button.
 */
@Composable
private fun LangTrailing(code: String?, selected: Boolean) {
    val accent = LocalGlassStyle.current.accent
    val good = Good
    val goodText = GoodText
    val bad = Bad
    val badText = BadText
    val dl = if (code != null) AppLang.packOf(code) else null
    val phase = dl?.phase ?: LangPhase.READY
    val canDownload = code != null && code != "en"
    var prev by remember { mutableStateOf(phase) }
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(phase) {
        if (phase == LangPhase.READY && prev != LangPhase.READY) {
            flash = true
            try { delay(1400) } finally { flash = false }
        }
        prev = phase
    }
    val kind = when {
        !canDownload -> if (selected) BtnKind.SELECTED else BtnKind.NONE
        phase == LangPhase.QUEUED || phase == LangPhase.DOWNLOADING || phase == LangPhase.TRANSLATING -> BtnKind.ACTIVE
        phase == LangPhase.FAILED -> BtnKind.FAILED
        phase == LangPhase.READY -> if (flash) BtnKind.DONE else if (selected) BtnKind.SELECTED else BtnKind.NONE
        else -> if (selected) BtnKind.SELECTED else BtnKind.DOWNLOAD
    }
    val tappable = kind == BtnKind.DOWNLOAD || kind == BtnKind.ACTIVE || kind == BtnKind.FAILED
    val hx = if (kind == BtnKind.ACTIVE) Hx.TOGGLE_OFF else Hx.TOGGLE_ON
    Box(
        Modifier
            .size(SlotSize)
            .then(
                if (tappable && code != null) Modifier.pressButton(hx) {
                    if (kind == BtnKind.ACTIVE) AppLang.cancel(code) else AppLang.download(code)
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = kind,
            contentAlignment = Alignment.Center,
            transitionSpec = {
                when {
                    // arrow -> ring: the arrow drops away (into the "tray") while the ring pops open
                    initialState == BtnKind.DOWNLOAD && targetState == BtnKind.ACTIVE ->
                        (fadeIn(tween(200, delayMillis = 50)) +
                            scaleIn(spring(dampingRatio = 0.55f, stiffness = 420f), initialScale = 0.45f)) togetherWith
                            (fadeOut(tween(110)) + slideOutVertically(tween(160, easing = FastOutSlowInEasing)) { it / 4 })
                    // ring -> tick: the tick lands with a bounce while the ring swells and fades
                    targetState == BtnKind.DONE ->
                        (fadeIn(tween(140)) +
                            scaleIn(spring(dampingRatio = 0.45f, stiffness = 380f), initialScale = 0.3f)) togetherWith
                            (fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 1.15f))
                    else ->
                        (fadeIn(tween(170)) +
                            scaleIn(spring(dampingRatio = 0.6f, stiffness = 520f), initialScale = 0.6f)) togetherWith
                            (fadeOut(tween(110)) + scaleOut(tween(110), targetScale = 0.7f))
                }
            },
            label = "langTrailing"
        ) { k ->
            Box(Modifier.size(SlotSize), contentAlignment = Alignment.Center) {
                when (k) {
                    BtnKind.DOWNLOAD -> Box(
                        Modifier.size(32.dp).background(accent.copy(alpha = 0.14f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) { Icon(AppleIcons.Download, "Download language", tint = accent, modifier = Modifier.size(17.dp)) }
                    BtnKind.ACTIVE -> Box(
                        Modifier.size(32.dp).background(accent.copy(alpha = 0.08f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        LangRing(phase, dl?.progress ?: 0f, accent, 2.5.dp, Modifier.fillMaxSize())
                        Icon(AppleIcons.Close, "Cancel download", tint = accent, modifier = Modifier.size(11.dp))
                    }
                    BtnKind.FAILED -> {
                        val shake = remember { Animatable(0f) }
                        LaunchedEffect(Unit) {
                            for (x in floatArrayOf(-5f, 4f, -3f, 2f, 0f)) shake.animateTo(x, tween(55, easing = LinearEasing))
                        }
                        Box(
                            Modifier
                                .size(32.dp)
                                .graphicsLayer { translationX = shake.value.dp.toPx() }
                                .background(bad.copy(alpha = 0.14f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) { Icon(AppleIcons.Refresh, "Try again", tint = badText, modifier = Modifier.size(16.dp)) }
                    }
                    BtnKind.DONE -> {
                        val pulse = remember { Animatable(0f) }
                        LaunchedEffect(Unit) { pulse.animateTo(1f, tween(640, easing = FastOutSlowInEasing)) }
                        Canvas(Modifier.fillMaxSize()) {
                            val p = pulse.value
                            drawCircle(
                                color = good.copy(alpha = 0.4f * (1f - p)),
                                radius = 14.dp.toPx() + 6.dp.toPx() * p,
                                style = Stroke(2.dp.toPx())
                            )
                        }
                        Box(
                            Modifier.size(32.dp).background(good.copy(alpha = 0.16f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) { Icon(AppleIcons.Check, "Downloaded", tint = goodText, modifier = Modifier.size(17.dp)) }
                    }
                    BtnKind.SELECTED -> Icon(AppleIcons.Check, null, tint = accent, modifier = Modifier.size(20.dp))
                    BtnKind.NONE -> Unit
                }
            }
        }
    }
}

// ── one row of a language list ───────────────────────────────────────────────

/**
 * Shared by the app-language and the website-language list, so both look and feel the same.
 * The row itself is the tap target; the trailing slot is laid over its right end (a sibling, not a child), so a tap on
 * the download button never also presses the row.
 *
 * [code]: the language whose pack the row shows (null = no pack, see [LangTrailing]). [isPending]: the row was tapped
 * as the app language and switches by itself when its download is done.
 */
@Composable
private fun LangRow(
    title: String,
    subtitle: String?,
    code: String?,
    selected: Boolean,
    isPending: Boolean,
    onPick: () -> Unit
) {
    val style = LocalGlassStyle.current
    val dl = if (code != null) AppLang.packOf(code) else null
    val phase = dl?.phase ?: LangPhase.IDLE
    val pct = ((dl?.progress ?: 0f) * 100).toInt()
    val tint by animateColorAsState(
        if (isPending) style.accent.copy(alpha = 0.08f) else Color.Transparent, tween(260), label = "langRowTint"
    )
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(tint)
                .clickableHaptic { onPick() }
                .padding(start = 16.dp, end = SlotSize + SlotEdge, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 16.sp, letterSpacing = (-0.3).sp)
                AnimatedContent(
                    targetState = Pair(phase, isPending),
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(110)) },
                    label = "langSub"
                ) { st ->
                    val ph = st.first
                    val pend = st.second
                    when (ph) {
                        LangPhase.QUEUED -> Text("Waiting…", color = TextTertiary, fontSize = 12.sp)
                        LangPhase.DOWNLOADING -> Text(
                            if (pend) "Downloading · switches when ready" else "Downloading language pack…",
                            color = style.accent, fontSize = 12.sp
                        )
                        LangPhase.TRANSLATING -> Text(
                            if (pend) "Preparing · $pct% · switches when ready" else "Preparing · $pct%",
                            color = style.accent, fontSize = 12.sp
                        )
                        LangPhase.FAILED -> Text("Couldn't download · tap to retry", color = BadText, fontSize = 12.sp)
                        LangPhase.READY ->
                            if (code != null && code != "en") Row {
                                if (subtitle != null) {
                                    Text(subtitle, color = TextTertiary, fontSize = 12.sp)
                                    Text(" · ", color = TextTertiary, fontSize = 12.sp)
                                }
                                Text("Downloaded", color = TextTertiary, fontSize = 12.sp)
                            } else if (subtitle != null) Text(subtitle, color = TextTertiary, fontSize = 12.sp)
                        LangPhase.IDLE -> if (subtitle != null) Text(subtitle, color = TextTertiary, fontSize = 12.sp)
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.CenterEnd).padding(end = SlotEdge)) {
            LangTrailing(code, selected)
        }
    }
}

/**
 * One row of the app-language list. [choice]: [AppLang.AUTO] or a language code. Tap the row = use it (downloads first
 * when it is not on the phone, then switches by itself). The button on the right only downloads.
 */
@Composable
fun AppLangRow(choice: String, title: String, subtitle: String?) {
    val code = AppLang.codeFor(choice)
    LangRow(
        title = title,
        subtitle = subtitle,
        code = code,
        selected = AppLang.uiChoice == choice && AppLang.ui == code,
        isPending = AppLang.pending == choice,
        onPick = { AppLang.pick(choice) }
    )
}

/**
 * One row of the website-language list: same look as [AppLangRow]. Tap the row = ask websites for this language
 * (nothing has to be downloaded for that). The download button is optional: it saves the same language pack the app
 * language uses, so the two lists always agree about what is on the phone. "Auto" follows the app and has no pack.
 */
@Composable
fun WebLangRow(choice: String, title: String, subtitle: String?, selected: Boolean, onPick: () -> Unit) {
    LangRow(
        title = title,
        subtitle = subtitle,
        code = if (choice == AppLang.AUTO) null else choice,
        selected = selected,
        isPending = false,
        onPick = onPick
    )
}

// ── full-screen layers: change veil + start-up setup page ────────────────────

/** Put once above the whole app (MainActivity). Without it, language changes are applied at once, without the veil. */
@Composable
fun LangOverlayHost() {
    DisposableEffect(Unit) {
        AppLang.hostAttached = true
        onDispose { AppLang.hostAttached = false }
    }
    val view = LocalView.current
    val veil = AppLang.veil
    val veilA = remember { Animatable(0f) }
    var revealed by remember { mutableStateOf(false) }
    // The veil: cover the screen -> change the language behind it -> show the new name with a tick -> uncover.
    LaunchedEffect(veil?.id) {
        val v = veil ?: return@LaunchedEffect
        revealed = false
        veilA.animateTo(1f, tween(if (PerfTier.low) 160 else 230, easing = FastOutSlowInEasing))
        AppLang.commitVeil(v)
        revealed = true
        Haptics.fire(view, if (v.viaDownload) Hx.SUCCESS else Hx.THRESHOLD)
        delay(560)
        veilA.animateTo(0f, tween(380, easing = FastOutSlowInEasing))
        AppLang.endVeil(v)
    }
    val setup = AppLang.setup
    if (setup != null) LangSetupScreen(setup)
    if (veil != null && veilA.value > 0.001f) {
        val palette = LocalPalette.current
        val accent = LocalGlassStyle.current.accent
        BackHandler(enabled = true) { }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = veilA.value }
                .background(palette.bg)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        targetState = revealed,
                        transitionSpec = {
                            (fadeIn(tween(180)) + scaleIn(spring(dampingRatio = 0.55f, stiffness = 380f), initialScale = 0.5f)) togetherWith
                                fadeOut(tween(120))
                        },
                        label = "veilIcon"
                    ) { done ->
                        if (done) Box(
                            Modifier.size(56.dp).background(accent.copy(alpha = 0.16f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) { Icon(AppleIcons.Check, null, tint = accent, modifier = Modifier.size(28.dp)) }
                        else Icon(AppleIcons.Language, null, tint = accent, modifier = Modifier.size(34.dp))
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    Langs.byCode(veil.code)?.native ?: "English",
                    color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private enum class SetupIcon { GLOBE, PERCENT, CHECK, ERROR }

/**
 * The language of the app (Auto = the device language) is not on the phone yet: this page covers the app while the pack
 * downloads, shows the progress, and when the pack is complete it fades away and the app is already in that language.
 * Always has a way out ("Continue in English") for people who are offline.
 */
@Composable
private fun LangSetupScreen(s: LangSetup) {
    val palette = LocalPalette.current
    val accent = LocalGlassStyle.current.accent
    val view = LocalView.current
    val lang = Langs.byCode(s.code)
    val dl = AppLang.packOf(s.code)
    val phase = dl.phase
    val enterA = remember { Animatable(0f) }
    val exitA = remember { Animatable(1f) }
    var finished by remember { mutableStateOf(false) }
    var skipVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        enterA.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
        delay(1200)
        skipVisible = true
    }
    LaunchedEffect(phase) {
        if (phase == LangPhase.READY && !finished) {
            finished = true
            Haptics.fire(view, Hx.SUCCESS)
            delay(700)                       // the ring is full and the tick is on screen
            AppLang.commitSetup()            // the app changes language underneath this page
            delay(150)
            exitA.animateTo(0f, tween(450, easing = FastOutSlowInEasing))
            AppLang.endSetup()
        }
    }
    val pct = (dl.progress * 100).toInt()
    // a soft tick at 25 / 50 / 75 % while the app strings are prepared
    LaunchedEffect(pct / 25) {
        if (phase == LangPhase.TRANSLATING && pct >= 25) Haptics.fire(view, Hx.TICK)
    }
    BackHandler(enabled = true) { }

    val breathe = rememberInfiniteTransition(label = "setupBreathe")
    val glowA by breathe.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glowA"
    )
    val globeS by breathe.animateFloat(
        initialValue = 1f, targetValue = 1.07f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "globeS"
    )
    val ringColor by animateColorAsState(
        when (phase) { LangPhase.FAILED -> Bad; LangPhase.READY -> Good; else -> accent }, tween(300), label = "setupRing"
    )
    val icon = when (phase) {
        LangPhase.READY -> SetupIcon.CHECK
        LangPhase.FAILED -> SetupIcon.ERROR
        LangPhase.TRANSLATING -> SetupIcon.PERCENT
        else -> SetupIcon.GLOBE
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = enterA.value * exitA.value }
            .background(palette.bg)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
    ) {
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(240.dp)
                        .graphicsLayer { alpha = glowA * (if (phase == LangPhase.FAILED) 0.3f else 1f) }
                        .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.26f), Color.Transparent)), CircleShape)
                )
                Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
                    LangRing(phase, dl.progress, ringColor, 5.dp, Modifier.fillMaxSize())
                    AnimatedContent(
                        targetState = icon,
                        transitionSpec = {
                            (fadeIn(tween(200)) + scaleIn(spring(dampingRatio = 0.55f, stiffness = 380f), initialScale = 0.5f)) togetherWith
                                (fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f))
                        },
                        label = "setupIcon"
                    ) { k ->
                        when (k) {
                            SetupIcon.GLOBE -> Icon(
                                AppleIcons.Language, null, tint = accent,
                                modifier = Modifier.size(52.dp).graphicsLayer { scaleX = globeS; scaleY = globeS }
                            )
                            SetupIcon.PERCENT -> Text("$pct%", color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                            SetupIcon.CHECK -> Icon(AppleIcons.Check, null, tint = Good, modifier = Modifier.size(56.dp))
                            SetupIcon.ERROR -> Icon(AppleIcons.ErrorOutline, null, tint = Bad, modifier = Modifier.size(52.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(lang?.native ?: s.code, color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (lang != null && lang.native != lang.english) {
                Spacer(Modifier.height(2.dp))
                Text(lang.english, color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(22.dp))
            AnimatedContent(
                targetState = phase,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
                label = "setupStatus"
            ) { ph ->
                Text(
                    when (ph) {
                        LangPhase.DOWNLOADING -> LangSetupText.DOWNLOADING
                        LangPhase.TRANSLATING -> LangSetupText.PREPARING
                        LangPhase.READY -> LangSetupText.READY
                        LangPhase.FAILED -> LangSetupText.FAILED
                        else -> LangSetupText.WAITING
                    },
                    color = if (ph == LangPhase.FAILED) BadText else TextPrimary,
                    fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(LangSetupText.NOTE, color = TextTertiary, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center)
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (phase == LangPhase.FAILED) {
                GlassPillButton(LangSetupText.RETRY, { AppLang.retrySetup() }, accent = true)
                Spacer(Modifier.height(10.dp))
                GlassPillButton(LangSetupText.SKIP, { AppLang.skipSetup() })
            } else {
                AnimatedVisibility(visible = skipVisible && !finished, enter = fadeIn(tween(400)), exit = fadeOut(tween(150))) {
                    GlassPillButton(LangSetupText.SKIP, { AppLang.skipSetup() })
                }
            }
        }
    }
}
