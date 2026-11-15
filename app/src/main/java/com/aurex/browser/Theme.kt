package com.aurex.browser

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick as semanticsOnClick
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// ── Aurex glass design tokens ────────────────────────────────────────────────

val Capsule = RoundedCornerShape(percent = 50)
// iOS 27: one concentric radius system (26 -> 16 -> 12) used by every surface.
val CardShape = RoundedCornerShape(26.dp)
val TileShape = RoundedCornerShape(16.dp)
val SmallShape = RoundedCornerShape(12.dp)

/** UI theme mode: 0 = Auto / system, 1 = Light, 2 = Dark. */
object ThemeModes {
    const val AUTO = 0
    const val LIGHT = 1
    const val DARK = 2
}

/** Dark-website mode: 0 = Auto (follows the app), 1 = Always, 2 = Never. */
object DarkWebModes {
    const val AUTO = 0
    const val ON = 1
    const val OFF = 2
}

data class AurexPalette(
    val dark: Boolean,
    val bg: Color,
    val bgTop: Color,
    val bgBottom: Color,
    val glass: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val surfaceVariant: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
    /** Status colours for TEXT / thin glyphs: the fill colours above are too pale on white. */
    val goodText: Color,
    val warnText: Color,
    val badText: Color,
    /** Dim layer behind dialogs and sheets (lighter in light mode, a black wash looks heavy there). */
    val scrimScale: Float
)

val DarkPalette = AurexPalette(
    dark = true,
    bg = Color(0xFF000000),
    bgTop = Color(0xFF000000),
    bgBottom = Color(0xFF000000),
    glass = Color(0xFF1C1C1E),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFAEAEB5),
    textTertiary = Color(0xFF7C7C80),
    surfaceVariant = Color(0xFF2C2C2E),
    good = Color(0xFF30D158),
    warn = Color(0xFFFF9F0A),
    bad = Color(0xFFFF453A),
    goodText = Color(0xFF30D158),
    warnText = Color(0xFFFF9F0A),
    badText = Color(0xFFFF453A),
    scrimScale = 1f
)

val LightPalette = AurexPalette(
    dark = false,
    bg = Color(0xFFF2F2F7),
    bgTop = Color(0xFFF2F2F7),
    bgBottom = Color(0xFFF2F2F7),
    glass = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF000000),
    textSecondary = Color(0xFF55555A),
    // 0xFF8E8E93 is only ~3.3:1 on white; 0xFF6C6C70 (iOS secondaryLabel on white) reads clearly.
    textTertiary = Color(0xFF6C6C70),
    surfaceVariant = Color(0xFFE5E5EA),
    good = Color(0xFF34C759),
    warn = Color(0xFFFF9500),
    bad = Color(0xFFFF3B30),
    // iOS "accessible" light-mode variants: >= 4.5:1 on white.
    goodText = Color(0xFF1F7A3A),
    warnText = Color(0xFFB25000),
    badText = Color(0xFFD70015),
    scrimScale = 0.55f
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

val Ink: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.bg

val TextPrimary: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.textPrimary

val TextSecondary: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.textSecondary

val TextTertiary: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.textTertiary

val Good: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.good

val Warn: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.warn

val Bad: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.bad

/** Use these (not Good / Warn / Bad) when the colour is applied to text or small glyphs. */
val GoodText: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.goodText

val WarnText: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.warnText

val BadText: Color
    @Composable @ReadOnlyComposable get() = LocalPalette.current.badText

/** Dim layer behind modal surfaces. [alpha] is the dark-mode strength; light mode scales it down. */
@Composable
@ReadOnlyComposable
fun scrim(alpha: Float): Color =
    Color.Black.copy(alpha = (alpha * LocalPalette.current.scrimScale).coerceIn(0f, 1f))

data class GlassStyle(
    val accent: Color,
    val opacity: Float,
    val haptics: Boolean,
    val glow: Boolean,
    val dark: Boolean = true
)

val LocalGlassStyle = compositionLocalOf { GlassStyle(Color(0xFF0A84FF), 0.86f, true, true, true) }

