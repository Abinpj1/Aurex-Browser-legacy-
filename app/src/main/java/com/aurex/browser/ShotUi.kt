package com.aurex.browser

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

private val ManualDot = Color(0xFFFF9F0A)
private const val PREVIEW_TILE_H = 2048

private fun hudLabel(p: ShotPhase): String = when (p) {
    ShotPhase.PREPARE -> "Getting ready…"
    ShotPhase.AUTO -> "Capturing page · touch to scroll yourself"
    ShotPhase.MANUAL -> "Your turn · scroll down, then tap Done"
    ShotPhase.FILL -> "Filling in gaps…"
    ShotPhase.FINISH -> "Stitching screenshot…"
}

private fun screensText(v: Float, pct: Int): String =
    String.format(Locale.US, "%.1f screens · %d%%", v, pct.coerceIn(0, 100))

/**
 * Floating Liquid Glass capture HUD shown while a long screenshot runs:
 *  - Never intercepts touches on the webpage (touching the page smoothly switches to manual scroll).
 *  - Top pill: live status + screen count + coverage %, with Light/Dark adaptive contrast.
 *  - Right edge rail: captured coverage fill + live scroll position dot.
 *  - Bottom pill: Cancel · This screen (instant 1-screen grab) / I'll scroll / Auto-scroll · Done.
 */
