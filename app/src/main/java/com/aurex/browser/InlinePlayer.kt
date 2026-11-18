package com.aurex.browser

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener

/** What the page's own player offers (YouTube API / JW Player / hls.js), read through PIP_JS qualities(). */
private class PageQualities(
    /** Page-side id per row ("auto", "hd1080", "3" ...), same order as [choices]. */
    val ids: List<String>,
    /** Rows for the quality panel of PlayerScreen (only label / height are used). */
    val choices: List<YtDlpEngine.StreamChoice>,
    val index: Int,
    /** 1440p / 4K / 8K heights the video has but the mobile player does not list (read from the page at once). */
    val extra: List<Int> = emptyList()
)

private fun parsePageQualities(raw: String?): PageQualities? {
    val txt = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
    val o = runCatching { JSONObject(txt) }.getOrNull() ?: return null
    val arr = o.optJSONArray("l") ?: return null
    if (arr.length() < 2) return null                      // one level = nothing to choose
    val auto = o.optInt("a", 1) == 1
    val current = o.optString("c")
    val autoNow = MediaTools.qualityLabel(o.optInt("w"), o.optInt("h"))
    val ids = ArrayList<String>()
    val rows = ArrayList<YtDlpEngine.StreamChoice>()
    ids.add("auto")
    rows.add(YtDlpEngine.StreamChoice(if (auto && autoNow.isNotBlank()) "Auto $autoNow" else "Auto", 0, "", null))
    var index = 0
    for (i in 0 until arr.length()) {
        val q = arr.optJSONObject(i) ?: continue
        val id = q.optString("i")
        val h = q.optInt("h")
        if (id.isBlank() || h <= 0) continue
        if (!DeviceCaps.supportsHeight(h)) continue          // a quality this phone cannot play is not listed
        if (!auto && id == current) index = rows.size
        ids.add(id)
        rows.add(YtDlpEngine.StreamChoice(MediaTools.labelForHeight(h), h, "", null))
    }
    if (rows.size < 3) return null
    val xs = ArrayList<Int>()
    o.optJSONArray("x")?.let { a -> for (i in 0 until a.length()) a.optInt(i).takeIf { it >= 1440 && DeviceCaps.supportsHeight(it) }?.let { xs.add(it) } }
    return PageQualities(ids, rows, index, xs)
}

/**
 * THE built-in player (PlayerScreen) shown over the page's own, already playing <video>.
 * Same look, gestures and tools as the full player; nothing is loaded, so nothing can buffer or pause.
 * The page video is made full-window by PIP_JS enter() (BrowserState.openPlayer / onShowCustomView).
 */
