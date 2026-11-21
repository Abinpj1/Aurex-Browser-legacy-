package com.aurex.browser

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
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
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
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
    bg = Color(0xFF070A14),
    bgTop = Color(0xFF070A14),
    bgBottom = Color(0xFF0A0C18),
    glass = Color(0xFF10131E),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFE2E2EC),
    textTertiary = Color(0xFFB8B8C6),
    surfaceVariant = Color(0xFF242836),
    good = Color(0xFF30D158),
    warn = Color(0xFFFF9F0A),
    bad = Color(0xFFFF453A),
    goodText = Color(0xFF3AE068),
    warnText = Color(0xFFFFB340),
    badText = Color(0xFFFF5A52),
    scrimScale = 1f
)

val LightPalette = AurexPalette(
    dark = false,
    bg = Color(0xFFECEFF6),
    bgTop = Color(0xFFECEFF6),
    bgBottom = Color(0xFFECEFF6),
    glass = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF0B0E14),
    textSecondary = Color(0xFF475060),
    textTertiary = Color(0xFF687285),
    surfaceVariant = Color(0xFFE0E5F0),
    good = Color(0xFF34C759),
    warn = Color(0xFFFF9500),
    bad = Color(0xFFFF3B30),
    goodText = Color(0xFF1B7434),
    warnText = Color(0xFFA84B00),
    badText = Color(0xFFCE0014),
    scrimScale = 0.52f
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

/**
 * False while a floating window or full-screen panel is playing its exit collapse animation:
 * immediately detaches all child pointer modifiers so touches pass 100% straight through on Frame 0!
 */
val LocalWindowInteractive = compositionLocalOf { true }

fun onAccent(accent: Color): Color = Color.White

/** Apple Music red: every audio / music element uses this one hue (icon, tile, ring, label). */
val MusicRed = Color(0xFFFA2D48)

/** Unified Apple iOS 27 floating popover metrics (Menu, Download Quality, Player Options). */
val FloatingPopoverWidth = 232.dp
val FloatingPopoverMaxHeight = 356.dp
val FloatingPopoverShape = RoundedCornerShape(26.dp)
val FloatingPopoverMargin = 12.dp

/**
 * Device class, decided once at start-up. Low-end phones get cheaper glass (no elevation shadow,
 * fewer gradient passes) so scrolling and swiping stay at full frame rate.
 */
object PerfTier {
    const val TIER_LOW = 0
    const val TIER_BALANCED = 1
    const val TIER_HIGH = 2

    @Volatile
    var tier: Int = if (Build.VERSION.SDK_INT < 29) TIER_LOW else TIER_BALANCED
        private set

    val low: Boolean get() = tier == TIER_LOW
    val balanced: Boolean get() = tier == TIER_BALANCED
    val high: Boolean get() = tier == TIER_HIGH
    val useAgsl: Boolean get() = tier == TIER_HIGH && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * Always automatic hardware calibration:
     *  - TIER_LOW: 2-4 GB RAM devices, Android <= 9, <= 4 cores, or Android Go (`isLowRamDevice`)
     *  - TIER_BALANCED: 6 GB mid-range or Android 10-12 devices
     *  - TIER_HIGH: 8-16 GB Android 13+ devices (full 4-tap AGSL Liquid Glass)
     */
    fun init(ctx: android.content.Context) {
        runCatching {
            val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val ramGb = info.totalMem / (1024f * 1024f * 1024f)
            val cores = Runtime.getRuntime().availableProcessors()
            tier = when {
                am.isLowRamDevice || Build.VERSION.SDK_INT < 29 || ramGb < 3.85f || cores <= 4 -> TIER_LOW
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ramGb < 5.8f || cores <= 6 -> TIER_BALANCED
                else -> TIER_HIGH
            }
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
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
        }
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }
}

/**
 * Pure OLED black (`#000000`) in Dark mode / crisp clean light (`#F2F4FA`) in Light mode.
 * Zero ambient glow, zero offscreen bitmap allocation, zero idle GPU work.
 */
@Composable
fun AppBackground(modifier: Modifier = Modifier) {
    val pureBase = if (LocalPalette.current.dark) Color.Black else Color(0xFFECEFF6)
    Box(modifier.fillMaxSize().background(pureBase))
}