fun onAccent(accent: Color): Color = Color.White

/** Apple Music red: every audio / music element uses this one hue (icon, tile, ring, label). */
val MusicRed = Color(0xFFFA2D48)

/**
 * Device class, decided once at start-up. Low-end phones get cheaper glass (no elevation shadow,
 * fewer gradient passes) so scrolling and swiping stay at full frame rate.
 */
object PerfTier {
    @Volatile
    var low: Boolean = Build.VERSION.SDK_INT < 28
        private set

    fun init(ctx: android.content.Context) {
        runCatching {
            val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val ramGb = info.totalMem / (1024f * 1024f * 1024f)
            low = am.isLowRamDevice ||
                Build.VERSION.SDK_INT < 28 ||
                ramGb < 3.3f ||
                Runtime.getRuntime().availableProcessors() <= 4
        }
    }
}

fun performTap(view: View) = Haptics.fire(view, Hx.TAP)

/** One step / tick: slider positions, lens slots, reorder slots. */
fun performPress(view: View) = Haptics.fire(view, Hx.TICK)

fun performHold(view: View) = Haptics.fire(view, Hx.HOLD)

fun performToggle(view: View, on: Boolean) = Haptics.fire(view, if (on) Hx.TOGGLE_ON else Hx.TOGGLE_OFF)

fun performDragStart(view: View) = Haptics.fire(view, Hx.DRAG_START)

fun performThreshold(view: View) = Haptics.fire(view, Hx.THRESHOLD)

@Composable
fun AurexTheme(
    accent: Color,
    glass: Float,
    haptics: Boolean,
    glow: Boolean,
    mode: Int = ThemeModes.DARK,
    /** Accent for Material widgets. Kept separate so a moving site accent never re-themes the whole tree. */
    schemeAccent: Color = accent,
    content: @Composable () -> Unit
) {
    val hapticCtx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.remember { Haptics.init(hapticCtx) }
    val hapticView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect { Haptics.enabled = haptics; Haptics.attach(hapticView) }
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeModes.LIGHT -> false
        ThemeModes.DARK -> true
        else -> systemDark
    }
    val palette = if (dark) DarkPalette else LightPalette
    // Apple system blue is fixed: no wallpaper / website / user-picked accent.
    val ink = if (dark) Color(0xFF0A84FF) else Color(0xFF007AFF)
    val scheme = remember(dark) { if (dark) {
        darkColorScheme(
            primary = ink,
            onPrimary = Color.White,
            secondary = ink,
            background = palette.bg,
            onBackground = palette.textPrimary,
            surface = palette.glass,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceVariant,
            onSurfaceVariant = palette.textSecondary,
            outline = Color.White.copy(alpha = 0.18f),
            error = palette.bad
        )
    } else {
        lightColorScheme(
            primary = ink,
            onPrimary = Color.White,
            secondary = ink,
            background = palette.bg,
            onBackground = palette.textPrimary,
            surface = palette.glass,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceVariant,
            onSurfaceVariant = palette.textSecondary,
            outline = Color.Black.copy(alpha = 0.14f),
            error = palette.bad
        )
    } }
    // Built once: re-creating this on every recomposition re-themed every text in the app.
    val type = remember {
        // SF-style text scale (Large Title 34 / Title 28-22-20 / Headline 17 / Body 17 / Subhead 15 / Footnote 13 / Caption 12-11)
        Typography(
            displayLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
            headlineLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
            headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.36.sp),
            headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.35.sp),
            titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.38.sp),
            titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.41).sp),
            titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.24).sp),
            bodyLarge = TextStyle(fontSize = 17.sp, letterSpacing = (-0.41).sp),
            bodyMedium = TextStyle(fontSize = 15.sp, letterSpacing = (-0.24).sp),
            bodySmall = TextStyle(fontSize = 13.sp, letterSpacing = (-0.08).sp),
            labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.24).sp),
            labelMedium = TextStyle(fontSize = 12.sp),
            labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 0.07.sp)
        )
    }
    CompositionLocalProvider(
        LocalGlassStyle provides GlassStyle(ink, glass, haptics, glow, dark),
        LocalPalette provides palette
    ) {
        MaterialTheme(colorScheme = scheme, typography = type) {
            ApplySystemBars(dark)
            content()
        }
    }
}

