package com.aurex.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Looper
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.webkit.ClientCertRequest
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** What kind of stream the sniffer found. */
enum class AurexStreamKind { MP4, M4V, WEBM, HLS, DASH, FILE, BLOB }

/**
 * A video the page is playing / loading, ready to be handed to the downloader.
 *
 *  - [url]      the real stream URL, or the raw blob: URL for [AurexStreamKind.BLOB].
 *  - [pageUrl]  canonical page URL (hash + tracking params stripped). For a blob this is the
 *               extraction target, because a blob: link can never be fetched on its own.
 *  - [cookie]   CookieManager cookies for the download target (page URL for a blob).
 */
data class AurexDetectedStream(
    val url: String,
    val kind: AurexStreamKind,
    val pageUrl: String,
    val title: String,
    val cookie: String?,
    val userAgent: String,
    val referer: String,
    val sizeBytes: Long = -1L
) {
    val isBlob: Boolean get() = kind == AurexStreamKind.BLOB
}

/**
 * AurexWebView – custom WebView that stops Chromium from throttling CPU timers,
 * video decoding, and audio scheduling whenever the Activity is backgrounded,
 * minimised, or in Picture-in-Picture mode.
 *
 * Key overrides:
 *  - [onWindowVisibilityChanged] → always passes VISIBLE to super while background
 *    media is active, so Chromium's renderer never enters its "backgrounded" low-power
 *    state.
 *  - [onWindowFocusChanged] → always passes true to super while background media is
 *    active, stopping focus-loss JS-timer throttling.
 *
 * Both flags are gated on [backgroundMediaActive] so the behaviour is only applied
 * when the user has actually started playing something; idle tabs continue to behave
 * normally.
 *
 * Play / pause control
 * --------------------
 * YouTube's own player API (#movie_player.getPlayerState / pauseVideo / playVideo) is always
 * targeted first, the plain <video> element is only the fallback. The real playback state is
 * pushed back to Kotlin immediately through [PipBridge.onState] (from <video> events, from
 * YouTube's onStateChange, right after every toggle and from a 1.5 s re-sync), so the PiP
 * buttons and the browser's own play/pause icons never drift apart. Listen with
 * [onMediaStateChanged] / read [isMediaPlaying].
 *
 * Universal video stream sniffer
 * ------------------------------
 * Streams (.mp4 .m4v .webm .m3u8 .mpd) are detected two ways, both off the render path:
 *  1. Network: every WebViewClient.shouldInterceptRequest call is checked with a pure string
 *     test; only a match is handed to a background thread. [setWebViewClient] is overridden so
 *     the page's own client is wrapped transparently (every callback is forwarded unchanged).
 *     [sniffRequest] is also public in case a client wants to call it directly.
 *  2. DOM: a MutationObserver + media-event listener finds <video>/<source> elements and
 *     resource-timing entries. A blob: video is reported together with the canonical page URL,
 *     title and user agent; cookies are attached on the Kotlin side.
 * Clips under 200 KB and known ad / tracker hosts are dropped. Results arrive on the main
 * thread through [onStreamDetected] and are kept in [detectedStreams].
 */
class AurexWebView(context: Context) : WebView(context) {

    /**
     * Set to true whenever background / PiP media playback should run unthrottled.
     * BrowserState sets this via [setBackgroundMediaActive] from the main thread
     * whenever the video state changes.
     */
    @Volatile
    var backgroundMediaActive: Boolean = false

    // ------------------------------------------------------------------------------------
    // Stream sniffer state (declared before the init blocks that use it)
    // ------------------------------------------------------------------------------------

    /** Called on the main thread whenever a new downloadable stream is found. */
    @Volatile
    var onStreamDetected: ((AurexDetectedStream) -> Unit)? = null

    @Volatile private var cachedPageUrl: String = ""
    @Volatile private var cachedTitle: String = ""
    @Volatile private var cachedUa: String = ""
    @Volatile private var userClient: WebViewClient? = null

    private val seenKeys = ConcurrentHashMap<String, Boolean>()
    private val detected = ArrayList<AurexDetectedStream>()

    // ------------------------------------------------------------------------------------
    // Our JS bridges (window.AurexMedia, AurexPipBridge ...) are invisible to Instagram.
    // addJavascriptInterface puts a named object on window of EVERY page, scripts or not, and any
    // page can list window's properties. Instagram therefore gets a page without them. Android applies
    // add / remove at the next document load, so this is synced right before each navigation.
    // (Declared before the init blocks that register the bridges.)
    // ------------------------------------------------------------------------------------
    private val bridges = LinkedHashMap<String, Any>()
    @Volatile private var bridgesExposed = true

    override fun addJavascriptInterface(obj: Any, name: String) {
        bridges[name] = obj
        if (bridgesExposed) super.addJavascriptInterface(obj, name)
    }

    // ------------------------------------------------------------------------------------
    // User agent. The "desktop site" switch swaps in a Linux-desktop user agent. On a phone that is a lie
    // that does not add up (touch screen, mobile client hints, phone screen size), and Instagram checks exactly
    // that kind of mismatch on a signed-in account. So Instagram always gets the phone's own WebView agent;
    // every other site gets what the user chose. Synced together with the bridges, before each navigation.
    // ------------------------------------------------------------------------------------
    @Volatile private var wantedUa: String? = null
    private var appliedUa: String? = null

    /** The user agent the user asked for on ordinary sites (null = the phone's own). Main thread. */
    fun setPreferredUa(ua: String?) {
        wantedUa = ua
        applyUaFor(runCatching { url }.getOrNull())
    }