/**
 * Apple Liquid Glass edge, drawn once per size (cached) BEHIND the content:
 *  - a soft inner glow that falls off over ~3 dp, brighter at the top (the glass has thickness),
 *  - a two-tone hairline rim lit from the top left, the way iOS lights its glass,
 *  - a "counter light" on the opposite (bottom right) corner, the signature two-corner highlight.
 * White stops use alpha 0 instead of Color.Transparent so the gradients do not fade through grey.
 */
internal fun Modifier.glassEdge(
    shape: Shape,
    dark: Boolean,
    touch: GlassTouch? = null,
    /** True when live GPU AGSL refraction is active: draws only a crisp 0.75dp Apple hairline (zero thick white border!). */
    subtle: Boolean = false
): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val none = Color.White.copy(alpha = 0f)
    val rim = (if (subtle) 0.65.dp else 0.75.dp).toPx()
    val warm = Color(1f, 0.95f, 0.88f)
    val cool = Color(0.84f, 0.91f, 1f)

    val glowA = if (subtle) 0f else (if (dark) 0.035f else 0.12f)
    val glow = Brush.verticalGradient(
        0f to Color.White.copy(alpha = glowA),
        1f to Color.White.copy(alpha = glowA * 0.30f)
    )
    val top = Brush.verticalGradient(
        0f to (if (dark) Color.White.copy(alpha = if (subtle) 0.22f else 0.26f) else Color(0xFF0F172A).copy(alpha = if (subtle) 0.09f else 0.10f)),
        0.55f to (if (dark) Color.White.copy(alpha = if (subtle) 0.08f else 0.10f) else Color(0xFF0F172A).copy(alpha = 0.08f)),
        1f to (if (dark) Color.White.copy(alpha = if (subtle) 0.07f else 0.09f) else Color(0xFF0F172A).copy(alpha = 0.15f))
    )
    val glowStrokes = arrayOf(
        Stroke(1.8.dp.toPx()),
        Stroke(3.4.dp.toPx())
    )
    val rimStroke = Stroke(rim * 2f)
    val touchRimStroke = Stroke(rim * 2.2f)
    val hx = size.width * 0.5f
    val hy = size.height * 0.5f
    val maxDim = maxOf(size.width, size.height)

    var cachedAngleQ = Int.MIN_VALUE
    var cachedTopLeft: Brush? = null
    var cachedBottomRight: Brush? = null

    onDrawWithContent {
        val th = GlassTilt.x * 0.55f + GlassTilt.y * 0.25f
        val angleQ = (th * 100f).toInt()
        if (angleQ != cachedAngleQ || cachedTopLeft == null || cachedBottomRight == null) {
            cachedAngleQ = angleQ
            val c = cos(th)
            val sn = sin(th)
            val rx = hx * c - hy * sn
            val ry = hx * sn + hy * c
            val a = Offset(hx - rx, hy - ry)
            val b = Offset(hx + rx, hy + ry)
            cachedTopLeft = Brush.linearGradient(
                0f to warm.copy(alpha = if (dark) (if (subtle) 0.26f else 0.34f) else 0.82f),
                0.28f to none,
                1f to none,
                start = a, end = b
            )
            cachedBottomRight = Brush.linearGradient(
                0f to none,
                0.72f to none,
                1f to cool.copy(alpha = if (dark) (if (subtle) 0.18f else 0.24f) else 0.68f),
                start = a, end = b
            )
        }

        val pr = touch?.press?.value?.coerceIn(0f, 1.25f) ?: 0f
        val rp = touch?.ripple?.value?.coerceIn(0f, 1f) ?: 0f

        // Live fingertip liquid mercury orb following finger inside the glass
        if (touch != null && pr > 0.01f) {
            val at = Offset(touch.x, touch.y)
            val pClamped = pr.coerceIn(0f, 1f)
            drawRect(
                Brush.radialGradient(
                    0f to Color.White.copy(alpha = (if (dark) 0.22f else 0.40f) * pClamped),
                    0.42f to cool.copy(alpha = (if (dark) 0.10f else 0.18f) * pClamped),
                    1f to none,
                    center = at, radius = (maxDim * 0.58f).coerceAtLeast(1f)
                )
            )
        }

        // Expanding concentric liquid caustic ripple wave (runs on ALL cards — 0 FBO, 120 FPS!)
        if (touch != null && rp > 0.01f && rp < 0.99f) {
            val at = Offset(touch.x, touch.y)
            val fade = (1f - rp) * (1f - rp)
            val ringR = (maxDim * 0.95f * rp).coerceAtLeast(4f)
            val peakA = (if (dark) 0.28f else 0.46f) * fade
            drawCircle(
                brush = Brush.radialGradient(
                    0.00f to none,
                    0.70f to none,
                    0.86f to cool.copy(alpha = peakA * 0.72f),
                    0.93f to Color.White.copy(alpha = peakA),
                    1.00f to none,
                    center = at,
                    radius = ringR
                ),
                radius = ringR,
                center = at
            )
        }

        if (!subtle && glowA > 0f) {
            for (i in glowStrokes.indices) {
                drawOutline(outline, glow, alpha = 1f - i * 0.45f, style = glowStrokes[i])
            }
        }
        drawOutline(outline, top, style = rimStroke)
        cachedTopLeft?.let { drawOutline(outline, it, style = rimStroke) }
        cachedBottomRight?.let { drawOutline(outline, it, style = rimStroke) }

        // Rim splash shimmer when the expanding liquid wave hits the glass border
        if (touch != null && rp > 0.12f && rp < 0.92f) {
            val splash = kotlin.math.sin(((rp - 0.12f) / 0.80f) * Math.PI).toFloat().coerceIn(0f, 1f)
            drawOutline(
                outline,
                Brush.radialGradient(
                    0f to Color.White.copy(alpha = (if (dark) 0.42f else 0.75f) * splash),
                    0.7f to cool.copy(alpha = (if (dark) 0.22f else 0.40f) * splash),
                    1f to none,
                    center = Offset(touch.x, touch.y),
                    radius = (maxDim * (0.45f + 0.55f * rp)).coerceAtLeast(1f)
                ),
                style = touchRimStroke
            )
        }
        if (touch != null && pr > 0.01f) {
            val pClamped = pr.coerceIn(0f, 1f)
            drawOutline(
                outline,
                Brush.radialGradient(
                    0f to Color.White.copy(alpha = (if (dark) 0.72f else 0.98f) * pClamped),
                    1f to none,
                    center = Offset(touch.x, touch.y), radius = (maxDim * 0.48f).coerceAtLeast(1f)
                ),
                style = touchRimStroke
            )
        }
        drawContent()
    }
}

