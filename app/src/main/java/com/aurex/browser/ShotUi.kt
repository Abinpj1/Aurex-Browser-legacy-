package com.aurex.browser

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

private val ManualDot = Color(0xFFFF9F0A)

private fun hudLabel(p: ShotPhase): String = when (p) {
    ShotPhase.PREPARE -> "Getting ready…"
    ShotPhase.AUTO -> "Scrolling for you · touch the page to take over"
    ShotPhase.MANUAL -> "Your turn · scroll down, then tap Done"
    ShotPhase.FILL -> "Filling in the gaps…"
    ShotPhase.FINISH -> "Joining the pieces…"
}

private fun screensText(v: Float): String = String.format(Locale.US, "%.1f screens captured", v)

/**
 * Shown on top of the page while a long screenshot is captured. It never takes touches from the page (a touch on the
 * page hands the scrolling to the user); only its own buttons react.
 *  - top: what is happening, with a pulsing dot,
 *  - right edge: a rail that fills as the page is captured, a dot shows where the page is scrolled to,
 *  - bottom: cancel, scroll-myself / auto-scroll, done,
 *  - a soft flash on every captured piece.
 */
@Composable
fun ShotHud(s: BrowserState, session: LongShot) {
    val style = LocalGlassStyle.current
    val phase = session.phase
    val busy = phase == ShotPhase.AUTO || phase == ShotPhase.MANUAL

    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 380f)) }

    val flash = remember { Animatable(0f) }
    LaunchedEffect(session.pulse) {
        if (session.pulse > 0) {
            flash.snapTo(0.2f)
            flash.animateTo(0f, tween(300))
        }
    }
    val cov by animateFloatAsState(session.coverage, tween(280), label = "shotCoverage")
    val pos by animateFloatAsState(session.position, tween(180), label = "shotPosition")

    Box(Modifier.fillMaxSize()) {
        if (flash.value > 0.004f) {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
        }

        // ── status ──
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 10.dp, start = 16.dp, end = 16.dp)
                .graphicsLayer {
                    alpha = enter.value
                    translationY = -28.dp.toPx() * (1f - enter.value)
                }
                .liquidGlass(Capsule, 1.1f, 0.06f)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val pulse = rememberInfiniteTransition(label = "shotDot")
            val dotAlpha by pulse.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
                label = "shotDotAlpha"
            )
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer { alpha = dotAlpha }
                    .background(if (phase == ShotPhase.MANUAL) ManualDot else style.accent, CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Crossfade(targetState = phase, animationSpec = tween(220), label = "shotLabel") { p ->
                    Text(
                        hudLabel(p),
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (session.screens > 1.05f) {
                    Text(
                        if (session.limited) "Maximum length reached" else screensText(session.screens),
                        color = TextTertiary,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // ── rail ──
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
                .width(14.dp)
                .height(200.dp)
                .graphicsLayer { alpha = enter.value },
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(Capsule)
                    .background(Color.White.copy(alpha = 0.24f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(cov.coerceIn(0.02f, 1f))
                        .background(style.accent)
                )
            }
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 186.dp * pos)
                    .size(14.dp)
                    .background(Color.White, CircleShape)
            )
        }

        // ── controls ──
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 20.dp)
                .graphicsLayer {
                    alpha = enter.value
                    translationY = 48.dp.toPx() * (1f - enter.value)
                }
                .liquidGlass(Capsule, 1.15f, 0.06f)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            GlassIconButton(
                AppleIcons.Close, "Cancel",
                onClick = { s.cancelShot() },
                size = 42.dp, iconSize = 17.dp, filled = false
            )
            if (phase == ShotPhase.MANUAL) {
                GlassPillButton("Auto-scroll", onClick = { session.resumeAuto() }, icon = AppleIcons.AutoScroll)
            } else {
                GlassPillButton(
                    "I'll scroll",
                    onClick = { session.takeControl() },
                    icon = AppleIcons.Swipe,
                    enabled = phase == ShotPhase.AUTO
                )
            }
            GlassPillButton(
                "Done",
                onClick = { session.done() },
                icon = AppleIcons.Check,
                accent = true,
                enabled = busy
            )
        }
    }
}