@Composable
fun InlinePlayerHost(s: BrowserState) {
    val ctx = LocalContext.current
    val act = ctx as? Activity
    val prefs = remember { ctx.getSharedPreferences("liquid", Context.MODE_PRIVATE) }
    val player = remember { WebVideoPlayer(s) }
    val scope = rememberCoroutineScope()
    val tab = s.tabs.getOrNull(s.current)
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Quality of online streams. 1) the page's own player lists its qualities (YouTube & co): picking one switches
    // the page video itself, nothing reloads. 2) no list from the page: the HLS ladder / engine heights (other video
    // sites); picking one opens the full player at that quality, continuing from the same position.
    var pageQ by remember { mutableStateOf<PageQualities?>(null) }
    // row picked from the fallback list: shown at once; cleared when the video's real height changes
    var pickedIdx by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(tab?.videoHeight) { pickedIdx = null }
    // true from the tap on a quality until the page video really plays the new one: buffer screen at once (dimmed last
    // picture + spinner), the controls stay put, nothing flickers. Never longer than 12 s.
    var switching by remember { mutableStateOf(false) }
    var switchAt by remember { mutableStateOf(0L) }
    var switchFromMs by remember { mutableStateOf(0L) }
    fun startSwitching() {
        switchAt = android.os.SystemClock.uptimeMillis()
        switchFromMs = player.currentPosition
        switching = true
    }
    LaunchedEffect(switching) {
        if (!switching) return@LaunchedEffect
        while (true) {
            delay(100)
            val waited = android.os.SystemClock.uptimeMillis() - switchAt
            val ready = player.playbackState == androidx.media3.common.Player.STATE_READY
            val moved = kotlin.math.abs(player.currentPosition - switchFromMs)
            val paused = !player.playWhenReady
            // done: the video really runs on past the tap point (a paused one has just settled)
            // (the page reports s:1 while it holds the video for the switch: the buffer screen stays up until it plays on)
            if ((waited > 450 && ready && !player.pageSwitching && (moved > 200 || (paused && waited > 700))) || waited > 14_000) break
        }
        switching = false
    }
    LaunchedEffect(Unit) {
        var pending = 0L
        while (true) {
            val web = s.tabs.getOrNull(s.current)?.web
            val now = android.os.SystemClock.uptimeMillis()
            if (web != null && (pending == 0L || now - pending > 4000L)) runCatching {
                pending = now                                   // one request at a time (busy pages answer slowly)
                web.evaluateJavascript("(function(){try{return window.__lqPip.qualities();}catch(e){return '';}})()") { raw ->
                    pending = 0L
                    pageQ = parsePageQualities(raw)
                }
            }
            delay(1500)
        }
    }
    // Fallback sources, used only when the page's own player gives no list (e.g. Pornhub, xHamster, other HLS sites):
    //  a) the HLS ladder of the stream the page is playing (one small request, no engine),
    //  b) every height the engine finds for the page (the same list the download quality sheet uses).
    var hlsQ by remember { mutableStateOf<List<DownloadManager.HlsVariant>>(emptyList()) }
    var engineH by remember { mutableStateOf<List<Int>>(emptyList()) }
    // muxed (video + audio in one URL) renditions per height: these can be switched inside the page video
    var engineUrls by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    // Page address of the video the three lists above belong to. YouTube keeps this player open while it
    // moves on to the next video, so a different video must reset them (a 4K row of the OLD video would
    // otherwise be offered for a video that has no 4K).
    var listFor by remember { mutableStateOf("") }
    LaunchedEffect(tab?.url, tab?.title) {
        val t0 = s.tabs.getOrNull(s.current)
        val isYtPage = YtDlpEngine.isYoutubeUrl(t0?.url)
        // YouTube: start at once. The engine lookup is the slow part (seconds), so every wait before it only
        // delays the 1440p / 4K / 8K rows. Other sites wait a moment so the page's own list (polled above) can answer first.
        if (!isYtPage) delay(700L)
        // A page that lists its own qualities needs no engine list - except YouTube, whose mobile player
        // often stops at 1080p while the video also exists in 1440p / 4K / 8K: those rows come from the engine.
        if (pageQ != null && !isYtPage) return@LaunchedEffect
        val t = s.tabs.getOrNull(s.current) ?: return@LaunchedEffect
        val ua = t.web?.settings?.userAgentString
        val media = s.playableMedia(t)
        VideoPage.resolve(t) { page, _ ->
            if (page == listFor) return@resolve          // same video as the lists already shown
            listFor = page
            hlsQ = emptyList(); engineH = emptyList(); engineUrls = emptyMap()
            if (!isYtPage && media != null && mediaKind(media) == "HLS") {
                Thread {
                    val v = runCatching {
                        s.downloads.listHlsVariants(media, ua, page, null)
                    }.getOrDefault(emptyList())
                    act?.runOnUiThread { if (v.size > 1 && listFor == page) hlsQ = v }
                }.start()
            }
            if (!YtDlpEngine.isQualitySite(page)) return@resolve
            fun take(m: YtDlpEngine.ResolvedMedia) {
                val hs = (m.heights + m.choices.map { it.height }).filter { it > 0 }.distinct().sortedDescending()
                if (hs.size > 1) {
                    engineH = hs
                    engineUrls = m.choices
                        .filter { it.height > 0 && it.video.isNotBlank() && it.audio == null && !it.video.startsWith("blob:") }
                        .groupBy { it.height }.mapValues { (_, l) -> l.first().video }
                }
            }
            val cached = YtDlpEngine.peekCached(page)
            if (cached != null) { take(cached); return@resolve }
            if (t.incognito) return@resolve
            val cookies = siteCookie(page)
            Thread {
                val r = runCatching { YtDlpEngine.resolveCached(ctx.applicationContext, page, cookies) }.getOrNull()
                act?.runOnUiThread {
                    if (listFor != page) return@runOnUiThread       // the video changed while looking
                    if (r != null) take(r) else listFor = ""         // failed: the next change may try again
                }
            }.start()
        }
    }
    val fallbackRows: List<YtDlpEngine.StreamChoice> =
        if (hlsQ.size > 1) hlsQ.filter { !MediaTools.tooSharp(it.width, it.height) && DeviceCaps.supportsHeight(it.height) }.map { YtDlpEngine.StreamChoice(MediaTools.qualityLabel(it.width, it.height), it.height, it.url, null, hls = true) }
        else engineH.map { h -> YtDlpEngine.StreamChoice(MediaTools.labelForHeight(h), h, engineUrls[h].orEmpty(), null) }
    val usingHls = pageQ == null && hlsQ.size > 1
    // 1440p / 4K / 8K that the video has but the page's own player does not offer (YouTube in a WebView):
    // shown as extra rows right under "Auto"; picking one plays that quality in the full player.
    val pageNow = pageQ
    val pageMaxH = pageNow?.choices?.maxOfOrNull { it.height } ?: 0
    val extraHigh: List<YtDlpEngine.StreamChoice> =
        if (pageNow != null && pageMaxH > 0 && YtDlpEngine.isYoutubeUrl(tab?.url))
            (pageNow.extra + engineH).filter { it > pageMaxH && it >= 1440 }.distinct().sortedDescending()
                .map { YtDlpEngine.StreamChoice(MediaTools.labelForHeight(it), it, "", null) }
        else emptyList()
    val qRows = if (pageNow != null) {
        if (extraHigh.isEmpty()) pageNow.choices
        else listOf(pageNow.choices[0]) + extraHigh + pageNow.choices.drop(1)
    } else fallbackRows
    val qIndex = if (pageNow != null) {
        if (pageNow.index > 0) pageNow.index + extraHigh.size else pageNow.index
    } else {
        pickedIdx
            ?: fallbackRows.indexOfFirst { it.height > 0 && it.height == (tab?.videoHeight ?: 0) }
    }

    // Live state of the page video, 4x a second. The page / video gone -> close by itself.
    LaunchedEffect(Unit) {
        while (true) {
            player.refresh()
            if (player.missCount > 10) { s.exitInlinePlayer(); break }
            delay(250)
        }
    }
    LaunchedEffect(Unit) {
        if (s.inlineWantLandscape) {
            s.inlineWantLandscape = false
            act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }
    // Landscape = true full screen (no system bars), like the full player.
    DisposableEffect(landscape) {
        val w = act?.window
        val c = w?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (landscape) {
            c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c?.hide(WindowInsetsCompat.Type.systemBars())
        } else c?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { c?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    DisposableEffect(Unit) {
        onDispose {
            act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            // brightness changed by the swipe gesture goes back to the system value
            act?.window?.let { w -> val a = w.attributes; a.screenBrightness = -1f; w.attributes = a }
        }
    }

    AurexTheme(
        accent = Color(s.effectiveAccent),
        glass = prefs.getFloat("glass", 0.86f),
        haptics = prefs.getBoolean("haptics", true),
        glow = prefs.getBoolean("glow", true),
        mode = ThemeModes.DARK
    ) {
        PlayerScreen(
            player = player,
            title = VideoPage.clean(tab?.title).ifBlank { "Video" },
            pageUrl = tab?.url,
            playUrl = tab?.url.orEmpty(),
            pipMode = false,
            resolving = false,
            resolveError = null,
            canResolve = false,
            isLocal = qRows.size < 2,  // quality chip only when there is a real choice (online streams)
            choices = qRows,
            choiceIndex = qIndex,
            switching = switching,
            onChoice = { i ->
                val pq = pageQ
                if (pq != null) {
                    val shift = extraHigh.size
                    if (shift > 0 && i in 1..shift) {
                        // a quality only the engine knows (4K / 8K ...): the full player plays it from the same position
                        val t = s.tabs.getOrNull(s.current)
                        if (t != null) s.openAdvancedPlayer(ctx, t, extraHigh[i - 1].height)
                    } else {
                        val pi = if (i > shift) i - shift else i
                        pq.ids.getOrNull(pi)?.let { id ->
                            startSwitching()                                    // buffer screen in the same frame as the tap
                            s.pipCommand("setQuality('" + id.filter { it.isLetterOrDigit() } + "')")
                            player.beginSwitch()                                // progress bar stays put while the picture changes
                            pageQ = PageQualities(pq.ids, pq.choices, pi, pq.extra) // shown at once, the next poll confirms
                        }
                    }
                } else {
                    val c = fallbackRows.getOrNull(i)
                    val t = s.tabs.getOrNull(s.current)
                    if (c != null && t != null) {
                        // Last resort only (this video cannot be switched inside the page): the separate full player.
                        // HLS ladder: play that variant; engine list: full player resolves the page at that height
                        val openFull = {
                            if (usingHls) s.openVariantPlayer(ctx, t, c.video) else s.openAdvancedPlayer(ctx, t, c.height)
                        }
                        if (c.video.isBlank() || c.video.startsWith("blob:")) {
                            openFull()
                        } else {
                            // Normal path: the page's own video switches to that rendition IN PLACE and continues at
                            // the same position - the player stays exactly where it is, only the quality changes.
                            pickedIdx = i
                            startSwitching()
                            player.beginSwitch()
                            s.switchPageSource(c.video) { r ->
                                if (r == "start") {
                                    scope.launch {
                                        var state = ""
                                        var n = 0
                                        while (n < 34 && state != "ok" && state != "fail") {
                                            delay(400)
                                            s.pageSourceState { state = it }
                                            n++
                                        }
                                        if (state != "ok") {
                                            switching = false
                                            pickedIdx = null
                                            s.notify("Couldn't change quality here, kept the current one")
                                        }
                                    }
                                } else {
                                    switching = false
                                    pickedIdx = null
                                    openFull()
                                }
                            }
                        }
                    }
                }
            },
            onRetry = {},
            onClose = { s.exitInlinePlayer() },
            onPip = { if (!s.enterBrowserPip()) s.notify("Picture-in-picture is not available here") },
            onDownload = { s.downloadPlayingPage() },
            overlay = true,
            onFit = { index, zoom ->
                val mode = if (index == 1) "cover" else "contain"
                s.pipCommand("fit('" + mode + "'," + String.format(java.util.Locale.US, "%.2f", zoom) + ")")
            }
        )
    }
}
