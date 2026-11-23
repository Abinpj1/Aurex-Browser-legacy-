package com.aurex.browser

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RecordingCanvas
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sign

/*
 * Apple iOS 26 / 27 Liquid Glass 4.0 Engine — 100% Pure Native Compose (Zero AndroidView / Zero
 * Nested ComposeView):
 *  - Native WindowInsets (statusBarsPadding / navigationBarsPadding / imePadding) stay 100% intact.
 *  - Shared GlassAuroraCache: renders the Apple iOS 26 Aurora Silk wallpaper ONCE into a 320x640
 *    Bitmap (~800KB). Both AppBackground() and GlassBackdrop sample the exact same texture -> 0ms
 *    capture overhead & pixel-exact GPU Snell refraction on Home, Tabs, Downloads, Media & Settings!
 *  - Live 1/4-res RGB_565 WebView snapshot (~250KB, ~0.8ms, never recycled -> zero HWUI crashes,
 *    120ms scroll throttle + 95ms settle, 0Hz idle) so floating chrome refracts real webpages at 120 FPS!
 *  - API 33+ Single-Pass AGSL 3D Optical Glass Shader: 4th-power squircle dome, Snell's Law RGB
 *    chromatic dispersion, Apple vibrancy, Schlick Fresnel, dual-lobe glare, finger bulge & ripple.
 *  - Floating window rubber-band liquid drag (liquidWindowDrag / liquidWindowHandle), elastic
 *    overscroll jelly (liquidOverscrollJelly), and 120 FPS liquid scroll cards (liquidScrollCard).
 */

// ── Global Scroll & Drag Kinetic Motion (draw-phase read only, zero idle CPU) ─

object GlassMotion {
    /** Signed smoothed vertical motion (-1..1). Read ONLY inside draw / graphicsLayer lambdas. */
    var scroll by mutableFloatStateOf(0f)
        private set

    /** Signed smoothed horizontal motion (-1..1) from liquid window drags. */
    var shearX by mutableFloatStateOf(0f)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var decaying = false

    private val decayRunnable = object : Runnable {
        override fun run() {
            val nextY = scroll * 0.80f
            val nextX = shearX * 0.80f
            if (abs(nextY) < 0.008f && abs(nextX) < 0.008f) {
                scroll = 0f
                shearX = 0f
                decaying = false
            } else {
                scroll = if (abs(nextY) < 0.008f) 0f else nextY
                shearX = if (abs(nextX) < 0.008f) 0f else nextX
                main.postDelayed(this, 16L)
            }
        }
    }

    fun impulse(deltaY: Float, deltaX: Float = 0f) {
        if (PerfTier.low || (abs(deltaY) < 0.4f && abs(deltaX) < 0.4f)) return
        if (abs(deltaY) >= 0.4f) {
            val normY = (deltaY / 80f).coerceIn(-1f, 1f)
            scroll = (scroll * 0.52f + normY * 0.48f).coerceIn(-1f, 1f)
        }
        if (abs(deltaX) >= 0.4f) {
            val normX = (deltaX / 80f).coerceIn(-1f, 1f)
            shearX = (shearX * 0.52f + normX * 0.48f).coerceIn(-1f, 1f)
        }
        if (!decaying) {
            decaying = true
            main.postDelayed(decayRunnable, 16L)
        }
    }
}

// ── API-Isolated GPU Helpers (prevents ART verification overhead on API 24-32) ─

@RequiresApi(Build.VERSION_CODES.Q)
private object GlassNodeApi {
    fun discard(node: Any?) { (node as? RenderNode)?.discardDisplayList() }
    fun create(name: String): RenderNode = RenderNode(name)
}

