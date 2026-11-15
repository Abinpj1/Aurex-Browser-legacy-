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
import kotlin.math.sqrt

enum class ShotPhase {
    /** Going to the top of the page and taking the first piece. */
    PREPARE,
    /** The page scrolls by itself, waits for what is on screen to load, takes a piece, goes on. */
    AUTO,
    /** The user scrolls; every place the page rests at is captured. */
    MANUAL,
    /** After "Done": pieces missing in between are scrolled to and captured. */
    FILL,
    /** Joining the pieces. */
    FINISH
}

/**
 * One long screenshot of a web page.
 *
 * The page is captured in screen-sized pieces that are placed at their own position in one tall picture, so the
 * order does not matter and nothing is captured twice:
 *  - AUTO: scroll from the top to the end, smooth, one piece per screen. Before each piece the engine waits until the
 *    images on screen have loaded (lazy-loaded pictures are asked to load), so what is captured is what the page shows.
 *  - A touch on the page hands the scrolling to the user (MANUAL). Whatever the user scrolls to is captured as soon as
 *    the page rests there. "Auto-scroll" takes over again from the first uncaptured place, "Done" ends it.
 *  - Pages that grow while scrolling (infinite lists) are followed until they stop growing or the size limit is hit.
 *  - Fixed / sticky bars are hidden after the first piece so they do not repeat down the picture.
 *
 * Everything runs on the UI thread; the state fields are Compose state read by the capture HUD.
 */