/** Keeps status/navigation icons readable while the page draws edge to edge. */
@Composable
fun ApplySystemBars(dark: Boolean) {
    val view = LocalView.current
    SideEffect {
        val activity = view.context as? Activity ?: return@SideEffect
        val window = activity.window
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }
}

/** Smooth backdrop: one continuous gradient so there is never a colour jump. */
@Composable
fun AppBackground(modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(palette.bgTop, palette.bg, palette.bgBottom)))
    )
}

fun Modifier.liquidGlass(
    shape: Shape = CardShape,
    level: Float = 1f,
    extraOpacity: Float = 0f
): Modifier = composed {
    val s = LocalGlassStyle.current
    val p = LocalPalette.current
    val dark = p.dark
    val highFidelity = Build.VERSION.SDK_INT >= 29 && !PerfTier.low

    // iOS 27 Liquid Glass: the Settings slider (clear -> tinted) drives the fill, with a higher floor so
    // text stays readable even at "clear", more depth behind the glass and a layered edge.
    val base = (s.opacity * 0.90f + extraOpacity * 0.15f).coerceIn(0.34f, 0.98f)
    val fill = if (dark) p.glass.copy(alpha = base)
               else Color.White.copy(alpha = (base + 0.05f).coerceAtMost(0.98f))
    val shadowDp = if (highFidelity) (6f + level.coerceIn(0.4f, 1.5f) * 4f).dp
                   else (2f + level.coerceIn(0.4f, 1.5f) * 1.5f).dp

    var m: Modifier = (
        if (PerfTier.low) this
        else this.shadow(
            elevation = shadowDp,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = if (dark) 0.36f else 0.10f),
            spotColor = Color.Black.copy(alpha = if (dark) 0.46f else 0.16f)
        )
    )
        .clip(shape)
        .background(fill)

    // iOS 27 layered glass: top specular + soft bottom refraction glow, then a sharper two-tone rim.
    if (highFidelity) {
        m = m.background(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (dark) 0.10f else 0.46f),
                0.40f to Color.Transparent,
                0.82f to Color.Transparent,
                1f to Color.White.copy(alpha = if (dark) 0.045f else 0.18f)
            )
        )
    }
    m.border(
        width = 0.75.dp,
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (dark) 0.36f else 0.95f),
            1f to (if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.10f))
        ),
        shape = shape
    )
}


val Separator: Color
    @Composable @ReadOnlyComposable get() =
        if (LocalPalette.current.dark) Color.White.copy(alpha = 0.13f) else Color(0xFF3C3C43).copy(alpha = 0.20f)

/** iOS 27 inset-grouped surface: flat fill, hairline rim, no shadow (lists and settings cards). */
fun Modifier.groupedSurface(shape: Shape = CardShape): Modifier = composed {
    val p = LocalPalette.current
    this
        .clip(shape)
        .background(if (p.dark) p.glass else Color.White)
        .border(0.5.dp, if (p.dark) Color.White.copy(alpha = 0.09f) else Color.Black.copy(alpha = 0.05f), shape)
}

/** iOS 27 floating toolbar: deeper liquid glass plus a thin specular streak along the top edge. */
fun Modifier.liquidGlassBar(shape: Shape = Capsule): Modifier = composed {
    val dark = LocalPalette.current.dark
    this
        .liquidGlass(shape, 1.4f, 0.05f)
        .drawWithContent {
            drawContent()
            if (!PerfTier.low) {
                val h = 1.dp.toPx()
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.5f to Color.White.copy(alpha = if (dark) 0.26f else 0.85f),
                        1f to Color.Transparent
                    ),
                    topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.10f, h),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.80f, h)
                )
            }
        }
}