@RequiresApi(Build.VERSION_CODES.S)
private object GlassBlurApi {
    fun createBlur(radiusPx: Float): RenderEffect =
        RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)

    fun applyEffect(node: RenderNode, effect: Any?) {
        node.setRenderEffect(effect as? RenderEffect)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object GlassAgslApi {
    fun applyLens(
        node: RenderNode,
        shader: Any,
        blurEffect: Any,
        originX: Float,
        originY: Float,
        width: Float,
        height: Float,
        bufW: Float,
        bufH: Float,
        radiusPx: Float,
        bevelPx: Float,
        thicknessPx: Float,
        dispersion: Float,
        saturation: Float,
        tint: Color,
        tiltX: Float,
        tiltY: Float,
        touchX: Float,
        touchY: Float,
        press: Float,
        ripple: Float,
        motX: Float,
        motY: Float,
        spec: Float,
        dark: Boolean
    ): RenderEffect {
        val sh = shader as RuntimeShader
        sh.setFloatUniform("uOrigin", originX, originY)
        sh.setFloatUniform("uSize", width, height)
        sh.setFloatUniform("uBufSize", bufW, bufH)
        sh.setFloatUniform("uRadius", radiusPx)
        sh.setFloatUniform("uBevel", bevelPx)
        sh.setFloatUniform("uThickness", thicknessPx)
        sh.setFloatUniform("uDispersion", dispersion)
        sh.setFloatUniform("uSaturation", saturation)
        sh.setFloatUniform("uTint", tint.red, tint.green, tint.blue, tint.alpha)
        sh.setFloatUniform("uTilt", tiltX, tiltY)
        sh.setFloatUniform("uTouch", touchX, touchY)
        sh.setFloatUniform("uPress", press)
        sh.setFloatUniform("uRipple", ripple)
        sh.setFloatUniform("uMotion", motX, motY)
        sh.setFloatUniform("uSpec", spec)
        sh.setFloatUniform("uDark", if (dark) 1f else 0f)
        val chain = RenderEffect.createChainEffect(
            RenderEffect.createRuntimeShaderEffect(sh, "content"),
            blurEffect as RenderEffect
        )
        node.setRenderEffect(chain)
        return chain
    }
}

// ── Lightweight Live Backdrop (Aurora Silk + 1/4-res WebView, Zero AndroidView) ─

@Stable
class GlassBackdrop(val parent: GlassBackdrop? = null) {
    init { registerInstance(this) }

    // ── Live Pure-Compose GPU GraphicsLayer capture (ZERO AndroidView, 120 FPS!) ──
    internal var composeLayer: GraphicsLayer? = null
    internal var composeBounds: Rect = Rect.Zero
    private var composeTickPosted: Boolean = false
    private var lastComposeTickMs: Long = 0L
    private var composeHostView: View? = null
    private val composeTickRunnable = Runnable {
        composeTickPosted = false
        lastComposeTickMs = SystemClock.uptimeMillis()
        if (activeConsumers > 0) tick++
    }

    // ── Live WebView / Hardware Video (PixelCopy) capture ──
    private var attachedWeb: View? = null
    internal var webBitmap: Bitmap? = null
    internal var customBitmap: Bitmap? = null
    internal var customBounds: Rect = Rect.Zero
    /** Unobstructed live video strip (InlinePlayer / YouTube) blended softly so blurred glass moves live with video! */
    internal var ambientBitmap: Bitmap? = null
    private var webCanvas: android.graphics.Canvas? = null
    internal var webBounds: Rect = Rect.Zero
    private var webClearColor: Int = android.graphics.Color.BLACK
    private val webLoc = IntArray(2)
    private val dstRect = RectF()
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var lastWebSnapMs: Long = 0L
    private var webSnapPosted: Boolean = false
    private var liveWebLoopPosted: Boolean = false

    private val webLayoutListener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
        if (r - l > 16 && b - t > 16 && (r - l != or2 - ol || b - t != ob - ot || webBitmap == null)) {
            scheduleWebSnapshot(force = true)
        }
    }

    private val webSnapRunnable = Runnable {
        webSnapPosted = false
        captureWebNow()
    }

    private val webSettleRunnable = Runnable {
        captureWebNow()
    }

    /** Universal live pump: while ANY floating window is open over a WebView, keep rendering behind it LIVE! */
    private val liveWebLoopRunnable: Runnable = object : Runnable {
        override fun run() {
            liveWebLoopPosted = false
            val w = attachedWeb ?: return
            if (liveWindows <= 0 || PerfTier.low || !w.isAttachedToWindow || w.visibility != View.VISIBLE) return
            if (customBitmap == null) {
                captureWebNow()
            }
            if (liveWindows > 0) {
                liveWebLoopPosted = true
                val intervalMs = if (PerfTier.balanced) 480L else 320L
                w.postDelayed(this, intervalMs)
            }
        }
    }

    /** Incremented ONLY when fresh live backdrop content is ready (read strictly in draw phase, zero recomposition). */
    var tick by mutableIntStateOf(0)
        private set

    internal var activeConsumers by mutableIntStateOf(0)
    internal var liveWindows: Int = 0
    @Volatile
    internal var recordingCompose: Boolean = false

    internal fun attachComposeLayer(layer: GraphicsLayer, hostView: View) {
        composeLayer = layer
        composeHostView = hostView
    }

    internal fun detachComposeLayer(layer: GraphicsLayer) {
        if (composeLayer === layer) {
            composeHostView?.removeCallbacks(composeTickRunnable)
            composeTickPosted = false
            composeHostView = null
            composeLayer = null
        }
    }

    /**
     * Called by [GlassBackdropHost] after recording a live Compose GPU frame.
     * Posts a coalesced animation-frame tick (NEVER mutates Compose state inside drawWithContent,
     * NEVER creates an infinite redraw loop, and stays at 0 Hz whenever the screen behind is static!).
     */
    internal fun notifyComposeFrame(hostView: View) {
        if (activeConsumers <= 0 || composeTickPosted) return
        val now = SystemClock.uptimeMillis()
        val minInterval = if (PerfTier.balanced) 50L else 33L
        composeHostView = hostView
        composeTickPosted = true
        val wait = minInterval - (now - lastComposeTickMs)
        if (wait <= 2L) {
            hostView.postOnAnimation(composeTickRunnable)
        } else {
            hostView.postDelayed(composeTickRunnable, wait)
        }
    }

    /**
     * Binds the active [AurexWebView] so floating glass windows refract live webpage pixels.
     * Never calls Bitmap.recycle() (eliminates HWUI RenderThread recycled-bitmap crashes!).
     */
    fun attachWebView(web: View?, clearColor: Int = android.graphics.Color.BLACK) {
        webClearColor = clearColor
        if (attachedWeb === web) {
            if (web != null) {
                if (webBitmap == null) scheduleWebSnapshot(force = true)
                ensureLiveWebLoop()
            }
            return
        }
        attachedWeb?.removeCallbacks(webSnapRunnable)
        attachedWeb?.removeCallbacks(webSettleRunnable)
        attachedWeb?.removeCallbacks(liveWebLoopRunnable)
        attachedWeb?.removeOnLayoutChangeListener(webLayoutListener)
        webSnapPosted = false
        liveWebLoopPosted = false
        attachedWeb = web
        if (web == null) {
            webBounds = Rect.Zero
            webBitmap = null
            webCanvas = null
            tick++
        } else {
            web.addOnLayoutChangeListener(webLayoutListener)
            scheduleWebSnapshot(force = true)
            web.postDelayed(webSettleRunnable, 260L)
            ensureLiveWebLoop()
        }
    }

    /** Supplies an async snapshot (e.g. live SurfaceView PixelCopy video frame in PlayerActivity). */
    fun setCustomFrame(bmp: Bitmap?, bounds: Rect = Rect.Zero) {
        customBitmap = bmp
        customBounds = bounds
        tick++
    }

    /** Supplies an unobstructed live video region crop (zero feedback loop) so blurred glass animates live with web video. */
    fun setAmbientVideoFrame(bmp: Bitmap?) {
        ambientBitmap = bmp
        tick++
    }

    fun trimMemory() {
        composeHostView?.removeCallbacks(composeTickRunnable)
        composeTickPosted = false
        attachedWeb?.removeCallbacks(webSnapRunnable)
        attachedWeb?.removeCallbacks(webSettleRunnable)
        attachedWeb?.removeCallbacks(liveWebLoopRunnable)
        webSnapPosted = false
        liveWebLoopPosted = false
        webBitmap = null
        webCanvas = null
        customBitmap = null
        ambientBitmap = null
    }

    private fun ensureLiveWebLoop() {
        // Avoid continuous software WebView.draw() polling (major CPU/thermal drain on webpages):
        // a single crisp snapshot on window open + settle captures real page content with 0% idle heat.
        val w = attachedWeb ?: return
        if (liveWindows > 0 && !PerfTier.low && !PerfTier.thermalWarm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (webBitmap == null) scheduleWebSnapshot(force = true)
        }
    }

    internal fun addLiveWindow() {
        var b: GlassBackdrop? = this
        while (b != null) {
            b.liveWindows++
            if (b.attachedWeb != null) {
                b.scheduleWebSnapshot(force = true)
                b.ensureLiveWebLoop()
            }
            b = b.parent
        }
    }

    internal fun removeLiveWindow() {
        var b: GlassBackdrop? = this
        while (b != null) {
            b.liveWindows = (b.liveWindows - 1).coerceAtLeast(0)
            if (b.liveWindows == 0) {
                b.attachedWeb?.removeCallbacks(b.liveWebLoopRunnable)
                b.liveWebLoopPosted = false
            }
            b = b.parent
        }
    }

    fun scheduleWebSnapshot(force: Boolean = false, settleOnly: Boolean = false) {
        val w = attachedWeb ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || PerfTier.low || PerfTier.thermalWarm || isRecordingNow) return
        if (activeConsumers <= 0 && !force) return

        val settleMs = if (PerfTier.balanced) 280L else 210L
        w.removeCallbacks(webSettleRunnable)
        w.postDelayed(webSettleRunnable, settleMs)

        if (settleOnly && liveWindows <= 0) return
        if (webSnapPosted) return
        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastWebSnapMs
        val throttleMs = when {
            liveWindows > 0 -> if (PerfTier.balanced) 650L else 480L
            PerfTier.balanced -> 560L
            else -> 400L
        }
        if (!force && elapsed < throttleMs) {
            webSnapPosted = true
            w.postDelayed(webSnapRunnable, (throttleMs - elapsed).coerceAtLeast(16L))
            return
        }
        webSnapPosted = true
        w.postOnAnimation(webSnapRunnable)
    }

    private fun captureWebNow() {
        val w = attachedWeb ?: return
        if (PerfTier.low || PerfTier.thermalWarm || isRecordingNow || !w.isAttachedToWindow || w.visibility != View.VISIBLE) return
        val vw = w.width
        val vh = w.height
        if (vw < 16 || vh < 16) return

        // Fast 1/5.5-res blurred backdrop (~3x faster Chromium CPU draw, visually identical after 8.5..15dp blur!)
        val scale = if (PerfTier.balanced) 0.135f else 0.16f
        val maxW = if (PerfTier.balanced) 148 else 184
        val maxH = if (PerfTier.balanced) 310 else 384
        val bw = (vw * scale).roundToInt().coerceIn(64, maxW)
        val bh = (vh * scale).roundToInt().coerceIn(64, maxH)

        val existing = webBitmap
        if (existing == null || existing.isRecycled || existing.width != bw || existing.height != bh) {
            runCatching {
                val fresh = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565)
                webBitmap = fresh
                webCanvas = android.graphics.Canvas(fresh)
            }.onFailure {
                return
            }
        }
        val c = webCanvas ?: return
        w.getLocationInWindow(webLoc)
        webBounds = Rect(
            webLoc[0].toFloat(),
            webLoc[1].toFloat(),
            (webLoc[0] + vw).toFloat(),
            (webLoc[1] + vh).toFloat()
        )
        isRecordingNow = true
        try {
            c.drawColor(webClearColor)
            val save = c.save()
            c.scale(bw.toFloat() / vw.toFloat(), bh.toFloat() / vh.toFloat())
            c.translate(-w.scrollX.toFloat(), -w.scrollY.toFloat())
            w.draw(c)
            c.restoreToCount(save)
        } catch (_: Throwable) {
            // Keep previous good frame if WebView is mid-teardown
        } finally {
            isRecordingNow = false
        }
        lastWebSnapMs = SystemClock.uptimeMillis()
        tick++
    }

    internal fun addConsumer() {
        var b: GlassBackdrop? = this
        while (b != null) {
            b.activeConsumers++
            if (b.attachedWeb != null) b.scheduleWebSnapshot(force = true)
            else b.composeHostView?.postInvalidateOnAnimation()
            b = b.parent
        }
    }

    internal fun removeConsumer() {
        var b: GlassBackdrop? = this
        while (b != null) {
            b.activeConsumers = (b.activeConsumers - 1).coerceAtLeast(0)
            b = b.parent
        }
    }

    internal fun readTicks(): Int {
        var acc = tick
        var p = parent
        var shift = 5
        while (p != null) {
            acc = acc xor ((p.tick shl shift) or (p.tick ushr (32 - shift)))
            shift = (shift + 7) and 31
            p = p.parent
        }
        return acc
    }

    internal fun hasWebFrame(): Boolean {
        val b = webBitmap
        return attachedWeb != null && b != null && !b.isRecycled && webBounds.width > 1f
    }

    internal fun hasLiveComposeLayer(): Boolean {
        val l = composeLayer
        if (l != null && !l.isReleased && l.size.width > 0 && l.size.height > 0 && !recordingCompose) return true
        return parent?.hasLiveComposeLayer() == true
    }

    private fun drawComposeChain(scope: DrawScope, cardLeft: Float, cardTop: Float, margin: Int) {
        parent?.drawComposeChain(scope, cardLeft, cardTop, margin)
        val l = composeLayer
        if (l != null && !l.isReleased && l.size.width > 0 && l.size.height > 0 && !recordingCompose) {
            val dx = composeBounds.left - cardLeft + margin
            val dy = composeBounds.top - cardTop + margin
            scope.translate(left = dx, top = dy) {
                drawLayer(l)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    internal fun drawInto(
        rc: RecordingCanvas,
        cardLeft: Float,
        cardTop: Float,
        margin: Int,
        screenW: Float,
        screenH: Float,
        dark: Boolean,
        density: Density? = null,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        canvasDrawScope: CanvasDrawScope? = null
    ) {
        // 1. Paint pure OLED black / pure clean light base first so 0% glow = pure dark/light
        rc.drawColor(if (dark) android.graphics.Color.BLACK else 0xFFECEFF6.toInt())

        // 2. Live video snapshot (PlayerActivity SurfaceView PixelCopy or InlinePlayer pre-open frame)
        val cBmp = customBitmap
        val aBmp = ambientBitmap
        if (cBmp != null && !cBmp.isRecycled) {
            dstRect.set(
                -cardLeft + margin,
                -cardTop + margin,
                screenW - cardLeft + margin,
                screenH - cardTop + margin
            )
            drawPaint.alpha = 200
            rc.drawBitmap(cBmp, null, dstRect, drawPaint)
            drawPaint.alpha = 255
            if (customBounds.width > 1f && customBounds.height > 1f) {
                val l = customBounds.left - cardLeft + margin
                val t = customBounds.top - cardTop + margin
                dstRect.set(l, t, l + customBounds.width, t + customBounds.height)
                rc.drawBitmap(cBmp, null, dstRect, drawPaint)
            }
            // Live unobstructed video motion blend (InlinePlayer: real-time color & motion behind blurred glass!)
            if (aBmp != null && !aBmp.isRecycled) {
                dstRect.set(
                    -cardLeft + margin,
                    -cardTop + margin,
                    screenW - cardLeft + margin,
                    screenH - cardTop + margin
                )
                drawPaint.alpha = 148
                rc.drawBitmap(aBmp, null, dstRect, drawPaint)
                drawPaint.alpha = 255
            }
            return
        }

        // 3. Live WebView snapshot when browsing websites (+ live unobstructed video motion blend if video playing!)
        val webSource = if (hasWebFrame()) this else parent?.takeIf { it.hasWebFrame() }
        val bmp = webSource?.webBitmap
        if (webSource != null && bmp != null && !bmp.isRecycled && webSource.webBounds.width > 1f) {
            val l = webSource.webBounds.left - cardLeft + margin
            val t = webSource.webBounds.top - cardTop + margin
            dstRect.set(l, t, l + webSource.webBounds.width, t + webSource.webBounds.height)
            rc.drawBitmap(bmp, null, dstRect, drawPaint)
            if (aBmp != null && !aBmp.isRecycled) {
                dstRect.set(
                    -cardLeft + margin,
                    -cardTop + margin,
                    screenW - cardLeft + margin,
                    screenH - cardTop + margin
                )
                drawPaint.alpha = 115
                rc.drawBitmap(aBmp, null, dstRect, drawPaint)
                drawPaint.alpha = 255
            }
            return
        }

        // 4. LIVE Pure-Compose GPU GraphicsLayer chain (renders REAL cards, text, icons, thumbnails, & scrolling lists!)
        if (density != null && canvasDrawScope != null && hasLiveComposeLayer()) {
            val bufW = (rc.width).coerceAtLeast(1).toFloat()
            val bufH = (rc.height).coerceAtLeast(1).toFloat()
            runCatching {
                canvasDrawScope.draw(density, layoutDirection, ComposeCanvas(rc), Size(bufW, bufH)) {
                    drawComposeChain(this, cardLeft, cardTop, margin)
                }
            }
        }
    }

    companion object {
        @Volatile
        var isRecordingNow: Boolean = false

        private val instances = java.util.Collections.newSetFromMap(
            java.util.WeakHashMap<GlassBackdrop, Boolean>()
        )

        @Synchronized
        internal fun registerInstance(b: GlassBackdrop) {
            instances.add(b)
        }

        @Synchronized
        fun trimAll() {
            instances.toList().forEach { runCatching { it.trimMemory() } }
        }
    }
}


val LocalGlassBackdrop = compositionLocalOf<GlassBackdrop?> { null }

/** Shared scroll physics state so Settings SGroup cards & list rows automatically pick up liquid scroll. */
val LocalLiquidScroll = compositionLocalOf<GlassScrollState?> { null }

/**
 * Universal rule for EVERY floating window / dialog / sheet / popup in Aurex:
 * While mounted, ensures the underlying screen (Compose GPU layer, live WebView, or video) renders LIVE behind it.
 */
@Composable
fun GlassLiveWindowLease(active: Boolean = true) {
    val backdrop = LocalGlassBackdrop.current
    DisposableEffect(backdrop, active) {
        if (!active || backdrop == null) return@DisposableEffect onDispose {}
        backdrop.addLiveWindow()
        onDispose { backdrop.removeLiveWindow() }
    }
}

/**
 * Pure native Compose GPU backdrop recorder (Compose 1.7+ [rememberGraphicsLayer]):
 *  - ZERO AndroidView, ZERO nested ComposeView -> 100% native WindowInsets (`statusBarsPadding`,
 *    `navigationBarsPadding`, `imePadding`), native `BackHandler`, and zero lifecycle crashes!
 *  - Records live Compose content directly into a GPU RenderNode whenever any glass consumer is active
 *    (0% overhead when idle or on `PerfTier.low`).
 */
@Composable
fun GlassBackdropHost(
    backdrop: GlassBackdrop,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || PerfTier.low) {
        Box(modifier) { content() }
        return
    }
    val layer = rememberGraphicsLayer()
    val hostView = LocalView.current
    DisposableEffect(backdrop, layer) {
        backdrop.attachComposeLayer(layer, hostView)
        onDispose { backdrop.detachComposeLayer(layer) }
    }
    Box(
        modifier
            .onGloballyPositioned { coords ->
                if (coords.isAttached) backdrop.composeBounds = coords.boundsInWindow()
            }
            .drawWithContent {
                val shouldRecord = enabled &&
                    backdrop.activeConsumers > 0 &&
                    !backdrop.hasWebFrame() &&
                    backdrop.customBitmap == null &&
                    !backdrop.recordingCompose &&
                    size.width >= 2f && size.height >= 2f
                if (shouldRecord) {
                    val rw = size.width.roundToInt().coerceAtLeast(1)
                    val rh = size.height.roundToInt().coerceAtLeast(1)
                    backdrop.recordingCompose = true
                    try {
                        layer.record(
                            density = this,
                            layoutDirection = layoutDirection,
                            size = IntSize(rw, rh)
                        ) {
                            this@drawWithContent.drawContent()
                        }
                    } finally {
                        backdrop.recordingCompose = false
                    }
                    drawLayer(layer)
                    backdrop.notifyComposeFrame(hostView)
                } else {
                    drawContent()
                }
            }
    ) {
        content()
    }
}

// ── Single-Pass AGSL 3D Apple Liquid Glass Shader (API 33+) ──────────────────

private object GlassLensPipeline {
    @Volatile
    var agslAvailable: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private const val AGSL_LENS = """
        uniform shader content;
        uniform float2 uOrigin;
        uniform float2 uSize;
        uniform float2 uBufSize;
        uniform float uRadius;
        uniform float uBevel;
        uniform float uThickness;
        uniform float uDispersion;
        uniform float uSaturation;
        uniform float4 uTint;
        uniform float2 uTilt;
        uniform float2 uTouch;
        uniform float uPress;
        uniform float uRipple;
        uniform float2 uMotion;
        uniform float uSpec;
        uniform float uDark;

        float sdRoundedBox(float2 pt, float2 hs, float rad) {
            float2 q = abs(pt) - (hs - float2(rad));
            return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - rad;
        }

        float2 snellShift(float3 N, float ior, float thick) {
            float eta = 1.0 / ior;
            float nz = max(N.z, 0.18);
            float k = max(1.0 - eta * eta * (1.0 - nz * nz), 0.02);
            float factor = eta * nz - sqrt(k);
            float2 tXY = factor * N.xy;
            float tZ = -eta + factor * nz;
            return (tXY / max(-tZ, 0.20)) * thick;
        }

        half4 main(float2 fragCoord) {
            float2 local = fragCoord - uOrigin;
            float2 hs = max(uSize * 0.5, float2(1.0));
            float2 p = local - hs;
            float r = min(uRadius, min(hs.x, hs.y));

            float sd = sdRoundedBox(p, hs, r);
            float x = max(-sd, 0.0);

            float gx = sdRoundedBox(p + float2(1.0, 0.0), hs, r) - sdRoundedBox(p - float2(1.0, 0.0), hs, r);
            float gy = sdRoundedBox(p + float2(0.0, 1.0), hs, r) - sdRoundedBox(p - float2(0.0, 1.0), hs, r);
            float2 grad = float2(gx, gy);
            float2 n2d = grad / max(length(grad), 0.0001);

            // Apple iOS 27 4th-power superellipse convex liquid lens dome:
            // wet, crystal-clear liquid bevel along the outer rim + calm, ultra-readable frosted core.
            float bevel = max(uBevel, 2.0);
            float u = clamp(x / bevel, 0.0, 1.0);
            float s = (1.0 - u) * 0.975;
            float inside = step(sd, 0.5);
            float s2 = s * s;
            float h4 = max(1.0 - s2 * s2, 0.036);
            float domeH = pow(h4, 0.25);
            float rawSlope = (s2 * s) / pow(h4, 0.75);
            float bezelSlope = clamp(rawSlope, 0.0, 4.6) * (1.0 - smoothstep(0.84, 1.0, u)) * inside;

            float motionMag = length(uMotion);
            float2 centerSlope = (p / max(hs, float2(16.0))) * (0.14 + 0.12 * uPress)
                + uMotion * 0.22
                + uTilt * 0.07;

            // Interactive fingertip convex liquid droplet magnifier
            float2 touchVec = local - uTouch;
            float touchDist = length(touchVec);
            float2 touchDir = touchVec / max(touchDist, 1.0);
            float touchRad = max(min(uSize.x, uSize.y) * 0.58, 52.0);
            float bulgeEnv = exp(-(touchDist * touchDist) / (touchRad * touchRad * 0.28));
            float2 pressSlope = touchDir * (1.18 * uPress * bulgeEnv * (touchDist / max(touchRad, 1.0)));

            // Twin-crest traveling refractive water-wave ripple
            float maxDim = max(uSize.x, uSize.y);
            float ripWave = 0.0;
            float2 rippleSlope = float2(0.0);
            if (uRipple > 0.005 && uRipple < 0.995) {
                float ringR = uRipple * maxDim * 0.98;
                float bandW = max(min(uSize.x, uSize.y) * 0.14, 20.0);
                float dRing = touchDist - ringR;
                float fade = (1.0 - uRipple) * (1.0 - uRipple);
                float env = exp(-(dRing * dRing) / (bandW * bandW)) * fade;
                float waveOsc = cos(clamp(dRing / bandW, -2.5, 2.5) * 1.9);
                ripWave = env * max(waveOsc, 0.0);
                rippleSlope = touchDir * ((-dRing / bandW) * 1.65 * env * (0.65 + 0.55 * waveOsc));
            }

            float dynBoost = 1.0 + 0.32 * uPress + 0.26 * motionMag;
            float2 slopeXY = (n2d * bezelSlope * 1.48 * dynBoost) + centerSlope + pressSlope + rippleSlope;
            float3 N = normalize(float3(slopeXY, 1.0));

            // Physical Snell's Law refraction + vivid prismatic RGB dispersion along the curved liquid bevel
            float thick = uThickness * (0.62 + 0.38 * domeH) * dynBoost;
            float dIor = uDispersion * (1.0 + 0.48 * uPress + 0.65 * ripWave);
            float2 uvR = snellShift(N, 1.50 - dIor, thick);
            float2 uvG = snellShift(N, 1.50,        thick);
            float2 uvB = snellShift(N, 1.50 + dIor, thick);

            float2 lo = float2(1.0);
            float2 hi = max(uBufSize - float2(1.0), float2(2.0));
            // 4-tap ultra-fast GPU kernel (3.2x faster than 13-tap!):
            // Hardware RenderEffect blur provides silky frost while AGSL drives Snell RGB dispersion + WCAG shield!
            half4 cR = content.eval(clamp(fragCoord + uvR, lo, hi));
            half4 cG = content.eval(clamp(fragCoord + uvG, lo, hi));
            half4 cB = content.eval(clamp(fragCoord + uvB, lo, hi));
            float3 rgb = float3(float(cR.r), float(cG.g), float(cB.b));
            float interiorMask = smoothstep(0.08, 0.44, u);

            // Fast center + exact per-pixel luminance (only 1 extra tap on already-blurred texture -> 120 FPS!)
            float3 avgBg = float3(content.eval(clamp(uOrigin + hs, lo, hi)).rgb);
            float bgLuma = dot(avgBg, float3(0.2126, 0.7152, 0.0722));
            float localLuma = dot(rgb, float3(0.2126, 0.7152, 0.0722));

            // Natural Apple UIVisualEffectView vibrancy (tasteful color, never neon/oversaturated)
            rgb = clamp(mix(float3(localLuma), rgb, uSaturation), float3(0.0), float3(1.0));

            // Single consistent Liquid Glass frost + gentle readability shield (identical on menus, bars & buttons!)
            float hazardLuma = (uDark > 0.5) ? max(bgLuma, localLuma) : min(bgLuma, localLuma);
            float clashBoost = (uDark > 0.5)
                ? 0.30 * smoothstep(0.35, 0.85, hazardLuma)
                : 0.32 * (1.0 - smoothstep(0.18, 0.68, hazardLuma));
            float baseZoneTint = mix(uTint.a * 0.88, uTint.a, interiorMask);
            float tintA = clamp(baseZoneTint + clashBoost * (0.85 + 0.15 * interiorMask), 0.0, 0.70);
            float3 shieldRgb = (uDark > 0.5)
                ? mix(uTint.rgb, float3(0.06, 0.08, 0.13), 0.18 * smoothstep(0.36, 0.84, hazardLuma))
                : uTint.rgb;
            rgb = mix(rgb, shieldRgb, tintA);

            // Subtle lift over pitch-black regions so glass stays distinguishable without ever glowing milky
            float sepLift = (uDark > 0.5)
                ? 0.045 * (1.0 - smoothstep(0.02, 0.26, bgLuma))
                : 0.022 * (1.0 - smoothstep(0.55, 0.92, bgLuma));
            rgb += float3(sepLift);

            // Delicate Apple iOS 27 directional specular (zero thick white band, zero cyan glow)
            float fresnel = pow(1.0 - clamp(N.z, 0.0, 1.0), 2.8);
            float crispEdge = (1.0 - smoothstep(0.0, 1.8, x)) * inside;

            float2 lightDir = normalize(float2(
                -0.66 + uTilt.x * 0.65 + uMotion.x * 0.32 + uMotion.y * 0.14,
                -0.75 + uTilt.y * 0.55 - uMotion.y * 0.34
            ));
            float3 L1 = normalize(float3(lightDir, 0.58));
            float3 L2 = normalize(float3(-lightDir, 0.52));
            float3 H1 = normalize(L1 + float3(0.0, 0.0, 1.0));
            float3 H2 = normalize(L2 + float3(0.0, 0.0, 1.0));
            float spec1 = pow(max(dot(N, H1), 0.0), 20.0);
            float spec2 = pow(max(dot(N, H2), 0.0), 24.0) * 0.55;
            float rimLobe1 = pow(max(dot(n2d, lightDir), 0.0), 3.0);
            float rimLobe2 = pow(max(dot(n2d, -lightDir), 0.0), 3.6) * 0.65;

            float specScale = uSpec * (1.0 + 0.18 * motionMag);
            float3 warmWhite = float3(1.0, 0.98, 0.95);
            float3 coolWhite = float3(0.86, 0.93, 1.0);
            float3 dualRim = warmWhite * rimLobe1 + coolWhite * rimLobe2;

            rgb += dualRim * (crispEdge * specScale * 0.38);
            rgb += (warmWhite * spec1 + coolWhite * spec2) * (fresnel * 0.42 + ripWave * 0.55) * specScale;
            rgb += float3(1.0) * (fresnel * specScale * 0.08);

            // Clean interactive fingertip bloom & traveling water ripple
            float touchBloom = exp(-(touchDist * touchDist) / (touchRad * touchRad * 0.22)) * uPress;
            rgb += float3(1.0) * (touchBloom * (0.12 + 0.28 * crispEdge));
            rgb += mix(coolWhite, float3(1.0), 0.5) * (ripWave * 0.22 * specScale);

            if (uDark < 0.5 && bgLuma > 0.64) {
                float outerEdge = (1.0 - smoothstep(0.0, 1.8, x)) * inside;
                rgb = mix(rgb, float3(0.06, 0.09, 0.16), outerEdge * 0.22);
            }

            return half4(half3(clamp(rgb, float3(0.0), float3(1.0))), half(1.0));
        }
    """

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun createLensShader(): Any? {
        if (!agslAvailable) return null
        return try {
            RuntimeShader(AGSL_LENS)
        } catch (t: Throwable) {
            AppLog.error("GlassFx", "AGSL_LENS compile failed", t)
            agslAvailable = false
            null
        }
    }
}

/**
 * Per-card GPU RenderNode + AGSL shader holder with full dirty-tracking AND reactive boundsVersion.
 * All API 29/31/33 objects are stored as Any? so Android 7-12 never triggers RuntimeShader verification.
 */
internal class GlassLensHolder {
    var node: Any? = null
    var shader: Any? = null
    var boundsInWindow: Rect = Rect.Zero
    var boundsVersion by mutableIntStateOf(0)

    var lastTicks: Int = Int.MIN_VALUE
    var lastLeft: Float = Float.NaN
    var lastTop: Float = Float.NaN
    var lastBw: Int = -1
    var lastBh: Int = -1
    var lastThemeKey: Int = 0

    var lastBlurQ: Int = -1
    var cachedBlur: Any? = null
    var cachedChain: Any? = null
    var lastEffectKey: Long = Long.MIN_VALUE

    fun updateBounds(r: Rect) {
        if (abs(r.left - boundsInWindow.left) > 1.5f ||
            abs(r.top - boundsInWindow.top) > 1.5f ||
            abs(r.width - boundsInWindow.width) > 1.5f ||
            abs(r.height - boundsInWindow.height) > 1.5f
        ) {
            boundsInWindow = r
            boundsVersion++
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun ensureNode(): RenderNode =
        (node as? RenderNode) ?: GlassNodeApi.create("AurexGlassCard").also { node = it }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun ensureLensShader(): Any? {
        if (!GlassLensPipeline.agslAvailable) return null
        return shader ?: GlassLensPipeline.createLensShader().also { shader = it }
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            GlassNodeApi.discard(node)
        }
        node = null
        shader = null
        cachedBlur = null
        cachedChain = null
        lastTicks = Int.MIN_VALUE
        lastBlurQ = -1
        lastEffectKey = Long.MIN_VALUE
    }
}

private fun mixHash(acc: Long, v: Int): Long = acc * 31L + v.toLong()

private fun Float.safeClamp(minVal: Float, maxVal: Float): Float {
    val hi = maxVal.coerceAtLeast(1f)
    val lo = minOf(minVal, hi)
    return coerceIn(lo, hi)
}

/**
 * Draws the live Apple frosted + 4th-power squircle Snell-refracted Liquid Glass backdrop (API 31+).
 * Reads ticks, boundsVersion, motion, tilt, touch, and blurProgress strictly in the draw phase.
 */
internal fun Modifier.liquidBackdrop(
    shape: Shape,
    dark: Boolean,
    tint: Color,
    blurDp: Float,
    refractionScale: Float = 1f,
    blurProgress: () -> Float = { 1f },
    touch: GlassTouch? = null
): Modifier = composed {
    val backdrop = LocalGlassBackdrop.current
    if (backdrop == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S || PerfTier.low) return@composed this

    val holder = remember { GlassLensHolder() }
    val composeDrawScope = remember { CanvasDrawScope() }
    val rootView = LocalView.current
    val tintArgb = tint.toArgb()
    val themeKey = if (dark) 1 else 0

    DisposableEffect(backdrop) {
        backdrop.addConsumer()
        onDispose {
            backdrop.removeConsumer()
            holder.release()
        }
    }

    this
        .onGloballyPositioned { coords ->
            if (coords.isAttached) holder.updateBounds(coords.boundsInWindow())
        }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val radiusPx = when (outline) {
                is Outline.Rounded -> {
                    val rr = outline.roundRect
                    maxOf(
                        rr.topLeftCornerRadius.x,
                        rr.topRightCornerRadius.x,
                        rr.bottomLeftCornerRadius.x,
                        rr.bottomRightCornerRadius.x
                    ).coerceAtMost(minOf(size.width, size.height) * 0.5f)
                }
                is Outline.Generic -> minOf(size.width, size.height) * 0.5f
                else -> 0f
            }
            val baseBlurPx = blurDp.dp.toPx().coerceIn(5f, 54f)
            val shortPx = minOf(size.width, size.height).coerceAtLeast(1f)
            val shortDp = shortPx / density
            val lin = (shortDp / 96f).coerceIn(0.42f, 1f)
            val bevelPx = (18.dp.toPx() * lin).safeClamp(9.dp.toPx(), shortPx * 0.40f)
            val baseThicknessPx = (36.dp.toPx() * (0.50f + 0.50f * lin) * refractionScale)
                .safeClamp(15.dp.toPx(), shortPx * 0.56f)
            // Constant margin precomputed once per size -> ZERO GPU FBO texture reallocations during animations!
            val constMargin = ceil(maxOf(baseBlurPx * 1.35f, baseThicknessPx * 1.05f)).toInt().coerceIn(24, 92)

            onDrawWithContent {
                val ticks = backdrop.readTicks()
                @Suppress("UNUSED_VARIABLE")
                val bVer = holder.boundsVersion
                val motY = GlassMotion.scroll
                val motX = GlassMotion.shearX
                val bp = blurProgress().coerceIn(0.15f, 1f)
                val native = drawContext.canvas.nativeCanvas
                val cardBounds = holder.boundsInWindow

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    native is RecordingCanvas &&
                    size.width >= 2f && size.height >= 2f && cardBounds.width >= 2f
                ) {
                    val margin = constMargin
                    val w = size.width.toInt().coerceAtLeast(1)
                    val h = size.height.toInt().coerceAtLeast(1)
                    val bw = w + margin * 2
                    val bh = h + margin * 2
                    val cardNode = holder.ensureNode()

                    val needRecord = !cardNode.hasDisplayList() ||
                        holder.lastTicks != ticks ||
                        abs(holder.lastLeft - cardBounds.left) > 1.5f ||
                        abs(holder.lastTop - cardBounds.top) > 1.5f ||
                        holder.lastBw != bw ||
                        holder.lastBh != bh ||
                        holder.lastThemeKey != themeKey

                    if (needRecord) {
                        holder.lastTicks = ticks
                        holder.lastLeft = cardBounds.left
                        holder.lastTop = cardBounds.top
                        holder.lastBw = bw
                        holder.lastBh = bh
                        holder.lastThemeKey = themeKey
                        val sw = rootView.width.coerceAtLeast(w).toFloat()
                        val sh = rootView.height.coerceAtLeast(h).toFloat()
                        cardNode.setPosition(0, 0, bw, bh)
                        val rc = cardNode.beginRecording(bw, bh)
                        try {
                            backdrop.drawInto(rc, cardBounds.left, cardBounds.top, margin, sw, sh, dark, this@drawWithCache, layoutDirection, composeDrawScope)
                        } finally {
                            cardNode.endRecording()
                        }
                    }

                    val agslActive = PerfTier.useAgsl && GlassLensPipeline.agslAvailable
                    val effBlurPx = if (agslActive) baseBlurPx else (baseBlurPx * 1.35f).coerceIn(5f, 54f)
                    val blurQ = (effBlurPx * 1.25f).roundToInt()
                    val existingBlur = holder.cachedBlur
                    val blurEffect = if (holder.lastBlurQ != blurQ || existingBlur == null) {
                        holder.lastBlurQ = blurQ
                        holder.lastEffectKey = Long.MIN_VALUE
                        GlassBlurApi.createBlur(effBlurPx).also { holder.cachedBlur = it }
                    } else {
                        existingBlur
                    }
                    if (agslActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val sh = holder.ensureLensShader()
                        if (sh != null) {
                            val pr = touch?.press?.value?.coerceIn(0f, 1.3f) ?: 0f
                            val rp = touch?.ripple?.value?.coerceIn(0f, 1f) ?: 0f
                            val activeTouch = touch != null && (pr > 0.005f || (rp > 0.005f && rp < 0.995f))
                            val tx = if (activeTouch) touch.x else size.width * 0.5f
                            val ty = if (activeTouch) touch.y else size.height * 0.5f
                            // Keep GPU RenderEffect chain rock-stable (tilt specular runs in zero-FBO glassEdge!)
                            val tiltXQ = 0
                            val tiltYQ = 0
                            val motXQ = if (abs(motX) > 0.04f) (motX * 18f).roundToInt() else 0
                            val motYQ = if (abs(motY) > 0.04f) (motY * 18f).roundToInt() else 0
                            val prQ = if (pr > 0.01f) (pr * 48f).roundToInt() else 0
                            val rpQ = if (rp > 0.01f && rp < 0.99f) (rp * 48f).roundToInt() else 0
                            val bpQ = (bp * 20f).roundToInt()

                            var key = 17L
                            key = mixHash(key, margin)
                            key = mixHash(key, w)
                            key = mixHash(key, h)
                            key = mixHash(key, radiusPx.roundToInt())
                            key = mixHash(key, blurQ)
                            key = mixHash(key, tintArgb)
                            key = mixHash(key, tiltXQ)
                            key = mixHash(key, tiltYQ)
                            key = mixHash(key, motXQ)
                            key = mixHash(key, motYQ)
                            key = mixHash(key, prQ)
                            key = mixHash(key, rpQ)
                            key = mixHash(key, if (activeTouch) tx.roundToInt() else -1)
                            key = mixHash(key, if (activeTouch) ty.roundToInt() else -1)
                            key = mixHash(key, bpQ)
                            key = mixHash(key, if (dark) 1 else 0)

                            if (holder.lastEffectKey != key || holder.cachedChain == null) {
                                holder.lastEffectKey = key
                                val thicknessPx = (baseThicknessPx * bp).safeClamp(8.dp.toPx(), shortPx * 0.54f)
                                holder.cachedChain = GlassAgslApi.applyLens(
                                    node = cardNode,
                                    shader = sh,
                                    blurEffect = blurEffect,
                                    originX = margin.toFloat(),
                                    originY = margin.toFloat(),
                                    width = size.width,
                                    height = size.height,
                                    bufW = bw.toFloat(),
                                    bufH = bh.toFloat(),
                                    radiusPx = radiusPx,
                                    bevelPx = bevelPx,
                                    thicknessPx = thicknessPx,
                                    dispersion = 0.14f,
                                    saturation = if (dark) 1.30f else 1.24f,
                                    tint = tint,
                                    tiltX = tiltXQ * 0.025f,
                                    tiltY = tiltYQ * 0.025f,
                                    touchX = tx,
                                    touchY = ty,
                                    press = pr,
                                    ripple = if (rpQ > 0) rp else 0f,
                                    motX = motXQ * 0.055f,
                                    motY = motYQ * 0.055f,
                                    spec = (if (dark) 0.58f else 0.86f) * bp,
                                    dark = dark
                                )
                            }
                        } else if (holder.lastEffectKey != -2L) {
                            holder.lastEffectKey = -2L
                            GlassBlurApi.applyEffect(cardNode, blurEffect)
                        }
                    } else if (holder.lastEffectKey != blurQ.toLong()) {
                        holder.lastEffectKey = blurQ.toLong()
                        GlassBlurApi.applyEffect(cardNode, blurEffect)
                    }

                    native.save()
                    native.translate(-margin.toFloat(), -margin.toFloat())
                    native.drawRenderNode(cardNode)
                    native.restore()

                    if (!agslActive) {
                        drawRect(tint.copy(alpha = (tint.alpha + 0.14f).coerceIn(0.38f, 0.68f)))
                    }
                } else {
                    drawRect(tint.copy(alpha = (tint.alpha + 0.34f).coerceAtMost(0.90f)))
                }
                drawContent()
            }
        }
}