/**
 * The finished long screenshot: it springs in, can be scrolled through (with a scroll indicator) and saved or shared;
 * "Retake" starts the capture again.
 */
@Composable
fun ShotPreview(s: BrowserState, tab: Tab, shot: ScreenshotData) {
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val enter = remember(shot) { Animatable(0f) }
    val actions = remember(shot) { Animatable(0f) }
    var saved by remember(shot) { mutableStateOf(false) }
    var saving by remember(shot) { mutableStateOf(false) }
    var areaH by remember { mutableIntStateOf(0) }

    LaunchedEffect(shot) { enter.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 260f)) }
    LaunchedEffect(shot) {
        delay(150)
        actions.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 320f))
    }
    val indicator by animateFloatAsState(
        if (scroll.isScrollInProgress) 1f else 0f,
        tween(if (scroll.isScrollInProgress) 90 else 700),
        label = "shotIndicator"
    )

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = enter.value }
            .background(scrim(0.78f))
            .clickableHaptic(haptic = false) { s.screenshot = null },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .widthIn(max = 440.dp)
                .graphicsLayer {
                    val e = enter.value
                    scaleX = 0.9f + 0.1f * e
                    scaleY = 0.9f + 0.1f * e
                    translationY = 64.dp.toPx() * (1f - e)
                }
                .liquidGlass(CardShape, 1.18f, 0.06f)
                .clickableHaptic(haptic = false) { }
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        shot.title,
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${shot.bitmap.width} × ${shot.bitmap.height} px" +
                            if (shot.screens > 1.05f) " · " + String.format(Locale.US, "%.1f", shot.screens) + " screens" else "",
                        color = TextTertiary,
                        fontSize = 12.sp
                    )
                }
                GlassIconButton(
                    AppleIcons.Close, "Close",
                    onClick = { s.screenshot = null },
                    size = 38.dp, iconSize = 16.dp, filled = false
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.28f))
                    .onSizeChanged { areaH = it.height }
            ) {
                Box(Modifier.fillMaxWidth().verticalScroll(scroll)) {
                    Image(
                        bitmap = shot.bitmap.asImageBitmap(),
                        contentDescription = "Screenshot",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth
                    )
                }
                if (scroll.maxValue > 0 && areaH > 0) {
                    val total = (scroll.maxValue + areaH).toFloat()
                    val thumbH = (areaH * areaH / total).coerceAtLeast(with(density) { 28.dp.toPx() })
                    val travel = areaH - thumbH
                    val y = travel * scroll.value / scroll.maxValue
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 3.dp)
                            .offset { IntOffset(0, y.toInt()) }
                            .width(4.dp)
                            .height(with(density) { thumbH.toDp() })
                            .graphicsLayer { alpha = indicator }
                            .background(Color.White.copy(alpha = 0.85f), Capsule)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = actions.value
                        translationY = 28.dp.toPx() * (1f - actions.value)
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassIconButton(
                    AppleIcons.Refresh, "Retake",
                    onClick = {
                        s.screenshot = null
                        s.captureScreenshot(tab)
                    },
                    size = 44.dp, iconSize = 19.dp
                )
                Spacer(Modifier.weight(1f))
                GlassPillButton(
                    if (saved) "Saved" else if (saving) "Saving…" else "Save",
                    onClick = {
                        saving = true
                        s.saveScreenshot(shot) { ok ->
                            saving = false
                            saved = ok
                        }
                    },
                    icon = if (saved) AppleIcons.Check else AppleIcons.Download,
                    enabled = !saved && !saving
                )
                GlassPillButton(
                    "Share",
                    onClick = { s.shareScreenshot(shot) },
                    icon = AppleIcons.Share,
                    accent = true
                )
            }
        }
    }
}