/**
 * The Aurex glass surface. Layers, bottom to top: soft depth shadow, tinted body, light falling
 * diagonally from the top left plus a faint refraction glow along the bottom, then [glassEdge].
 * Phones in the low [PerfTier] get the plain tinted body and a single two-tone border.
 */

fun Modifier.liquidGlass(
    shape: Shape = CardShape,
    level: Float = 1f,
    extraOpacity: Float = 0f,
    /** Cards: lean/dip under the finger, touch-following highlight, tilt-driven specular. */
    reactive: Boolean = false,
    /** 0..1 optical crystallization progress (read in draw phase — zero recomposition). */
    blurProgress: () -> Float = { 1f },
    /** True = full GPU RenderEffect blur + AGSL 3D Snell refraction; false = fast 0-FBO frosted glass for repeating items. */
    optical: Boolean = true
): Modifier = composed {
    val s = LocalGlassStyle.current
    val p = LocalPalette.current
    val dark = p.dark
    val highFidelity = Build.VERSION.SDK_INT >= 29 && !PerfTier.low
    val hasLiveBackdrop = optical && LocalGlassBackdrop.current != null &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !PerfTier.low
    val lv = level.coerceIn(0.4f, 1.5f)

    // Single unified Apple iOS 27 Liquid Glass formula across EVERY button, orb, toolbar, menu & sheet:
    // identical blur radius, identical translucent frost, identical delicate specular rim everywhere!
    val sliderT = ((s.opacity - 0.35f) / 0.65f).coerceIn(0f, 1f)
    val base = if (hasLiveBackdrop) {
        val lo = if (dark) 0.28f else 0.44f
        val hi = if (dark) 0.48f else 0.64f
        (lo + (hi - lo) * sliderT + extraOpacity * 0.10f).coerceIn(0.26f, 0.68f)
    } else {
        val lo = if (dark) 0.48f else 0.72f
        val hi = if (dark) 0.68f else 0.88f
        (lo + (hi - lo) * sliderT + extraOpacity * 0.10f).coerceIn(0.46f, 0.90f)
    }

    val fill = if (dark) androidx.compose.ui.graphics.lerp(p.glass, Color.White, 0.05f).copy(alpha = base)
               else Color.White.copy(alpha = base)
    val shadowDp = if (highFidelity) (9f + lv * 4.5f).dp else (2f + lv * 1.5f).dp
    // One single consistent GPU blur radius across the entire UI (matches 3-dot Menu & Quality Sheet identically!)
    val blurDp = (9.5f + 4.5f * sliderT).coerceIn(9.5f, 14.0f)

    val enableTouch = LocalWindowInteractive.current && highFidelity && (reactive || hasLiveBackdrop)
    val touch = if (enableTouch) remember { GlassTouch() } else null
    val scope = if (touch != null) rememberCoroutineScope() else null
    if (enableTouch) GlassTiltLease(enabled = s.glow)
    val root: Modifier = when {
        reactive && touch != null && scope != null -> this.glassPressTilt(touch, scope)
        hasLiveBackdrop && touch != null && scope != null -> this.glassPressObserve(touch, scope)
        else -> this
    }

    var m: Modifier = (
        if (PerfTier.low) root
        else root.shadow(
            elevation = shadowDp,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = if (dark) 0.30f else 0.12f),
            spotColor = Color.Black.copy(alpha = if (dark) 0.40f else 0.18f)
        )
    ).clip(shape)

    if (hasLiveBackdrop) {
        m = m.liquidBackdrop(
            shape = shape,
            dark = dark,
            tint = fill,
            blurDp = blurDp,
            refractionScale = (0.85f + 0.25f * lv),
            blurProgress = blurProgress,
            touch = touch
        )
    } else {
        m = m.background(fill)
    }

    if (highFidelity) {
        val none = Color.White.copy(alpha = 0f)
        // Keep dark-mode overlay sheen whisper-thin so cards never look milky or whitish-frosted
        val sheenScale = when {
            hasLiveBackdrop -> 0.20f
            dark -> 0.16f
            else -> 0.72f
        }
        m = m.background(
            Brush.linearGradient(
                0f to Color.White.copy(alpha = (if (dark) 0.10f else 0.40f) * sheenScale),
                0.34f to none,
                1f to Color.White.copy(alpha = (if (dark) 0.03f else 0.12f) * sheenScale)
            )
        )
        if (!hasLiveBackdrop && !dark) {
            m = m.background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.30f),
                    0.30f to none,
                    0.78f to none,
                    1f to Color.White.copy(alpha = 0.16f)
                )
            )
        }
        m.glassEdge(shape, dark, touch, subtle = hasLiveBackdrop || dark)
    } else {
        m.border(
            width = 0.85.dp,
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (dark) 0.34f else 0.95f),
                0.55f to Color.White.copy(alpha = if (dark) 0.12f else 0.45f),
                1f to (if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.10f))
            ),
            shape = shape
        )
    }
}