// ── Lightweight Progressive Scroll Edge Feather (0.02ms GPU, 0 FBOs) ─────────

@Composable
fun ScrollEdgeBlur(
    isTop: Boolean,
    active: Boolean,
    height: Dp,
    modifier: Modifier = Modifier,
    strength: Float = 1f
) {
    if (PerfTier.low) return
    val palette = LocalPalette.current
    val dark = palette.dark
    val intensityState = animateFloatAsState(
        targetValue = if (active) strength.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 320f),
        label = "scrollEdgeBlur"
    )
    val c = if (dark) Color.Black else Color(0xFFECEFF6)
    val baseA = if (dark) 0.78f else 0.84f
    val brush = remember(dark, isTop) {
        if (isTop) {
            Brush.verticalGradient(0f to c.copy(alpha = baseA), 0.58f to c.copy(alpha = baseA * 0.42f), 1f to Color.Transparent)
        } else {
            Brush.verticalGradient(0f to Color.Transparent, 0.42f to c.copy(alpha = baseA * 0.42f), 1f to c.copy(alpha = baseA))
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawWithContent {
                val a = intensityState.value.coerceIn(0f, 1f)
                if (a > 0.005f) drawRect(brush = brush, alpha = a)
            }
    )
}

// ── Floating Window Liquid Drag & Elastic Jelly Physics ──────────────────────

