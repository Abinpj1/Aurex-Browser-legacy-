package com.aurex.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/**
 * Live state + controls behind the background-playback notification.
 *
 *  - While a page video plays, [start] polls it once a second (position, length, playing, next-able)
 *    and asks [BrowserMediaService] to redraw only when something visible changed.
 *  - The buttons of the notification (and headset / lock-screen keys through the MediaSession) land in
 *    [toggle] / [prev] / [next] / [seekTo]; each flips the shown state at once (optimistic), the page then confirms.
 *  - The poll is paused while the screen is off: nobody can see the notification, so no work is done.
 */
object MediaNotif {
    const val ACTION_TOGGLE = "com.aurex.browser.MEDIA_TOGGLE"
    const val ACTION_PREV = "com.aurex.browser.MEDIA_PREV"
    const val ACTION_NEXT = "com.aurex.browser.MEDIA_NEXT"

    class Snap(
        val posMs: Long,
        val durMs: Long,
        val playing: Boolean,
        val live: Boolean,
        val hasNext: Boolean
    ) {
        val frac: Float
            get() = when {
                live -> 1f
                durMs > 0 -> (posMs.toFloat() / durMs).coerceIn(0f, 1f)
                else -> 0f
            }
    }

    /** A player that is not a web page (the built-in player with a downloaded video) drives the same card through this. */
    interface LocalCtl {
        fun snapshot(): Snap?
        fun toggle()
        fun prev()
        /** false when there is nothing to skip to. */
        fun next(): Boolean
        fun seekTo(ms: Long)
    }

    @Volatile var snap: Snap? = null
    @Volatile var local: LocalCtl? = null

    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var source: (() -> WebView?)? = null
    private var running = false
    private var lastTap = 0L
    private var forced: Boolean? = null
    private var forcedUntil = 0L

    // ── Poll loop ────────────────────────────────────────────────────────────

    fun start(ctx: Context, src: () -> WebView?) {
        appCtx = ctx.applicationContext
        source = src
        if (!running) {
            running = true
            snap = null
            main.post(tick)
        } else {
            // Already running (the next video of the same card): read the new video right now, not in up to a second.
            main.post { poll() }
        }
    }

    /** The built-in player owns the card: polling reads the player, taps go to the player. */
    fun attachLocal(ctx: Context, c: LocalCtl) {
        appCtx = ctx.applicationContext
        local = c
        forced = null
        if (!running) {
            running = true
            snap = null
            main.post(tick)
        } else {
            snap = null
            main.post { poll() }
        }
    }

    fun detachLocal(c: LocalCtl) {
        if (local !== c) return
        local = null
        stop()
    }

    /** Redraw from the player right now (play / pause / seek happened), without waiting for the next tick. */
    fun poke() {
        if (running) main.post { poll() }
    }

