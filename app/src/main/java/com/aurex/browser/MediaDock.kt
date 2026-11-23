package com.aurex.browser

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.geometry.CornerRadius
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Media dock (iOS 27 Liquid Glass): ONE panel for what is playing and what is downloading.
 *
 *   Now Playing  - hero glass card for the page video: artwork, live scrubber, transport, speed, player
 *   Save page    - the existing universal downloader card
 *   Downloads    - aggregate ring + live rows (pause / resume / retry), "See all" opens the full list
 *   On this page - detected streams with Play / Download
 *
 * Only tokens from Theme.kt are used (palette, CardShape/TileShape, liquidGlass, groupedSurface,
 * GlowPress), so it follows light / dark, the glass slider and the haptics setting automatically.
 */

private val DockNum = TextStyle(fontFeatureSettings = "tnum")
private val VideoPurple = Color(0xFFBF5AF2)
private val SpeedSteps = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)

/** What the page video reports (window.__lqPip.info()). */
private class PvInfo(
    val t: Float, val d: Float, val playing: Boolean,
    val rate: Float, val ad: Boolean, val buffering: Boolean
)

private fun parsePv(raw: String?): PvInfo? {
    if (raw.isNullOrBlank() || raw == "null") return null
    val txt = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
    if (txt.isBlank()) return null
    val o = runCatching { JSONObject(txt) }.getOrNull() ?: return null
    return PvInfo(
        t = o.optDouble("t", 0.0).toFloat(),
        d = o.optDouble("d", 0.0).toFloat(),
        playing = o.optInt("p", 0) == 1,
        rate = o.optDouble("r", 1.0).toFloat(),
        ad = o.optInt("ad", 0) == 1,
        buffering = o.optInt("b", 0) == 1
    )
}

private fun fmtTime(sec: Float): String {
    val s = sec.toInt().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}

private fun fmtRate(r: Float): String =
    (if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString().trimEnd('0')) + "×"

private fun Tab.pageJs(call: String) {
    runCatching { web?.evaluateJavascript("(function(){try{var p=window.__lqPip;if(p){$call}}catch(e){}})()", null) }
}