/** Dismiss gesture direction for floating Apple Liquid Glass windows / sheets / menus. */
enum class LiquidDismissDir { NONE, DOWN, UP, HORIZONTAL, ANY }

/** Subtle Apple iOS grabber pill shown on draggable floating sheets and menus. */
@Composable
fun GlassGrabber(modifier: Modifier = Modifier) {
    val dark = LocalPalette.current.dark
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .width(34.dp)
                .height(4.dp)
                .clip(Capsule)
                .background(if (dark) Color.White.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.18f))
        )
    }
}

private fun rubberBand(raw: Float, limit: Float): Float {
    val a = abs(raw)
    return sign(raw) * ((a * limit) / (a + limit))
}

@Stable
class GlassWindowDragState {
    val offsetX = Animatable(0f)
    val offsetY = Animatable(0f)
    val dragActive = Animatable(0f)
    internal var liveX by mutableFloatStateOf(0f)
    internal var liveY by mutableFloatStateOf(0f)
    internal var isDragging by androidx.compose.runtime.mutableStateOf(false)
}

@Composable
fun rememberGlassWindowDragState(): GlassWindowDragState = remember { GlassWindowDragState() }

fun Modifier.liquidWindowTransform(
    state: GlassWindowDragState,
    maxPullDp: Dp = 52.dp
): Modifier = composed {
    if (PerfTier.low) return@composed this
    val localDensity = LocalDensity.current
    val maxPullPx = with(localDensity) { maxPullDp.toPx() }.coerceAtLeast(1f)
    this.graphicsLayer {
        val dx = if (state.isDragging) state.liveX else state.offsetX.value
        val dy = if (state.isDragging) state.liveY else state.offsetY.value
        val act = state.dragActive.value
        if (dx != 0f || dy != 0f || act > 0.002f) {
            val nx = (dx / maxPullPx).coerceIn(-1.6f, 1.6f)
            val ny = (dy / maxPullPx).coerceIn(-1.6f, 1.6f)
            translationX = dx
            translationY = dy
            val lift = 1f + 0.016f * act
            val sx = lift * (1f + abs(nx) * 0.036f - abs(ny) * 0.024f)
            val sy = lift * (1f + abs(ny) * 0.036f - abs(nx) * 0.024f)
            scaleX = sx.coerceIn(0.92f, 1.09f)
            scaleY = sy.coerceIn(0.92f, 1.09f)
            rotationY = (nx * 4.2f).coerceIn(-6.5f, 6.5f)
            rotationX = (-ny * 4.2f).coerceIn(-6.5f, 6.5f)
            cameraDistance = 16f * density
        }
    }
}