    fun stop() {
        if (local != null) return          // the player's card is not the page's to remove
        running = false
        main.removeCallbacks(tick)
        source = null
        snap = null
        forced = null
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            main.postDelayed(this, 1000L)
            val pm = appCtx?.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isInteractive) return      // screen off: nothing to draw
            poll()
        }
    }

    private fun poll() {
        local?.let { c ->
            val raw = runCatching { c.snapshot() }.getOrNull() ?: return
            publish(raw.playing, raw.posMs, raw.durMs, false, raw.hasNext)
            return
        }
        val web = source?.invoke() ?: return
        runCatching { web.evaluateJavascript(POLL_JS) { raw -> onPoll(raw) } }
    }

    private fun onPoll(raw: String?) {
        if (!running) return
        val o = runCatching { JSONObject(JSONTokener(raw ?: "").nextValue() as? String ?: "") }.getOrNull() ?: return
        if (!o.optBoolean("ok")) return
        val live = o.optBoolean("l")
        val t = o.optDouble("t", 0.0)
        val d = o.optDouble("d", -1.0)
        publish(
            o.optBoolean("p"),
            if (t.isFinite() && t >= 0) (t * 1000).toLong() else 0L,
            if (!live && d.isFinite() && d > 0) (d * 1000).toLong() else -1L,
            live,
            o.optBoolean("n")
        )
    }

    private fun publish(isPlaying: Boolean, posMs: Long, durMs: Long, live: Boolean, hasNext: Boolean) {
        var playing = isPlaying
        // Right after a tap the source may still report the old state for a moment: keep what the tap showed.
        val f = forced
        if (f != null) {
            if (SystemClock.uptimeMillis() < forcedUntil && playing != f) playing = f else forced = null
        }
        val n = Snap(posMs, durMs, playing, live, hasNext)
        val old = snap
        snap = n
        val stateChanged = old == null || old.playing != n.playing || old.live != n.live ||
            old.hasNext != n.hasNext || old.durMs != n.durMs
        val expectedPos = if (old != null && old.playing) old.posMs + 1000L else (old?.posMs ?: 0L)
        val drifted = old != null && kotlin.math.abs(n.posMs - expectedPos) > 2_500L
        if (stateChanged) {
            BrowserMediaService.live?.refresh()
        } else if (drifted) {
            BrowserMediaService.live?.syncPositionOnly()
        }
    }

    // ── Controls ─────────────────────────────────────────────────────────────

    private fun debounced(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastTap < 120L) return false
        lastTap = now
        return true
    }

    /** One entry for every button / headset key: the action goes straight to the page or player (no broadcast hop). */
    fun perform(action: String) {
        val web = source?.invoke() as? AurexWebView
        when (action) {
            ACTION_TOGGLE -> { toggle(web); Haptics.fireDetached(Hx.TAP) }
            ACTION_PREV -> { prev(web); Haptics.fireDetached(Hx.TAP) }
            ACTION_NEXT -> if (next(web)) Haptics.fireDetached(Hx.TAP) else Haptics.fireDetached(Hx.ERROR)
        }
    }

    fun toggle(web: AurexWebView?) {
        if (!debounced()) return
        val l = local
        if (l != null) runCatching { l.toggle() } else runCatching { web?.togglePlayPauseFromPip() }
        val s = snap
        val np = !(s?.playing ?: true)
        forced = np
        forcedUntil = SystemClock.uptimeMillis() + 700L
        if (s != null) snap = Snap(s.posMs, s.durMs, np, s.live, s.hasNext)
        BrowserMediaService.live?.refresh()
    }

    /** Apple's rule: past 3 s "previous" restarts the video, within 3 s it goes to the previous one (if the page has one). */
    fun prev(web: WebView?) {
        if (!debounced()) return
        val l = local
        if (l != null) runCatching { l.prev() } else runCatching { web?.evaluateJavascript(PREV_JS, null) }
        val s = snap
        if (s != null) snap = Snap(0L, s.durMs, s.playing, s.live, s.hasNext)
        BrowserMediaService.live?.refresh()
    }

    /** Returns false when the page has nothing to skip to (the caller answers with a "no" haptic). */
    fun next(web: AurexWebView?): Boolean {
        if (snap?.hasNext == false) return false
        if (!debounced()) return true
        val l = local
        if (l != null) { if (!runCatching { l.next() }.getOrDefault(false)) return false }
        else runCatching { web?.playNextFromPip() }
        val s = snap
        if (s != null) snap = Snap(0L, s.durMs, true, s.live, s.hasNext)
        forced = true
        forcedUntil = SystemClock.uptimeMillis() + 900L
        BrowserMediaService.live?.refresh()
        return true
    }

    fun seekTo(ms: Long) {
        val l = local
        if (l != null) {
            runCatching { l.seekTo(ms) }
            val s0 = snap
            if (s0 != null) snap = Snap(ms, s0.durMs, s0.playing, s0.live, s0.hasNext)
            BrowserMediaService.live?.refresh()
            return
        }
        val web = source?.invoke() ?: return
        val sec = String.format(Locale.US, "%.3f", ms.coerceAtLeast(0L) / 1000.0)
        runCatching { web.evaluateJavascript(SEEK_JS.replace("__T__", sec), null) }
        val s = snap
        if (s != null) snap = Snap(ms, s.durMs, s.playing, s.live, s.hasNext)
        BrowserMediaService.live?.refresh()
    }

    // ── Drawing / text helpers ───────────────────────────────────────────────

    /** Thin fully-rounded progress bar: grey track + accent fill (a notification ProgressBar cannot take a runtime colour). */
    fun bar(frac: Float, accent: Int, w: Int = 640, h: Int = 12): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = h / 2f
        p.color = 0x44808080
        c.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, p)
        val f = frac.coerceIn(0f, 1f)
        if (f > 0f) {
            val fw = (w * f).coerceAtLeast(h.toFloat())
            p.color = accent
            c.drawRoundRect(0f, 0f, fw, h.toFloat(), r, r, p)
        }
        return b
    }

    fun fmt(ms: Long): String {
        val s = ms.coerceAtLeast(0L) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    // ── Page scripts ─────────────────────────────────────────────────────────

    /** Position / length / playing / "has next" of the page video. YouTube's own player API wins when present. */
    private const val POLL_JS = """
(function(){try{
 var mp=document.getElementById('movie_player');
 var vs=document.getElementsByTagName('video'),v=null,best=-1;
 for(var i=0;i<vs.length;i++){var x=vs[i];
  var s=(x.paused?0:1e9)+(x.videoWidth||0)*(x.videoHeight||0)+(isFinite(x.duration)?1:0);
  if(x.readyState>0&&s>best){best=s;v=x;}}
 if(!v)return JSON.stringify({ok:false});
 var t=v.currentTime,d=v.duration,ad=false;
 try{ad=!!document.querySelector('.ad-showing');}catch(e){}
 if(mp&&mp.getCurrentTime&&!ad){
  var c=mp.getCurrentTime();if(isFinite(c)&&c>=0)t=c;
  var dd=mp.getDuration&&mp.getDuration();if(isFinite(dd)&&dd>0)d=dd;}
 var live=!isFinite(d)||d<=0;
 var nb=document.querySelector('.ytp-next-button,a.ytp-next-button,button[aria-label*="Next"]');
 var nx=!!(mp&&mp.nextVideo)||!!(nb&&nb.offsetParent!==null);
 return JSON.stringify({ok:true,t:t,d:live?-1:d,p:(!v.paused&&!v.ended),l:live,n:nx});
}catch(e){return JSON.stringify({ok:false});}})()
"""

    private const val PREV_JS = """
(function(){try{
 window.__aurexBgTransitionUntil=0;window.__aurexExplicitUserPause=false;
 var mp=document.getElementById('movie_player');
 var vs=document.getElementsByTagName('video'),v=null;
 for(var i=0;i<vs.length;i++){if(!v||!vs[i].paused)v=vs[i];}
 var t=(mp&&mp.getCurrentTime)?mp.getCurrentTime():(v?v.currentTime:0);
 var pb=document.querySelector('.ytp-prev-button');
 var hasPrev=!!(pb&&pb.offsetParent!==null);
 if(t<=3&&hasPrev){
  if(mp&&mp.previousVideo)mp.previousVideo();else pb.click();
 }else{
  if(mp&&mp.seekTo)mp.seekTo(0,true);else if(v)v.currentTime=0;
 }
 var rep=function(){try{window.__aurexReport&&window.__aurexReport();}catch(e){}};
 setTimeout(rep,300);setTimeout(rep,900);
}catch(e){}})();
"""

    private const val SEEK_JS = """
(function(){try{
 var t=__T__;var mp=document.getElementById('movie_player');
 if(mp&&mp.seekTo){mp.seekTo(t,true);}else{var v=document.querySelector('video');if(v)v.currentTime=t;}
 setTimeout(function(){try{window.__aurexReport&&window.__aurexReport();}catch(e){}},300);
}catch(e){}})();
"""
}