// ── Panel ────────────────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MediaDockPanel(s: BrowserState, onClose: () -> Unit, onPanel: (Panel) -> Unit) {
    val tab = s.tabs.getOrNull(s.current)
    val mediaCount = tab?.media?.size ?: 0
    val found = remember(tab, mediaCount) { tab?.media.orEmpty().reversed() }
    val all = s.downloads.items
    // Only re-evaluated when a download CHANGES STATE, not on every progress tick.
    val live by remember(all) { derivedStateOf { all.filter { it.status != DlStatus.DONE } } }
    val finished by remember(all) { derivedStateOf { all.filter { it.status == DlStatus.DONE }.takeLast(2).reversed() } }
    val playing = tab?.videoActive == true

    val parentBackdrop = LocalGlassBackdrop.current
    val mediaBackdrop = remember(parentBackdrop) { GlassBackdrop(parent = parentBackdrop) }
    val liquidScroll = rememberGlassScrollState()
    val listState = rememberLazyListState()
    val scrolledTop by remember { derivedStateOf { listState.canScrollBackward } }
    val density = LocalDensity.current
    var headerPx by remember { mutableIntStateOf(0) }
    val headerDp = if (headerPx > 0) with(density) { headerPx.toDp() } else 132.dp

    Box(Modifier.fillMaxSize()) {
        GlassBackdropHost(mediaBackdrop, Modifier.fillMaxSize()) {
            when {
                !s.videoDownload -> {
                    Box(Modifier.fillMaxSize().padding(top = headerDp)) {
                        EmptyState(
                            AppleIcons.Download, "Video download is off",
                            "Pages are not scanned for videos and the downloader uses no memory. Turn it on to detect videos and save them.",
                            action = { GlassPillButton("Turn on", { s.setVideoDownload(true) }, icon = AppleIcons.Download, accent = true) }
                        )
                    }
                }
                !playing && found.isEmpty() && all.isEmpty() -> {
                    Column(Modifier.fillMaxSize().padding(top = headerDp)) {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { UniversalDownloadCard(s, tab) }
                        EmptyState(
                            AppleIcons.MusicNote, "Nothing playing or downloading",
                            "Play a video or open a page with media. Audio, HLS, DASH and video links are detected automatically."
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().liquidOverscrollJelly().liquidScrollTrack(liquidScroll),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = headerDp + 4.dp, bottom = 44.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (playing) item("now") {
                            Box(Modifier.liquidScrollCard(liquidScroll)) { NowPlayingCard(s, tab) }
                        }
                        item("save") {
                            Box(Modifier.liquidScrollCard(liquidScroll)) { UniversalDownloadCard(s, tab) }
                        }

                        if (all.isNotEmpty()) {
                            item("dl-head") {
                                DockSection("Downloads", if (all.size > 3) "See all" else null) { onPanel(Panel.Downloads) }
                            }
                            item("dl-sum") {
                                Box(Modifier.liquidScrollCard(liquidScroll)) { DownloadSummary(s, live, finished.size) }
                            }
                            items(live.take(4), key = { "l${it.id}" }) {
                                DockDownloadRow(s, it, Modifier.animateItem().liquidScrollCard(liquidScroll, 0.025f, 1.8f))
                            }
                            items(finished, key = { "f${it.id}" }) {
                                DockDownloadRow(s, it, Modifier.animateItem().liquidScrollCard(liquidScroll, 0.025f, 1.8f))
                            }
                        }

                        if (found.isNotEmpty() && s.mediaPlayerEnabled && tab != null) {
                            item("found-head") { DockSection("On this page", null) {} }
                            items(found.take(12), key = { "m$it" }) { url ->
                                DetectedRow(s, tab, url, Modifier.animateItem().liquidScrollCard(liquidScroll, 0.025f, 1.8f))
                            }
                        }
                    }
                }
            }
        }

        androidx.compose.runtime.CompositionLocalProvider(LocalGlassBackdrop provides mediaBackdrop) {
            ScrollEdgeBlur(
                isTop = true,
                active = scrolledTop,
                height = headerDp + 16.dp,
                strength = 0.94f,
                modifier = Modifier.align(Alignment.TopCenter)
            )
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .onSizeChanged { headerPx = it.height }
            ) {
                ScreenHeader(
                    "Media",
                    when {
                        !s.videoDownload -> "Video download is off"
                        playing -> "Now playing · ${live.count { it.status == DlStatus.RUNNING }} downloading"
                        live.isNotEmpty() -> "${live.count { it.status == DlStatus.RUNNING }} downloading"
                        else -> "${found.size} detected on this page"
                    },
                    onClose
                ) {
                    if (s.mediaPlayerEnabled && s.videoDownload) {
                        GlassIconButton(
                            AppleIcons.Refresh, "Rescan page",
                            onClick = { tab?.web?.reload() }, size = 44.dp, iconSize = 19.dp, filled = false
                        )
                    }
                }
            }
            ScrollEdgeBlur(
                isTop = false,
                active = true,
                height = 52.dp,
                strength = 0.65f,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun DockSection(title: String, action: String?, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 2.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 0.35.sp, modifier = Modifier.weight(1f)
        )
        if (action != null) {
            Row(
                Modifier.height(44.dp).clickableHaptic { onAction() }.padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(action, color = LocalGlassStyle.current.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Icon(AppleIcons.ChevronRight, null, tint = LocalGlassStyle.current.accent, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ── Now Playing ──────────────────────────────────────────────────────────────

@Composable
private fun NowPlayingCard(s: BrowserState, tab: Tab) {
    val style = LocalGlassStyle.current
    val accent = style.accent
    var info by remember(tab.id) { mutableStateOf<PvInfo?>(null) }
    var scrubbing by remember { mutableStateOf(false) }

    // Live position: the page video is polled twice a second while this panel is open.
    LaunchedEffect(tab.id) {
        while (true) {
            if (!scrubbing) {
                runCatching {
                    tab.web?.evaluateJavascript(
                        "(function(){var p=window.__lqPip;return p&&p.info?p.info():''})()"
                    ) { raw -> parsePv(raw)?.let { info = it } }
                }
            }
            delay(if (info?.playing == false) 1200 else 500)
        }
    }

    val i = info
    val isPlaying = i?.playing ?: tab.videoPlaying
    val dur = i?.d ?: 0f
    val pos = i?.t ?: 0f
    val live = dur <= 0f
    val ad = i?.ad == true
    val host = hostOf(tab.url)
    val view = LocalView.current
    val glow = animateFloatAsState(if (isPlaying) 0.26f else 0.12f, tween(500), label = "npGlow")
    // Apple Music: the artwork settles back a little when paused and springs up when it plays.
    val art = animateFloatAsState(if (isPlaying) 1f else 0.9f, spring(dampingRatio = 0.6f, stiffness = 320f), label = "npArt")

    Column(
        Modifier
            .fillMaxWidth()
            .liquidGlass(CardShape, 1.25f, 0.04f, reactive = true)
            .drawBehind {
                // soft accent bloom behind the artwork: the card "lights up" while it plays
                drawCircle(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = glow.value.coerceIn(0f, 1f)), Color.Transparent),
                        center = Offset(size.width * 0.14f, size.height * 0.10f),
                        radius = size.width * 0.75f
                    ),
                    radius = size.width * 0.75f,
                    center = Offset(size.width * 0.14f, size.height * 0.10f)
                )
            }
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(72.dp).graphicsLayer { scaleX = art.value; scaleY = art.value }.clip(RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                SiteBadge(host, size = 72.dp, bitmap = tab.favicon)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "NOW PLAYING", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp, maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    tab.title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.4).sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(host, color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            if (ad) {
                Text(
                    "AD", color = WarnText, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(Warn.copy(alpha = 0.16f), Capsule).padding(horizontal = 8.dp, vertical = 3.dp)
                )
            } else EqBars(isPlaying && i?.buffering != true, accent)
        }

        Spacer(Modifier.height(14.dp))
        Scrubber(
            pos = pos, dur = dur, enabled = !live && !ad, accent = accent,
            onScrub = { scrubbing = it },
            onSeek = { t ->
                tab.pageJs("p.seekAbs(${"%.2f".format(java.util.Locale.US, t)})")
                info = i?.let { PvInfo(t, it.d, it.playing, it.rate, it.ad, it.buffering) }
            }
        )
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text(fmtTime(pos), color = TextTertiary, fontSize = 12.sp, style = DockNum, modifier = Modifier.weight(1f))
            Text(
                if (live) "LIVE" else "-" + fmtTime((dur - pos).coerceAtLeast(0f)),
                color = if (live) BadText else TextTertiary, fontSize = 12.sp,
                fontWeight = if (live) FontWeight.Bold else FontWeight.Normal, style = DockNum
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SkipButton(forward = false, enabled = !live && !ad) {
                tab.pageJs("p.seekAbs(${(pos - 10f).coerceAtLeast(0f)})")
            }
            // Primary control: the one filled, accent-tinted glass disc on the card.
            GlowPress(
                onClick = {
                    val next = !isPlaying
                    performToggle(view, next)
                    tab.pageJs("p.setPlay($next)")
                    info = i?.let { PvInfo(it.t, it.d, next, it.rate, it.ad, it.buffering) }
                },
                modifier = Modifier.size(68.dp).tintedGlass(CircleShape, accent, swell = 0.08f),
                shape = CircleShape, haptic = false, glassBody = true
            ) {
                Crossfade(isPlaying, animationSpec = tween(120), label = "npPP") { on ->
                    Icon(
                        if (on) AppleIcons.Pause else AppleIcons.PlayArrow,
                        if (on) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(32.dp)
                    )
                }
            }
            SkipButton(forward = true, enabled = !live && !ad) {
                val end = if (dur > 0f) dur else pos + 10f
                tab.pageJs("p.seekAbs(${(pos + 10f).coerceAtMost(end)})")
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val ctx = LocalContext.current
            val rate = i?.rate ?: 1f
            GlassPillButton(fmtRate(rate), {
                val next = SpeedSteps[(SpeedSteps.indexOfFirst { it == rate } + 1).mod(SpeedSteps.size)]
                tab.pageJs("p.rate($next)")
                info = i?.let { PvInfo(it.t, it.d, it.playing, next, it.ad, it.buffering) }
            }, icon = AppleIcons.Speed)
            val url = s.playableMedia(tab)
            if (url != null) {
                GlassPillButton("Player", {
                    s.openPlayer(ctx, tab, url, s.effectiveAccent, s.playableAudio(tab, url))
                }, icon = AppleIcons.PictureInPictureAlt)
                Spacer(Modifier.weight(1f))
                GlassIconButton(AppleIcons.Download, "Download this video", { s.download(url) }, size = 44.dp, iconSize = 19.dp)
            }
        }
    }
}

@Composable
private fun SkipButton(forward: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    GlowPress(onClick, Modifier.size(52.dp), CircleShape, enabled = enabled, haptic = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!forward) Icon(AppleIcons.ArrowBack, "Back 10 seconds", tint = TextPrimary, modifier = Modifier.size(17.dp))
            Text("10", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, style = DockNum)
            if (forward) Icon(AppleIcons.ArrowForward, "Forward 10 seconds", tint = TextPrimary, modifier = Modifier.size(17.dp))
        }
    }
}

/** Apple-Music-style level bars. One infinite transition, read only while drawing; still when idle / low-end. */
@Composable
private fun EqBars(active: Boolean, color: Color) {
    val live = active && !PerfTier.low
    val amp = animateFloatAsState(if (live) 1f else 0f, tween(300), label = "eqAmp")
    val phase = if (live) rememberInfiniteTransition(label = "eq").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "eqPhase"
    ) else null
    Canvas(Modifier.size(width = 18.dp, height = 22.dp)) {
        val bw = 4.dp.toPx()
        val gap = 3.dp.toPx()
        val p = phase?.value ?: 0f
        val a = amp.value
        val k = intArrayOf(1, 2, 1)
        val off = floatArrayOf(0f, 0.2f, 0.55f)
        for (n in 0..2) {
            val wave = sin((p * k[n] + off[n]) * 2.0 * PI).toFloat() * 0.5f + 0.5f
            val h = size.height * (0.28f + 0.72f * wave * a)
            drawRoundRect(color, Offset(n * (bw + gap), size.height - h), Size(bw, h), CornerRadius(bw / 2f))
        }
    }
}

/**
 * Apple Music scrubber: thin track that swells under the finger, glides between polls, ticks every 5 %
 * while dragging (haptic detents). Everything animated is read in the draw phase only.
 */
@Composable
private fun Scrubber(
    pos: Float, dur: Float, enabled: Boolean, accent: Color,
    onScrub: (Boolean) -> Unit, onSeek: (Float) -> Unit
) {
    val view = LocalView.current
    var drag by remember { mutableStateOf<Float?>(null) }
    val grow = animateFloatAsState(if (drag != null) 12f else 6f, spring(dampingRatio = 0.7f, stiffness = 600f), label = "scrubH")
    val follow = animateFloatAsState(
        if (dur > 0f) (pos / dur).coerceIn(0f, 1f) else 0f, tween(500, easing = LinearEasing), label = "scrubPos"
    )
    val track = TextPrimary.copy(alpha = 0.12f)
    val fillC = if (enabled) accent else TextPrimary.copy(alpha = 0.30f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(enabled, dur) {
                if (!enabled) return@pointerInput
                detectTapGestures { o ->
                    Haptics.fire(view, Hx.TICK)
                    onSeek((o.x / size.width).coerceIn(0f, 1f) * dur)
                }
            }
            .pointerInput(enabled, dur) {
                if (!enabled) return@pointerInput
                var last = -1
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        val f = (o.x / size.width).coerceIn(0f, 1f)
                        drag = f; last = (f * 20).toInt(); onScrub(true); performDragStart(view)
                    },
                    onDragEnd = { drag?.let { onSeek(it * dur) }; drag = null; onScrub(false); Haptics.fire(view, Hx.DRAG_END) },
                    onDragCancel = { drag = null; onScrub(false) },
                    onHorizontalDrag = { c, _ ->
                        c.consume()
                        val f = (c.position.x / size.width).coerceIn(0f, 1f)
                        drag = f
                        val step = (f * 20).toInt()
                        if (step != last) { last = step; performPress(view) }
                    }
                )
            }
    ) {
        val hh = grow.value.dp.toPx()
        val top = (size.height - hh) / 2f
        val cr = CornerRadius(hh / 2f)
        drawRoundRect(track, Offset(0f, top), Size(size.width, hh), cr)
        val w = size.width * (drag ?: follow.value)
        if (w > 0.5f) drawRoundRect(fillC, Offset(0f, top), Size(maxOf(w, hh), hh), cr)
    }
}