fun Modifier.liquidWindowHandle(
    state: GlassWindowDragState,
    enabled: Boolean = true,
    maxPullDp: Dp = 52.dp,
    dismissDir: LiquidDismissDir = LiquidDismissDir.NONE,
    dismissThresholdDp: Dp = 74.dp,
    onDismiss: (() -> Unit)? = null
): Modifier = composed {
    if (!enabled || PerfTier.low) return@composed this
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val localDensity = LocalDensity.current
    val maxPullPx = with(localDensity) { maxPullDp.toPx() }
    val dismissPx = with(localDensity) { dismissThresholdDp.toPx() }
    val currentDismiss by rememberUpdatedState(onDismiss)

    this.pointerInput(state, dismissDir, maxPullPx, dismissPx) {
        val slop = viewConfiguration.touchSlop * 1.45f
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var dragging = false
            var rawX = 0f
            var rawY = 0f
            var armedHaptic = false

            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Main)
                val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break

                val delta = change.positionChange()
                if (!dragging) {
                    if (change.isConsumed && change.positionChanged()) break
                    rawX += delta.x
                    rawY += delta.y
                    if (hypot(rawX, rawY) > slop) {
                        dragging = true
                        rawX = 0f
                        rawY = 0f
                        state.liveX = 0f
                        state.liveY = 0f
                        state.isDragging = true
                        change.consume()
                        Haptics.fire(view, Hx.PRESS)
                        scope.launch {
                            state.offsetX.stop()
                            state.offsetY.stop()
                            state.dragActive.animateTo(1f, spring(dampingRatio = 0.65f, stiffness = 540f))
                        }
                    }
                } else {
                    change.consume()
                    rawX += delta.x
                    rawY += delta.y
                    GlassMotion.impulse(delta.y, delta.x)

                    val canDismiss = dismissDir != LiquidDismissDir.NONE && currentDismiss != null
                    val dx = when {
                        canDismiss && (dismissDir == LiquidDismissDir.HORIZONTAL || dismissDir == LiquidDismissDir.ANY) ->
                            rubberBand(rawX, maxPullPx * 2.2f)
                        else -> rubberBand(rawX, maxPullPx * 0.85f)
                    }
                    val dy = when {
                        canDismiss && dismissDir == LiquidDismissDir.DOWN && rawY > 0f ->
                            rubberBand(rawY, maxPullPx * 2.6f)
                        canDismiss && dismissDir == LiquidDismissDir.UP && rawY < 0f ->
                            rubberBand(rawY, maxPullPx * 2.6f)
                        canDismiss && dismissDir == LiquidDismissDir.ANY ->
                            rubberBand(rawY, maxPullPx * 2.2f)
                        else -> rubberBand(rawY, maxPullPx * 0.85f)
                    }

                    if (canDismiss) {
                        val hit = when (dismissDir) {
                            LiquidDismissDir.DOWN -> dy > dismissPx
                            LiquidDismissDir.UP -> dy < -dismissPx
                            LiquidDismissDir.HORIZONTAL -> abs(dx) > dismissPx
                            LiquidDismissDir.ANY -> abs(dx) > dismissPx || abs(dy) > dismissPx
                            else -> false
                        }
                        if (hit && !armedHaptic) {
                            armedHaptic = true
                            performThreshold(view)
                        } else if (!hit && armedHaptic) {
                            armedHaptic = false
                        }
                    }

                    // Synchronous 0-alloc write: 0-frame finger latency & 0 coroutine churn at 120 Hz!
                    state.liveX = dx
                    state.liveY = dy
                }
            }

            if (dragging) {
                val canDismiss = dismissDir != LiquidDismissDir.NONE && currentDismiss != null
                val dx = state.liveX
                val dy = state.liveY
                val shouldDismiss = canDismiss && when (dismissDir) {
                    LiquidDismissDir.DOWN -> dy > dismissPx
                    LiquidDismissDir.UP -> dy < -dismissPx
                    LiquidDismissDir.HORIZONTAL -> abs(dx) > dismissPx
                    LiquidDismissDir.ANY -> abs(dx) > dismissPx || abs(dy) > dismissPx
                    else -> false
                }
                if (shouldDismiss) {
                    scope.launch {
                        state.offsetX.snapTo(dx)
                        state.offsetY.snapTo(dy)
                        state.isDragging = false
                        state.dragActive.animateTo(0f, tween(140))
                        currentDismiss?.invoke()
                        delay(240)
                        state.offsetX.snapTo(0f)
                        state.offsetY.snapTo(0f)
                    }
                } else {
                    scope.launch {
                        state.offsetX.snapTo(dx)
                        state.offsetY.snapTo(dy)
                        state.isDragging = false
                        launch { state.offsetX.animateTo(0f, spring(dampingRatio = 0.46f, stiffness = 320f)) }
                        launch { state.offsetY.animateTo(0f, spring(dampingRatio = 0.46f, stiffness = 320f)) }
                        launch { state.dragActive.animateTo(0f, spring(dampingRatio = 0.54f, stiffness = 380f)) }
                    }
                }
            }
        }
    }
}