    private fun applyUaFor(target: String?) {
        val want = if (isHandsOffHost(target)) null else wantedUa
        if (want == appliedUa) return
        appliedUa = want
        runCatching { settings.userAgentString = want }
    }

    /** Back / forward never call loadUrl: the page they go to is looked up in the history list first. */
    private fun syncForHistory(delta: Int) {
        runCatching {
            val list = copyBackForwardList()
            list.getItemAtIndex(list.currentIndex + delta)?.url?.let { syncBridgesFor(it) }
        }
    }

    override fun goBack() {
        syncForHistory(-1)
        super.goBack()
    }

    override fun goForward() {
        syncForHistory(1)
        super.goForward()
    }

    override fun goBackOrForward(steps: Int) {
        syncForHistory(steps)
        super.goBackOrForward(steps)
    }

    /** Main thread. Call with the address that is ABOUT to load (http / https only; anything else is ignored). */
    fun syncBridgesFor(target: String?) {
        if (target.isNullOrEmpty() || !target.startsWith("http", ignoreCase = true)) return
        applyUaFor(target)
        val expose = !isHandsOffHost(target)
        if (expose == bridgesExposed) return
        bridgesExposed = expose
        for ((name, obj) in bridges) {
            if (expose) super.addJavascriptInterface(obj, name) else super.removeJavascriptInterface(name)
        }
    }

    override fun loadUrl(url: String) {
        syncBridgesFor(url)
        super.loadUrl(url)
    }

    override fun loadUrl(url: String, additionalHttpHeaders: Map<String, String>) {
        syncBridgesFor(url)
        super.loadUrl(url, additionalHttpHeaders)
    }