/**
 * Apple's tinted ("prominent") liquid glass - what the blue buttons are on iOS 26. Not a flat paint:
 *  - a lit body: lighter on top, deeper at the bottom, a little see-through,
 *  - a soft coloured glow under the control (the glass tints the light it throws),
 *  - the same lit rim, counter-light and tilt-driven specular as [liquidGlass],
 *  - interactive press: the control swells under the finger, a bloom follows the finger, and it
 *    wobbles back on release ([glassPressSwell]). [swell] is the scale gained at full press: keep it
 *    small on wide controls (a 0.07 swell on a 140 dp pill is +10 dp).
 * Low-tier phones get the lit body + border and the swell, without the extra gradient passes.
 */
fun Modifier.tintedGlass(shape: Shape, tint: Color, swell: Float = 0.075f): Modifier = composed {
    val s = LocalGlassStyle.current
    val dark = LocalPalette.current.dark
    val highFidelity = Build.VERSION.SDK_INT >= 29 && !PerfTier.low
    val touch = if (LocalWindowInteractive.current && highFidelity) remember { GlassTouch() } else null
    val scope = if (touch != null) rememberCoroutineScope() else null
    if (highFidelity) GlassTiltLease(enabled = s.glow)

    val none = Color.White.copy(alpha = 0f)
    val lit = androidx.compose.ui.graphics.lerp(tint, Color.White, if (dark) 0.28f else 0.32f)
    val deep = androidx.compose.ui.graphics.lerp(tint, Color.Black, if (dark) 0.14f else 0.08f)

    var m: Modifier = if (touch != null && scope != null) this.glassPressSwell(touch, scope, swell) else this
    if (!PerfTier.low) {
        m = m.shadow(
            elevation = if (highFidelity) 10.dp else 3.dp,
            shape = shape,
            clip = false,
            ambientColor = tint.copy(alpha = 0.38f),
            spotColor = tint.copy(alpha = 0.56f)
        )
    }
    m = m
        .clip(shape)
        .background(
            Brush.verticalGradient(
                0f to lit.copy(alpha = 0.88f),
                0.55f to tint.copy(alpha = 0.84f),
                1f to deep.copy(alpha = 0.90f)
            )
        )
    if (highFidelity) {
        m = m.background(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (dark) 0.26f else 0.34f),
                0.38f to none,
                0.76f to none,
                1f to Color.White.copy(alpha = if (dark) 0.16f else 0.20f)
            )
        )
        m.glassEdge(shape, dark, touch)
    } else {
        m.border(
            width = 0.75.dp,
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.58f),
                1f to Color.White.copy(alpha = 0.14f)
            ),
            shape = shape
        )
    }
}