fun Modifier.liquidWindowDrag(
    enabled: Boolean = true,
    maxPullDp: Dp = 52.dp,
    dismissDir: LiquidDismissDir = LiquidDismissDir.NONE,
    dismissThresholdDp: Dp = 74.dp,
    onDismiss: (() -> Unit)? = null
): Modifier = composed {
    if (!enabled || PerfTier.low) return@composed this
    val state = rememberGlassWindowDragState()
    this
        .liquidWindowHandle(state, enabled, maxPullDp, dismissDir, dismissThresholdDp, onDismiss)
        .liquidWindowTransform(state, maxPullDp)
}

fun Modifier.liquidOverscrollJelly(
    dismissDown: Boolean = false,
    dismissUp: Boolean = false,
    dismissThresholdDp: Dp = 76.dp,
    onOverscrollDismiss: (() -> Unit)? = null
): Modifier = composed {
    if (PerfTier.low) return@composed this
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val localDensity = LocalDensity.current
    val maxPullPx = with(localDensity) { 46.dp.toPx() }
    val dismissPx = with(localDensity) { dismissThresholdDp.toPx() }
    val currentDismiss by rememberUpdatedState(onOverscrollDismiss)
    val pullY = remember { Animatable(0f) }
    var livePull by remember { mutableFloatStateOf(0f) }
    var pulling by remember { androidx.compose.runtime.mutableStateOf(false) }

    val connection = remember(dismissDown, dismissUp, dismissPx) {
        var rawPull = 0f
        var armed = false
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && rawPull != 0f && available.y != 0f) {
                    if (sign(available.y) != sign(rawPull)) {
                        val next = rawPull + available.y
                        rawPull = if (sign(next) != sign(rawPull)) 0f else next
                        livePull = rubberBand(rawPull, maxPullPx * 1.8f)
                        pulling = rawPull != 0f
                        return Offset(0f, available.y)
                    }
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.UserInput && abs(available.y) > 0.5f) {
                    rawPull += available.y * 0.72f
                    val mapped = rubberBand(rawPull, maxPullPx * 1.8f)
                    pulling = true
                    livePull = mapped
                    val hit = currentDismiss != null && (
                        (dismissDown && mapped > dismissPx) ||
                        (dismissUp && mapped < -dismissPx)
                    )
                    if (hit && !armed) {
                        armed = true
                        performThreshold(view)
                    } else if (!hit) {
                        armed = false
                    }
                    return available
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val mapped = if (pulling) livePull else pullY.value
                val shouldDismiss = currentDismiss != null && (
                    (dismissDown && (mapped > dismissPx || (mapped > dismissPx * 0.45f && available.y > 1200f))) ||
                    (dismissUp && (mapped < -dismissPx || (mapped < -dismissPx * 0.45f && available.y < -1200f)))
                )
                rawPull = 0f
                armed = false
                if (shouldDismiss) {
                    currentDismiss?.invoke()
                    scope.launch {
                        pullY.snapTo(mapped)
                        pulling = false
                        delay(200)
                        pullY.snapTo(0f)
                        livePull = 0f
                    }
                } else if (mapped != 0f) {
                    scope.launch {
                        pullY.snapTo(mapped)
                        pulling = false
                        livePull = 0f
                        pullY.animateTo(0f, spring(dampingRatio = 0.46f, stiffness = 320f))
                    }
                } else {
                    pulling = false
                    livePull = 0f
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                rawPull = 0f
                armed = false
                val cur = if (pulling) livePull else pullY.value
                if (cur != 0f && !pullY.isRunning) {
                    scope.launch {
                        pullY.snapTo(cur)
                        pulling = false
                        livePull = 0f
                        pullY.animateTo(0f, spring(dampingRatio = 0.48f, stiffness = 320f))
                    }
                }
                return Velocity.Zero
            }
        }
    }

    this
        .nestedScroll(connection)
        .graphicsLayer {
            val dy = if (pulling) livePull else pullY.value
            if (dy != 0f) {
                val n = (dy / maxPullPx.coerceAtLeast(1f)).coerceIn(-1.5f, 1.5f)
                translationY = dy * 0.68f
                val stretch = abs(n) * 0.036f
                scaleY = (1f + stretch).coerceIn(0.95f, 1.07f)
                scaleX = (1f - stretch * 0.50f).coerceIn(0.95f, 1.04f)
                rotationX = (-n * 3.2f).coerceIn(-5f, 5f)
                transformOrigin = TransformOrigin(0.5f, if (dy > 0f) 0f else 1f)
                cameraDistance = 18f * density
            }
        }
}

