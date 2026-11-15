package com.aurex.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject
import org.json.JSONTokener
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * Keeps the page video (YouTube etc.) and the built-in player on ONE timeline.
 *
 *  page -> player : [capture] reads the page video's position, pauses it (and locks it, so the site
 *                   cannot restart it under the player) and hands the position to PlayerActivity
 *                   as "startMs". The player starts exactly there.
 *  player -> page : when the player closes, [release] seeks the page video to the player's position
 *                   and resumes it only if the player was playing (X in the player's PiP = stays paused).
 *
 * Picture-in-picture: the player's own PiP window is the same PlayerActivity, so it is on the same
 * timeline by construction; the page's PiP (PIP_JS) plays the page video itself.
 */
object PlayerSync {
    /** [frame]: the picture the page video showed at that moment (only when asked for, see [capture]). */
    class Info(val ms: Long, val playing: Boolean, val live: Boolean, val frame: Bitmap? = null)

    // The last picture of the page video, handed to the full player so it opens ON that picture instead of on black.
    @Volatile private var bridgeBmp: Bitmap? = null
    @Volatile private var bridgeAt = 0L

    fun setBridge(b: Bitmap?) { bridgeBmp = b; bridgeAt = System.currentTimeMillis() }
    fun clearBridge() { bridgeBmp = null }

    /** Taken once by the player that opens next; a picture older than 10 s belongs to some earlier switch and is dropped. */
    fun takeBridge(): Bitmap? {
        val b = bridgeBmp
        bridgeBmp = null
        return if (b != null && System.currentTimeMillis() - bridgeAt < 10_000L) b else null
    }

    private var owner: WeakReference<Tab>? = null

    private fun outJs(keep: Boolean, frame: Boolean) =
        "(function(){try{var p=window.__lqPip;if(p&&p.syncOut)return p.syncOut($keep,$frame);}catch(e){}" +
            "return JSON.stringify({ok:false});})()"

    /**
     * [done] runs on the main thread with the page video's state, or null when there is no page video.
     * [keep] = true only READS the position; the page video keeps playing (see [peek] / [take]).
     */
    fun capture(tab: Tab?, keep: Boolean = false, frame: Boolean = false, done: (Info?) -> Unit) {
        val web = tab?.web
        if (web == null) { done(null); return }
        var answered = false
        fun finish(i: Info?) { if (!answered) { answered = true; done(i) } }
        runCatching {
            web.evaluateJavascript(outJs(keep, frame)) { raw ->
                val o = runCatching {
                    JSONObject(JSONTokener(raw).nextValue() as? String ?: "")
                }.getOrNull()
                if (o == null || !o.optBoolean("ok")) { finish(null); return@evaluateJavascript }
                owner = WeakReference(tab)
                val t = o.optDouble("t", -1.0)
                finish(
                    Info(
                        ms = if (t >= 0) (t * 1000).toLong() else -1L,
                        playing = o.optBoolean("playing"),
                        live = o.optBoolean("live"),
                        frame = if (frame) decodeFrame(o.optString("f")) else null
                    )
                )
            }
        }.onFailure { finish(null) }
    }

    private fun decodeFrame(dataUrl: String): Bitmap? {
        val i = dataUrl.indexOf("base64,")
        if (i < 0) return null
        return runCatching {
            val bytes = Base64.decode(dataUrl.substring(i + 7), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    /** Current page-video position, page keeps playing. */
    fun peek(done: (Info?) -> Unit) = capture(owner?.get(), true, false, done)

    /** Pauses + locks the page video and returns its exact position: the moment the player takes over. */
    fun take(done: (Info?) -> Unit) = capture(owner?.get(), false, false, done)

    /** Player closed: put the page video where the player stopped. [seek] = false for live streams. */
    fun release(posMs: Long, playing: Boolean, seek: Boolean) {
        val tab = owner?.get() ?: return
        owner = null
        val t = if (seek && posMs >= 0) String.format(Locale.US, "%.3f", posMs / 1000.0) else "-1"
        val js = "(function(){try{var p=window.__lqPip;if(p&&p.syncIn)p.syncIn($t,$playing);}catch(e){}})()"
        runCatching { tab.web?.evaluateJavascript(js, null) }
    }
}