val Separator: Color
    @Composable @ReadOnlyComposable get() =
        if (LocalPalette.current.dark) Color.White.copy(alpha = 0.13f) else Color(0xFF0F172A).copy(alpha = 0.10f)

/**
 * Clean, uniform Apple iOS inset-grouped card surface for Settings, Downloads, Media rows,
 * Bookmarks & History:
 *  - Dark mode: sleek deep charcoal (`#14161D`) with a whisper-thin hairline border (zero whitish frosted haze).
 *  - Light mode: crisp elevated pure white (`#FFFFFF`) card over `#ECEFF6` canvas with subtle slate border.
 */
fun Modifier.groupedSurface(shape: Shape = CardShape, reactive: Boolean = false): Modifier = composed {
    val p = LocalPalette.current
    val s = LocalGlassStyle.current
    val dark = p.dark
    val highFidelity = Build.VERSION.SDK_INT >= 29 && !PerfTier.low

    val touch = if (LocalWindowInteractive.current && reactive && highFidelity) remember { GlassTouch() } else null
    val scope = if (touch != null) rememberCoroutineScope() else null
    if (reactive && highFidelity) GlassTiltLease(enabled = s.glow)
    val root: Modifier = if (touch != null && scope != null) this.glassPressTilt(touch, scope) else this

    val fill = if (dark) Color(0xFF14161D) else Color.White.copy(alpha = 0.98f)
    val base: Modifier = (
        if (!dark && !PerfTier.low) {
            root.shadow(
                elevation = 3.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.07f),
                spotColor = Color.Black.copy(alpha = 0.11f)
            )
        } else root
    ).clip(shape).background(fill)

    val rim = if (dark) {
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.10f),
            0.5f to Color.White.copy(alpha = 0.055f),
            1f to Color.White.copy(alpha = 0.04f)
        )
    } else {
        Brush.verticalGradient(
            0f to Color(0xFF0F172A).copy(alpha = 0.07f),
            1f to Color(0xFF0F172A).copy(alpha = 0.13f)
        )
    }
    base.border(0.75.dp, rim, shape)
}

/**
 * Floating toolbar: deeper glass plus a thin specular streak along the top edge and a fainter
 * reflection along the bottom edge (light bouncing back through the glass).
 */