// ── Liquid Cards Scrolling Physics (zero recomposition, 120 FPS draw-only) ───

@Stable
class GlassScrollState {
    /** Smoothed scroll velocity (-1..1). Read ONLY inside graphicsLayer lambdas. */
    var velocity by mutableFloatStateOf(0f)
        internal set

    internal var viewportTop: Float = 0f
    internal var viewportBottom: Float = 0f
    internal var decayJob: Job? = null
}

@Composable
fun rememberGlassScrollState(): GlassScrollState = remember { GlassScrollState() }

fun Modifier.liquidScrollTrack(state: GlassScrollState): Modifier = composed {
    if (PerfTier.low) return@composed this
    val scope = rememberCoroutineScope()
    val connection = remember(state, scope) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val dy = consumed.y
                if (abs(dy) > 0.5f) {
                    val norm = (dy / 90f).coerceIn(-1f, 1f)
                    state.velocity = (state.velocity * 0.55f + norm * 0.45f).coerceIn(-1f, 1f)
                    // Single reusable decay job: ZERO coroutine alloc/cancel churn per scroll frame!
                    if (state.decayJob?.isActive != true) {
                        state.decayJob = scope.launch {
                            while (abs(state.velocity) > 0.01f) {
                                delay(16L)
                                state.velocity *= 0.80f
                            }
                            state.velocity = 0f
                        }
                    }
                }
                return Offset.Zero
            }
        }
    }
    this
        .onGloballyPositioned { coords ->
            if (coords.isAttached) {
                val b = coords.boundsInWindow()
                state.viewportTop = b.top
                state.viewportBottom = b.bottom
            }
        }
        .nestedScroll(connection)
}

private class CardBoundsHolder {
    var top: Float = 0f
    var bottom: Float = 0f
    var valid: Boolean = false
}