    /**
     * The single choke point for EVERY script Aurex runs inside a page (theme colour, adult scan, dark mode,
     * PiP / media controls, notification polling, screenshots ...). On Instagram none of them may run: the page
     * is the account's live session. The caller still gets an answer ("null": what a script that returns
     * nothing gives), so nothing waits forever.
     */
    override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
        if (isHandsOffHost(url)) {
            if (resultCallback != null) post { resultCallback.onReceiveValue("null") }
            return
        }
        super.evaluateJavascript(script, resultCallback)
    }

    /**
     * False while "Video download" is off: no request is checked, the DOM sniffer is not installed
     * in new pages and the bridge ignores whatever an already loaded page still sends. Nothing is
     * queued, probed or kept in memory.
     */
    @Volatile
    var sniffEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) clearDetected()
        }

    /** Forgets every stream found so far. */
    fun clearDetected() {
        synchronized(detected) { detected.clear() }
        seenKeys.clear()
    }

    init {
        // No forced layer type: the window is already hardware accelerated, and flipping a WebView
        // between LAYER_TYPE_NONE / HARDWARE re-allocates its render surface (flash, missing icons,
        // dropped video frames). It stays at the default for its whole life.

        // Lock the renderer process at IMPORTANT priority and prevent it from being
        // killed when the activity goes to the background (second arg = false means
        // "do not kill on low memory when unimportant").
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false)
        }

        // Required for background audio / video playback: a user gesture has
        // already happened (they pressed play), so the gesture gate must be open.
        settings.apply {
            mediaPlaybackRequiresUserGesture = false
            javaScriptEnabled              = true
            domStorageEnabled              = true
        }
    }

    /**
     * Chromium monitors window visibility to decide whether to throttle background
     * tasks (timers, decoding workers, audio scheduling). By always reporting VISIBLE
     * to the renderer while [backgroundMediaActive] is true we keep everything running
     * at full speed during PiP and background playback.
     */
    override fun onWindowVisibilityChanged(visibility: Int) {
        // Never on Instagram: pretending the window is still on screen after the app was left (or the screen
        // turned off) keeps the page "visible" for hours - reels playing, feed polling, time-on-screen counting -
        // which is exactly the usage pattern of a bot, not a person. There the page must see the truth (hidden).
        if (backgroundMediaActive && visibility != View.VISIBLE && !isHandsOffHost(url)) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }

    /**
     * When the Activity loses focus (entering PiP, screen-off, etc.) Chromium's
     * focus-loss path can throttle JS timers. Reporting focus = true while media is
     * active prevents that path from firing.
     */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (backgroundMediaActive && !isHandsOffHost(url)) {   // same reasoning as above: Instagram sees real focus
            super.onWindowFocusChanged(true)
        } else {
            super.onWindowFocusChanged(hasWindowFocus)
        }
        syncPipTransition()
    }

    /**
     * Called when the PiP window opens. A forced hardware layer on a WebView is a known cause of
     * "sound plays, picture is black / frozen" once the view is resized into the small window, so the
     * layer is dropped while PiP is open (the window itself is hardware accelerated anyway). The
     * renderer is also told explicitly that it is resumed and visible.
     */
    fun prepareForPip() {
        runCatching { onResume() }
        runCatching { resumeTimers() }
        runCatching { requestLayout(); invalidate() }
    }

    /** The window changes size when PiP opens / closes; used to notice the PiP -> browser return. */
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        syncPipTransition()
    }



    // ------------------------------------------------------------------------------------
    // PiP media control + real-time state sync
    // ------------------------------------------------------------------------------------

    /** Last real playback state reported by the page (true = actually playing). Main-thread updated. */
    @Volatile
    var isMediaPlaying: Boolean = false
        private set

    /** Last real mute state reported by the page. */
    @Volatile
    var isMediaMuted: Boolean = false
        private set

    /**
     * Called on the main thread every time the page's REAL playback state changes
     * (isPlaying, isMuted, hasNext). Hook the browser's own play/pause icons here so they
     * follow the page exactly like the PiP buttons do.
     */
    @Volatile
    var onMediaStateChanged: ((Boolean, Boolean, Boolean) -> Unit)? = null

    /** Called by the page (JS) on every video / YouTube player event; forwards the state at once. */
    inner class PipBridge {
        @JavascriptInterface
        fun onState(isPlaying: Boolean, isMuted: Boolean, hasNext: Boolean) {
            post {
                if (!isAttachedToWindow || !isShown) return@post   // ignore background tabs
                isMediaPlaying = isPlaying
                isMediaMuted = isMuted
                onMediaStateChanged?.invoke(isPlaying, isMuted, hasNext)
                val act = hostActivity() ?: return@post
                if (act.isFinishing || act.isDestroyed) return@post
                PipSupport.updatePipState(act, isPlaying, isMuted, hasNext)
            }
        }
    }

    private var wasInPip = false

    /**
     * Re-installs the listeners after every page load (a new document has no flag set). When the
     * listeners are already installed the tick instead asks the page to re-sync its real playback
     * state, which repairs any desync (YouTube changing state without a <video> event, SPA
     * navigation swapping the player element, ...). The page only reports when something changed.
     */
    private val bootstrapTick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            refreshPageInfo()
            if (isHandsOffHost(url)) {          // Instagram: no scripts of ours in the page
                postDelayed(this, 1500L)
                return
            }
            evaluateJavascript(CHECK_JS) { r ->
                if (r != "true") evaluateJavascript(BOOTSTRAP_JS, null)
                else evaluateJavascript(SYNC_JS, null)
            }
            if (sniffEnabled) {
                evaluateJavascript(CHECK_SNIFF_JS) { r ->
                    if (r != "true" && sniffEnabled) evaluateJavascript(SNIFF_JS, null)
                }
            }
            postDelayed(this, 1500L)
        }
    }

    init {
        addJavascriptInterface(PipBridge(), "AurexPipBridge")
    }

    init {
        addJavascriptInterface(SniffBridge(), "AurexSniffBridge")
        cachedUa = runCatching { settings.userAgentString }.getOrNull().orEmpty()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(bootstrapTick)
        post(bootstrapTick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(bootstrapTick)
        super.onDetachedFromWindow()
    }

    /**
     * Transition-only guard. Called from MainActivity right when onUserLeaveHint() / entering PiP
     * fires: for the next 1200 ms (a page-side timestamp, no timer to throttle) the page may
     * restart a video that the system pauses during the hand-over. At every other time a pause is
     * left alone, so YouTube's own button and the PiP button work on the first tap.
     */
    fun armBackgroundTransition() {
        runJs(
            "(function(){var now=Date.now();" +
                    "if(!(now<(window.__aurexBgTransitionUntil||0))){" +
                    "var was=false;" +
                    "try{var mp=document.getElementById('movie_player');" +
                    "if(mp&&typeof mp.getPlayerState==='function'){was=(mp.getPlayerState()===1);}" +
                    "else{var v=document.querySelector('video');was=!!(v&&!v.paused&&!v.ended);}}catch(e){}" +
                    "window.__aurexBgWasPlaying=was;}" +
                    "window.__aurexBgTransitionUntil=now+1200;})();"
        )
    }

    /**
     * PiP -> normal browser: clears the PiP flags and fires a resize so YouTube refreshes its
     * control overlay and answers the first tap, then forces a fresh state report so every play /
     * pause icon matches the page. Called by MainActivity.onPictureInPictureModeChanged(false)
     * and, as a fallback, when the window is seen leaving PiP.
     */
    private var keepTransitionAt = 0L

    fun onReturnFromPip(keepTransition: Boolean = false) {
        // The size-change fallback (syncPipTransition) calls this a moment after MainActivity did;
        // it must not wipe the hand-over window MainActivity just armed.
        val now = System.currentTimeMillis()
        if (keepTransition) keepTransitionAt = now
        val keep = keepTransition || now - keepTransitionAt < 1_500L
        wasInPip = false
        // No layer-type flip and no synthetic resize here: __lqPip.exit() (called right after by
        // MainActivity) removes the PiP stylesheet and fires ONE resize after the layout settled.
        // An earlier resize ran while the PiP stylesheet was still applied, so YouTube measured a
        // wrong player size (controls / icons vanished) and re-laid-out twice (jitter).
        runJs(
            "window.__aurexInPip=false;window.__aurexAutoResumeGuard=false;" +   // legacy flag, unused here
                    (if (keep) "" else "window.__aurexBgTransitionUntil=0;") +
                    "window.__aurexReport&&window.__aurexReport();"
        )
    }

    /**
     * PiP centre button / browser play-pause: YouTube's #movie_player API is targeted first
     * (getPlayerState() === 1 -> pauseVideo(), otherwise playVideo()); the plain <video> element is
     * only the fallback. An explicit pause is never auto-resumed, and the real resulting state is
     * reported straight back through [PipBridge.onState].
     */
    fun togglePlayPauseFromPip() = runJs(TOGGLE_PLAY_JS)

    /** PiP right button. */
    fun playNextFromPip() = runJs(NEXT_JS)

    private fun runJs(js: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) evaluateJavascript(js, null)
        else post { evaluateJavascript(js, null) }
    }

    private fun hostActivity(): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private fun syncPipTransition() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val now = hostActivity()?.isInPictureInPictureMode ?: return
        if (now == wasInPip) return
        wasInPip = now
        if (now) runJs("window.__aurexInPip=true;window.__aurexReport&&window.__aurexReport();")
        else onReturnFromPip()
    }

    // ------------------------------------------------------------------------------------
    // Universal video stream sniffer – public API
    // ------------------------------------------------------------------------------------

    /** Snapshot of every stream found so far, newest first. */
    fun detectedStreams(): List<AurexDetectedStream> = synchronized(detected) { ArrayList(detected) }

    /**
     * Network hook. Safe to call from any thread (Chromium calls shouldInterceptRequest on an IO
     * thread). The synchronous cost is one string check; everything else runs on a background
     * thread, so page rendering is never slowed down. Never throws.
     */
    fun sniffRequest(request: WebResourceRequest?) {
        if (!sniffEnabled) return
        val req = request ?: return
        try {
            if (!req.method.equals("GET", ignoreCase = true)) return
            val url = req.url?.toString() ?: return
            if (url.length > MAX_URL_LEN) return
            val kind = classify(url) ?: return
            val hdr = req.requestHeaders
            val referer = hdr?.get("Referer") ?: hdr?.get("referer") ?: cachedPageUrl
            enqueue(Candidate(url, kind, cachedPageUrl, referer, cachedTitle, cachedUa, -1L))
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------------------------
    // Universal video stream sniffer – internals
    // ------------------------------------------------------------------------------------

    private class Candidate(
        val url: String,
        val kind: AurexStreamKind,
        val pageUrl: String,
        val referer: String,
        val title: String,
        val ua: String,
        val sizeHint: Long
    )

    private class Probe(val size: Long, val reject: Boolean)

    /** Called by the page's DOM observer (JavaBridge thread). Input is untrusted and validated. */
    inner class SniffBridge {
        @JavascriptInterface
        fun onVideo(
            url: String?,
            kind: String?,
            pageUrl: String?,
            title: String?,
            ua: String?,
            sizeBytes: Double
        ) {
            if (!sniffEnabled) return
            try {
                val u = url?.trim().orEmpty()
                if (u.isEmpty() || u.length > MAX_URL_LEN) return
                val page = pageUrl?.trim().orEmpty()
                if (!isHttp(page) || page.length > MAX_URL_LEN) return
                val k = when (kind) {
                    "mp4" -> AurexStreamKind.MP4
                    "m4v" -> AurexStreamKind.M4V
                    "webm" -> AurexStreamKind.WEBM
                    "m3u8" -> AurexStreamKind.HLS
                    "mpd" -> AurexStreamKind.DASH
                    "file" -> AurexStreamKind.FILE
                    "blob" -> AurexStreamKind.BLOB
                    else -> return
                }
                if (k == AurexStreamKind.BLOB) {
                    if (!u.startsWith("blob:")) return
                } else if (!isHttp(u)) {
                    return
                }
                val size = if (sizeBytes >= 0.0) sizeBytes.toLong() else -1L
                enqueue(
                    Candidate(
                        url = u,
                        kind = k,
                        pageUrl = page,
                        referer = page,
                        title = title?.trim().orEmpty().take(300),
                        ua = ua?.trim().orEmpty().take(500),
                        sizeHint = size
                    )
                )
            } catch (_: Throwable) {
            }
        }
    }

    private fun isHttp(u: String): Boolean =
        u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)

    /** Pure string test: extension of the path (query and fragment ignored). */
    private fun classify(url: String): AurexStreamKind? {
        if (!isHttp(url)) return null
        var end = url.indexOfAny(charArrayOf('?', '#'))
        if (end < 0) end = url.length
        if (end < 2) return null
        val dot = url.lastIndexOf('.', end - 1)
        if (dot < 0 || end - dot > 6) return null
        return when (url.substring(dot + 1, end).lowercase()) {
            "mp4" -> AurexStreamKind.MP4
            "m4v" -> AurexStreamKind.M4V
            "webm" -> AurexStreamKind.WEBM
            "m3u8" -> AurexStreamKind.HLS
            "mpd" -> AurexStreamKind.DASH
            else -> null
        }
    }

    private fun isAdUrl(url: String): Boolean {
        if (url.startsWith("blob:", ignoreCase = true)) return false
        val u = Uri.parse(url)
        val host = u.host?.lowercase() ?: return false
        for (h in AD_HOSTS) {
            if (host == h || host.endsWith(".$h")) return true
        }
        val path = (u.path ?: "").lowercase()
        for (hint in AD_PATH_HINTS) {
            if (path.contains(hint)) return true
        }
        return false
    }

    private fun dedupeKey(c: Candidate): String {
        if (c.kind == AurexStreamKind.BLOB) return "blob|" + c.pageUrl
        val u = Uri.parse(c.url)
        val q = if (c.kind == AurexStreamKind.FILE) "?" + (u.query ?: "") else ""
        return (u.host ?: "") + (u.path ?: "") + q + "|" + c.kind
    }

    private fun enqueue(c: Candidate) {
        if (isAdUrl(c.url)) return
        if (c.kind == AurexStreamKind.BLOB && isAdUrl(c.pageUrl)) return
        if (seenKeys.size > MAX_SEEN) seenKeys.clear()
        if (seenKeys.putIfAbsent(dedupeKey(c), true) != null) return
        try {
            sniffExec.execute { process(c) }
        } catch (_: Throwable) {
        }
    }

    /** Runs on the sniffer thread: size filter, cookies, publish. */
    private fun process(c: Candidate) {
        try {
            // Instagram: no background probing, no cookies, nothing of ours.
            if (isHandsOffHost(c.url) || isHandsOffHost(c.pageUrl)) return
            val isBlob = c.kind == AurexStreamKind.BLOB
            val cookieUrl = if (isBlob) c.pageUrl else c.url
            val cookie = siteCookie(cookieUrl)
            val referer = c.referer.ifBlank { c.pageUrl }
            val ua = c.ua.ifBlank { cachedUa }
            var size = c.sizeHint

            when (c.kind) {
                AurexStreamKind.MP4, AurexStreamKind.M4V, AurexStreamKind.WEBM, AurexStreamKind.FILE -> {
                    val p = probeSize(c.url, ua, cookie, referer)
                    if (p.reject) return
                    size = p.size
                    if (size in 0 until MIN_STREAM_BYTES) return      // tiny ad / tracker clip
                }
                AurexStreamKind.BLOB -> {
                    if (size in 0 until MIN_STREAM_BYTES) return
                }
                else -> Unit                                          // manifests are always small
            }

            val host = runCatching { Uri.parse(c.pageUrl.ifBlank { c.url }).host }.getOrNull().orEmpty()
            val title = c.title.ifBlank { cachedTitle }.ifBlank { host }.ifBlank { "video" }
            val stream = AurexDetectedStream(
                url = c.url,
                kind = c.kind,
                pageUrl = c.pageUrl.ifBlank { c.url },
                title = title,
                cookie = cookie,
                userAgent = ua,
                referer = referer,
                sizeBytes = size
            )
            synchronized(detected) {
                detected.removeAll { it.url == stream.url && it.pageUrl == stream.pageUrl }
                detected.add(0, stream)
                while (detected.size > MAX_KEEP) detected.removeAt(detected.size - 1)
            }
            post { onStreamDetected?.invoke(stream) }
        } catch (_: Throwable) {
        }
    }

    /** One tiny ranged GET (bytes=0-0): total size from Content-Range / Content-Length. Never blocks the UI. */
    private fun probeSize(url: String, ua: String, cookie: String?, referer: String): Probe {
        return try {
            val rb = Request.Builder()
                .url(url)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=0-0")
            if (ua.isNotBlank()) rb.header("User-Agent", ua)
            if (!cookie.isNullOrBlank()) {
                rb.header("Cookie", cookie)
                rb.tag(CookieHome::class.java, CookieHome.of(url))
            }
            if (referer.isNotBlank()) rb.header("Referer", referer)
            sniffHttp.newCall(rb.build()).execute().use { r ->
                if (r.code == 404 || r.code == 410) return Probe(-1L, true)
                val type = r.header("Content-Type").orEmpty().lowercase()
                if (type.startsWith("text/") || type.startsWith("image/") ||
                    type.contains("json") || type.contains("xml")
                ) {
                    return Probe(-1L, true)
                }
                val total = when (r.code) {
                    206 -> r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                    200 -> r.header("Content-Length")?.toLongOrNull() ?: -1L
                    else -> -1L
                }
                Probe(total, false)
            }
        } catch (_: Exception) {
            Probe(-1L, false)       // cannot tell: keep it rather than lose a real video
        }
    }

    /** Main thread: keeps page URL / title / user agent fresh for network-sniffed streams. */
    private fun refreshPageInfo() {
        try {
            val u = url
            if (u != null && isHttp(u)) cachedPageUrl = u
            val t = title
            if (!t.isNullOrBlank()) cachedTitle = t
            val ua = settings.userAgentString
            if (!ua.isNullOrBlank()) cachedUa = ua
        } catch (_: Throwable) {
        }
    }

    private fun noteNavigation(newUrl: String?) {
        if (newUrl == null || !isHttp(newUrl)) return
        val prev = cachedPageUrl.substringBefore('#')
        cachedPageUrl = newUrl
        if (newUrl.substringBefore('#') != prev) seenKeys.clear()
        refreshPageInfo()
    }

    // ------------------------------------------------------------------------------------
    // Transparent WebViewClient wrapper: sniffs requests, forwards EVERYTHING else unchanged
    // ------------------------------------------------------------------------------------

    override fun setWebViewClient(client: WebViewClient) {
        userClient = client
        super.setWebViewClient(if (client is SniffingClient) client else SniffingClient(client))
    }

    override fun getWebViewClient(): WebViewClient =
        userClient ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) super.getWebViewClient() else WebViewClient()

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private inner class SniffingClient(private val d: WebViewClient) : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            syncBridgesFor(url)
            return d.shouldOverrideUrlLoading(view, url)
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            if (request != null && request.isForMainFrame) syncBridgesFor(request.url?.toString())
            return d.shouldOverrideUrlLoading(view, request)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            noteNavigation(url)
            d.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            noteNavigation(url)
            d.onPageFinished(view, url)
        }

        override fun onLoadResource(view: WebView?, url: String?) {
            d.onLoadResource(view, url)
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            d.onPageCommitVisible(view, url)
        }

        override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? =
            d.shouldInterceptRequest(view, url)

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            sniffRequest(request)
            // Back / forward / reload / redirects never pass shouldOverrideUrlLoading: the top-level request
            // itself is the last moment before the new document exists (network thread -> main thread).
            if (request != null && request.isForMainFrame) {
                val target = request.url?.toString()
                if (target != null && target.startsWith("http", ignoreCase = true) &&
                    isHandsOffHost(target) == bridgesExposed
                ) {
                    post { syncBridgesFor(target) }
                }
            }
            return d.shouldInterceptRequest(view, request)
        }

        override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
            d.onReceivedError(view, errorCode, description, failingUrl)
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            d.onReceivedError(view, request, error)
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            d.onReceivedHttpError(view, request, errorResponse)
        }

        override fun onFormResubmission(view: WebView?, dontResend: Message?, resend: Message?) {
            d.onFormResubmission(view, dontResend, resend)
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            noteNavigation(url)
            d.doUpdateVisitedHistory(view, url, isReload)
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            d.onReceivedSslError(view, handler, error)
        }

        override fun onReceivedClientCertRequest(view: WebView?, request: ClientCertRequest?) {
            d.onReceivedClientCertRequest(view, request)
        }

        override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
            d.onReceivedHttpAuthRequest(view, handler, host, realm)
        }

        override fun shouldOverrideKeyEvent(view: WebView?, event: KeyEvent?): Boolean =
            d.shouldOverrideKeyEvent(view, event)

        override fun onUnhandledKeyEvent(view: WebView?, event: KeyEvent?) {
            d.onUnhandledKeyEvent(view, event)
        }

        override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
            d.onScaleChanged(view, oldScale, newScale)
        }

        override fun onReceivedLoginRequest(view: WebView?, realm: String?, account: String?, args: String?) {
            d.onReceivedLoginRequest(view, realm, account, args)
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean =
            d.onRenderProcessGone(view, detail)

        override fun onSafeBrowsingHit(
            view: WebView?,
            request: WebResourceRequest?,
            threatType: Int,
            callback: SafeBrowsingResponse?
        ) {
            d.onSafeBrowsingHit(view, request, threatType, callback)
        }
    }

    private companion object {
        const val CHECK_JS = "window.__aurexPipInstalled===true"
        const val CHECK_SNIFF_JS = "window.__aurexSniffInstalled===true"

        /** Asks the installed page script to re-read the real player state (reports only on change). */
        const val SYNC_JS = "window.__aurexSync&&window.__aurexSync();"

        const val MIN_STREAM_BYTES = 200L * 1024L
        const val MAX_URL_LEN = 4096
        const val MAX_SEEN = 256
        const val MAX_KEEP = 24

        /** Ad / tracker hosts (suffix match). Streams from these are never reported. */
        val AD_HOSTS = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "imasdk.googleapis.com",
            "2mdn.net", "adnxs.com", "adsrvr.org", "moatads.com", "pubmatic.com", "rubiconproject.com",
            "openx.net", "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "scorecardresearch.com",
            "amazon-adsystem.com", "smartadserver.com", "adform.net", "yieldmo.com", "teads.tv",
            "spotxchange.com", "springserve.com", "tremorhub.com", "adsafeprotected.com", "innovid.com",
            "serving-sys.com", "mookie1.com", "vungle.com", "applovin.com", "mopub.com", "adcolony.com",
            "unityads.unity3d.com", "ads.twitter.com", "ads.tiktok.com", "ads.yahoo.com", "advertising.com",
            "casalemedia.com", "lijit.com", "sharethrough.com", "connatix.com", "primis.tech",
            "adsystem.com", "exoclick.com", "juicyads.com", "trafficjunky.net", "popads.net", "propellerads.com"
        )

        /** Path fragments that mark ad delivery. */
        val AD_PATH_HINTS = listOf(
            "/ads/", "/adserver/", "/adserv", "/vast/", "/preroll", "/pre-roll", "/doubleclick/", "/ad_break"
        )

        val sniffExec: ThreadPoolExecutor by lazy {
            ThreadPoolExecutor(
                2, 2, 20L, TimeUnit.SECONDS,
                LinkedBlockingQueue<Runnable>(64),
                ThreadFactory { r ->
                    Thread(r, "aurex-sniff").apply {
                        isDaemon = true
                        priority = Thread.MIN_PRIORITY
                    }
                },
                ThreadPoolExecutor.DiscardOldestPolicy()
            ).apply { allowCoreThreadTimeOut(true) }
        }

        val sniffHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .addNetworkInterceptor(CookieGuard.interceptor)
                .build()
        }

        // Every action is wrapped in a function so repeated calls never re-declare a global const.
        // A user action cancels the transition guard first, so a pause from PiP is never undone.
        // YouTube's #movie_player API is always tried first; the plain <video> is only the fallback.
        // The real resulting state is reported back to Kotlin right away and twice more shortly
        // after (the player applies pauseVideo / playVideo asynchronously).
        val TOGGLE_PLAY_JS = """
            (function() {
              window.__aurexBgTransitionUntil = 0;
              // PiP window open: the PiP helper presses the site's own play/pause button.
              if (window.__lqPipOn && window.__lqPip && window.__lqPip.toggle) { window.__lqPip.toggle(); return; }
              var getV = function() { return document.querySelector('video'); };
              var getMp = function() { return document.getElementById('movie_player'); };
              // The REAL media state decides: YouTube reports 3 (buffering) / -1 (unstarted) while
              // the picture is actually playing, which used to turn a pause tap into a no-op play.
              var isPlaying = function() {
                var v = getV();
                if (v) return !v.paused && !v.ended;
                var mp = getMp();
                if (mp && mp.getPlayerState) { var s = mp.getPlayerState(); return s === 1 || s === 3; }
                return false;
              };
              var rep = function() { try { window.__aurexReport && window.__aurexReport(); } catch (e) {} };
              var was = isPlaying();
              if (was) {
                window.__aurexExplicitUserPause = true;
                try { var m1 = getMp(); if (m1 && m1.pauseVideo) m1.pauseVideo(); } catch (e) {}
                try { var v1 = getV(); if (v1) v1.pause(); } catch (e) {}
              } else {
                window.__aurexExplicitUserPause = false;
                try { var m2 = getMp(); if (m2 && m2.playVideo) m2.playVideo(); } catch (e) {}
                try { var v2 = getV(); if (v2) { var p = v2.play(); if (p && p.catch) p.catch(function(){}); } } catch (e) {}
              }
              rep();
              setTimeout(rep, 120);
              setTimeout(function() {
                // Verify: if nothing changed, press the site's own button as a last resort.
                if (isPlaying() === was) {
                  var b = document.querySelector('.ytp-play-button, button.player-control-play-pause-icon, .ytmCustomControlsPlayPauseButton');
                  if (b) b.click();
                }
                rep();
              }, 420);
              setTimeout(rep, 900);
            })();
        """.trimIndent()

        val NEXT_JS = """
            (function(){
              window.__aurexBgTransitionUntil = 0;
              window.__aurexExplicitUserPause = false;
              const mp = document.getElementById('movie_player');
              if (mp && mp.nextVideo) { mp.nextVideo(); }
              else {
                const b = document.querySelector('.ytp-next-button, button[aria-label*="Next"], a.ytp-next-button');
                if (b) b.click();
              }
              var rep = function(){ try { window.__aurexReport && window.__aurexReport(); } catch (e) {} };
              setTimeout(rep, 300);
              setTimeout(rep, 900);
            })();
        """.trimIndent()

        /**
         * Installed once per document. Nothing is overridden: HTMLMediaElement.prototype.pause / play
         * are only saved (window.__origPause / __origPlay) and never replaced. Capture-phase listeners
         * on document see every <video>. A pause is auto-resumed ONLY while
         * Date.now() < __aurexBgTransitionUntil (set from Kotlin when leaving the app / entering PiP),
         * the video was playing when the window was armed, and no explicit user pause is flagged;
         * any touch / key on the page cancels that window, so the user's own pause is never fought.
         * At all other times the pause happens naturally.
         *
         * Playing state is read from YouTube's #movie_player (getPlayerState) first and from the
         * <video> element otherwise. It is reported to Kotlin through AurexPipBridge on every
         * play / playing / pause / volumechange (and waiting / canplay / ended), on YouTube's own
         * onStateChange, after every toggle (window.__aurexReport) and on the 1.5 s re-sync
         * (window.__aurexSync).
         */
        val BOOTSTRAP_JS = """
            (function(){
              if (!window.__origPause) {
                window.__origPause = HTMLMediaElement.prototype.pause;
                window.__origPlay = HTMLMediaElement.prototype.play;
              }
              if (window.__aurexPipInstalled) return;
              window.__aurexPipInstalled = true;
              window.__aurexBgTransitionUntil = window.__aurexBgTransitionUntil || 0;
              window.__aurexExplicitUserPause = false;
              window.__aurexInPip = !!window.__aurexInPip;
              var last = '';
              function ytPlayer(){
                var mp = document.getElementById('movie_player');
                return (mp && typeof mp.getPlayerState === 'function' &&
                        typeof mp.pauseVideo === 'function' && typeof mp.playVideo === 'function') ? mp : null;
              }
              function activeVideo(v){
                var mp = ytPlayer();
                var mv = (mp && mp.querySelector) ? mp.querySelector('video') : null;
                return mv || v || document.querySelector('video');
              }
              function nextAvailable(){
                var b = document.querySelector('.ytp-next-button:not([aria-disabled="true"]), button[aria-label*="Next"]:not([disabled])');
                if (!b) return false;
                try { var cs = getComputedStyle(b); if (cs.display === 'none' || cs.visibility === 'hidden') return false; } catch (e) {}
                return true;
              }
              function isPlayingNow(v){
                // The <video> element is the truth (like the native player's getPlayWhenReady()).
                // YouTube's getPlayerState() lags behind a pause/play done on the element, which left
                // the PiP button showing "pause" for a video that was already paused.
                if (v && v.tagName === 'VIDEO') return !v.paused && !v.ended;
                var mp = ytPlayer();
                if (mp) {
                  try {
                    var s = mp.getPlayerState();
                    if (s === 1) return true;
                    if (s === 2 || s === 0) return false;
                  } catch (e) {}
                }
                return !!v && !v.paused && !v.ended;
              }
              function isMutedNow(v){
                var mp = document.getElementById('movie_player');
                if (mp && typeof mp.isMuted === 'function') {
                  try { return !!mp.isMuted(); } catch (e) {}
                }
                return !!(v && v.muted);
              }
              function report(v, force){
                try {
                  v = activeVideo(v);
                  if (!v || !window.AurexPipBridge) return;
                  var playing = isPlayingNow(v), muted = isMutedNow(v), next = nextAvailable();
                  var key = playing + '|' + muted + '|' + next;
                  if (!force && key === last) return;
                  last = key;
                  window.AurexPipBridge.onState(playing, muted, next);
                } catch (e) {}
              }
              function hookYt(){
                var mp = ytPlayer();
                if (!mp || mp.__aurexHooked) return;
                try {
                  mp.addEventListener('onStateChange', function(){ report(null, true); });
                  mp.__aurexHooked = true;
                } catch (e) {}
              }
              window.__aurexReport = function(){ hookYt(); report(null, true); };
              window.__aurexSync = function(){ hookYt(); report(null, false); };
              ['pointerdown','touchstart','keydown'].forEach(function(t){
                document.addEventListener(t, function(){
                  window.__aurexBgTransitionUntil = 0;
                }, { capture: true, passive: true });
              });
              ['play','playing','pause','waiting','canplay','volumechange','ended'].forEach(function(t){
                document.addEventListener(t, function(e){
                  var v = e.target;
                  if (!v || v.tagName !== 'VIDEO') return;
                  if (t === 'play' || t === 'playing') window.__aurexExplicitUserPause = false;
                  if (t === 'pause' && Date.now() < window.__aurexBgTransitionUntil &&
                      !window.__aurexExplicitUserPause && window.__aurexBgWasPlaying === true && !v.ended) {
                    try {
                      var mp = ytPlayer();
                      if (mp) { mp.playVideo(); }
                      else {
                        var p = window.__origPlay.call(v);
                        if (p && p.catch) p.catch(function(){});
                      }
                    } catch (err) {}
                    return;
                  }
                  hookYt();
                  report(v);
                }, true);
              });
              hookYt();
              report(null, true);
            })();
        """.trimIndent()

        /**
         * DOM video sniffer (installed once per document, independent of the PiP script).
         * Finds <video>, <source>, and resource-timing entries ending in .mp4 .m4v .webm .m3u8 .mpd.
         * A blob: video is reported with the cleaned page URL, title and user agent so the downloader
         * gets an extraction target instead of a raw blob link. Work is batched through
         * requestIdleCallback (or a 600 ms timeout) so it never competes with rendering. Nothing is
         * prevented or stopped, no media method is replaced.
         */
        val SNIFF_JS = """
            (function(){
              if (window.__aurexSniffInstalled) return;
              window.__aurexSniffInstalled = true;
              var B = window.AurexSniffBridge;
              if (!B) return;
              var seen = {};
              var blobInfo = {};
              var count = 0;
              var MIN_BLOB = 204800;
              try {
                var origCreate = URL.createObjectURL;
                if (origCreate && !origCreate.__aurexWrapped) {
                  var wrapped = function(o){
                    var u = origCreate.apply(this, arguments);
                    try {
                      blobInfo[u] = {
                        ms: (typeof MediaSource !== 'undefined' && o instanceof MediaSource),
                        size: (o && typeof o.size === 'number') ? o.size : -1
                      };
                    } catch (e) {}
                    return u;
                  };
                  wrapped.__aurexWrapped = true;
                  URL.createObjectURL = wrapped;
                }
              } catch (e) {}
              function absUrl(u){
                try { return new URL(u, document.baseURI).href; } catch (e) { return u; }
              }
              function pageUrl(){
                try {
                  var u = new URL(location.href);
                  u.hash = '';
                  ['utm_source','utm_medium','utm_campaign','utm_term','utm_content','fbclid','gclid','igshid','si'].forEach(function(k){
                    u.searchParams.delete(k);
                  });
                  return u.href;
                } catch (e) { return location.href; }
              }
              function kindOf(u){
                var p = u.split('#')[0].split('?')[0].toLowerCase();
                var i = p.lastIndexOf('.');
                if (i < 0) return '';
                var e = p.substring(i + 1);
                return (e === 'mp4' || e === 'm4v' || e === 'webm' || e === 'm3u8' || e === 'mpd') ? e : '';
              }
              function send(u, kind, size){
                var key = (kind === 'blob') ? ('blob|' + pageUrl()) : u;
                if (seen[key] || count > 200) return;
                seen[key] = 1;
                count++;
                try {
                  B.onVideo(u, kind, pageUrl(), document.title || '', navigator.userAgent || '', size);
                } catch (e) {}
              }
              function handle(raw, v){
                if (!raw) return;
                var u = absUrl(String(raw));
                if (u.indexOf('blob:') === 0) {
                  var bi = blobInfo[u];
                  if (bi && !bi.ms && bi.size >= 0 && bi.size < MIN_BLOB) return;
                  send(u, 'blob', (bi && !bi.ms) ? bi.size : -1);
                  return;
                }
                if (u.indexOf('http') !== 0) return;
                var k = kindOf(u);
                if (k) send(u, k, -1);
                else if (v) send(u, 'file', -1);
              }
              function scanVideo(v){
                try {
                  handle(v.currentSrc, v);
                  var a = v.getAttribute('src');
                  if (a) handle(a, v);
                  var s = v.querySelectorAll('source');
                  for (var i = 0; i < s.length; i++) handle(s[i].src || s[i].getAttribute('src'), v);
                } catch (e) {}
              }
              var resSeen = 0;
              function scanResources(){
                try {
                  var es = performance.getEntriesByType('resource');
                  for (; resSeen < es.length; resSeen++) {
                    var n = es[resSeen].name;
                    if (n && kindOf(n)) handle(n, null);
                  }
                } catch (e) {}
              }
              function scanAll(){
                var vs = document.getElementsByTagName('video');
                for (var i = 0; i < vs.length; i++) scanVideo(vs[i]);
                scanResources();
              }
              var pending = false;
              function schedule(){
                if (pending) return;
                pending = true;
                var run = function(){ pending = false; scanAll(); };
                if (window.requestIdleCallback) window.requestIdleCallback(run, { timeout: 1500 });
                else setTimeout(run, 600);
              }
              function hasVideo(n){
                if (!n || n.nodeType !== 1) return false;
                var t = n.tagName;
                if (t === 'VIDEO' || t === 'SOURCE') return true;
                return !!(n.getElementsByTagName && n.getElementsByTagName('video').length);
              }
              try {
                new MutationObserver(function(muts){
                  for (var i = 0; i < muts.length; i++) {
                    var m = muts[i];
                    if (m.type === 'attributes') {
                      if (hasVideo(m.target)) { schedule(); return; }
                      continue;
                    }
                    var a = m.addedNodes;
                    for (var j = 0; a && j < a.length; j++) {
                      if (hasVideo(a[j])) { schedule(); return; }
                    }
                  }
                }).observe(document.documentElement || document, {
                  childList: true, subtree: true, attributes: true, attributeFilter: ['src']
                });
              } catch (e) {}
              ['loadstart','loadedmetadata','play','playing'].forEach(function(t){
                document.addEventListener(t, function(e){
                  var v = e.target;
                  if (v && v.tagName === 'VIDEO') { scanVideo(v); scanResources(); }
                }, true);
              });
              scanAll();
              setTimeout(scanAll, 1500);
            })();
        """.trimIndent()
    }
}