class LongShot(
    private val web: WebView,
    private val background: Int,
    /** (picture, message, screens): picture null = cancelled (message null) or failed (message tells why). */
    private val finished: (Bitmap?, String?, Float) -> Unit
) {
    var phase by mutableStateOf(ShotPhase.PREPARE)
        private set
    /** Share of the page that is captured, 0..1. */
    var coverage by mutableFloatStateOf(0f)
        private set
    /** Where the page is scrolled to, 0..1. */
    var position by mutableFloatStateOf(0f)
        private set
    /** Length captured so far, in screens. */
    var screens by mutableFloatStateOf(1f)
        private set
    /** Counts up with every captured piece (drives the shutter flash). */
    var pulse by mutableIntStateOf(0)
        private set
    /** The size limit was reached: the picture ends there. */
    var limited by mutableStateOf(false)
        private set

    private val ui = Handler(Looper.getMainLooper())
    private var token = 0
    private var ended = false
    private var takeOver = false

    private var w = 0
    private var viewH = 0
    private var outScale = 1f
    private var capDoc = 0
    private var allocDoc = 0
    private var big: Bitmap? = null
    private var bigCanvas: Canvas? = null
    private var strip: Bitmap? = null
    private var stripCanvas: Canvas? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Captured stretches of the page [start, end), sorted and merged. */
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
        // The toolbar slides away first, so the page has its final size when it is measured.
        ui.postDelayed({ begin() }, 320)
    }

    private fun begin() {
        if (ended) return
        w = web.width
        viewH = web.height
        if (w <= 0 || viewH <= 0) { end(null, "Open a webpage first to capture a screenshot"); return }
        val plan = max(docHeight(), viewH * 3)
        val px = w.toDouble() * plan
        outScale = if (px > MAX_PIXELS) sqrt(MAX_PIXELS / px).toFloat() else 1f
        capDoc = min(MAX_DOC_HEIGHT, (MAX_PIXELS / (w.toDouble() * outScale * outScale)).toInt())
        try {
            val st = Bitmap.createBitmap(w, viewH, Bitmap.Config.ARGB_8888)
            strip = st
            stripCanvas = Canvas(st)
            newBig(min(capDoc, (docHeight() * 1.25f).toInt() + viewH))
        } catch (t: Throwable) {
            end(null, "Not enough memory to capture this page")
            return
        }
        savedX = web.scrollX
        savedY = web.scrollY
        web.evaluateJavascript(FIXED_COLLECT_JS, null)
        scrollSmooth(0) {
            awaitReady(260) {
                if (!grab()) { end(null, "Could not capture this page"); return@awaitReady }
                // Bars that stay on screen are only wanted once, at the top.
                web.evaluateJavascript(FIXED_HIDE_JS, null)
                if (takeOver) enterManual() else {
                    phase = ShotPhase.AUTO
                    fillStep()
                }
            }
        }
    }

    // ── Buttons and touches ─────────────────────────────────────────────────────────

    /** Raw touch actions on the page. A touch while the page scrolls by itself hands the scrolling to the user. */
    fun onTouch(action: Int) {
        if (ended) return
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

    /** The "scroll myself" button. */
    fun takeControl() {
        if (!ended && phase == ShotPhase.AUTO) enterManual()
    }

    /** The "auto-scroll" button: carries on from the first place that is not captured yet. */
    fun resumeAuto() {
        if (ended || phase != ShotPhase.MANUAL) return
        token++
        growthChecked = false
        phase = ShotPhase.AUTO
        fillStep()
    }

    /** "Done": stop where the capture is. Gaps inside what was captured are filled first. */
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

    /**
     * Goes to the first stretch of the page that is not captured, waits for it to load and captures it; repeats until
     * there is nothing left (AUTO: the whole page, FILL: everything down to the lowest captured point).
     */
    private fun fillStep() {
        if (ended) return
        val my = token
        if (++steps > 700) { finish(); return }
        val knownEnd = if (docEnd > 0) docEnd else Int.MAX_VALUE
        val limit = if (phase == ShotPhase.FILL) min(hi, knownEnd) else (if (docEnd > 0) docEnd else docHeight())
        val gap = firstGap(min(limit, capDoc))
        if (gap < 0) {
            // The end of the page: a page that is still growing (infinite scroll) gets one more look.
            if (phase == ShotPhase.AUTO && !growthChecked && limit < capDoc) {
                growthChecked = true
                awaitReady(450) {
                    if (my != token) return@awaitReady
                    if (docHeight() > limit + 8 && growthRounds++ < 40) {
                        docEnd = -1
                        growthChecked = false
                        fillStep()
                    } else finish()
                }
                return
            }
            finish()
            return
        }
        growthChecked = false
        scrollSmooth(gap) {
            if (my != token) return@scrollSmooth
            val actual = web.scrollY
            // The page could not go as far as asked: its real end is here.
            if (gap > 0 && actual < gap - 2) docEnd = actual + viewH
            awaitReady(240) {
                if (my != token) return@awaitReady
                if (!grab()) {
                    if (limited) finish() else end(null, "Capture failed")
                    return@awaitReady
                }
                // A stretch that cannot be reached (page shorter than it said) must not keep the loop going.
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
        a.duration = (200 + min(abs(to - from), viewH * 2) * 0.12f).toLong().coerceIn(220L, 460L)
        a.interpolator = DecelerateInterpolator(1.3f)
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
     * Waits until the pictures on screen have loaded (lazy ones are told to load), at least [minMs]; never longer
     * than about three seconds. A short pause at the end lets the screen draw the new content.
     */
    private fun awaitReady(minMs: Long, then: () -> Unit) {
        val my = token
        val start = SystemClock.uptimeMillis()
        var called = false
        var quiet = 0
        fun fire() {
            if (called) return
            called = true
            if (my == token && !ended) ui.postDelayed({ if (my == token && !ended) then() }, 70)
        }
        ui.postDelayed({ fire() }, 2800)
        fun poll() {
            if (called || my != token || ended) return
            web.evaluateJavascript(PENDING_JS) { r ->
                if (called || my != token || ended) return@evaluateJavascript
                val pending = r?.trim('"')?.toIntOrNull() ?: 0
                if (pending == 0) quiet++ else quiet = 0
                if (SystemClock.uptimeMillis() - start >= minMs && quiet >= 2) fire()
                else ui.postDelayed({ poll() }, 100)
            }
        }
        ui.postDelayed({ poll() }, 120)
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

    /** While the user scrolls: wherever the page rests (finger up, not moving) and is not captured yet, capture it. */
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
            } else if (!manualBusy && now - stableSince >= 260 && !isCovered(y, min(y + viewH, capDoc))) {
                manualBusy = true
                awaitReady(0) {
                    manualBusy = false
                    if (my == token && phase == ShotPhase.MANUAL && web.scrollY == y && !fingerDown) grab()
                    watch()
                }
                return@postDelayed
            }
            watch()
        }, 100)
    }

    // ── Capturing ───────────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun docHeight(): Int = (web.contentHeight * web.scale).toInt().coerceAtLeast(viewH)

    private fun newBig(docPx: Int) {
        val nb = Bitmap.createBitmap(
            (w * outScale).toInt().coerceAtLeast(1),
            (docPx * outScale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val c = Canvas(nb)
        c.drawColor(background)
        val old = big
        if (old != null) {
            c.drawBitmap(old, 0f, 0f, null)
            old.recycle()
        }
        big = nb
        bigCanvas = c
        allocDoc = docPx
    }

    /** Takes the piece the screen shows now and puts it where it belongs in the tall picture. */
    private fun grab(): Boolean {
        val st = strip ?: return false
        val sc = stripCanvas ?: return false
        val at = web.scrollY.coerceAtLeast(0)
        if (at >= capDoc) { limited = true; return false }
        val bottom = min(at + viewH, capDoc)
        if (at + viewH > capDoc) limited = true
        return try {
            if (bottom > allocDoc) newBig(min(capDoc, max(bottom, (allocDoc * 1.5f).toInt())))
            sc.drawColor(background)
            sc.save()
            sc.translate(-web.scrollX.toFloat(), -at.toFloat())
            web.draw(sc)
            sc.restore()
            val bc = bigCanvas ?: return false
            bc.save()
            bc.scale(outScale, outScale)
            bc.drawBitmap(st, 0f, at.toFloat(), paint)
            bc.restore()
            addCover(at, bottom)
            hi = max(hi, bottom)
            updateState()
            pulse++
            true
        } catch (t: Throwable) {
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

    /** First place of the page, above [limit], that is not captured; -1 when everything is. */
    private fun firstGap(limit: Int): Int {
        var p = 0
        for (r in cover) {
            if (r[0] > p) break
            if (r[1] > p) p = r[1]
        }
        return if (p < limit) p else -1
    }

    private fun isCovered(a: Int, b: Int): Boolean = cover.any { it[0] <= a && it[1] >= b }

    // ── End ─────────────────────────────────────────────────────────────────────────

    private fun finish() {
        if (ended) return
        phase = ShotPhase.FINISH
        token++
        animator?.cancel()
        animator = null
        // A moment for the HUD to show "joining" before the (heavy) copy below.
        ui.postDelayed({ build() }, 200)
    }

    private fun build() {
        if (ended) return
        val b = big
        if (b == null) { end(null, "Capture failed"); return }
        val bottom = if (docEnd > 0) min(hi, docEnd) else hi
        val docPx = min(max(bottom, 1), capDoc)
        val outH = (docPx * outScale).toInt().coerceIn(1, b.height)
        val result = try {
            if (outH == b.height) b else Bitmap.createBitmap(b, 0, 0, b.width, outH)
        } catch (t: Throwable) {
            null
        }
        if (result == null) { end(null, "Not enough memory to finish the screenshot"); return }
        if (looksBlank(result)) {
            if (result !== b) result.recycle()
            end(null, "The page came out empty, try again")
            return
        }
        end(result, null, docPx.toFloat() / max(1, viewH))
    }

    private fun end(result: Bitmap?, message: String?, screens: Float = 1f) {
        if (ended) return
        ended = true
        token++
        animator?.cancel()
        animator = null
        ui.removeCallbacksAndMessages(null)
        runCatching {
            web.evaluateJavascript(FIXED_RESTORE_JS, null)
            if (savedY >= 0) web.scrollTo(savedX, savedY)
        }
        big?.let { if (it !== result) it.recycle() }
        big = null
        bigCanvas = null
        strip?.recycle()
        strip = null
        stripCanvas = null
        finished(result, message, screens)
    }

    /** True when nothing but the background colour was drawn (a failed capture); sampled, not every pixel. */
    private fun looksBlank(bmp: Bitmap): Boolean {
        val stepX = (bmp.width / 36).coerceAtLeast(1)
        val stepY = (bmp.height / 260).coerceAtLeast(1)
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
        /** About 64 MB of picture; longer / wider pages are scaled down instead of being cut. */
        const val MAX_PIXELS = 16_000_000.0
        const val MAX_DOC_HEIGHT = 40000

        /** Counts pictures on (or near) the screen that have not loaded; lazy ones are told to load now. */
        const val PENDING_JS =
            "(function(){try{var h=window.innerHeight||800,n=0,im=document.images;" +
                "for(var i=0;i<im.length;i++){var m=im[i],r=m.getBoundingClientRect();" +
                "if(r.bottom<-200||r.top>h+200||r.width<24||r.height<24)continue;" +
                "if(m.loading==='lazy')m.loading='eager';" +
                "var d=m.getAttribute('data-src')||m.getAttribute('data-lazy-src');" +
                "if(d&&(!m.currentSrc||m.src.indexOf('data:')===0))m.src=d;" +
                "if(!m.complete)n++;}return n;}catch(e){return 0;}})()"

        const val FIXED_COLLECT_JS =
            "(function(){try{var a=[],e=document.querySelectorAll('body *');for(var i=0;i<e.length;i++){var p=getComputedStyle(e[i]).position;" +
                "if(p==='fixed'||p==='sticky')a.push([e[i],e[i].style.getPropertyValue('visibility'),e[i].style.getPropertyPriority('visibility')]);}" +
                "window.__aurexFx=a;}catch(x){}})()"
        const val FIXED_HIDE_JS =
            "(function(){try{(window.__aurexFx||[]).forEach(function(r){r[0].style.setProperty('visibility','hidden','important');});}catch(x){}})()"
        const val FIXED_RESTORE_JS =
            "(function(){try{(window.__aurexFx||[]).forEach(function(r){r[0].style.removeProperty('visibility');" +
                "if(r[1])r[0].style.setProperty('visibility',r[1],r[2]);});window.__aurexFx=null;}catch(x){}})()"
    }
}