// ── Downloads ────────────────────────────────────────────────────────────────

@Composable
private fun DownloadSummary(s: BrowserState, live: List<DlItem>, doneCount: Int) {
    val accent = LocalGlassStyle.current.accent
    val running = live.filter { it.status == DlStatus.RUNNING }
    val paused = live.filter { it.status == DlStatus.PAUSED }
    val speed = running.sumOf { it.speed }
    val eta = running.maxOfOrNull { it.eta } ?: -1L
    val raw = if (live.isEmpty()) 1f else live.map { it.progress }.average().toFloat()
    val p by animateFloatAsState(raw.coerceIn(0f, 1f), tween(450), label = "sumP")
    val track = TextPrimary.copy(alpha = 0.10f)
    val ringColor = if (live.isEmpty()) Good else accent

    Row(
        Modifier.fillMaxWidth().liquidGlass(CardShape, 1f, reactive = true).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = 5.dp.toPx()
                val tl = Offset(sw / 2, sw / 2)
                val sz = Size(size.width - sw, size.height - sw)
                drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(sw))
                drawArc(ringColor, -90f, 360f * p, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            }
            if (live.isEmpty()) Icon(AppleIcons.Check, null, tint = Good, modifier = Modifier.size(24.dp))
            else Text("${(p * 100).toInt()}%", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, style = DockNum)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    running.isNotEmpty() -> "Downloading ${running.size}"
                    paused.isNotEmpty() -> "${paused.size} paused"
                    live.isNotEmpty() -> "Waiting…"
                    else -> "All caught up"
                },
                color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                when {
                    running.isNotEmpty() -> buildString {
                        if (speed > 0) append(formatSpeed(speed))
                        if (eta > 0) { if (isNotEmpty()) append("  ·  "); append("${((eta + 59L) / 60L).coerceAtLeast(1L)} min left") }
                        if (isEmpty()) append("Starting…")
                    }
                    else -> "$doneCount saved"
                },
                color = TextSecondary, fontSize = 13.sp, style = DockNum, maxLines = 1
            )
        }
        if (running.isNotEmpty()) {
            GlassPillButton("Pause", { s.downloads.pauseAll() }, icon = AppleIcons.Pause)
        } else if (paused.isNotEmpty()) {
            GlassPillButton("Resume", { paused.forEach { s.downloads.resume(it) } }, icon = AppleIcons.PlayArrow, accent = true)
        }
    }
}