fun Modifier.liquidGlassBar(shape: Shape = Capsule, blurProgress: () -> Float = { 1f }): Modifier = composed {
    val dark = LocalPalette.current.dark
    val highFidelity = Build.VERSION.SDK_INT >= 29 && !PerfTier.low
    val barTouch = if (highFidelity) remember { GlassTouch() } else null
    val barScope = if (barTouch != null) rememberCoroutineScope() else null
    val pulseMod = if (barTouch != null && barScope != null) this.glassBarPulse(barTouch, barScope) else this
    pulseMod
        .liquidGlass(shape, 1.08f, 0f, reactive = false, blurProgress = blurProgress)
        .drawWithCache {
            if (PerfTier.low) {
                onDrawWithContent { drawContent() }
            } else {
                val h = 1.25.dp.toPx()
                val clear = Color.White.copy(alpha = 0f)
                val cyan = Color(0xFF8BE2FF)
                val topY = h
                val botY = size.height - h * 2.2f
                val w1 = size.width * 0.78f
                val w2 = size.width * 0.58f
                val s1 = androidx.compose.ui.geometry.Size(w1, h)
                val s2 = androidx.compose.ui.geometry.Size(w2, h)
                var lastShiftQ = Int.MIN_VALUE
                var topStreak: Brush? = null
                var botStreak: Brush? = null
                onDrawWithContent {
                    drawContent()
                    val shift = (GlassTilt.x * 0.22f).coerceIn(-0.20f, 0.20f)
                    val q = (shift * 60f).toInt()
                    if (q != lastShiftQ || topStreak == null || botStreak == null) {
                        lastShiftQ = q
                        val cTop = (0.50f + shift).coerceIn(0.24f, 0.76f)
                        val cBot = (0.50f - shift).coerceIn(0.24f, 0.76f)
                        topStreak = Brush.horizontalGradient(
                            0f to clear,
                            cTop to Color.White.copy(alpha = if (dark) 0.46f else 0.96f),
                            1f to clear
                        )
                        botStreak = Brush.horizontalGradient(
                            0f to clear,
                            cBot to cyan.copy(alpha = if (dark) 0.24f else 0.50f),
                            1f to clear
                        )
                    }
                    topStreak?.let { drawRect(brush = it, topLeft = Offset(size.width * 0.11f, topY), size = s1) }
                    botStreak?.let { drawRect(brush = it, topLeft = Offset(size.width * 0.21f, botY), size = s2) }
                }
            }
        }
}

/** Frosted glassmorphic inset fill for rows, chips and pills with high-contrast smoked backing in dark mode. */
fun Modifier.glassTile(
    shape: Shape = TileShape,
    alpha: Float = 0.075f,
    tint: Color = Color.White
): Modifier = composed {
    val p = LocalPalette.current
    val dark = p.dark
    val darkUnderlay = Color(0xFF0A0D16)
    val rim = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (dark) 0.22f else 0.88f),
        1f to (if (dark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.08f))
    )
    if (dark && tint == Color.White) {
        // Smoked dark quartz prevents bright thumbnails underneath from turning chips into pale unreadable grey!
        val smokeA = (0.36f + alpha * 1.4f).coerceIn(0.42f, 0.66f)
        this
            .clip(shape)
            .background(darkUnderlay.copy(alpha = smokeA))
            .background(Color.White.copy(alpha = 0.055f))
            .border(0.65.dp, rim, shape)
    } else if (dark) {
        this
            .clip(shape)
            .background(darkUnderlay.copy(alpha = 0.38f))
            .background(tint.copy(alpha = (alpha * 1.55f).coerceIn(0.16f, 0.36f)))
            .border(0.65.dp, rim, shape)
    } else {
        val base = if (tint == Color.White) {
            Color(0xFF0F172A).copy(alpha = (0.065f + alpha * 0.30f).coerceIn(0.065f, 0.13f))
        } else {
            tint.copy(alpha = (alpha * 1.15f).coerceIn(0.13f, 0.28f))
        }
        val lightRim = if (tint == Color.White) {
            Color(0xFF0F172A).copy(alpha = 0.10f)
        } else {
            tint.copy(alpha = 0.24f)
        }
        this
            .clip(shape)
            .background(base)
            .border(0.75.dp, lightRim, shape)
    }
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
    /** True when the caller's background is a [tintedGlass] body: that body swells itself, so the label must not shrink / dim. */
    glassBody: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val style = LocalGlassStyle.current
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLong by rememberUpdatedState(onLongClick)
    val hasLong = onLongClick != null

    val interactive = enabled && LocalWindowInteractive.current
    val lowTier = PerfTier.low
    val scaleState = animateFloatAsState(
        if (pressed && interactive && !glassBody) 1.030f else 1f,
        if (lowTier) tween(if (pressed) 35 else 80)
        else if (pressed) spring(dampingRatio = 0.62f, stiffness = 780f)
        else spring(dampingRatio = 0.52f, stiffness = 480f),
        label = "pressScale"
    )
    val glowState = animateFloatAsState(
        if (pressed && interactive && !glassBody) 1f else 0f,
        tween(if (pressed) 35 else if (lowTier) 80 else 140),
        label = "pressGlow"
    )
    val dark = LocalPalette.current.dark
    Box(
        modifier
            .graphicsLayer {
                val sc = scaleState.value
                if (sc != 1f) {
                    scaleX = sc
                    scaleY = sc
                }
                if (!enabled) alpha = 0.42f
            }
            .clip(shape)
            .drawWithContent {
                drawContent()
                val g = glowState.value.coerceIn(0f, 1f)
                if (g > 0.01f) {
                    drawRect((if (dark) Color.White.copy(alpha = 0.12f * g) else Color.Black.copy(alpha = 0.065f * g)))
                }
            }
            .semantics { role = Role.Button; semanticsOnClick { if (interactive) currentOnClick(); true } }
            .then(
                if (!interactive) Modifier
                else Modifier.pointerInput(haptic, style.haptics, hasLong) {
                    val maxDrift = viewConfiguration.touchSlop * 1.65f
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        pressed = true
                        if (!hasLong) {
                            val up = awaitSlopTolerantUp(down.id, down.position, maxDrift)
                            pressed = false
                            if (up != null) {
                                up.consume()
                                if (haptic) performTap(view)
                                currentOnClick()
                            }
                        } else {
                            var up: PointerInputChange? = null
                            val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                up = awaitSlopTolerantUp(down.id, down.position, maxDrift)
                                false
                            } ?: true
                            if (held) {
                                pressed = false
                                Haptics.fire(view, Hx.HOLD)
                                currentOnLong?.invoke()
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
                }
            ),
        contentAlignment = contentAlignment
    ) {
        content()
    }
}