/** Inset fill for rows, chips and content inside a surface (iOS system fill). No border, no accent. */
fun Modifier.glassTile(
    shape: Shape = TileShape,
    alpha: Float = 0.075f,
    tint: Color = Color.White
): Modifier = composed {
    val p = LocalPalette.current
    val base = when {
        p.dark -> tint.copy(alpha = alpha.coerceIn(0.03f, 0.26f))
        tint == Color.White -> Color(0xFF787880).copy(alpha = (alpha * 1.6f).coerceIn(0.06f, 0.16f))
        else -> tint.copy(alpha = (alpha * 0.46f).coerceIn(0.03f, 0.18f))
    }
    this.clip(shape).background(base)
}

/** Press scale + coloured liquid bloom used by every control in the app. */
@Composable
fun GlowPress(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = TileShape,
    enabled: Boolean = true,
    haptic: Boolean = true,
    contentAlignment: Alignment = Alignment.Center,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val style = LocalGlassStyle.current
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLong by rememberUpdatedState(onLongClick)
    val hasLong = onLongClick != null

    val scale by animateFloatAsState(
        if (pressed && enabled) 0.97f else 1f,
        // Higher stiffness + lower damping = snappier tap response (Apple feel).
        // This runs on the render thread via graphicsLayer so CPU cost is nil.
        spring(dampingRatio = 0.62f, stiffness = 1100f),
        label = "pressScale"
    )
    val glow by animateFloatAsState(
        if (pressed && enabled) 1f else 0f,
        // Faster bloom-in (80 ms) with a longer fade (280 ms) = tactile but clean.
        tween(if (pressed) 80 else 280),
        label = "pressGlow"
    )
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = (if (enabled) 1f else 0.42f) * (1f - 0.30f * glow)
            }
            .clip(shape)
            .semantics { role = Role.Button; semanticsOnClick { currentOnClick(); true } }
            .pointerInput(enabled, haptic, style.haptics, hasLong) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    if (!hasLong) {
                        val up = waitForUpOrCancellation()
                        pressed = false
                        if (up != null) {
                            up.consume()
                            if (haptic) performTap(view)
                            currentOnClick()
                        }
                    } else {
                        // Tap or press-and-hold. A drag that another detector takes over (the swipe-up on the
                        // Tabs button) cancels the wait before the timeout, so it never counts as a hold.
                        var up: androidx.compose.ui.input.pointer.PointerInputChange? = null
                        val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            up = waitForUpOrCancellation()
                            false
                        } ?: true
                        if (held) {
                            pressed = false
                            Haptics.fire(view, Hx.HOLD)
                            currentOnLong?.invoke()
                            // swallow the release so it does not also count as a tap
                            waitForUpOrCancellation()?.consume()
                        } else {
                            pressed = false
                            val u = up
                            if (u != null) {
                                u.consume()
                                if (haptic) performTap(view)
                                currentOnClick()
                            }
                        }
                    }
                }
            },
        contentAlignment = contentAlignment
    ) {
        content()
    }
}

/** Instant-response tap: soft accent glow + press scale + haptic confirm. */
fun Modifier.clickableHaptic(haptic: Boolean = true, kind: Hx = Hx.TAP, onClick: () -> Unit): Modifier = composed {
    val view = LocalView.current
    val style = LocalGlassStyle.current
    var pressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val glowA by animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 65 else 260), label = "tapGlow")
    val sc by animateFloatAsState(if (pressed) 0.983f else 1f, spring(dampingRatio = 0.65f, stiffness = 1200f), label = "tapScale")
    this
        .graphicsLayer { scaleX = sc; scaleY = sc }
        .drawWithContent {
            drawContent()
            if (glowA > 0.01f) drawRect(Color(0xFF787880).copy(alpha = 0.14f * glowA))
        }
        .pointerInput(haptic, kind, style.haptics) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                pressed = true
                val up = waitForUpOrCancellation()
                pressed = false
                if (up != null) {
                    up.consume()
                    if (haptic) Haptics.fire(view, kind)
                    currentOnClick()
                }
            }
        }
}