@Composable
private fun DockDownloadRow(s: BrowserState, d: DlItem, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val accent = LocalGlassStyle.current.accent
    val done = d.status == DlStatus.DONE
    val running = d.status == DlStatus.RUNNING
    val (icon, tint) = when (d.kindLabel) {
        "AUDIO" -> AppleIcons.MusicNote to MusicRed
        "VIDEO", "HLS" -> AppleIcons.Movie to VideoPurple
        else -> AppleIcons.Download to accent
    }
    val main = if (d.kindLabel == "AUDIO") MusicRed else accent
    val statusColor by animateColorAsState(
        when (d.status) {
            DlStatus.DONE -> GoodText
            DlStatus.RUNNING -> main
            DlStatus.PAUSED -> WarnText
            DlStatus.FAILED -> BadText
            DlStatus.QUEUED -> TextSecondary
        }, tween(250), label = "rowStatus"
    )
    val progress by animateFloatAsState(d.progress.coerceIn(0f, 1f), tween(450), label = "rowP")

    Column(
        modifier
            .fillMaxWidth()
            .animateContentSize(spring(dampingRatio = 0.85f, stiffness = 500f))
            .groupedSurface(TileShape, reactive = true)
            .then(
                if (done) Modifier.clickableHaptic {
                    if (d.file.exists() && MediaTools.isPlayable(d.file))
                        MediaTools.playLocal(ctx, d.file, d.name, accent.toArgb())
                    else DownloadManager.open(ctx, d)
                } else Modifier
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).glassTile(RoundedCornerShape(13.dp), 0.14f, tint),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    d.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.2).sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    when (d.status) {
                        DlStatus.RUNNING -> "${(progress * 100).toInt()}%" +
                            (if (d.speed > 0) "  ·  ${formatSpeed(d.speed)}" else "") +
                            (if (d.shownTotal > 0) "  ·  ${formatBytes(d.done)} of ${formatBytes(d.shownTotal)}" else "")
                        DlStatus.DONE -> "Completed  ·  ${formatBytes(maxOf(d.total, d.done))}"
                        DlStatus.PAUSED -> "Paused  ·  ${(progress * 100).toInt()}%"
                        DlStatus.FAILED -> d.error?.takeIf { it.isNotBlank() } ?: "Failed"
                        DlStatus.QUEUED -> "Queued"
                    },
                    color = statusColor, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                    style = DockNum, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            if (done) {
                Box(Modifier.size(44.dp).background(Good.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(AppleIcons.Check, "Completed", tint = Good, modifier = Modifier.size(20.dp))
                }
            } else {
                val failed = d.status == DlStatus.FAILED
                val c = if (failed) Bad else main
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(c.copy(alpha = 0.15f), CircleShape)
                        .clickableHaptic { if (running) s.downloads.pause(d) else s.downloads.resume(d) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        when { running -> AppleIcons.Pause; failed -> AppleIcons.Refresh; else -> AppleIcons.PlayArrow },
                        when { running -> "Pause"; failed -> "Retry"; else -> "Resume" },
                        tint = c, modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        if (!done) {
            Spacer(Modifier.height(12.dp))
            GlassProgressBar(progress, Modifier.fillMaxWidth(), height = 5.dp)
        }
    }
}

// ── Detected on this page ────────────────────────────────────────────────────

@Composable
private fun DetectedRow(s: BrowserState, tab: Tab, url: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val kind = if (url in tab.hlsUrls) "HLS" else mediaKind(url)
    val audio = kind == "AUDIO"
    val tint = if (audio) MusicRed else VideoPurple
    val file = url.substringBefore('?').substringAfterLast('/').ifBlank { hostOf(url) }
    Row(
        modifier.fillMaxWidth().groupedSurface(TileShape, reactive = true).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp).glassTile(RoundedCornerShape(13.dp), 0.14f, tint), contentAlignment = Alignment.Center) {
            Icon(if (audio) AppleIcons.MusicNote else AppleIcons.Movie, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    kind, color = tint, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp,
                    modifier = Modifier.background(tint.copy(alpha = 0.14f), Capsule).padding(horizontal = 7.dp, vertical = 2.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text(hostOf(url), color = TextTertiary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(3.dp))
            Text(file, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        GlassIconButton(
            AppleIcons.Download, "Download", { s.download(url, if (kind == "HLS") "application/x-mpegURL" else null) },
            size = 44.dp, iconSize = 19.dp
        )
        Spacer(Modifier.width(8.dp))
        GlassIconButton(
            AppleIcons.PlayArrow, "Play",
            { s.openPlayer(ctx, tab, url, s.effectiveAccent, s.playableAudio(tab, url)) },
            size = 44.dp, iconSize = 20.dp, tint = Color.White, accent = true
        )
    }
}