/**
 * Reliable, slop-tolerant tap finisher:
 *  - Never cancels a rapid tap if the thumb rolls 1-2px past a small icon edge.
 *  - Cancels immediately if a parent scroll/swipe/drag takes ownership of the movement.
 */
private suspend fun AwaitPointerEventScope.awaitSlopTolerantUp(
    pointerId: PointerId,
    startPos: Offset,
    maxDriftPx: Float
): PointerInputChange? {
    val maxDriftSq = maxDriftPx * maxDriftPx
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        val ch = event.changes.firstOrNull { it.id == pointerId } ?: return null
        if (ch.changedToUp()) return ch
        if (ch.changedToUpIgnoreConsumed()) return null
        if (ch.isConsumed && ch.positionChanged()) return null
        val dx = ch.position.x - startPos.x
        val dy = ch.position.y - startPos.y
        if (dx * dx + dy * dy > maxDriftSq) return null
    }
}

/** Instant-response tap backed by native Compose clickable; completely hit-test transparent while parent window closes. */
fun Modifier.clickableHaptic(haptic: Boolean = true, kind: Hx = Hx.TAP, onClick: () -> Unit): Modifier = composed {
    val interactive = LocalWindowInteractive.current
    val view = LocalView.current
    val currentOnClick by rememberUpdatedState(onClick)
    val dark = LocalPalette.current.dark
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val lowTier = PerfTier.low
    val glowState = animateFloatAsState(
        if (pressed && interactive) 1f else 0f,
        tween(if (pressed) 35 else if (lowTier) 80 else 140),
        label = "tapGlow"
    )
    val scState = animateFloatAsState(
        if (pressed && interactive) 0.978f else 1f,
        if (lowTier) tween(if (pressed) 35 else 80)
        else if (pressed) spring(dampingRatio = 0.68f, stiffness = 780f)
        else spring(dampingRatio = 0.56f, stiffness = 500f),
        label = "tapScale"
    )
    this
        .graphicsLayer {
            val s = scState.value
            if (s != 1f) {
                scaleX = s
                scaleY = s
            }
        }
        .drawWithContent {
            drawContent()
            val g = glowState.value.coerceIn(0f, 1f)
            if (g > 0.01f) drawRect((if (dark) Color.White.copy(alpha = 0.13f * g) else Color.Black.copy(alpha = 0.065f * g)))
        }
        .then(
            if (!interactive) Modifier
            else Modifier.clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                if (haptic) Haptics.fire(view, kind)
                currentOnClick()
            }
        )
}
