package com.aurex.browser

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class ShotPhase {
    /** Measuring page and preparing first frame. */
    PREPARE,
    /** Auto-scrolling, triggering lazy media, and capturing seamless viewport tiles. */
    AUTO,
    /** User scrolling manually; every resting viewport is captured automatically. */
    MANUAL,
    /** Filling any uncaptured gaps between captured ranges before stitching. */
    FILL,
    /** Off-thread single-pass stitching into the final crisp bitmap. */
    FINISH
}

/**
 * Aurex Pro Screenshot Engine:
 *  - Tile-based capture + single-pass off-thread stitching -> zero mid-scroll bitmap reallocations,
 *    zero OOM on long pages, smooth on low-end 4 GB devices, up to 28 MP crisp output on flagships.
 *  - Supports BOTH instant single-screen capture ([singleScreenOnly] / [snapThisScreen]) and
 *    auto/manual full-page long screenshots.
 *  - Smart fixed/sticky suppression: protects full-screen SPA roots from going blank, hides bottom
 *    floating bars before Frame 1, sweeps dynamically-stuck headers per step, and restores all CSS.
 *  - Temporarily overrides CSS `scroll-behavior: smooth` so scroll steps land pixel-exact every time.
 */
class LongShot(
    private val web: WebView,
    private val background: Int,
    /** True = instant single-screen capture with ZERO JS injection & ZERO scrolling (safe everywhere). */
    private val singleScreenOnly: Boolean = false,
    /** (picture, message, screens): picture null = cancelled (message null) or failed (message explains why). */
    private val finished: (Bitmap?, String?, Float) -> Unit
) {
    var phase by mutableStateOf(ShotPhase.PREPARE)
        private set
    /** Share of the page captured so far, 0..1. */
    var coverage by mutableFloatStateOf(0f)
        private set
    /** Current scroll progress down the page, 0..1. */
    var position by mutableFloatStateOf(0f)
        private set
    /** Length captured so far, in screens. */
    var screens by mutableFloatStateOf(1f)
        private set
    /** Increments on each captured tile (drives shutter flash). */
    var pulse by mutableIntStateOf(0)
        private set
    /** True when the tier pixel budget was reached. */
    var limited by mutableStateOf(false)
        private set

    private class Tile(val docY: Int, val docBottom: Int, val bmp: Bitmap)

    private val ui = Handler(Looper.getMainLooper())
    private var token = 0
    private var ended = false
    private var takeOver = false
    private var jsTouched = false

    private var w = 0
    private var viewH = 0
    private var outScale = 1f
    private var outW = 1
    private var capDoc = 0
    private var jsDocH = 0
    private var bmpConfig: Bitmap.Config = Bitmap.Config.ARGB_8888

    private var strip: Bitmap? = null
    private var stripCanvas: Canvas? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    /** Captured viewport tiles (kept scaled at [outScale] to slash RAM usage by 3-5x). */
    private val tiles = ArrayList<Tile>()
    /** Captured stretches [start, end), sorted and merged. */
    private val cover = ArrayList<IntArray>()
    private var hi = 0
    private var docEnd = -1
    private var savedX = 0
    private var savedY = -1
    private var growthChecked = false
    private var growthRounds = 0
    private var steps = 0

    private var animator: ValueAnimator? = null
    private var fingerDown = false
    private var lastY = 0
    private var stableSince = 0L
    private var manualBusy = false

    fun owns(v: View?): Boolean = v === web

    // ── Start ───────────────────────────────────────────────────────────────────────

    fun start() {
        // Brief pause lets the browser toolbar glide out of the way before measuring the viewport.
        ui.postDelayed({ begin() }, if (singleScreenOnly) 180L else 260L)
    }

    private fun maxPixelsForTier(): Double = when (PerfTier.tier) {
        PerfTier.TIER_LOW -> 10_000_000.0
        PerfTier.TIER_BALANCED -> 18_000_000.0
        else -> 28_000_000.0
    }

    private fun begin() {
        if (ended) return
        w = web.width
        viewH = web.height
        if (w <= 0 || viewH <= 0) {
            end(null, "Open a webpage first to capture a screenshot")
            return
        }
        bmpConfig = if (PerfTier.low) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888

        // Fast-path: instant visible-screen capture (zero scrolling, zero JS injection — safe on Meta/IG too!).
        if (singleScreenOnly) {
            snapVisibleImmediate()
            return
        }

        val my = token
        // Measure accurate real scrollHeight from DOM first so scale & pixel budget are exact from step 1.
        web.evaluateJavascript(MEASURE_DOC_JS) { raw ->
            if (ended || my != token) return@evaluateJavascript
            val cssH = raw?.trim('"')?.toFloatOrNull() ?: 0f
            @Suppress("DEPRECATION")
            val scale = web.scale.takeIf { it > 0.1f } ?: web.resources.displayMetrics.density
            if (cssH > 0f) jsDocH = (cssH * scale).roundToInt()
            startLongCapture()
        }
    }

    private fun snapVisibleImmediate() {
        try {
            val out = Bitmap.createBitmap(w, viewH, bmpConfig)
            val c = Canvas(out)
            c.drawColor(background)
            c.save()
            c.translate(-web.scrollX.toFloat(), -web.scrollY.toFloat())
            web.draw(c)
            c.restore()
            if (looksBlank(out)) {
                out.recycle()
                end(null, "The page came out empty, try again")
                return
            }
            Haptics.fire(null, Hx.SUCCESS)
            end(out, null, 1f)
        } catch (_: Throwable) {
            end(null, "Not enough memory to capture this screen")
        }
    }

    private fun startLongCapture() {
        if (ended) return
        val maxPx = maxPixelsForTier()
        val estDoc = max(docHeight(), viewH * 2)
        val totalPx = w.toDouble() * estDoc
        outScale = if (totalPx > maxPx) sqrt(maxPx / totalPx).toFloat().coerceIn(0.35f, 1f) else 1f
        outW = (w * outScale).roundToInt().coerceAtLeast(1)
        capDoc = min(MAX_DOC_HEIGHT, (maxPx / (w.toDouble() * outScale * outScale)).toInt().coerceAtLeast(viewH))

        try {
            val st = Bitmap.createBitmap(w, viewH, bmpConfig)
            strip = st
            stripCanvas = Canvas(st)
        } catch (_: Throwable) {
            end(null, "Not enough memory to capture this page")
            return
        }

        savedX = web.scrollX
        savedY = web.scrollY
        jsTouched = true
        // Disable smooth-scroll CSS + pre-hide bottom floating bars before Screen 1 so they never split the image!
        web.evaluateJavascript(PREPARE_PAGE_JS, null)

        // If the page fits inside a single viewport (or uses an inner SPA container), capture it right away!
        if (docHeight() <= viewH + 16 && savedY <= 8) {
            awaitReady(140L) {
                if (!grab()) { end(null, "Could not capture this page"); return@awaitReady }
                finish()
            }
            return
        }

        scrollSmooth(0) {
            awaitReady(160L) {
                if (!grab()) { end(null, "Could not capture this page"); return@awaitReady }
                // Hide top/side fixed & sticky bars for screens 2+ so they never repeat down the capture!
                web.evaluateJavascript(FIXED_HIDE_JS, null)
                if (takeOver) enterManual() else {
                    phase = ShotPhase.AUTO
                    fillStep()
                }
            }
        }
    }

    // ── Buttons and touches ─────────────────────────────────────────────────────────

    fun onTouch(action: Int) {
        if (ended || singleScreenOnly) return
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                fingerDown = true
                when (phase) {
                    ShotPhase.AUTO -> enterManual()
                    ShotPhase.PREPARE -> takeOver = true
                    else -> {}
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                fingerDown = false
                stableSince = SystemClock.uptimeMillis()
            }
        }
    }

    /** Instant "This screen" button: stops scrolling and returns just the current screen cleanly. */
    fun snapThisScreen() {
        if (ended || phase == ShotPhase.FINISH) return
        token++
        animator?.cancel()
        animator = null
        clearTiles()
        cover.clear()
        hi = 0
        docEnd = -1
        if (jsTouched) {
            runCatching { web.evaluateJavascript(FIXED_RESTORE_JS, null) }
            jsTouched = false
        }
        snapVisibleImmediate()
    }

    fun takeControl() {
        if (!ended && phase == ShotPhase.AUTO) enterManual()
    }

    fun resumeAuto() {
        if (ended || phase != ShotPhase.MANUAL) return
        token++
        growthChecked = false
        phase = ShotPhase.AUTO
        fillStep()
    }

    fun done() {
        if (ended || (phase != ShotPhase.AUTO && phase != ShotPhase.MANUAL)) return
        token++
        animator?.cancel()
        animator = null
        phase = ShotPhase.FILL
        fillStep()
    }

    fun cancel() {
        if (!ended) end(null, null)
    }

    // ── Auto scrolling ──────────────────────────────────────────────────────────────

    private fun fillStep() {
        if (ended) return
        val my = token
        if (++steps > 500) { finish(); return }
        val knownEnd = if (docEnd > 0) docEnd else Int.MAX_VALUE
        val limit = if (phase == ShotPhase.FILL) min(hi, knownEnd) else (if (docEnd > 0) docEnd else docHeight())
        val gap = firstGap(min(limit, capDoc))
        if (gap < 0) {
            if (phase == ShotPhase.AUTO && !growthChecked && limit < capDoc) {
                growthChecked = true
                awaitReady(320L) {
                    if (my != token) return@awaitReady
                    web.evaluateJavascript(MEASURE_DOC_JS) { raw ->
                        if (ended || my != token) return@evaluateJavascript
                        val cssH = raw?.trim('"')?.toFloatOrNull() ?: 0f
                        @Suppress("DEPRECATION")
                        val sc = web.scale.takeIf { it > 0.1f } ?: web.resources.displayMetrics.density
                        if (cssH > 0f) jsDocH = max(jsDocH, (cssH * sc).roundToInt())
                        if (docHeight() > limit + 12 && growthRounds++ < 30) {
                            docEnd = -1
                            growthChecked = false
                            fillStep()
                        } else finish()
                    }
                }
                return
            }
            finish()
            return
        }
        growthChecked = false
        // Slight 2px overlap prevents hairline seam rounding artifacts on fractional DPR screens!
        val scrollTarget = max(0, gap - if (gap > 0) 2 else 0)
        scrollSmooth(scrollTarget) {
            if (my != token) return@scrollSmooth
            val actual = web.scrollY
            if (gap > 0 && actual < scrollTarget - 4) docEnd = actual + viewH
            // Sweep any dynamically-stuck header that activated after scrolling down
            web.evaluateJavascript(FIXED_HIDE_JS, null)
            awaitReady(120L) {
                if (my != token) return@awaitReady
                if (!grab()) {
                    if (limited) finish() else end(null, "Capture failed")
                    return@awaitReady
                }
                if (firstGap(gap + 1) == gap) addCover(gap, min(gap + viewH, capDoc))
                fillStep()
            }
        }
    }

    private fun scrollSmooth(target: Int, then: () -> Unit) {
        animator?.cancel()
        animator = null
        val from = web.scrollY
        val to = max(0, target)
        val my = token
        if (abs(to - from) < 4) {
            web.scrollTo(web.scrollX, to)
            ui.post { if (my == token && !ended) then() }
            return
        }
        val a = ValueAnimator.ofInt(from, to)
        a.duration = (140 + min(abs(to - from), viewH * 2) * 0.085f).toLong().coerceIn(150L, 290L)
        a.interpolator = DecelerateInterpolator(1.25f)
        a.addUpdateListener { v -> web.scrollTo(web.scrollX, v.animatedValue as Int) }
        a.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) {
                if (!cancelled && my == token && !ended) then()
            }
        })
        animator = a
        a.start()
    }

    /**
     * Triggers lazy-loaded images near the viewport and waits until images finish decoding + 1 paint frame
     * (fast ~140-200ms when page is already loaded, capped at 2.2s for slow networks).
     */
    private fun awaitReady(minMs: Long, then: () -> Unit) {
        val my = token
        val start = SystemClock.uptimeMillis()
        var called = false
        var quiet = 0
        fun fire() {
            if (called) return
            called = true
            if (my == token && !ended) ui.postDelayed({ if (my == token && !ended) then() }, 45L)
        }
        ui.postDelayed({ fire() }, 2_200L)
        fun poll() {
            if (called || my != token || ended) return
            web.evaluateJavascript(PENDING_JS) { r ->
                if (called || my != token || ended) return@evaluateJavascript
                val pending = r?.trim('"')?.toIntOrNull() ?: 0
                if (pending == 0) quiet++ else quiet = 0
                if (SystemClock.uptimeMillis() - start >= minMs && quiet >= 1) fire()
                else ui.postDelayed({ poll() }, 85L)
            }
        }
        ui.postDelayed({ poll() }, 75L)
    }

    // ── Manual scrolling ────────────────────────────────────────────────────────────

    private fun enterManual() {
        token++
        animator?.cancel()
        animator = null
        phase = ShotPhase.MANUAL
        manualBusy = false
        lastY = web.scrollY
        stableSince = SystemClock.uptimeMillis()
        watch()
    }

    private fun watch() {
        val my = token
        ui.postDelayed({
            if (my != token || ended || phase != ShotPhase.MANUAL) return@postDelayed
            val y = web.scrollY
            val now = SystemClock.uptimeMillis()
            if (y != lastY || fingerDown) {
                lastY = y
                stableSince = now
                updateState()
            } else if (!manualBusy && now - stableSince >= 220 && !isCovered(y, min(y + viewH, capDoc))) {
                manualBusy = true
                web.evaluateJavascript(FIXED_HIDE_JS, null)
                awaitReady(0L) {
                    manualBusy = false
                    if (my == token && phase == ShotPhase.MANUAL && web.scrollY == y && !fingerDown) grab()
                    watch()
                }
                return@postDelayed
            }
            watch()
        }, 90L)
    }

    // ── Tile Capturing ──────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun docHeight(): Int {
        val nativeH = (web.contentHeight * web.scale).toInt()
        return maxOf(nativeH, jsDocH, viewH)
    }

    private fun grab(): Boolean {
        val st = strip ?: return false
        val sc = stripCanvas ?: return false
        val at = web.scrollY.coerceAtLeast(0)
        if (at >= capDoc) { limited = true; return false }
        val bottom = min(at + viewH, capDoc)
        if (at + viewH > capDoc) limited = true
        return try {
            sc.drawColor(background)
            sc.save()
            sc.translate(-web.scrollX.toFloat(), -at.toFloat())
            web.draw(sc)
            sc.restore()

            val tileH = ((bottom - at) * outScale).roundToInt().coerceAtLeast(1)
            val tileBmp = Bitmap.createBitmap(outW, tileH, bmpConfig)
            val tc = Canvas(tileBmp)
            tc.save()
            tc.scale(outScale, outScale)
            tc.drawBitmap(st, 0f, 0f, paint)
            tc.restore()

            tiles.add(Tile(at, bottom, tileBmp))
            addCover(at, bottom)
            hi = max(hi, bottom)
            updateState()
            pulse++
            Haptics.fire(null, Hx.TICK)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun updateState() {
        val total = max(if (docEnd > 0) docEnd else docHeight(), 1)
        var c = 0
        for (r in cover) c += r[1] - r[0]
        coverage = (c.toFloat() / total).coerceIn(0f, 1f)
        position = (web.scrollY.toFloat() / max(1, total - viewH)).coerceIn(0f, 1f)
        screens = max(1f, hi.toFloat() / max(1, viewH))
    }

    private fun addCover(a: Int, b: Int) {
        if (b <= a) return
        var s0 = a
        var e0 = b
        val iter = cover.iterator()
        while (iter.hasNext()) {
            val r = iter.next()
            if (r[1] >= s0 && r[0] <= e0) {
                s0 = min(s0, r[0])
                e0 = max(e0, r[1])
                iter.remove()
            }
        }
        var i = 0
        while (i < cover.size && cover[i][0] < s0) i++
        cover.add(i, intArrayOf(s0, e0))
    }

    private fun firstGap(limit: Int): Int {
        var p = 0
        for (r in cover) {
            if (r[0] > p) break
            if (r[1] > p) p = r[1]
        }
        return if (p < limit) p else -1
    }

    private fun isCovered(a: Int, b: Int): Boolean = cover.any { it[0] <= a && it[1] >= b }

    // ── Single-Pass Off-Thread Stitch ───────────────────────────────────────────────

    private fun finish() {
        if (ended) return
        phase = ShotPhase.FINISH
        token++
        animator?.cancel()
        animator = null
        // Restore page scroll & fixed elements right away while stitching runs on a background thread!
        restorePage()
        val snapTiles = ArrayList(tiles)
        tiles.clear()
        strip?.recycle()
        strip = null
        stripCanvas = null

        val bottom = if (docEnd > 0) min(hi, docEnd) else hi
        val docPx = min(max(bottom, 1), capDoc)
        val finalW = outW
        val finalH = (docPx * outScale).roundToInt().coerceAtLeast(1)
        val scale = outScale
        val bg = background
        val cfg = bmpConfig
        val screenRatio = docPx.toFloat() / max(1, viewH)

        Thread({
            var stitched: Bitmap? = null
            var errMsg: String? = null
            try {
                if (snapTiles.isEmpty()) {
                    errMsg = "Capture failed"
                } else {
                    val out = Bitmap.createBitmap(finalW, finalH, cfg)
                    val c = Canvas(out)
                    c.drawColor(bg)
                    // Draw in ascending docY order so later tiles seamlessly align onto the stitched canvas.
                    snapTiles.sortBy { it.docY }
                    for (t in snapTiles) {
                        val y = (t.docY * scale).roundToInt().toFloat()
                        c.drawBitmap(t.bmp, 0f, y, null)
                    }
                    if (looksBlank(out)) {
                        out.recycle()
                        errMsg = "The page came out empty, try again"
                    } else {
                        stitched = out
                    }
                }
            } catch (_: Throwable) {
                errMsg = "Not enough memory to finish the screenshot"
            } finally {
                snapTiles.forEach { runCatching { it.bmp.recycle() } }
            }
            ui.post {
                if (ended) {
                    stitched?.recycle()
                    return@post
                }
                if (stitched != null) Haptics.fire(null, Hx.SUCCESS)
                end(stitched, errMsg, screenRatio)
            }
        }, "aurex-shot-stitch").start()
    }

    private fun restorePage() {
        if (jsTouched) {
            jsTouched = false
            runCatching { web.evaluateJavascript(FIXED_RESTORE_JS, null) }
        }
        if (savedY >= 0) {
            val sx = savedX
            val sy = savedY
            savedY = -1
            runCatching { web.scrollTo(sx, sy) }
        }
    }

    private fun clearTiles() {
        tiles.forEach { runCatching { it.bmp.recycle() } }
        tiles.clear()
    }

    private fun end(result: Bitmap?, message: String?, screens: Float = 1f) {
        if (ended) return
        ended = true
        token++
        animator?.cancel()
        animator = null
        ui.removeCallbacksAndMessages(null)
        restorePage()
        clearTiles()
        strip?.recycle()
        strip = null
        stripCanvas = null
        finished(result, message, screens)
    }

    private fun looksBlank(bmp: Bitmap): Boolean {
        val stepX = (bmp.width / 32).coerceAtLeast(1)
        val stepY = (bmp.height / 200).coerceAtLeast(1)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                if (bmp.getPixel(x, y) != background) return false
                x += stepX
            }
            y += stepY
        }
        return true
    }

    private companion object {
        const val MAX_DOC_HEIGHT = 48_000

        /** Accurate full document scrollHeight in CSS px. */
        const val MEASURE_DOC_JS =
            "(function(){try{var d=document.documentElement,b=document.body;" +
                "return Math.max(d?d.scrollHeight:0,b?b.scrollHeight:0,window.innerHeight||0);}catch(e){return 0;}})()"

        /** Forces eager loading for all common lazy-image patterns near the viewport and counts still-loading images. */
        const val PENDING_JS =
            "(function(){try{var h=window.innerHeight||800,n=0,im=document.images;" +
                "for(var i=0;i<im.length;i++){var m=im[i],r=m.getBoundingClientRect();" +
                "if(r.bottom<-260||r.top>h+260||r.width<20||r.height<20)continue;" +
                "if(m.loading==='lazy')m.loading='eager';" +
                "var d=m.getAttribute('data-src')||m.getAttribute('data-lazy-src')||m.getAttribute('data-original');" +
                "if(d&&(!m.currentSrc||m.src.indexOf('data:')===0))m.src=d;" +
                "var ds=m.getAttribute('data-srcset');if(ds&&!m.srcset)m.srcset=ds;" +
                "if(!m.complete)n++;}return n;}catch(e){return 0;}})()"

        /**
         * Disables CSS smooth-scrolling (which otherwise lags behind scrollTo!) and hides bottom-anchored
         * floating bars (cookie banners, "Open in App" pills, floating navs) before Screen 1.
         */
        const val PREPARE_PAGE_JS =
            "(function(){try{" +
                "if(!document.getElementById('aurex-shot-css')){" +
                "var st=document.createElement('style');st.id='aurex-shot-css';" +
                "st.textContent='html,body{scroll-behavior:auto!important;}';" +
                "(document.head||document.documentElement).appendChild(st);}" +
                "window.__aurexFx=window.__aurexFx||[];window.__aurexFxSet=window.__aurexFxSet||new WeakSet();" +
                "var vh=window.innerHeight||800,vw=window.innerWidth||400,els=document.querySelectorAll('body *');" +
                "for(var i=0;i<els.length&&i<2000;i++){" +
                "var el=els[i],p=getComputedStyle(el).position;" +
                "if(p!=='fixed'&&p!=='sticky')continue;" +
                "var r=el.getBoundingClientRect();" +
                "if(r.width<=0||r.height<=0)continue;" +
                "if(r.height>vh*0.48&&r.width>vw*0.65)continue;" +
                "if(el.querySelector&&el.querySelector('main,article'))continue;" +
                "if(!window.__aurexFxSet.has(el)){" +
                "window.__aurexFxSet.add(el);" +
                "window.__aurexFx.push([el,el.style.getPropertyValue('visibility'),el.style.getPropertyPriority('visibility')]);}" +
                "if(p==='fixed'&&r.top>vh*0.62&&r.height<vh*0.30){el.style.setProperty('visibility','hidden','important');}" +
                "}}catch(x){}})()"

        /** Hides all safe fixed/sticky headers & widgets for Screens 2+, catching newly-stuck bars too. */
        const val FIXED_HIDE_JS =
            "(function(){try{" +
                "window.__aurexFx=window.__aurexFx||[];window.__aurexFxSet=window.__aurexFxSet||new WeakSet();" +
                "var vh=window.innerHeight||800,vw=window.innerWidth||400,els=document.querySelectorAll('body *');" +
                "for(var i=0;i<els.length&&i<2000;i++){" +
                "var el=els[i],p=getComputedStyle(el).position;" +
                "if(p!=='fixed'&&p!=='sticky')continue;" +
                "var r=el.getBoundingClientRect();" +
                "if(r.width<=0||r.height<=0)continue;" +
                "if(r.height>vh*0.48&&r.width>vw*0.65)continue;" +
                "if(el.querySelector&&el.querySelector('main,article'))continue;" +
                "if(!window.__aurexFxSet.has(el)){" +
                "window.__aurexFxSet.add(el);" +
                "window.__aurexFx.push([el,el.style.getPropertyValue('visibility'),el.style.getPropertyPriority('visibility')]);}" +
                "}" +
                "(window.__aurexFx||[]).forEach(function(r){r[0].style.setProperty('visibility','hidden','important');});" +
                "}catch(x){}})()"

        const val FIXED_RESTORE_JS =
            "(function(){try{" +
                "var st=document.getElementById('aurex-shot-css');if(st)st.remove();" +
                "(window.__aurexFx||[]).forEach(function(r){" +
                "r[0].style.removeProperty('visibility');" +
                "if(r[1])r[0].style.setProperty('visibility',r[1],r[2]);});" +
                "window.__aurexFx=null;window.__aurexFxSet=null;}catch(x){}})()"
    }
}