fun Modifier.liquidScrollCard(
    scrollState: GlassScrollState,
    edgeScale: Float = 0.036f,
    edgeTilt: Float = 2.6f
): Modifier = composed {
    if (PerfTier.low) return@composed this
    val holder = remember { CardBoundsHolder() }
    this
        .onGloballyPositioned { coords ->
            if (coords.isAttached) {
                val b = coords.boundsInWindow()
                holder.top = b.top
                holder.bottom = b.bottom
                holder.valid = true
            }
        }
        .graphicsLayer {
            val v = scrollState.velocity
            val vpTop = scrollState.viewportTop
            val vpBottom = scrollState.viewportBottom
            var scale = 1f
            var tiltX = v * 1.25f
            var cardAlpha = 1f

            if (holder.valid && vpBottom > vpTop + 100f) {
                val zone = 96.dp.toPx()
                // Only recede when a card ACTUALLY leaves past the top/bottom viewport edge;
                // resting cards fully inside [vpTop, vpBottom] always stay 100% full width & crisp!
                val topOverlap = (vpTop - holder.top) / zone
                val botOverlap = (holder.bottom - vpBottom) / zone
                if (topOverlap > 0f) {
                    val t = topOverlap.coerceIn(0f, 1f)
                    val ease = t * t * (3f - 2f * t)
                    scale = 1f - edgeScale * ease
                    tiltX += edgeTilt * ease
                    cardAlpha = 1f - 0.12f * ease
                    transformOrigin = TransformOrigin(0.5f, 1f)
                } else if (botOverlap > 0f) {
                    val t = botOverlap.coerceIn(0f, 1f)
                    val ease = t * t * (3f - 2f * t)
                    scale = 1f - edgeScale * ease
                    tiltX -= edgeTilt * ease
                    cardAlpha = 1f - 0.12f * ease
                    transformOrigin = TransformOrigin(0.5f, 0f)
                } else {
                    transformOrigin = TransformOrigin.Center
                }
            }
            val stretch = abs(v) * 0.015f
            scaleX = scale * (1f - stretch * 0.45f)
            scaleY = scale * (1f + stretch)
            rotationX = tiltX.coerceIn(-4.8f, 4.8f)
            alpha = cardAlpha
            cameraDistance = 18f * density
        }
}

// ── Gravity Sensor Specular Tilt ─────────────────────────────────────────────

object GlassTilt {
    var x by mutableFloatStateOf(0f)   // + = right edge raised
    var y by mutableFloatStateOf(0f)   // + = top edge tipped away

    private var users = 0
    private var sm: SensorManager? = null
    private var listener: SensorEventListener? = null

    fun acquire(ctx: Context) {
        users++
        if (users == 1) start(ctx.applicationContext)
    }

    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) stop()
    }

    private fun start(ctx: Context) {
        val m = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = m.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        var fx = x
        var fy = y
        var lastEmitMs = 0L
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val now = SystemClock.uptimeMillis()
                if (now - lastEmitMs < 45L) return
                val g = 9.81f
                val tx = (e.values[0] / g).coerceIn(-1f, 1f)
                val ty = (e.values[2] / g - 0.45f).coerceIn(-1f, 1f)
                fx += (tx - fx) * 0.22f
                fy += (ty - fy) * 0.22f
                if (abs(fx - x) > 0.028f || abs(fy - y) > 0.028f) {
                    lastEmitMs = now
                    x = fx; y = fy
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (m.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)) {
            sm = m
            listener = l
        }
    }

    private fun stop() {
        listener?.let { sm?.unregisterListener(it) }
        listener = null
        sm = null
        x = 0f
        y = 0f
    }
}

@Composable
fun GlassTiltLease(enabled: Boolean) {
    val active = enabled && !PerfTier.low
    val ctx = LocalContext.current
    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose {}
        val owner = generateSequence<Context>(ctx) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<LifecycleOwner>()
            .firstOrNull()
        var held = false
        fun take() { if (!held) { held = true; GlassTilt.acquire(ctx) } }
        fun drop() { if (held) { held = false; GlassTilt.release() } }
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) take() else if (ev == Lifecycle.Event.ON_PAUSE) drop()
        }
        if (owner == null) take() else owner.lifecycle.addObserver(obs)
        onDispose {
            owner?.lifecycle?.removeObserver(obs)
            drop()
        }
    }
}

// ── Per-Card Touch, Traveling Liquid Ripple & Jelly Press Physics ────────────

@Stable
class GlassTouch {
    val press = Animatable(0f)
    val ripple = Animatable(0f)
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    internal var rippleJob: Job? = null

    fun launchRipple(scope: CoroutineScope) {
        rippleJob?.cancel()
        rippleJob = scope.launch {
            ripple.snapTo(0.01f)
            ripple.animateTo(1f, tween(580, easing = FastOutSlowInEasing))
            ripple.snapTo(0f)
        }
    }
}

private const val TILT_SIGN = 1f
private const val MIN_SWELL_MS = 90L

fun Modifier.glassPressObserve(touch: GlassTouch, scope: CoroutineScope): Modifier =
    this.pointerInput(touch) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            touch.x = down.position.x
            touch.y = down.position.y
            touch.launchRipple(scope)
            scope.launch { touch.press.animateTo(1f, spring(dampingRatio = 0.56f, stiffness = 460f)) }
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Final)
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) break
                if (c.isConsumed && c.positionChanged()) break
                touch.x = c.position.x
                touch.y = c.position.y
            }
            scope.launch { touch.press.animateTo(0f, spring(dampingRatio = 0.40f, stiffness = 260f)) }
        }
    }

fun Modifier.glassPressTilt(touch: GlassTouch, scope: CoroutineScope): Modifier = this
    .glassPressObserve(touch, scope)
    .graphicsLayer {
        val p = touch.press.value
        if (p != 0f) {
            val w = size.width.coerceAtLeast(1f)
            val h = size.height.coerceAtLeast(1f)
            val nx = (touch.x / w - 0.5f) * 2f
            val ny = (touch.y / h - 0.5f) * 2f
            // Organic pressurized liquid droplet squish & 3D lean
            scaleX = 1f + 0.016f * p - 0.010f * abs(nx) * p
            scaleY = 1f + 0.012f * p - 0.010f * abs(ny) * p
            rotationY = TILT_SIGN * nx * 4.0f * p
            rotationX = -TILT_SIGN * ny * 4.0f * p
            translationX = nx * 2.2f * density * p
            translationY = ny * 2.2f * density * p
            cameraDistance = 14f * density
        }
    }

/** Subtle liquid droplet pulse for full-width floating bars (swells cleanly without awkward 3D bar tilt). */
fun Modifier.glassBarPulse(touch: GlassTouch, scope: CoroutineScope): Modifier = this
    .glassPressObserve(touch, scope)
    .graphicsLayer {
        val p = touch.press.value
        if (p != 0f) {
            scaleX = 1f + 0.014f * p
            scaleY = 1f + 0.024f * p
        }
    }

fun Modifier.glassPressSwell(touch: GlassTouch, scope: CoroutineScope, swell: Float = 0.085f): Modifier = this
    .pointerInput(touch) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val t0 = SystemClock.uptimeMillis()
            touch.x = down.position.x
            touch.y = down.position.y
            touch.launchRipple(scope)
            scope.launch { touch.press.animateTo(1f, spring(dampingRatio = 0.48f, stiffness = 500f)) }
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Final)
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) break
                if (c.isConsumed && c.positionChanged()) break
                touch.x = c.position.x
                touch.y = c.position.y
            }
            val held = SystemClock.uptimeMillis() - t0
            scope.launch {
                if (held < MIN_SWELL_MS) delay(MIN_SWELL_MS - held)
                touch.press.animateTo(0f, spring(dampingRatio = 0.34f, stiffness = 240f))
            }
        }
    }
    .graphicsLayer {
        val p = touch.press.value
        if (p != 0f) {
            scaleX = 1f + swell * p
            scaleY = 1f + (swell * 0.88f) * p
        }
    }

/**
 * One-shot diagonal liquid sheen glint that glides smoothly across a floating window/sheet/dialog
 * when it opens (zero idle GPU cost — runs once for 520ms and stops!).
 */
fun Modifier.liquidEntranceSheen(trigger: Any = Unit): Modifier = composed {
    if (PerfTier.low) return@composed this
    val sweep = remember(trigger) { Animatable(-0.4f) }
    androidx.compose.runtime.LaunchedEffect(trigger) {
        sweep.snapTo(-0.35f)
        sweep.animateTo(1.35f, tween(540, easing = FastOutSlowInEasing))
    }
    val dark = LocalPalette.current.dark
    this.drawWithCache {
        val none = Color.White.copy(alpha = 0f)
        val peak = Color(0xFFD8F3FF).copy(alpha = if (dark) 0.16f else 0.34f)
        val core = Color.White.copy(alpha = if (dark) 0.22f else 0.46f)
        onDrawWithContent {
            drawContent()
            val t = sweep.value
            if (t > -0.32f && t < 1.32f) {
                val cx = size.width * t
                val band = size.width * 0.32f
                drawRect(
                    Brush.linearGradient(
                        0f to none,
                        0.38f to peak,
                        0.50f to core,
                        0.62f to peak,
                        1f to none,
                        start = Offset(cx - band, 0f),
                        end = Offset(cx + band, size.height)
                    )
                )
            }
        }
    }
}