@Composable
fun ShotHud(s: BrowserState, session: LongShot) {
    val style = LocalGlassStyle.current
    val dark = LocalPalette.current.dark
    val phase = session.phase
    val busy = phase == ShotPhase.AUTO || phase == ShotPhase.MANUAL

    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 420f)) }

    val flash = remember { Animatable(0f) }
    LaunchedEffect(session.pulse) {
        if (session.pulse > 0) {
            flash.snapTo(0.16f)
            flash.animateTo(0f, tween(240))
        }
    }
    val cov by animateFloatAsState(session.coverage, tween(240), label = "shotCoverage")
    val pos by animateFloatAsState(session.position, tween(160), label = "shotPosition")

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().drawBehind {
            val f = flash.value.coerceIn(0f, 1f)
            if (f > 0.004f) drawRect(Color.White.copy(alpha = f))
        })

        // ── status pill ──
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 10.dp, start = 16.dp, end = 16.dp)
                .graphicsLayer {
                    alpha = enter.value.coerceIn(0f, 1f)
                    translationY = -28.dp.toPx() * (1f - enter.value)
                }
                .liquidWindowDrag(maxPullDp = 32.dp)
                .liquidGlass(Capsule, 1.14f, 0.05f, reactive = false)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val pulse = rememberInfiniteTransition(label = "shotDot")
            val dotAlphaState = pulse.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                label = "shotDotAlpha"
            )
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer { alpha = dotAlphaState.value.coerceIn(0f, 1f) }
                    .background(if (phase == ShotPhase.MANUAL) ManualDot else style.accent, CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Crossfade(targetState = phase, animationSpec = tween(180), label = "shotLabel") { p ->
                    Text(
                        hudLabel(p),
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (session.screens > 1.02f || session.coverage > 0.05f) {
                    Text(
                        if (session.limited) "Maximum length reached"
                        else screensText(session.screens, (session.coverage * 100f).roundToInt()),
                        color = TextTertiary,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // ── progress rail (Light & Dark mode visible) ──
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 6.dp)
                .width(14.dp)
                .height(200.dp)
                .graphicsLayer { alpha = enter.value.coerceIn(0f, 1f) },
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(Capsule)
                    .background(if (dark) Color.White.copy(alpha = 0.26f) else Color.Black.copy(alpha = 0.16f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(cov.coerceIn(0.03f, 1f))
                        .background(style.accent)
                )
            }
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 186.dp * pos)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (dark) Color.White else style.accent, CircleShape)
                    .border(1.5.dp, if (dark) Color.Black.copy(alpha = 0.24f) else Color.White, CircleShape)
            )
        }

        // ── bottom controls ──
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 20.dp)
                .graphicsLayer {
                    alpha = enter.value.coerceIn(0f, 1f)
                    translationY = 48.dp.toPx() * (1f - enter.value)
                }
                .liquidWindowDrag(maxPullDp = 32.dp)
                .liquidGlass(Capsule, 1.18f, 0.05f, reactive = false)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            GlassIconButton(
                AppleIcons.Close, "Cancel",
                onClick = { s.cancelShot() },
                size = 42.dp, iconSize = 17.dp, filled = false
            )
            if (phase == ShotPhase.PREPARE) {
                GlassPillButton(
                    "This screen",
                    onClick = { session.snapThisScreen() },
                    icon = AppleIcons.FitScreen
                )
            } else if (phase == ShotPhase.MANUAL) {
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
 * Slices a tall Bitmap into <= 2048px GPU-safe ImageBitmap tiles so even a 25,000px full-page
 * screenshot renders razor-sharp on every Android GPU (never hits GL_MAX_TEXTURE_SIZE crash!).
 */
private fun slicePreviewTiles(bmp: Bitmap): List<ImageBitmap> {
    if (bmp.height <= PREVIEW_TILE_H) return listOf(bmp.asImageBitmap())
    val out = ArrayList<ImageBitmap>((bmp.height / PREVIEW_TILE_H) + 1)
    var y = 0
    while (y < bmp.height) {
        val h = min(PREVIEW_TILE_H, bmp.height - y)
        val tile = runCatching { Bitmap.createBitmap(bmp, 0, y, bmp.width, h) }.getOrNull() ?: break
        out.add(tile.asImageBitmap())
        y += h
    }
    return if (out.isEmpty()) listOf(bmp.asImageBitmap()) else out
}

/**
 * Upgraded Screenshot Preview:
 *  - GPU-safe tiled preview (smooth 120 FPS scroll on 1..25+ screen captures, zero blank/crash!).
 *  - Interactive Top & Bottom Trim (Crop) mode with live visual mask & pixel dimension readout.
 *  - Format toggle (`PNG` lossless vs `JPG` compact fast-share).
 *  - Instant 1-tap Retake, Copy to Clipboard, Save to Gallery, and Share.
 */
@Composable
fun ShotPreview(s: BrowserState, tab: Tab, shot: ScreenshotData) {
    val shotView = LocalView.current
    val style = LocalGlassStyle.current
    val dark = LocalPalette.current.dark
    DisposableEffect(Unit) {
        Haptics.fire(shotView, Hx.WINDOW_OPEN)
        onDispose { Haptics.fire(shotView, Hx.WINDOW_CLOSE) }
    }
    GlassLiveWindowLease(active = true)
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val enter = remember(shot) { Animatable(0f) }
    val actions = remember(shot) { Animatable(0f) }

    var asJpg by remember(shot) { mutableStateOf(false) }
    var cropping by remember(shot) { mutableStateOf(false) }
    var topTrim by remember(shot) { mutableFloatStateOf(0f) }
    var bottomTrim by remember(shot) { mutableFloatStateOf(1f) }
    var saved by remember(shot) { mutableStateOf(false) }
    var saving by remember(shot) { mutableStateOf(false) }
    var copied by remember(shot) { mutableStateOf(false) }
    var areaH by remember { mutableIntStateOf(0) }
    var contentH by remember { mutableIntStateOf(0) }

    val previewTiles = remember(shot) { slicePreviewTiles(shot.bitmap) }
    val croppedHeightPx = ((bottomTrim - topTrim).coerceIn(0.05f, 1f) * shot.bitmap.height).roundToInt().coerceAtLeast(1)
    val hasCrop = topTrim > 0.005f || bottomTrim < 0.995f

    LaunchedEffect(topTrim, bottomTrim, asJpg) {
        saved = false
        copied = false
    }

    LaunchedEffect(shot) { enter.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 420f)) }
    LaunchedEffect(shot) {
        delay(100)
        actions.animateTo(1f, spring(dampingRatio = 0.84f, stiffness = 420f))
    }
    val indicator by animateFloatAsState(
        if (scroll.isScrollInProgress) 1f else 0f,
        tween(if (scroll.isScrollInProgress) 90 else 650),
        label = "shotIndicator"
    )

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = enter.value.coerceIn(0f, 1f) }
            .background(scrim(0.76f))
            .clickable(remember { MutableInteractionSource() }, null) { s.screenshot = null },
        contentAlignment = Alignment.Center
    ) {
        val shotDrag = rememberGlassWindowDragState()
        Column(
            Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .widthIn(max = 440.dp)
                .graphicsLayer {
                    val e = enter.value
                    scaleX = 0.92f + 0.08f * e
                    scaleY = 0.92f + 0.08f * e
                    translationY = 48.dp.toPx() * (1f - e)
                }
                .liquidWindowTransform(shotDrag, maxPullDp = 46.dp)
                .liquidOverscrollJelly(
                    dismissDown = true,
                    dismissThresholdDp = 80.dp,
                    onOverscrollDismiss = { s.screenshot = null }
                )
                .liquidGlass(CardShape, 1.22f, 0.06f, reactive = false, blurProgress = { 1f })
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 14.dp)
        ) {
            GlassGrabber(
                Modifier.liquidWindowHandle(
                    shotDrag,
                    maxPullDp = 46.dp,
                    dismissDir = LiquidDismissDir.DOWN,
                    dismissThresholdDp = 64.dp,
                    onDismiss = { s.screenshot = null }
                )
            )
            // ── Header: Title + Dimensions + PNG/JPG pill + Crop button + Close ──
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        shot.title,
                        color = TextPrimary,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${shot.bitmap.width} × $croppedHeightPx px" +
                            (if (hasCrop) " · Cropped" else if (shot.screens > 1.05f) " · " + String.format(Locale.US, "%.1f", shot.screens) + " screens" else ""),
                        color = TextTertiary,
                        fontSize = 11.5.sp
                    )
                }
                // Compact PNG / JPG format chip
                GlowPress(
                    onClick = { asJpg = !asJpg },
                    shape = Capsule,
                    modifier = Modifier
                        .height(30.dp)
                        .glassTile(Capsule, 0.14f, if (asJpg) style.accent else Color.White)
                ) {
                    Text(
                        if (asJpg) "JPG" else "PNG",
                        color = if (asJpg) style.accent else TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    )
                }
                GlassIconButton(
                    AppleIcons.Crop, "Trim screenshot",
                    onClick = { cropping = !cropping },
                    size = 34.dp, iconSize = 16.5.dp,
                    accent = cropping || hasCrop,
                    filled = !cropping && !hasCrop
                )
                GlassIconButton(
                    AppleIcons.Close, "Close",
                    onClick = { s.screenshot = null },
                    size = 34.dp, iconSize = 16.dp, filled = false
                )
            }

            // ── Interactive Top / Bottom Trim Controls (when Crop is open) ──
            AnimatedVisibility(visible = cropping) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .glassTile(RoundedCornerShape(14.dp), 0.10f)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Top", color = TextSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(46.dp))
                        GlassSlider(
                            value = topTrim,
                            onValueChange = { topTrim = it.coerceIn(0f, (bottomTrim - 0.08f).coerceAtLeast(0f)) },
                            valueRange = 0f..0.92f,
                            modifier = Modifier.weight(1f)
                        )
                        Text("${(topTrim * 100f).roundToInt()}%", color = TextTertiary, fontSize = 11.sp, modifier = Modifier.width(34.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Bottom", color = TextSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(46.dp))
                        GlassSlider(
                            value = bottomTrim,
                            onValueChange = { bottomTrim = it.coerceIn((topTrim + 0.08f).coerceAtMost(1f), 1f) },
                            valueRange = 0.08f..1f,
                            modifier = Modifier.weight(1f)
                        )
                        Text("${(bottomTrim * 100f).roundToInt()}%", color = TextTertiary, fontSize = 11.sp, modifier = Modifier.width(34.dp))
                    }
                    if (hasCrop) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Text(
                                "Reset crop",
                                color = style.accent,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(Capsule)
                                    .clickableHaptic { topTrim = 0f; bottomTrim = 1f }
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── GPU-Safe Tiled Preview Canvas with Live Trim Dim Overlay ──
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (dark) Color.Black.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.06f))
                    .onSizeChanged { areaH = it.height }
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { contentH = it.height }
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            previewTiles.forEach { tile ->
                                Image(
                                    bitmap = tile,
                                    contentDescription = "Screenshot",
                                    modifier = Modifier.fillMaxWidth(),
                                    contentScale = ContentScale.FillWidth
                                )
                            }
                        }
                        // Live visual crop mask over trimmed top & bottom portions
                        if (hasCrop && contentH > 0) {
                            val topMaskDp = with(density) { (contentH * topTrim).toDp() }
                            val botMaskDp = with(density) { (contentH * (1f - bottomTrim)).toDp() }
                            if (topTrim > 0.005f) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopCenter)
                                        .fillMaxWidth()
                                        .height(topMaskDp)
                                        .background(Color.Black.copy(alpha = 0.62f))
                                ) {
                                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(style.accent))
                                }
                            }
                            if (bottomTrim < 0.995f) {
                                Box(
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .height(botMaskDp)
                                        .background(Color.Black.copy(alpha = 0.62f))
                                ) {
                                    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp).background(style.accent))
                                }
                            }
                        }
                    }
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
                            .background(if (dark) Color.White.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.45f), Capsule)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Action Row: Retake · Copy · Save · Share ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = actions.value.coerceIn(0f, 1f)
                        translationY = 24.dp.toPx() * (1f - actions.value)
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
                    size = 42.dp, iconSize = 18.dp
                )
                GlassIconButton(
                    if (copied) AppleIcons.Check else AppleIcons.Copy, "Copy image",
                    onClick = {
                        s.copyScreenshot(shot, asJpg, topTrim, bottomTrim) { ok ->
                            if (ok) copied = true
                        }
                    },
                    size = 42.dp, iconSize = 18.dp,
                    accent = copied
                )
                Spacer(Modifier.weight(1f))
                GlassPillButton(
                    if (saved) "Saved" else if (saving) "Saving…" else "Save",
                    onClick = {
                        saving = true
                        s.saveScreenshot(shot, asJpg, topTrim, bottomTrim) { ok ->
                            saving = false
                            saved = ok
                        }
                    },
                    icon = if (saved) AppleIcons.Check else AppleIcons.Download,
                    enabled = !saved && !saving
                )
                GlassPillButton(
                    "Share",
                    onClick = { s.shareScreenshot(shot, asJpg, topTrim, bottomTrim) },
                    icon = AppleIcons.Share,
                    accent = true
                )
            }
        }
    }
}
