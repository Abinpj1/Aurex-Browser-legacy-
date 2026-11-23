package com.aurex.browser

import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.Handler
import android.os.Bundle
import android.os.SystemClock
import android.webkit.WebSettings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.SizeTransform
import android.view.SurfaceView
import android.view.TextureView
import android.view.PixelCopy
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

/** Fixed distance every player control keeps from the physical screen edge (same on left and right;
 *  the camera-cutout / status-bar area is used too, so nothing is pushed inward on one side only). */
private val PlayerEdge = 38.dp

class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var meter: androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
    private var mediaSession: MediaSession? = null
    /** Compose follows a player swap (seamless quality switch) through this. */
    private val playerRef = mutableStateOf<ExoPlayer?>(null)
    private lateinit var playerListener: Player.Listener
    private var hiResLoad = false
    private var hiResBytes = 0
    private var autoPip = true
    private var pipRequested = false
    private val pipModeState = mutableStateOf(false)

    // What was asked for
    private var pageUrl: String? = null
    private var introTitle: String = ""
    private var referer: String? = null
    private var userAgent: String? = null
    private var cookie: String? = null
    private var firstUrl = ""
    private var firstAudio: String? = null

    // Stream resolver (YouTube & co): real links from the page instead of sniffed CDN chunks.
    private val playUrlState = mutableStateOf("")
    private val resolving = mutableStateOf(false)
    /** Length the site reported while resolving: the progress bar knows it before the new stream has loaded. */
    private val knownDurationMs = mutableLongStateOf(0L)
    private val resolveError = mutableStateOf<String?>(null)
    private val resolvedTitle = mutableStateOf("")
    private val choices = mutableStateOf<List<YtDlpEngine.StreamChoice>>(emptyList())
    private val choiceIndex = mutableIntStateOf(-1)
    private var resolvedUa: String? = null
    private var triedResolve = false
    private var canResolve = false
    /**
     * Any other web page (no site extractor): when the link the browser sniffed will not play (hotlink 403, odd
     * container), the engine gets ONE try at the page itself. Never for decoder or network-drop errors.
     */
    private var engineFallback = false
    private var wantHeight = 0          // quality picked in the in-app player (0 = automatic choice)
    /**
     * True from the exact moment a quality is picked by hand until the new stream shows its first picture.
     * The screen keeps the last frame, dims it a little and shows the buffering spinner AT ONCE (no 700 ms wait).
     */
    private val qualitySwitching = mutableStateOf(false)
    private val switchBackstop = Runnable { qualitySwitching.value = false }
    /** The new quality has drawn its first picture (it is drawn while still pre-rolling, before playback is READY). */
    private var switchFrameSeen = false
    /** Raises the buffer screen. Called FIRST in a hand-picked switch, before any lookup can cost a frame. */
    private fun beginSwitching() {
        switchFrameSeen = false
        qualitySwitching.value = true
        handler.removeCallbacks(switchBackstop)
        handler.postDelayed(switchBackstop, 25_000L)
    }
    private fun endSwitching() {
        handler.removeCallbacks(switchBackstop)
        if (qualitySwitching.value) qualitySwitching.value = false
    }
    private var netRetries = 0          // automatic retries after a network drop (reset once playing)
    /** The automatic retries ran out while the phone was offline: playback resumes by itself the moment a network is back. */
    private var waitingForNet = false
    /** The page lookup (not the stream) failed, e.g. because the phone was offline: it is repeated when a network is back. */
    private var resolveFailed = false
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null
    /** A quality was picked by hand: the automatic step-down on a slow connection must never override that choice. */
    private var userPickedQuality = false
    private var stallStepDowns = 0
    private var lastReadyAt = 0L        // last moment the stream was READY (0 = never): a stall only counts after real playback
    private var lastSeekAt = 0L
    private var stallAt1 = 0L           // start of the two most recent stalls
    private var stallAt2 = 0L
    private var reResolves = 0          // fresh lookups after an expired / refused stream link (reset once playing)
    private var isLocal = false
    private var externalContentSize = 0L
    /** True until the orientation was matched to the video (portrait video -> portrait screen, landscape -> landscape). */
    private var needOrient = true

    @Suppress("DEPRECATION")
    private fun noTransition() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else overridePendingTransition(0, 0)
        }
    }

    private fun orientFor(portrait: Boolean) {
        requestedOrientation = if (portrait) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    /** Local files: the real size is known before the first frame, so the screen is turned before the player shows. */
    private fun peekLocalOrientation(url: String) {
        runCatching {
            val r = android.media.MediaMetadataRetriever()
            try {
                if (url.startsWith("file:", true) || url.startsWith("content:", true)) r.setDataSource(this, Uri.parse(url)) else r.setDataSource(url)
                val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (w > 0 && h > 0) {
                    val portrait = if (rot % 180 != 0) w > h else h > w
                    orientFor(portrait); needOrient = false
                }
            } finally { runCatching { r.release() } }
        }
    }
    private var posKey = ""

    // Page <-> player sync (see PlayerSync): on close the page video continues from here.
    private var syncBack = false
    private var syncSeek = true
    private var syncSent = false

    // Seamless hand-over: the page keeps playing (sound goes on) while this player loads silently.
    // 0 = not used, 1 = waiting for the first frame, 2 = aligned seek in progress, 3 = done (player plays, page paused).
    private var handoff = 0
    private var handoffClean = true   // false: the player never had data when the page was paused (error / timeout)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private var handoffArmed = false

    private fun startHandoff() {
        handoff = 1
        // Absolute safety net: never wait forever, even if no stream ever starts loading.
        handler.postDelayed({ if (handoff in 1..2) finishHandoff() }, 45_000L)
    }

    /**
     * The "no picture yet" timeout only starts once a stream really begins to load. It used to start in onCreate,
     * so a slow stream lookup + a 4K start could use it all up: the page was paused while the player still showed
     * nothing. 1440p / 4K / 8K also get more time than the rest.
     */
    private fun armHandoffTimer() {
        if (handoff != 1 || handoffArmed) return
        handoffArmed = true
        val h = choices.value.getOrNull(choiceIndex.intValue)?.let { DeviceCaps.tier(it) } ?: 0
        val hi = MediaTools.plainHeight(wantHeight) >= 1440 || h >= 1440
        handler.postDelayed({ if (handoff in 1..2) finishHandoff() }, if (hi) 20_000L else 9_000L)
    }

    // ---- Smooth quality hand-over --------------------------------------------------------------------------------
    // The page keeps playing at its CURRENT quality (sound never stops) while the new stream loads silently, parked a
    // little ahead of the page ("lead"). The switch happens only when (a) the page has reached the parked point and
    // (b) enough of the new stream is already buffered beyond it. Then the player simply starts: no seek, no stall.
    private var handoffParked = 0L
    private val handoffPoll = Runnable { handoffTick() }

    private fun handoffIsHiRes(): Boolean {
        val h = choices.value.getOrNull(choiceIndex.intValue)?.height ?: 0
        return MediaTools.plainHeight(wantHeight) >= 1440 || h >= 1440
    }

    /** How far ahead of the page the new stream starts: about as long as it needs to load (4K / 8K take longer). */
    private fun handoffLeadMs(): Long =
        if (handoff != 1) 0L else if (MediaTools.plainHeight(wantHeight) >= 1440) 2_000L else 1_000L

    /** Data that must already be buffered beyond the switch point, so nothing can stall right after the switch. */
    private fun handoffAheadMs(): Long = if (handoffIsHiRes()) 2_000L else 1_200L

    /** Player has data: from here the switch waits only for the page to arrive and for the buffer. */
    private fun onHandoffReady() {
        if (handoff != 1) return
        handler.removeCallbacks(handoffPoll)
        handoffTick()
    }

    private fun handoffTick() {
        if (handoff != 1) return
        if (player.playbackState != Player.STATE_READY) { handler.postDelayed(handoffPoll, 150L); return }
        PlayerSync.peek { info ->
            if (handoff != 1) return@peek
            if (info == null || info.ms < 0) { finishHandoff(); return@peek }
            val page = info.ms
            val parked = handoffParked
            val buffered = player.bufferedPosition
            val ahead = handoffAheadMs()
            val ready = when {
                page < parked - 60L -> false                          // page has not reached the parked point: keep playing it
                page <= parked + 250L -> buffered >= parked + ahead   // aligned: switch with no seek at all
                else -> buffered >= page + ahead + 800L               // page ran past: only when the buffer covers that spot
            }
            if (ready) finishHandoff() else handler.postDelayed(handoffPoll, 100L)
        }
    }

    /** Pause the page at its exact position and start here at the same spot: no gap, no overlap. */
    private fun finishHandoff() {
        if (handoff == 3 || handoff == 0) return
        handler.removeCallbacks(handoffPoll)
        handoff = 3
        handoffClean = player.playbackState == Player.STATE_READY
        PlayerSync.take { info ->
            if (info != null && info.ms >= 0) {
                val target = info.ms + 60L
                if (kotlin.math.abs(player.currentPosition - target) > 200L) {
                    // EXACT, not "nearest keyframe": in 4K / 8K the nearest keyframe is seconds away (the old jump)
                    player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
                    player.seekTo(target)
                    player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
                }
            }
            player.playWhenReady = true
            player.play()
        }
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val act = intent?.action ?: return
            if (!PipSupport.acceptTap(act)) return   // duplicate / bounced tap
            Haptics.fireDetached(Hx.TAP)
            when (act) {
                ACTION_PIP_TOGGLE -> togglePlayPause()
                ACTION_PIP_AUDIO -> enterAudioOnly()
                ACTION_PIP_NEXT -> {
                    if (player.hasNextMediaItem()) {
                        player.seekToNextMediaItem(); player.play(); refreshPip()
                    }
                }
            }
        }
    }

    /**
     * Landscape: status + navigation bars hidden, the video uses the whole screen.
     * Portrait: bars visible, and the controls sit below / above them (never underneath).
     */
    private fun applySystemBars() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.isAppearanceLightStatusBars = false
        c.isAppearanceLightNavigationBars = false
        if (landscape) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars()
    }

    // ── Notification card for a downloaded video (same card as the page player: play / pause, prev, next, tap) ──
    private var cardUp = false

    private val localCtl = object : MediaNotif.LocalCtl {
        override fun snapshot(): MediaNotif.Snap? = runCatching {
            val d = player.duration
            MediaNotif.Snap(
                player.currentPosition.coerceAtLeast(0L),
                if (d != C.TIME_UNSET && d > 0) d else -1L,
                wantsPlay() && player.playbackState != Player.STATE_ENDED,
                false,
                player.hasNextMediaItem()
            )
        }.getOrNull()

        override fun toggle() = togglePlayPause()

        override fun prev() {
            if (player.currentPosition > 3_000L || !player.hasPreviousMediaItem()) player.seekTo(0L)
            else player.seekToPreviousMediaItem()
            MediaNotif.poke()
        }

        override fun next(): Boolean {
            if (!player.hasNextMediaItem()) return false
            player.seekToNextMediaItem()
            player.play()
            MediaNotif.poke()
            return true
        }

        override fun seekTo(ms: Long) { player.seekTo(ms) }
    }

    /** Shows / updates the card while a downloaded video plays; a pause keeps it (resume from the card). */
    private fun localCard(playing: Boolean) {
        if (!isLocal) return
        if (!playing && (wantsPlay() || !cardUp)) return        // a buffering dip is not a pause; nothing shown yet = nothing to keep
        val name = introTitle.ifBlank {
            runCatching { File(android.net.Uri.parse(firstUrl).path.orEmpty()).nameWithoutExtension }.getOrNull().orEmpty()
        }.ifBlank { "Downloaded video" }
        val i = Intent(this, BrowserMediaService::class.java)
            .putExtra(BrowserMediaService.EXTRA_PLAYING, true)
            .putExtra(BrowserMediaService.EXTRA_PAUSED, !playing)
            .putExtra(BrowserMediaService.EXTRA_LOCAL, true)
            .putExtra(BrowserMediaService.EXTRA_TITLE, name)
            .putExtra(BrowserMediaService.EXTRA_HOST, if (firstUrl.startsWith("content:", true)) "Video" else "Downloads")
            .putExtra(BrowserMediaService.EXTRA_URL, firstUrl)
            .putExtra(BrowserMediaService.EXTRA_ACCENT, 0xFF0A84FF.toInt())
        runCatching {
            if (playing) {
                MediaNotif.attachLocal(applicationContext, localCtl)
                cardUp = true
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            } else {
                startService(i)
            }
        }
        MediaNotif.poke()
    }

    private data class ExternalMeta(val title: String, val size: Long)

    private fun queryContentMeta(uri: Uri): ExternalMeta {
        var name = ""
        var size = 0L
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni).orEmpty()
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        val clean = name.substringAfterLast('/').substringBeforeLast('.', name).trim()
        return ExternalMeta(clean, size)
    }

    private fun extractExternalUri(i: Intent?): Uri? {
        if (i == null) return null
        return when (i.action) {
            Intent.ACTION_VIEW -> i.data ?: i.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            Intent.ACTION_SEND -> {
                val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                    i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    i.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                stream ?: i.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri ?: i.data
            }
            else -> null
        }
    }

    private fun externalTitleFor(i: Intent, uri: Uri, contentName: String): String {
        if (contentName.isNotBlank()) return contentName
        val extra = i.getStringExtra(Intent.EXTRA_TITLE)?.takeIf { it.isNotBlank() }
            ?: i.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }
        if (extra != null) return extra.trim()
        val seg = uri.lastPathSegment.orEmpty().substringAfterLast('/').substringBeforeLast('.')
        if (seg.isNotBlank() && !seg.all { it.isDigit() }) return seg
        return if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) hostOf(uri.toString()) else "Video"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val ext = intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_SEND
        val incoming = (if (ext) extractExternalUri(intent)?.toString() else intent.getStringExtra("url"))?.trim()
        // Notification tap / bring-to-front carries no video URI: keep the running player intact!
        if (incoming.isNullOrBlank() || incoming == firstUrl) return
        savePosition()
        setIntent(intent)
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No slide / fade between the in-app player and this one, and never the light app background in between:
        // that was the 'player opens again + jitter' when a quality above FHD was picked.
        noTransition()
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applySystemBars()

        val isExternalAction = intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_SEND
        val extUri = if (isExternalAction) extractExternalUri(intent) else null
        val rawUrl = if (isExternalAction) extUri?.toString() else intent.getStringExtra("url")
        val url = rawUrl?.trim()
        if (url.isNullOrBlank()) { finish(); return }
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: run { finish(); return }
        val scheme = uri.scheme?.lowercase().orEmpty()
        val allowedScheme = scheme == "content" || scheme == "file" || scheme == "http" || scheme == "https" ||
            (!isExternalAction && scheme == "blob")
        if (!allowedScheme) { finish(); return }

        val contentMeta = if (scheme == "content") queryContentMeta(uri) else ExternalMeta("", 0L)
        externalContentSize = contentMeta.size

        // Quality switched from the in-page / inline player: open in the SAME orientation the screen has right now
        // (landscape stays landscape, portrait stays portrait) and do not re-match it to the video's shape afterwards.
        val keepOrient = !isExternalAction && intent.getBooleanExtra("keepOrient", false)
        if (keepOrient) {
            orientFor(portrait = !intent.getBooleanExtra("landscape", false))
            needOrient = false
        } else if (!isExternalAction && intent.getBooleanExtra("landscape", false)) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        isLocal = (!isExternalAction && intent.getBooleanExtra("local", false)) || scheme == "file" || scheme == "content"
        if (isLocal && !keepOrient) peekLocalOrientation(url)
        firstUrl = url
        syncBack = !isExternalAction && intent.getBooleanExtra("sync", false)
        syncSeek = !isExternalAction && !intent.getBooleanExtra("syncLive", false)
        if (syncBack && intent.getBooleanExtra("handoff", false)) startHandoff()
        firstAudio = if (isExternalAction) null else intent.getStringExtra("audioUrl")
            ?.takeIf { it.isNotBlank() && it != url && isMediaUrl(it) }
        pageUrl = if (isExternalAction) null else intent.getStringExtra("pageUrl")?.takeIf { it.isNotBlank() }
        val title = if (isExternalAction) {
            externalTitleFor(intent, uri, contentMeta.title)
        } else {
            intent.getStringExtra("title").orEmpty().ifBlank { contentMeta.title }
        }
        introTitle = title
        playUrlState.value = url
        if (!isExternalAction) {
            intent.getLongExtra("durMs", 0L).takeIf { it > 0L }?.let { knownDurationMs.longValue = it }
        }

        val origin = if (!uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank())
            "${uri.scheme}://${uri.host}/" else ""
        userAgent = (if (isExternalAction) null else intent.getStringExtra("ua")?.takeIf { it.isNotBlank() })
            ?: runCatching { WebSettings.getDefaultUserAgent(this) }.getOrNull()?.takeIf { it.isNotBlank() }
        referer = ((if (isExternalAction) null else intent.getStringExtra("referer")?.takeIf { it.isNotBlank() }) ?: origin).takeIf { it.isNotBlank() }
        cookie = if (isLocal) null else siteCookie(url)
            ?.takeIf { it.isNotBlank() }
            ?: (if (isExternalAction) null else intent.getStringExtra("cookie")?.takeIf { it.isNotBlank() })

        // YouTube & co hand out signed, throttled, chunked CDN links. Those are what used to give
        // 403 / "container unsupported", so the page itself is resolved to proper stream links.
        val host = uri.host.orEmpty()
        wantHeight = intent.getIntExtra("height", 0)
        val cdn = host.endsWith("googlevideo.com") || host.contains("youtube") || url.startsWith("blob:") || wantHeight > 0
        // A blob: / MSE player (ok.ru, many embed sites) has no link a player can open: the page itself is handed to
        // the engine, whose generic extractor finds the real stream (the same way "Save page" does).
        canResolve = !isLocal && YtDlpEngine.canTry(pageUrl) &&
            (YtDlpEngine.isItemUrl(pageUrl) || (wantHeight > 0 && YtDlpEngine.isQualitySite(pageUrl)) ||
                url.startsWith("blob:", true))
        engineFallback = !isLocal && !canResolve && VideoDl.enabled && YtDlpEngine.canTry(pageUrl) &&
            !isHandsOffHost(pageUrl)
        posKey = "pos2_" + resumeId(url, pageUrl, canResolve, isLocal, title).hashCode()
        // The old key was the same for every video that came through a blob: / generic link, so a new video
        // started where the previous one stopped. Those entries are wrong by design: drop them once.
        getSharedPreferences("liquid", Context.MODE_PRIVATE).let { sp ->
            if (!sp.getBoolean("posMigrated2", false)) {
                val ed = sp.edit()
                sp.all.keys.filter { it.startsWith("pos_") }.forEach { ed.remove(it) }
                ed.putBoolean("posMigrated2", true).apply()
            }
        }

        // Start playing after ~0.8 s of data instead of the 2.5 s default.
        // 1440p / 4K / 8K carry 3-10x the bytes per second of 1080p. 60 s of 8K in memory would run the app out of
        // heap, so those get a short buffer and a hard byte cap; everything else keeps the old long buffer (the
        // cap is only a safety net there: 60 s of 1080p is far below it).
        // Any resolvable page (YouTube & co) can be switched to 1440p / 4K / 8K at any moment - from the quality
        // panel, or by the automatic pick - and the load control cannot be changed after the player exists. So all of
        // them start with the short, byte-capped buffer; the long 60 s buffer stays for plain / local streams.
        hiResLoad = canResolve || MediaTools.plainHeight(wantHeight) >= 1440
        // The byte cap follows the phone's real heap (largeHeap is on): a big-RAM phone keeps ~50 s of 4K ahead,
        // a small one stays safe. More buffered seconds = a longer network dip is absorbed without any visible stall.
        val heapMb = runCatching {
            (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).largeMemoryClass
        }.getOrDefault(256)
        hiResBytes = (heapMb * 0.40).toInt().coerceIn(96, 220) * 1024 * 1024
        // One bandwidth meter for the whole player (and for the standby player of a quality switch).
        meter = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(this).build()
        player = newExo()
        playerRef.value = player
        // A downloaded video gets the app's own card (BrowserMediaService); online playback uses lightweight native MediaSession.
        if (!isLocal) ensureNativeSession()

        val prefs = getSharedPreferences("liquid", Context.MODE_PRIVATE)
        autoPip = prefs.getBoolean("autoPip", true)
        val accent = 0xFF0A84FF.toInt() // fixed Apple system blue

        val filter = IntentFilter().apply {
            addAction(ACTION_PIP_TOGGLE); addAction(ACTION_PIP_AUDIO); addAction(ACTION_PIP_NEXT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pipReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(pipReceiver, filter)
        }

        playerListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    netRetries = 0; reResolves = 0; endSwitching()
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                refreshPip()
                syncNativeSession()
                localCard(isPlaying)
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                refreshPip()
                if (cardUp) MediaNotif.poke()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                refreshPip()
                if (cardUp) MediaNotif.poke()
                if (playbackState == Player.STATE_READY) lastReadyAt = SystemClock.elapsedRealtime()
                else if (playbackState == Player.STATE_BUFFERING && lastReadyAt > 0L) onPossibleStall()
                if (playbackState == Player.STATE_READY && exactStart) {
                    // the exact start is done: scrubbing is snappy (nearest keyframe) again
                    exactStart = false
                    player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
                }
                if (playbackState == Player.STATE_READY && audioOnly) endSwitching()   // no picture to wait for
                if (playbackState == Player.STATE_READY && switchFrameSeen) endSwitching()   // picture is up AND data is there
                if (playbackState == Player.STATE_READY) {
                    if (handoff == 1) onHandoffReady()
                    else if (handoff == 2) finishHandoff()
                }
            }
            override fun onRenderedFirstFrame() {
                // The first picture is drawn while the stream is still pre-rolling. Hiding the buffer screen right here
                // left a frozen picture with NO spinner until the next stall showed one (700 ms later) = the 'jitter'.
                // So: hide it only when playback can really go on (READY); if READY comes later, that callback hides it.
                switchFrameSeen = true
                if (player.playbackState == Player.STATE_READY) endSwitching()
                fireFirstFrame()
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                refreshPip()
                // Match the screen to the video once per video: portrait video -> portrait player, landscape -> landscape.
                if (needOrient && videoSize.width > 0 && videoSize.height > 0) {
                    needOrient = false
                    var w = videoSize.width * videoSize.pixelWidthHeightRatio
                    var h = videoSize.height.toFloat()
                    if (videoSize.unappliedRotationDegrees % 180 != 0) { val x = w; w = h; h = x }
                    orientFor(h > w)
                }
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT)
                    lastSeekAt = SystemClock.elapsedRealtime()
            }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // next / previous video of a list: decide the orientation again for the new video
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) needOrient = true
                if (cardUp) MediaNotif.poke()
            }
            override fun onPlayerError(error: PlaybackException) {
                lastReadyAt = 0L                      // buffering after an error / retry is not a "stall" for the step-down logic
                endSwitching()
                if (!((canResolve || engineFallbackFor(error.errorCode)) && !triedResolve)) fireFirstFrame()   // nothing more to wait for: let the caller go
                // The indexed source could not be read for any reason: play the file the plain way instead of failing.
                if (indexedActive && error.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED &&
                    error.errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED &&
                    error.errorCode != PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES &&
                    error.errorCode != PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED) {
                    indexedActive = false
                    playDirect(player.currentPosition.coerceAtLeast(0L))
                    return
                }
                // The first failure on a resolvable page silently switches to the resolved stream.
                if (canResolve && !triedResolve) { startResolve(); return }
                if (engineFallbackFor(error.errorCode)) { startResolve(); return }
                when (error.errorCode) {
                    // Live / sliding-window stream fell behind: jump back to the live edge.
                    PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                        player.seekToDefaultPosition(); player.prepare()
                    }
                    // Signed link expired (paused for long) or refused (403 / 404 / 410): ask the site for fresh
                    // links and continue at the same second and quality.
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        if (canResolve && reResolves < 2) {
                            reResolves++
                            pageUrl?.let { YtDlpEngine.invalidate(it) }
                            choices.value.getOrNull(choiceIndex.intValue)?.height?.takeIf { it > 0 }?.let { wantHeight = it }
                            startResolve(player.currentPosition)
                        }
                    // The decoder refused the picture (8K / a codec this chip lacks): go one quality down, same second.
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> stepDownAfterDecoderError()
                    // Short network drop (lift, tunnel, wifi switch): retry by itself, 1.2 s / 2.4 s / 3.6 s later.
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        if (netRetries < 3) {
                            netRetries++
                            window.decorView.postDelayed({
                                if (!isFinishing && !isDestroyed) { player.prepare(); player.playWhenReady = true }
                            }, 1_200L * netRetries)
                        } else {
                            // Out of quick retries (a long tunnel, wifi -> mobile hand-over): wait for the network callback.
                            waitingForNet = true
                        }
                    else -> Unit
                }
            }
        }
        player.addListener(playerListener)
        watchNetwork()
        // A quality picked in the in-app player: the page was paused on the exact frame (see launchPlayer), this player shows
        // that picture dimmed with the spinner from its very first frame, and continues from the same second.
        // (Before, the screen sat on a frozen picture with no spinner, a play button and a stuck time while the page's
        // sound kept running underneath.)
        if (intent.getBooleanExtra("qualitySwitch", false) && !isLocal) beginSwitching()

        setContent {
            AurexTheme(
                accent = Color(accent),
                glass = prefs.getFloat("glass", 0.86f),
                haptics = prefs.getBoolean("haptics", true),
                glow = prefs.getBoolean("glow", true),
                mode = ThemeModes.DARK
            ) {
                PlayerScreen(
                    player = playerRef.value ?: player,
                    title = resolvedTitle.value.ifBlank { title.ifBlank { hostOf(url) } },
                    pageUrl = pageUrl,
                    playUrl = playUrlState.value,
                    pipMode = pipModeState.value,
                    resolving = resolving.value,
                    knownDurationMs = knownDurationMs.longValue,
                    resolveError = resolveError.value,
                    canResolve = canResolve,
                    isLocal = isLocal,
                    choices = choices.value,
                    choiceIndex = choiceIndex.intValue,
                    onChoice = { userPickedQuality = true; selectChoice(it, player.currentPosition, instant = true) },
                    switching = qualitySwitching.value,
                    onRetry = { retry() },
                    onClose = { finish() },
                    onPip = { enterPip() },
                    onDownload = { download() }
                )
            }
        }

        // An explicit start position (extra "startMs") wins over the saved resume position.
        val resume = if (intent.hasExtra("startMs")) intent.getLongExtra("startMs", 0L) + handoffLeadMs()
        else prefs.getLong(posKey, 0L)
        val localFile = if (isLocal) FragFix.localFile(url) else null
        if (localFile != null) {
            // A downloaded file is already on the phone: it starts at once, nothing is prepared or rewritten first.
            // HLS downloads have no seek index; it is read from the file's fragment headers in memory (a few ms), so
            // seeking and the duration work like in VLC / MX - for every codec, and the file itself is never touched.
            if (FragFix.needsFix(localFile)) playIndexed(localFile, resume) else playDirect(resume)
        } else if (canResolve && cdn) startResolve(resume) else playDirect(resume)
        runCatching { setPictureInPictureParams(buildPipParams()) }
    }

    // -- Sources --------------------------------------------------------------------------------

    private fun httpFactory(h: Map<String, String>): androidx.media3.datasource.DataSource.Factory =
        OkHttpStreamDataSource.Factory(h, h["User-Agent"])

    /**
     * http(s) with the page headers, plus file:// for downloaded videos. YouTube's CDN is read in ranged chunks
     * (see [ChunkedDataSource]) so it never throttles a 2K / 4K stream; every other host is untouched.
     */
    private fun dataFactory(h: Map<String, String>): androidx.media3.datasource.DefaultDataSource.Factory {
        val http = httpFactory(h)
        return androidx.media3.datasource.DefaultDataSource.Factory(
            this,
            androidx.media3.datasource.DataSource.Factory { ChunkedDataSource(http.createDataSource(), http) }
        )
    }

    private fun kindMime(u: String): String? = when (mediaKind(u)) {
        "HLS" -> MimeTypes.APPLICATION_M3U8
        "DASH" -> MimeTypes.APPLICATION_MPD
        else -> null
    }

    private fun source(
        videoUrl: String, audioUrl: String?, vHeaders: Map<String, String>, aHeaders: Map<String, String>,
        forceHls: Boolean = false,
        forceMime: String? = null
    ): MediaSource {
        val vItem = MediaItem.Builder().setUri(videoUrl).apply {
            (kindMime(videoUrl) ?: forceMime ?: if (forceHls) MimeTypes.APPLICATION_M3U8 else null)?.let { setMimeType(it) }
        }.build()
        val vSrc = DefaultMediaSourceFactory(dataFactory(vHeaders))
            .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(6))
            .createMediaSource(vItem)
        if (audioUrl == null) return vSrc
        val aItem = MediaItem.Builder().setUri(audioUrl).apply { kindMime(audioUrl)?.let { setMimeType(it) } }.build()
        val aSrc = DefaultMediaSourceFactory(dataFactory(aHeaders))
            .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(6))
            .createMediaSource(aItem)
        return runCatching { MergingMediaSource(vSrc, aSrc) as MediaSource }.getOrDefault(vSrc)
    }

    /** True from a start at a given position until the first picture: that start must land on the exact frame. */
    private var exactStart = false

    private fun begin(src: MediaSource, startMs: Long) {
        // A quality switch continues at the same second. With CLOSEST_SYNC the new stream snapped to ITS nearest keyframe
        // (seconds away in 4K / 8K), so the progress bar jumped forward / back. EXACT decodes up to the real position.
        if (startMs > 0L) {
            exactStart = true
            player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
        }
        player.setMediaSource(src, startMs.coerceAtLeast(0L))
        if (handoff == 1) handoffParked = startMs.coerceAtLeast(0L)
        player.prepare()
        // During a hand-over the page is still playing: load silently, play only when aligned.
        player.playWhenReady = handoff != 1 && handoff != 2
        armHandoffTimer()
    }

    private fun playDirect(startMs: Long = 0L) {
        val h = LinkedHashMap<String, String>()
        referer?.let { ref ->
            h["Referer"] = ref
            runCatching {
                val ru = Uri.parse(ref)
                if (!ru.scheme.isNullOrBlank() && !ru.host.isNullOrBlank()) {
                    h["Origin"] = "${ru.scheme}://${ru.host}"
                }
            }
        }
        userAgent?.let { h["User-Agent"] = it }
        cookie?.let { h["Cookie"] = it }
        val ah = LinkedHashMap<String, String>(h)
        firstAudio?.let { a ->
            val c = siteCookie(a).orEmpty()
            if (c.isNotBlank()) ah["Cookie"] = c
            else if (Uri.parse(a).host != Uri.parse(firstUrl).host) ah.remove("Cookie")
        }
        playUrlState.value = firstUrl
        val extType = intent?.type.orEmpty()
        val extMime = when {
            extType.contains("mpegurl", true) -> MimeTypes.APPLICATION_M3U8
            extType.contains("dash+xml", true) -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        begin(source(firstUrl, firstAudio, h, ah, forceMime = extMime), startMs)
    }

    /** True while the current source is the index-less fMP4 file played through [FragIndex]. */
    private var indexedActive = false

    /**
     * Plays a downloaded fragmented MP4 that has no seek index: ExoPlayer's normal parser, but with a seek map built from the
     * file's own fragment headers ([FragIndex]). No waiting, no rewrite of the file, seeking works from the first second.
     */
    private fun playIndexed(f: File, startMs: Long) {
        indexedActive = true
        val src = ProgressiveMediaSource.Factory(
            dataFactory(emptyMap()),
            ExtractorsFactory { arrayOf<Extractor>(FragIndex.IndexedExtractor(f)) }
        ).createMediaSource(MediaItem.fromUri(Uri.fromFile(f)))
        playUrlState.value = firstUrl
        begin(src, startMs)
    }

    private fun engineFallbackFor(code: Int): Boolean = engineFallback && !triedResolve && when (code) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> true
        else -> false
    }

    private fun startResolve(startMs: Long = player.currentPosition) {
        val page = pageUrl ?: return
        triedResolve = true
        resolving.value = true
        resolveError.value = null
        resolveFailed = false
        val cookies = siteCookie(page)
        // Already looked up (prefetch / quality sheet / earlier open)? Then playback starts right now,
        // without a thread hop and without the "resolving" spinner.
        val cached = YtDlpEngine.peekCached(page)
        if (cached != null && cached.choices.isNotEmpty()) {
            applyResolved(cached, startMs)
            return
        }
        Thread {
            val result = runCatching { YtDlpEngine.resolveCached(applicationContext, page, cookies) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                resolving.value = false
                result.onSuccess { r ->
                    if (r.choices.isEmpty()) {
                        endSwitching()
                        resolveError.value = "No playable stream was found on this page."
                        if (handoff == 1 || handoff == 2) finishHandoff()
                    } else {
                        applyResolved(r, startMs)
                    }
                }.onFailure {
                    endSwitching()
                    resolveFailed = true
                    resolveError.value = YtDlpEngine.friendlyError(it.message)
                    if (handoff == 1 || handoff == 2) finishHandoff()
                }
            }
        }.start()
    }

    /**
     * True when a hardware decoder handles this stream's codec at its real size and frame rate (smooth playback, no heat).
     * [hardwareOnly] = false: any decoder counts, software included.
     */
    private fun hwDecodes(c: YtDlpEngine.StreamChoice, hardwareOnly: Boolean = true): Boolean =
        DeviceCaps.decodes(c, hardwareOnly)

    private fun applyResolved(r: YtDlpEngine.ResolvedMedia, startMs: Long) {
        resolvedTitle.value = r.title
        resolvedUa = r.userAgent
        if (r.durationSec > 0) knownDurationMs.longValue = (r.durationSec * 1000).toLong()
        choices.value = r.choices
        // The 4K / 8K decoder answers are looked up once per stream (MediaCodecList is slow the first time). Do it here,
        // off the main thread, so tapping 4K later costs nothing.
        r.choices.filter { DeviceCaps.tier(it) >= 2160 }.takeIf { it.isNotEmpty() }?.let { warm ->
            Thread { warm.forEach { runCatching { hwDecodes(it); hwDecodes(it, hardwareOnly = false) } } }.start()
        }
        // A quality picked in the in-app player wins: that height, else the best one below it.
        // (an HDR pick carries a marker on top of the height: compare the plain height, or it would match "everything")
        val want = MediaTools.plainHeight(wantHeight)
        val wanted = if (want > 0)
            r.choices.indexOfFirst { DeviceCaps.tier(it) == want }.takeIf { it >= 0 }
                ?: r.choices.withIndex().filter { DeviceCaps.tier(it.value) in 1..want }.maxByOrNull { DeviceCaps.tier(it.value) }?.index
        else null
        resolving.value = false
        if (wanted != null) { selectChoice(wanted, startMs); return }
        // Nothing was picked: the list is sharp -> soft, so start with the sharpest stream this phone decodes in hardware.
        selectChoice(r.choices.indexOfFirst { it.height > 0 && hwDecodes(it) }.takeIf { it >= 0 } ?: 0, startMs)
    }

    private fun selectChoice(index: Int, startMs: Long, instant: Boolean = false) {
        val list = choices.value
        val c = list.getOrNull(index) ?: return
        // A hand-picked quality: the buffer screen goes up in the same frame as the tap - before the decoder checks
        // and the source building below (they used to run first, so the screen reacted a few frames late).
        if (instant && player.mediaItemCount > 0 && !isLocal) beginSwitching()
        // 8K (and some 4K codecs) has no decoder at all on many phones: that would end in DECODER_INIT_FAILED.
        // Take the next sharper quality the phone can decode instead, and say so.
        val cTier = DeviceCaps.tier(c)
        if (cTier >= 2160 && !hwDecodes(c, hardwareOnly = false)) {
            val lower = (index + 1 until list.size).firstOrNull { i ->
                val n = list[i]; val nt = DeviceCaps.tier(n)
                nt in 1 until cTier && (nt < 2160 || hwDecodes(n, hardwareOnly = false))
            }
            if (lower != null) {
                val wasQ = MediaTools.labelForHeight(DeviceCaps.tier(c))
                val nowQ = MediaTools.labelForHeight(DeviceCaps.tier(list[lower]))
                Toast.makeText(
                    this,
                    AppLang.translate("$wasQ can't be played on this phone, playing $nowQ instead"),
                    Toast.LENGTH_LONG
                ).show()
                selectChoice(lower, startMs, instant)
                return
            }
        }
        // Changing quality of a video that is already loaded must not start a paused video.
        val hadMedia = player.mediaItemCount > 0
        val wasPlayWhenReady = player.playWhenReady
        // Playing right now: the old quality keeps playing (picture and sound) while a standby player loads the new one
        // in the background; the two change hands at one exact instant. Nothing stops, nothing is re-buffered.
        // A quality picked by hand is applied at that very instant (no standby wait, no parked lead): [instant].
        val hot = !instant && canHotSwitch()
        choiceIndex.intValue = index
        // 4K / 8K picked by hand on a phone without a hardware decoder for it: say so instead of a silent stutter.
        if (cTier >= 2160 && !hwDecodes(c)) {
            val qLabel = MediaTools.labelForHeight(DeviceCaps.tier(c))
            Toast.makeText(
                this,
                AppLang.translate("$qLabel may stutter: this phone has no hardware decoder for it"),
                Toast.LENGTH_LONG
            ).show()
        }
        if (hot) { standbyTries = 0; if (startHotSwitch(index, c)) return }
        else cancelStandby()
        // (buffer screen is already up, see above) the new stream starts at the exact second of the tap
        applyChoice(c, startMs, hadMedia, wasPlayWhenReady)
    }

    /** The stream for one quality, with the headers its CDN wants. */
    private fun choiceSource(c: YtDlpEngine.StreamChoice): MediaSource {
        val page = Uri.parse(pageUrl ?: firstUrl)
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = resolvedUa ?: userAgent ?: "Mozilla/5.0"
        if (!page.scheme.isNullOrBlank() && !page.host.isNullOrBlank()) h["Referer"] = "${page.scheme}://${page.host}/"
        // The engine knows what each CDN wants (exact Referer / Origin / Cookie): those win over the generic ones.
        fun withSite(extra: Map<String, String>): Map<String, String> {
            val m = LinkedHashMap(h)
            extra.forEach { (k, v) ->
                if (v.isNotBlank() && k.lowercase() in SITE_HEADERS) {
                    m.keys.firstOrNull { it.equals(k, true) }?.let { old -> m.remove(old) }
                    m[k] = v
                }
            }
            return m
        }
        return source(c.video, c.audio, withSite(c.vHeaders), withSite(c.aHeaders), c.hls)
    }

    /** The plain switch: the current player is given the new stream (a short load, same second). */
    private fun applyChoice(c: YtDlpEngine.StreamChoice, startMs: Long, hadMedia: Boolean, wasPlayWhenReady: Boolean) {
        playUrlState.value = c.video
        begin(choiceSource(c), startMs)
        if (hadMedia && handoff != 1 && handoff != 2) player.playWhenReady = wasPlayWhenReady
    }

    // ---- Seamless in-player quality switch ----------------------------------------------------------------------------
    // 1. the picked quality is loaded by a STANDBY player (muted, no screen) a moment ahead of where the video is now;
    //    its decoder is already warm and the first picture is decoded, because it renders into a hidden surface;
    // 2. the visible player keeps playing the old quality the whole time: no pause, no spinner, no black frame;
    // 3. when the video arrives at the parked spot and the standby has enough data ahead, picture + sound change hands
    //    in one step (screen, audio focus, media session, Compose all follow). The old player is then released.
    // Anything that goes wrong (decoder, network, a long seek, 14 s without result) falls back to the plain switch.
    private var standby: ExoPlayer? = null
    private var standbyChoice: YtDlpEngine.StreamChoice? = null
    private var standbyIndex = -1
    private var standbyParked = 0L
    private var standbyStartedAt = 0L
    private var standbyTries = 0
    private var standbyTex: android.graphics.SurfaceTexture? = null
    private var standbySurface: android.view.Surface? = null
    private val standbyPoll = Runnable { standbyTick() }

    private fun canHotSwitch(): Boolean =
        !isLocal && !audioOnly && (handoff == 0 || handoff == 3) && !resolving.value &&
            player.mediaItemCount > 0 && player.playbackState == Player.STATE_READY && player.playWhenReady

    private fun isHi(c: YtDlpEngine.StreamChoice) = MediaTools.plainHeight(c.height) >= 1440

    /** How far ahead of the picture the standby starts: about as long as a (4K) stream needs to get going. */
    private fun hotLeadMs(c: YtDlpEngine.StreamChoice): Long = if (isHi(c)) 2_500L else 1_800L

    /** Data the standby must already hold beyond the switch point, so nothing can run dry right after it. */
    private fun hotAheadMs(c: YtDlpEngine.StreamChoice): Long = if (isHi(c)) 2_000L else 1_200L

    private fun startHotSwitch(index: Int, c: YtDlpEngine.StreamChoice): Boolean {
        cancelStandby()
        val dur = player.duration
        val park = player.currentPosition.coerceAtLeast(0L) + hotLeadMs(c)
        if (dur != C.TIME_UNSET && dur > 0L && park > dur - 2_000L) return false   // last seconds: the plain switch is fine
        return runCatching {
            val sb = newExo(handleAudioFocus = false)
            sb.volume = 0f
            sb.playbackParameters = player.playbackParameters
            sb.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().build()
            // A hidden surface: the decoder starts and decodes the first picture now, not at the moment of the switch.
            runCatching {
                val tex = android.graphics.SurfaceTexture(0)
                standbyTex = tex
                standbySurface = android.view.Surface(tex)
                sb.setVideoSurface(standbySurface)
            }
            sb.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { if (standby === sb) standbyFailed() }
            })
            sb.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
            sb.setMediaSource(choiceSource(c), park)
            sb.playWhenReady = false
            sb.prepare()
            standby = sb; standbyChoice = c; standbyIndex = index; standbyParked = park
            standbyStartedAt = SystemClock.uptimeMillis()
            handler.postDelayed(standbyPoll, 50L)
            true
        }.getOrElse { cancelStandby(); false }
    }

    private fun standbyTick() {
        val sb = standby ?: return
        val c = standbyChoice ?: return
        if (isFinishing || isDestroyed) { cancelStandby(); return }
        // Too slow, or nothing left to line up with (paused / ended): the plain switch takes over.
        if (SystemClock.uptimeMillis() - standbyStartedAt > 14_000L ||
            !player.playWhenReady || player.playbackState == Player.STATE_ENDED) { standbyFailed(); return }
        val now = player.currentPosition
        val parked = standbyParked
        // The viewer seeked far away: line up again from the new spot (a few times at most).
        if (now > parked + 3_000L || now < parked - hotLeadMs(c) - 4_000L) {
            if (standbyTries++ < 3 && startHotSwitch(standbyIndex, c)) return
            standbyFailed(); return
        }
        if (sb.playbackState != Player.STATE_READY) { handler.postDelayed(standbyPoll, 40L); return }
        val buffered = sb.bufferedPosition
        val ahead = hotAheadMs(c)
        when {
            // the video has not reached the parked spot yet: wake up right before it
            now < parked - 25L -> handler.postDelayed(standbyPoll, (parked - now - 25L).coerceIn(4L, 40L))
            // standing on the parked spot (within about one frame): switch if the standby has enough data ahead
            now <= parked + 60L ->
                if (buffered >= parked + ahead) swapNow() else handler.postDelayed(standbyPoll, 25L)
            // the video ran past the parked spot (slow start): park again a little ahead of where it is, but only
            // where the standby already holds data; the standby just seeks inside what it has loaded
            else -> {
                if (buffered >= now + 400L + ahead) {
                    standbyParked = now + 400L
                    runCatching { sb.seekTo(standbyParked) }
                }
                handler.postDelayed(standbyPoll, 40L)
            }
        }
    }

    private fun findPlayerView(v: android.view.View): PlayerView? {
        if (v is PlayerView) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findPlayerView(v.getChildAt(i))?.let { return it }
        return null
    }

    /** Picture + sound change hands, on the main thread, in one step. */
    private fun swapNow() {
        val sb = standby ?: return
        val c = standbyChoice ?: return
        val old = player
        handler.removeCallbacks(standbyPoll)
        standby = null; standbyChoice = null
        val ok = runCatching {
            sb.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
            sb.volume = if (old.volume > 0.01f) old.volume else 1f
            old.removeListener(playerListener)
            sb.addListener(playerListener)
            // sound first (the standby is warm: it starts at once), then the old one goes silent
            sb.playWhenReady = true
            old.playWhenReady = false
            old.volume = 0f
            sb.setAudioAttributes(mediaAudioAttributes(), true)      // takes the audio focus over
            player = sb
            findPlayerView(window.decorView)?.player = sb            // the picture follows in the same step
            playerRef.value = sb                                     // ... and the controls
            if (!isLocal) syncNativeSession()
            playUrlState.value = c.video
            true
        }.getOrDefault(false)
        releaseStandbySurface()
        if (ok) {
            runCatching { old.release() }
            standbyTries = 0
            refreshPip()
        } else {
            // the hand-over itself failed: the old player is still the one on screen, go the plain way
            runCatching { sb.release() }
            runCatching { old.volume = 1f; old.addListener(playerListener) }
            player = old
            applyChoice(c, old.currentPosition, true, true)
        }
    }

    private fun standbyFailed() {
        val c = standbyChoice
        cancelStandby()
        if (c != null && !isFinishing && !isDestroyed) applyChoice(c, player.currentPosition, true, player.playWhenReady)
    }

    private fun releaseStandbySurface() {
        runCatching { standbySurface?.release() }
        runCatching { standbyTex?.release() }
        standbySurface = null; standbyTex = null
    }

    private fun cancelStandby() {
        handler.removeCallbacks(standbyPoll)
        val sb = standby
        standby = null; standbyChoice = null
        if (sb != null) runCatching { sb.release() }
        releaseStandbySurface()
    }

    private fun mediaAudioAttributes() = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .setUsage(C.USAGE_MEDIA)
        .build()

    /**
     * One fully configured ExoPlayer: used once at start and once for every seamless quality switch (the standby).
     * Start playing after ~0.8 s of data instead of the 2.5 s default.
     * 1440p / 4K / 8K carry 3-10x the bytes per second of 1080p. 60 s of 8K in memory would run the app out of
     * heap, so those get a short buffer and a hard byte cap; everything else keeps the old long buffer.
     */
    private fun newExo(handleAudioFocus: Boolean = true): ExoPlayer {
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .apply {
                val stdBufBytes = when (PerfTier.tier) {
                    PerfTier.TIER_LOW -> 36 * 1024 * 1024
                    PerfTier.TIER_BALANCED -> 72 * 1024 * 1024
                    else -> 144 * 1024 * 1024
                }
                if (hiResLoad) {
                    setBufferDurationsMs(15_000, 45_000, 550, 1_800)
                    setBackBuffer(if (PerfTier.low) 3_000 else 6_000, false)
                    setTargetBufferBytes(minOf(hiResBytes, stdBufBytes))
                    setPrioritizeTimeOverSizeThresholds(false)
                } else {
                    setBufferDurationsMs(16_000, if (PerfTier.low) 32_000 else 56_000, 400, 1_100)
                    setBackBuffer(if (PerfTier.low) 6_000 else 18_000, true)
                    setTargetBufferBytes(stdBufBytes)
                    setPrioritizeTimeOverSizeThresholds(true)
                }
            }
            .build()
        // A device decoder that fails to start (VP9 / AV1 on weak chips) falls back to the next one
        // instead of ending in a playback error.
        // Asynchronous codec queueing: the decoder is fed from its own thread, so a busy UI thread no longer drops 2K / 4K frames.
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .forceEnableMediaCodecAsynchronousQueueing()
        // HLS / DASH: climb after ~7 s of spare buffer, drop only when really needed, use 75 % of the measured speed.
        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(
            this, androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection.Factory(4_500, 20_000, 20_000, 0.80f)
        )
        return ExoPlayer.Builder(this, renderers).setLoadControl(loadControl)
            .setBandwidthMeter(meter).setTrackSelector(trackSelector).build().apply {
            // Snappy scrubbing on streams: seeks land on the nearest keyframe instead of decoding up to the exact frame.
            setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
            // Adaptive streams never pick a picture bigger than the screen (saves data and decoder load).
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setViewportSizeToPhysicalDisplaySize(this@PlayerActivity, true)
                .build()
            setAudioAttributes(mediaAudioAttributes(), handleAudioFocus)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_NETWORK)
        }
    }

    // ---- Network recovery + slow-connection step-down ----------------------------------------------------------------

    /**
     * Wakes the player up the moment a network is back. Before, three quick retries (1.2 / 2.4 / 3.6 s) were all a dropped
     * connection got: a tunnel or a wifi -> mobile hand-over longer than that ended on an error screen that needed a tap.
     * Healthy playback is never touched: the callback only acts when an error / a failed lookup is actually waiting.
     */
    private fun watchNetwork() {
        if (isLocal || netCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { runOnUiThread { onNetworkBack() } }
        }
        if (runCatching { cm.registerDefaultNetworkCallback(cb) }.isSuccess) netCallback = cb
    }

    private fun unwatchNetwork() {
        val cb = netCallback ?: return
        netCallback = null
        runCatching {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).unregisterNetworkCallback(cb)
        }
    }

    private fun onNetworkBack() {
        if (isFinishing || isDestroyed) return
        if (resolveFailed && canResolve && !resolving.value) {
            resolveFailed = false
            retry()                                   // the page lookup failed while offline: do it again now
            return
        }
        val err = player.playerError ?: return
        val netError = err.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            err.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        if (netError || waitingForNet) {
            waitingForNet = false
            netRetries = 0
            player.prepare()
            player.playWhenReady = true
        }
    }

    /**
     * A resolved stream (plain MP4 / WebM) has no adaptive bitrate: on a connection too slow for its quality it just stalls
     * again and again. Two stalls within 45 s -> one quality down, same second. Never overrides a quality picked by hand,
     * never counts seeks, and stops after 3 steps.
     */
    private fun onPossibleStall() {
        if (isLocal || audioOnly || userPickedQuality || wantHeight > 0 || stallStepDowns >= 3) return
        if (choices.value.size < 2 || !(handoff == 0 || handoff == 3) || resolving.value) return
        val startedAt = SystemClock.elapsedRealtime()
        handler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            // still waiting for data 1.5 s later, and not because of a seek: a real stall
            if (player.playbackState != Player.STATE_BUFFERING || !player.playWhenReady) return@postDelayed
            if (lastSeekAt >= startedAt - 300L) return@postDelayed
            stallAt2 = stallAt1
            stallAt1 = startedAt
            if (stallAt2 > 0L && stallAt1 - stallAt2 <= 45_000L) stepDownForSlowNetwork()
        }, 1_500L)
    }

    private fun stepDownForSlowNetwork() {
        val list = choices.value
        val cur = choiceIndex.intValue
        val now = list.getOrNull(cur) ?: return
        val nowH = MediaTools.plainHeight(now.height)
        if (nowH <= 480) return
        val next = (cur + 1 until list.size).firstOrNull {
            val h = MediaTools.plainHeight(list[it].height)
            h in 1 until nowH
        } ?: return
        stallStepDowns++
        stallAt1 = 0L; stallAt2 = 0L
        lastReadyAt = 0L                              // the new stream's own start-up buffering is not a stall
        val nextQ = MediaTools.playerText(MediaTools.labelForHeight(list[next].height))
        Toast.makeText(this, AppLang.translate("Slow connection, switched to $nextQ"), Toast.LENGTH_SHORT).show()
        selectChoice(next, player.currentPosition)
    }

    private var decoderStepDowns = 0

    private fun stepDownAfterDecoderError() {
        val list = choices.value
        val cur = choiceIndex.intValue
        val now = list.getOrNull(cur) ?: return
        if (decoderStepDowns >= 4) return
        val nowTier = DeviceCaps.tier(now)
        val next = (cur + 1 until list.size).firstOrNull { DeviceCaps.tier(list[it]) in 1 until nowTier } ?: return
        decoderStepDowns++
        val failedQ = MediaTools.labelForHeight(DeviceCaps.tier(now))
        val nextQ = MediaTools.labelForHeight(DeviceCaps.tier(list[next]))
        Toast.makeText(
            this,
            AppLang.translate("This phone can't decode $failedQ, switched to $nextQ"),
            Toast.LENGTH_LONG
        ).show()
        selectChoice(next, player.currentPosition)
    }

    private fun retry() {
        resolveError.value = null
        if (canResolve) { triedResolve = true; startResolve(player.currentPosition) }
        else { player.prepare(); player.playWhenReady = true }
    }

    private fun download() {
        val app = applicationContext as? AurexApp
        val manager = app?.downloads
        if (manager == null) {
            Toast.makeText(this, AppLang.translate("Download manager unavailable"), Toast.LENGTH_SHORT).show(); return
        }
        if (isLocal) {
            Toast.makeText(this, AppLang.translate("Already downloaded"), Toast.LENGTH_SHORT).show(); return
        }
        val page = pageUrl
        val name = resolvedTitle.value.ifBlank { introTitle }.ifBlank { null }
        if (canResolve && page != null) {
            if (!VideoDl.enabled) {
                Toast.makeText(this, AppLang.translate(VideoDl.OFF_HINT), Toast.LENGTH_LONG).show(); return
            }
            // the quality that is playing right now is the quality that gets saved
            val h = choices.value.getOrNull(choiceIndex.intValue)?.height ?: 0
            manager.startUniversal(page, YtQuality.BEST, userAgent, true, title = name, height = h)
        } else {
            manager.start(playUrlState.value, userAgent, null, kindMime(playUrlState.value), referer, title = name)
        }
        Toast.makeText(this, AppLang.translate("Download started"), Toast.LENGTH_SHORT).show()
    }

    // -- Lightweight native MediaSession (headset / Bluetooth / hardware keys, 0 extra libraries) --

    private fun ensureNativeSession() {
        if (mediaSession != null) return
        runCatching {
            mediaSession = MediaSession(this, "AurexPlayer").apply {
                @Suppress("DEPRECATION")
                setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() { if (::player.isInitialized) { player.play(); syncNativeSession() } }
                    override fun onPause() { if (::player.isInitialized) { player.pause(); syncNativeSession() } }
                    override fun onSkipToNext() {
                        if (::player.isInitialized && player.hasNextMediaItem()) {
                            player.seekToNextMediaItem(); player.play(); syncNativeSession()
                        }
                    }
                    override fun onSkipToPrevious() {
                        if (::player.isInitialized) {
                            if (player.currentPosition > 3_000L || !player.hasPreviousMediaItem()) player.seekTo(0)
                            else player.seekToPreviousMediaItem()
                            syncNativeSession()
                        }
                    }
                    override fun onSeekTo(pos: Long) {
                        if (::player.isInitialized) { player.seekTo(pos.coerceAtLeast(0L)); syncNativeSession() }
                    }
                    override fun onStop() { if (::player.isInitialized) player.pause() }
                })
                isActive = true
            }
            syncNativeSession()
        }
    }

    private fun syncNativeSession() {
        val ms = mediaSession ?: return
        if (!::player.isInitialized) return
        runCatching {
            val playing = wantsPlay()
            val pos = player.currentPosition.coerceAtLeast(0L)
            ms.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
                    )
                    .setState(
                        if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        pos,
                        if (playing) player.playbackParameters.speed else 0f
                    )
                    .build()
            )
        }
    }

    // -- Picture in picture ----------------------------------------------------------------------

    private fun wantsPlay(): Boolean =
        player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE

    /** Left PiP button: the window goes away, the sound keeps playing (onStop does not pause while this is set). */
    private var audioOnly = false
    private fun enterAudioOnly() {
        audioOnly = true
        if (!player.playWhenReady) player.play()
        moveTaskToBack(true)
    }

    /** Params are re-sent only when something visible changed (no per-event IPC, no window flicker). */
    private var lastPipSig = ""
    private fun refreshPip() {
        if (!::player.isInitialized || isDestroyed) return
        val sz = player.videoSize
        val sig = "${wantsPlay()}|${player.hasNextMediaItem()}|${sz.width}x${sz.height}|$autoPip"
        if (sig == lastPipSig) return
        lastPipSig = sig
        runCatching { setPictureInPictureParams(buildPipParams()) }
    }

    /** Where the letter-boxed video sits in the window: the system animates exactly from there into the PiP window. */
    private fun pipSourceRect(): android.graphics.Rect? {
        if (!::player.isInitialized) return null
        val dv = window?.decorView ?: return null
        val w = dv.width; val h = dv.height
        val vs = player.videoSize
        if (w <= 0 || h <= 0 || vs.width <= 0 || vs.height <= 0) return null
        val ar = vs.width.toFloat() / vs.height.toFloat()
        val rw: Int; val rh: Int
        if (ar > w.toFloat() / h) { rw = w; rh = (w / ar).toInt().coerceAtLeast(1) }
        else { rh = h; rw = (h * ar).toInt().coerceAtLeast(1) }
        val l = (w - rw) / 2; val t = (h - rh) / 2
        return android.graphics.Rect(l, t, l + rw, t + rh)
    }

    /**
     * Toggle by INTENT (playWhenReady), not by isPlaying: while buffering isPlaying is false, so the
     * old check turned every "pause" tap in the PiP window into a no-op play().
     */
    private fun togglePlayPause() {
        if (!::player.isInitialized) return
        if (wantsPlay()) player.pause()
        else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
        refreshPip()
    }

    private fun buildPipParams(): PictureInPictureParams {
        val playing = wantsPlay()
        val size = player.videoSize
        val aspect = PipSupport.aspect(size.width, size.height)
        val actions = PipSupport.buildThree(
            this, playing, player.hasNextMediaItem(),
            ACTION_PIP_AUDIO, ACTION_PIP_TOGGLE, ACTION_PIP_NEXT, 10
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(aspect)
            .setActions(actions)
            .apply {
                pipSourceRect()?.let { setSourceRectHint(it) }
                PipSupport.applyMeta(this, resolvedTitle.value.ifBlank { introTitle }, pageUrl?.let { hostOf(it) })
                if (Build.VERSION.SDK_INT >= 31) {
                    setAutoEnterEnabled(autoPip && playing)
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()
    }

    /** Android 12+: hide the controls the moment the system starts the PiP animation. */
    @android.annotation.TargetApi(31)
    override fun onPictureInPictureUiStateChanged(uiState: android.app.PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(uiState)
        if (uiState.isTransitioningToPip) {
            pipModeState.value = true
            window?.decorView?.postDelayed({ if (!isDestroyed) pipModeState.value = isInPictureInPictureMode }, 1500L)
        }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        if (!wantsPlay()) {
            Toast.makeText(this, AppLang.translate("Play the video first to enter Picture-in-Picture"), Toast.LENGTH_SHORT).show()
            return
        }
        pipRequested = runCatching { enterPictureInPictureMode(buildPipParams()) }.getOrDefault(false)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (::player.isInitialized && autoPip && wantsPlay() && Build.VERSION.SDK_INT >= 26 &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            pipRequested = runCatching { enterPictureInPictureMode(buildPipParams()) }.getOrDefault(false)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipModeState.value = isInPictureInPictureMode
        if (!isInPictureInPictureMode) {
            pipRequested = false
            // Closed with the X -> stop and leave, like every video app.
            if (!audioOnly && lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED) {
                if (::player.isInitialized) runCatching { player.pause() }
                finish(); return
            }
        }
        refreshPip()
    }

    /**
     * Identity of THE video (not of the player), used to remember where it stopped. Every video must
     * get its own id, otherwise a new video would open at the previous video's position.
     */
    private fun resumeId(url: String, page: String?, resolvable: Boolean, local: Boolean, title: String): String = when {
        // a downloaded file: its path + size (a re-download under the same name is a different video)
        local -> url + "|" + (if (url.startsWith("content:", true)) externalContentSize else (runCatching { java.io.File(Uri.parse(url).path.orEmpty()).length() }.getOrNull() ?: 0L))
        // YouTube & co: the page address names the video
        resolvable && !page.isNullOrBlank() -> page
        // blob: links (and our "blob:aurex-page" placeholder) are the same for every video: page + title tell them apart
        url.startsWith("blob:", true) -> page.orEmpty() + "|" + title
        // plain stream / file: the link without its changing tokens, on its page
        else -> page.orEmpty() + "|" + url.substringBefore('#').substringBefore('?')
    }

    private fun savePosition() {
        if (!::player.isInitialized) return
        val d = player.duration
        val p = player.currentPosition
        val prefs = getSharedPreferences("liquid", Context.MODE_PRIVATE)
        if (d != C.TIME_UNSET && d > 0 && p > 5_000 && p < d - 8_000) prefs.edit().putLong(posKey, p).apply()
        else prefs.edit().remove(posKey).apply()
    }

    /** Hands the final position / play state back to the page video, once. */
    private fun publishSync() {
        if (syncSent || !syncBack || !::player.isInitialized) return
        syncSent = true
        // Closed before the hand-over finished: the page was never paused, so just leave it playing.
        // (Or the player never loaded anything: its position means nothing, the page just continues.)
        if (handoff == 1 || handoff == 2 || (handoff == 3 && !handoffClean)) { PlayerSync.release(-1L, true, false); return }
        val d = player.duration
        val ended = player.playbackState == Player.STATE_ENDED
        val pos = if (ended && d != C.TIME_UNSET && d > 0) d else player.currentPosition
        PlayerSync.release(pos, wantsPlay(), syncSeek)
    }

    override fun finish() {
        publishSync()
        super.finish()
        noTransition()
    }

    override fun onStart() {
        super.onStart()
        audioOnly = false   // back on screen: video mode again
        PipSupport.resetSent()
    }

    override fun onStop() {
        super.onStop()
        if (standby != null) standbyFailed()      // leaving the screen: finish the switch the plain way, free the standby
        savePosition()
        if (::player.isInitialized && !audioOnly && (Build.VERSION.SDK_INT < 26 || (!isInPictureInPictureMode && !pipRequested))) {
            runCatching { player.pause() }
        }
    }

    override fun onDestroy() {
        fireFirstFrame()
        publishSync()
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(pipReceiver) }
        unwatchNetwork()
        if (cardUp) {
            cardUp = false
            MediaNotif.detachLocal(localCtl)
            BrowserMediaService.live?.dismissNow()
        }
        cancelStandby()
        runCatching { mediaSession?.isActive = false; mediaSession?.release() }
        if (::player.isInitialized) runCatching { player.release() }
        super.onDestroy()
    }

    companion object {
        private val SITE_HEADERS = setOf("referer", "origin", "cookie", "user-agent", "authorization")
        private const val ACTION_PIP_TOGGLE = "com.aurex.browser.PLAYER_PIP_TOGGLE"
        private const val ACTION_PIP_AUDIO = "com.aurex.browser.PLAYER_PIP_AUDIO"
        private const val ACTION_PIP_NEXT = "com.aurex.browser.PLAYER_PIP_NEXT"

        // The in-app (inline) player that started this one stays on screen until this player has its first
        // picture (or failed / closed / 25 s passed): the browser page and a portrait flip never show in between.
        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var firstFrameCb: (() -> Unit)? = null
        private val firstFrameTimeout = Runnable { fireFirstFrame() }

        fun whenFirstFrame(cb: () -> Unit) {
            fireFirstFrame()                     // an older, never-finished wait is released first
            firstFrameCb = cb
            mainHandler.postDelayed(firstFrameTimeout, 25_000L)
        }

        fun fireFirstFrame() {
            val cb = firstFrameCb ?: return
            firstFrameCb = null
            mainHandler.removeCallbacks(firstFrameTimeout)
            cb()
        }
    }
}

// ----------------------------------------------------------------------------------------------
// UI
// ----------------------------------------------------------------------------------------------

private const val AUTO_HIDE_MS = 3_200L
private val PLAYER_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
private val SLEEP_OPTIONS = listOf(0, 5, 10, 15, 30, 45, 60, 90)
private val BOOST_OPTIONS = listOf(0, 25, 50, 75, 100)
/**
 * The picture modes the resize button cycles through (one tap = next mode). Only the two that really differ and keep
 * the picture undistorted: Fit (the whole video, bars where needed) and Crop (fills the screen, the edges are cut).
 * "Fill (stretch)" bends the picture, and "Fit width" / "Fit height" always end up the same as Fit or Crop depending
 * on how the phone is held, so they are not offered.
 */
private val ASPECTS = listOf(
    "Fit" to AspectRatioFrameLayout.RESIZE_MODE_FIT,
    "Crop" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM
)

private enum class PlayerPanel { SPEED, QUALITY, AUDIO, SUBS, SLEEP, BOOST, MORE }

private class TrackOpt(val label: String, val group: Tracks.Group, val index: Int, val selected: Boolean, val sort: Int)

private fun speedLabel(s: Float): String =
    if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"

private fun trackOptions(tracks: Tracks, type: Int): List<TrackOpt> {
    val out = ArrayList<TrackOpt>()
    tracks.groups.forEach { g ->
        if (g.type != type) return@forEach
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            val f = g.getTrackFormat(i)
            val tier = if (type == C.TRACK_TYPE_VIDEO) DeviceCaps.tierOf(f.width, f.height) else 0
            if (type == C.TRACK_TYPE_VIDEO && tier > 0 && !DeviceCaps.supportsHeight(tier)) continue
            val label = if (type == C.TRACK_TYPE_VIDEO) {
                if (tier > 0) MediaTools.heightText(tier)
                else "Video ${out.size + 1}"
            } else {
                f.label?.takeIf { it.isNotBlank() }
                    ?: f.language?.let { l -> runCatching { java.util.Locale(l).displayLanguage }.getOrNull() }?.takeIf { it.isNotBlank() }
                    ?: "Track ${out.size + 1}"
            }
            out.add(TrackOpt(label, g, i, g.isTrackSelected(i), if (tier > 0) tier else f.height))
        }
    }
    return if (type == C.TRACK_TYPE_VIDEO) {
        out.groupBy { it.sort }
            .map { (_, list) -> list.firstOrNull { it.selected } ?: list.first() }
            .sortedByDescending { it.sort }
    } else out
}

private fun applyTrack(player: Player, type: Int, opt: TrackOpt?) {
    val b = player.trackSelectionParameters.buildUpon()
    if (opt == null) {
        b.clearOverridesOfType(type)
        if (type == C.TRACK_TYPE_TEXT) b.setTrackTypeDisabled(type, true)
    } else {
        b.setTrackTypeDisabled(type, false)
        b.setOverrideForType(TrackSelectionOverride(opt.group.mediaTrackGroup, opt.index))
    }
    player.trackSelectionParameters = b.build()
}

private fun Modifier.glassTouchShield(onTouch: () -> Unit): Modifier = composed {
    if (!LocalWindowInteractive.current) return@composed this
    this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.pressed || it.previousPressed }) onTouch()
                }
            }
        }
        .pointerInput(Unit) { detectTapGestures { } }
}

/**
 * Simple, consistent Apple iOS 27 Liquid Glass surface for all video player buttons & pills:
 * uses the exact same `.liquidGlass(...)` material, live blur radius, and subtle specular edge
 * as the 3-dot Menu and Download Quality windows (no extra cyan glow or thick borders).
 */
private fun Modifier.playerLiquidGlass(
    shape: Shape = Capsule,
    active: Boolean = false,
    level: Float = 1.05f
): Modifier = composed {
    val accent = LocalGlassStyle.current.accent
    this
        .liquidGlass(
            shape = shape,
            level = level,
            extraOpacity = 0.04f,
            reactive = false,
            optical = true
        )
        .then(if (active) Modifier.background(accent.copy(alpha = 0.20f), shape) else Modifier)
}

/** Perfect circular Apple iOS 27 Liquid Glass icon orb used consistently across top bar, rotate, audio, CC & unlock. */
@Composable
private fun PlayerGlassOrb(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean = false,
    size: Dp = 38.dp,
    iconSize: Dp = 18.5.dp,
    haptic: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = LocalGlassStyle.current.accent
    val iconTint = if (active) androidx.compose.ui.graphics.lerp(accent, Color.White, 0.42f) else Color.White
    GlowPress(
        onClick = onClick,
        haptic = haptic,
        shape = CircleShape,
        modifier = modifier
            .size(size)
            .playerLiquidGlass(CircleShape, active = active)
    ) {
        Icon(icon, contentDescription, tint = iconTint, modifier = Modifier.size(iconSize))
    }
}

/** Unified Apple iOS 27 Liquid Glass capsule chip (Speed, Quality, Unlock) matching PlayerGlassOrb identically. */
@Composable
private fun PlayerChip(
    label: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    active: Boolean = false,
    badge: String? = null,
    badgeColor: Color? = null,
    haptic: Boolean = true,
    onClick: () -> Unit
) {
    val accent = LocalGlassStyle.current.accent
    val brightAccent = androidx.compose.ui.graphics.lerp(accent, Color.White, 0.44f)
    val badgeTint = badgeColor ?: brightAccent
    val fgTint = if (active) brightAccent else Color.White
    GlowPress(
        onClick = onClick,
        haptic = haptic,
        modifier = Modifier
            .height(34.dp)
            .playerLiquidGlass(Capsule, active = active),
        shape = Capsule
    ) {
        Row(Modifier.padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            if (badge != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(badgeTint.copy(alpha = 0.22f))
                        .border(1.2.dp, badgeTint.copy(alpha = 0.90f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 4.5.dp, vertical = 1.5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        badge,
                        color = androidx.compose.ui.graphics.lerp(badgeTint, Color.White, 0.28f),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 11.sp,
                        maxLines = 1
                    )
                }
            } else if (icon != null) {
                Icon(icon, null, tint = fgTint, modifier = Modifier.size(16.5.dp))
            }
            if ((icon != null || badge != null) && label != null) Spacer(Modifier.width(5.5.dp))
            if (label != null) {
                Text(
                    label,
                    color = if (badge != null) Color.White.copy(alpha = 0.94f) else fgTint,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

/** One option inside the player's floating menu. [sub] is a small value shown on the right. */
@Composable
private fun PlayerStaggerRow(index: Int, content: @Composable () -> Unit) {
    if (PerfTier.low) { content(); return }
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index.coerceAtMost(6) * 10L))
        p.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 680f))
    }
    Box(
        Modifier.graphicsLayer {
            val v = p.value
            alpha = v.coerceIn(0f, 1f)
            translationY = (1f - v) * 8.dp.toPx()
        }
    ) { content() }
}

/** Short resolution tag matching QualityUi.kt (8K / UHD / QHD / FHD / HD / SD / Low). */
private fun playerQualityTag(label: String): String {
    val clean = label.trim().uppercase()
    if (clean == "AUTO") return "SMART"
    val base = clean.substringBefore(' ')
    val h = base.removeSuffix("P").toIntOrNull() ?: when {
        base == "8K" -> 4320
        base == "4K" -> 2160
        base == "2K" -> 1440
        else -> 0
    }
    return when {
        h >= 4320 -> "8K"
        h >= 2160 -> "UHD"
        h >= 1440 -> "QHD"
        h >= 1080 -> "FHD"
        h >= 720 -> "HD"
        h >= 480 -> "SD"
        h > 0 -> "Low"
        else -> ""
    }
}

/**
 * Unified floating popover header matching QualitySheetHost (QualityUi.kt) pixel-for-pixel:
 * glossy 25dp tinted orb (or back orb) + bold 13sp title + 10sp subtitle + 24dp close orb.
 */
@Composable
private fun PlayerSheetHeader(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClose: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    val accent = LocalGlassStyle.current.accent
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            GlassIconButton(
                AppleIcons.ArrowBack, "Back",
                onClick = onBack,
                size = 25.dp, iconSize = 13.5.dp
            )
        } else {
            Box(
                Modifier
                    .size(25.dp)
                    .tintedGlass(CircleShape, accent, swell = 0.06f),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(13.5.dp))
            }
        }
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                color = TextSecondary, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(5.dp))
        GlassIconButton(
            AppleIcons.Close, "Close",
            onClick = onClose,
            size = 24.dp, iconSize = 12.dp
        )
    }
}

/**
 * Compact 2-column smoked Liquid Glass option chip matching QualityGridChip (QualityUi.kt):
 * smoked dark glassTile when unselected; vibrant accent glass + crisp rim when selected.
 */
@Composable
private fun PlayerOptionChip(
    label: String,
    tag: String = "",
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = LocalGlassStyle.current.accent
    val brightAccent = androidx.compose.ui.graphics.lerp(accent, Color.White, 0.38f)
    val shape = RoundedCornerShape(13.dp)
    val rim = Brush.verticalGradient(
        0f to accent.copy(alpha = 0.72f),
        1f to accent.copy(alpha = 0.22f)
    )
    GlowPress(
        onClick = onClick,
        shape = shape,
        modifier = modifier.then(
            if (selected) {
                Modifier
                    .clip(shape)
                    .background(Color(0xFF0A0D16).copy(alpha = 0.50f), shape)
                    .background(
                        Brush.linearGradient(listOf(accent.copy(alpha = 0.34f), accent.copy(alpha = 0.16f))),
                        shape
                    )
                    .border(0.95.dp, rim, shape)
            } else {
                Modifier.glassTile(shape, 0.11f)
            }
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                label,
                color = Color.White,
                fontSize = 12.5.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (selected) {
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier.size(16.dp).background(accent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(AppleIcons.Check, null, tint = Color.White, modifier = Modifier.size(10.5.dp))
                }
            } else if (tag.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    tag,
                    color = brightAccent,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Menu row matching BrowserMenu's MenuRow (Ui.kt) 1-for-1:
 * 30dp CircleShape smoked glassTile icon orb, luminous sky-blue tint, 13.5sp label, dark-backed Capsule badge.
 */
@Composable
private fun PanelRow(
    label: String,
    selected: Boolean,
    sub: String? = null,
    icon: ImageVector? = null,
    chevron: Boolean = false,
    onClick: () -> Unit
) {
    val accent = LocalGlassStyle.current.accent
    val brightAccent = androidx.compose.ui.graphics.lerp(accent, Color.White, 0.34f)
    val rowShape = RoundedCornerShape(13.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .then(
                if (selected && !chevron) Modifier
                    .background(accent.copy(alpha = 0.22f), rowShape)
                    .border(0.75.dp, brightAccent.copy(alpha = 0.45f), rowShape)
                else Modifier
            )
            .clickableHaptic(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            PanelIconTile(icon, selected || !chevron)
            Spacer(Modifier.width(10.dp))
        } else {
            Spacer(Modifier.width(4.dp))
        }
        Text(
            label,
            color = TextPrimary,
            fontSize = 13.5.sp,
            fontWeight = if (selected && !chevron) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (sub != null) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .clip(Capsule)
                    .background(
                        if (selected) accent else Color(0xFF0A0D16).copy(alpha = 0.56f),
                        Capsule
                    )
                    .padding(horizontal = 7.dp, vertical = 2.5.dp)
            ) {
                Text(
                    sub,
                    color = if (selected) Color.White else TextPrimary,
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                )
            }
        }
        if (chevron) {
            Spacer(Modifier.width(4.dp))
            Icon(AppleIcons.ChevronRight, null, tint = TextSecondary, modifier = Modifier.size(15.dp))
        } else if (selected && icon == null) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(18.dp).background(accent, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(AppleIcons.Check, null, tint = Color.White, modifier = Modifier.size(11.5.dp))
            }
        }
    }
}

@Composable
private fun PanelIconTile(icon: ImageVector, active: Boolean) {
    val accent = LocalGlassStyle.current.accent
    val brightAccent = androidx.compose.ui.graphics.lerp(accent, Color.White, 0.48f)
    Box(
        Modifier.size(31.dp).glassTile(CircleShape, 0.20f, if (active) accent else Color.White),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = if (active) brightAccent else TextPrimary, modifier = Modifier.size(17.dp))
    }
}

/** Thin separator matching QualityUi.kt & BrowserMenu. */
@Composable
private fun PanelDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp).height(0.5.dp).background(Separator))
}

@Composable
internal fun PlayerScreen(
    player: Player,
    title: String,
    pageUrl: String?,
    playUrl: String,
    pipMode: Boolean,
    resolving: Boolean,
    knownDurationMs: Long = 0L,
    resolveError: String?,
    canResolve: Boolean,
    isLocal: Boolean,
    choices: List<YtDlpEngine.StreamChoice>,
    choiceIndex: Int,
    onChoice: (Int) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onPip: () -> Unit,
    onDownload: () -> Unit,
    // true from the tap on a quality until the new stream's first picture: spinner at once, last frame dimmed, no jitter
    switching: Boolean = false,
    // overlay = drawn over the page's own <video> (WebVideoPlayer): transparent, no PlayerView, fit / zoom go to the page
    overlay: Boolean = false,
    onFit: ((Int, Float) -> Unit)? = null
) {
    // A seamless quality switch hands the screen to another ExoPlayer: every handler / loop below reads the current one.
    @Suppress("NAME_SHADOWING")
    val player by rememberUpdatedState(player)
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val maxVolume = remember { audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 1 }
    val accent = LocalGlassStyle.current.accent

    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var playWhenReady by remember { mutableStateOf(player.playWhenReady) }
    val knownDur = rememberUpdatedState(knownDurationMs)
    var duration by remember {
        mutableLongStateOf(player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: knownDurationMs)
    }
    // A quality switch opens at the second the page was paused on: the time and the bar are right from the first frame
    // (they started at 0 and the thumb swept across the bar to catch up).
    var position by remember {
        mutableLongStateOf(
            if (overlay) 0L
            else (context as? android.app.Activity)?.intent?.getLongExtra("startMs", 0L)?.coerceAtLeast(0L) ?: 0L
        )
    }
    var bufferedPosition by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    var holding by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }
    var error by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var unlockVisible by remember { mutableStateOf(false) }
    // Where the lock button is on screen: the unlock button appears exactly there, so it does not jump to the side.
    var lockAt by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var rootAt by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    // Quality switch from the in-app player: that player's picture sat lower on screen (its page starts under the status
    // bar). The full player used to show it dead-centre, so the picture jumped up the moment 4K / 2K was picked. Now it
    // starts exactly where the old picture was and STAYS there: a quality switch changes the quality and nothing else
    // (no jump, no glide to the centre afterwards).
    val srcCenterY = remember {
        if (overlay) -1f else (context as? android.app.Activity)?.intent?.getFloatExtra("srcCenterY", -1f) ?: -1f
    }
    val hostView = LocalView.current
    var shiftPx by remember { mutableFloatStateOf(0f) }
    var shiftSet by remember { mutableStateOf(false) }
    // screen shape (wide / tall) when the shift was taken, and now: a different shape (rotation) or the small picture-in-picture
    // window cannot use that pixel offset
    var shiftWide by remember { mutableStateOf(false) }
    var rootWide by remember { mutableStateOf(false) }
    var hud by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    // Fit / Crop is remembered SEPARATELY for landscape and for portrait (and kept for the next video too):
    // choosing Crop in landscape does not touch the portrait choice, and coming back to landscape restores Crop.
    val aspectPrefs = remember { context.getSharedPreferences("liquid", android.content.Context.MODE_PRIVATE) }
    var aspectLand by remember { mutableIntStateOf(aspectPrefs.getInt("aspectLand", 0).coerceIn(0, ASPECTS.lastIndex)) }
    var aspectPort by remember { mutableIntStateOf(aspectPrefs.getInt("aspectPort", 0).coerceIn(0, ASPECTS.lastIndex)) }
    val screenLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val aspectIndex = if (screenLandscape) aspectLand else aspectPort
    var sleepMinutes by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf<PlayerPanel?>(null) }
    var audioBoostPct by remember { mutableIntStateOf(0) }
    var nightMode by remember { mutableStateOf(aspectPrefs.getBoolean("playerNightMode", false)) }
    var showRemaining by remember { mutableStateOf(false) }
    var dblTapSide by remember { mutableIntStateOf(0) } // -1 left, +1 right, 0 hidden
    var dblTapAccSec by remember { mutableIntStateOf(0) }
    var dblTapToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(dblTapToken) {
        if (dblTapToken == 0) return@LaunchedEffect
        delay(680)
        dblTapSide = 0
        dblTapAccSec = 0
    }

    // Hardware Audio Boost (100% -> 200%) cleanly attached to ExoPlayer's audio session
    DisposableEffect(player, audioBoostPct) {
        val exo = player as? ExoPlayer
        val sid = exo?.audioSessionId ?: 0
        val enhancer = if (audioBoostPct > 0 && sid > 0 && !overlay) {
            runCatching {
                LoudnessEnhancer(sid).apply {
                    setTargetGain(audioBoostPct * 15) // up to +1500 mB (+15 dB)
                    enabled = true
                }
            }.getOrNull()
        } else null
        onDispose {
            runCatching { enhancer?.enabled = false; enhancer?.release() }
        }
    }
    // The picture the in-app player showed when it handed over to this one (a quality switch such as 4K): it stays on
    // screen until the new stream renders its first frame, so the swap never shows a black screen ('player opens again').
    val bridge = remember { if (overlay) null else PlayerSync.takeBridge() }
    val bridgeImage = remember(bridge) { bridge?.asImageBitmap() }
    var bridgeUp by remember { mutableStateOf(bridge != null) }
    LaunchedEffect(bridgeUp) {
        // backstop: never stuck on a frozen picture (first-frame callback missed, or the stream never starts)
        var waited = 0L
        while (bridgeUp && waited < 30_000L) {
            delay(250L); waited += 250L
            if (player.isPlaying && player.videoSize.width > 0) bridgeUp = false
        }
        bridgeUp = false
    }
    LaunchedEffect(resolveError) { if (resolveError != null) bridgeUp = false }
    var tracks by remember { mutableStateOf(player.currentTracks) }
    var videoH by remember { mutableIntStateOf(player.videoSize.height) }
    var videoW by remember { mutableIntStateOf(player.videoSize.width) }
    var lastTouchAt by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    fun touch() { lastTouchAt = SystemClock.uptimeMillis() }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
            override fun onPlayWhenReadyChanged(play: Boolean, reason: Int) { playWhenReady = play }
            override fun onPlaybackStateChanged(state: Int) {
                player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { duration = it }   // keep the old length while the new stream loads
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) controlsVisible = true
                if (state == Player.STATE_READY) error = null
            }
            override fun onTracksChanged(t: Tracks) { tracks = t }
            // a new stream was set (automatic step down after a decoder error, quality switch): the old error no longer applies
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && !timeline.isEmpty) error = null
            }
            override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) { videoH = v.height; videoW = v.width }
            override fun onRenderedFirstFrame() { bridgeUp = false }
            override fun onPlayerError(e: PlaybackException) {
                buffering = false
                error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "The site refused this stream (it is protected or has expired)."
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                        "This link is not a playable video file."
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "Network problem. Check the connection and retry."
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                        "This phone can't decode this video (an unsupported codec, or too sharp). " +
                            "Play a lower quality, such as 4K or FHD."
                    else -> e.errorCodeName
                }
            }
        }
        val pl = player
        pl.addListener(l)
        // a swapped-in player is already playing: catch the controls up with it
        isPlaying = pl.isPlaying; playWhenReady = pl.playWhenReady
        buffering = pl.playbackState == Player.STATE_BUFFERING
        tracks = pl.currentTracks; videoH = pl.videoSize.height; videoW = pl.videoSize.width
        speed = pl.playbackParameters.speed
        onDispose { pl.removeListener(l) }
    }
    LaunchedEffect(resolving, resolveError) { if (resolving) error = null }
    LaunchedEffect(sleepMinutes) {
        if (sleepMinutes <= 0) return@LaunchedEffect
        delay(sleepMinutes * 60_000L)
        player.pause(); hud = "Sleep timer - paused"; sleepMinutes = 0
    }
    LaunchedEffect(hud) { if (hud != null) { delay(900); hud = null } }
    LaunchedEffect(unlockVisible) { if (unlockVisible) { delay(2500); unlockVisible = false } }
    LaunchedEffect(controlsVisible, isPlaying, locked, buffering, error, dragging, panel) {
        if (!controlsVisible || locked || !isPlaying || buffering || error != null || dragging || panel != null) return@LaunchedEffect
        lastTouchAt = SystemClock.uptimeMillis()
        while (isActive) {
            val left = AUTO_HIDE_MS - (SystemClock.uptimeMillis() - lastTouchAt)
            if (left <= 0L) { controlsVisible = false; break }
            delay(left)
        }
    }

    fun brightnessNow(): Float {
        val b = activity?.window?.attributes?.screenBrightness ?: -1f
        return if (b < 0f) 0.5f else b
    }
    fun setBrightness(v: Float) {
        val w = activity?.window ?: return
        val a = w.attributes; a.screenBrightness = v.coerceIn(0.02f, 1f); w.attributes = a
    }
    fun volumeNow(): Float = (audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat() / maxVolume
    fun setVolume(v: Float) {
        val am = audio ?: return
        val i = (v.coerceIn(0f, 1f) * maxVolume).roundToInt().coerceIn(0, maxVolume)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, i, 0) }
        if (i > 0 && player.volume < 0.01f) player.volume = 1f
    }
    fun seekBy(delta: Long) {
        val d = player.duration
        var t = player.currentPosition + delta
        t = if (d != C.TIME_UNSET && d > 0L) t.coerceIn(0L, d) else t.coerceAtLeast(0L)
        player.seekTo(t); position = t
    }
    fun togglePlay() {
        val wants = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        if (wants) player.pause()
        else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    val barsVisible = controlsVisible && !locked && error == null && !resolving
    // Poll timeline ONLY while bars are visible (0 recompositions/sec while watching full-screen video!)
    LaunchedEffect(barsVisible, pipMode) {
        if (!barsVisible || pipMode) return@LaunchedEffect
        while (isActive) {
            if (!dragging) {
                val d = player.duration
                if (d != C.TIME_UNSET && d > 0) duration = d
                else if (duration <= 0L && knownDur.value > 0L) duration = knownDur.value
                val p = player.currentPosition.coerceAtLeast(0)
                if (!(p == 0L && position > 1_000L && player.playbackState != Player.STATE_READY)) position = p
            }
            bufferedPosition = player.bufferedPosition.coerceAtLeast(0)
            delay(250)
        }
    }
    val videoOpts = remember(tracks) { trackOptions(tracks, C.TRACK_TYPE_VIDEO) }
    val audioOpts = remember(tracks) { trackOptions(tracks, C.TRACK_TYPE_AUDIO) }
    val textOpts = remember(tracks) { trackOptions(tracks, C.TRACK_TYPE_TEXT) }
    val subsOff = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || textOpts.none { it.selected }
    val curChoice = choices.getOrNull(choiceIndex)
    val qualityLabel = when {
        choices.size > 1 && curChoice != null ->
            if (curChoice.height > 0) MediaTools.heightText(DeviceCaps.tier(curChoice))
            else MediaTools.playerText(curChoice.label)
        videoH > 0 -> MediaTools.heightText(DeviceCaps.tierOf(videoW, videoH))
        else -> "Auto"
    }
    val isAutoQuality = qualityLabel.startsWith("Auto", ignoreCase = true)
    // Single canonical resolution class (identical in Quality sheet, Current header & bottom badge!):
    // - Manual pick: always matches the picked StreamChoice tier (2K -> 2K, 4K -> 4K)
    // - Auto mode: reflects the live playing frame's canonical tier via DeviceCaps.tierOf(videoW, videoH)
    val activeTier = when {
        !isAutoQuality && curChoice != null && curChoice.height > 0 -> DeviceCaps.tier(curChoice)
        videoH > 0 -> DeviceCaps.tierOf(videoW, videoH)
        curChoice != null && curChoice.height > 0 -> DeviceCaps.tier(curChoice)
        else -> 0
    }
    val qualityBadge = if (activeTier > 0) MediaTools.heightText(activeTier)
                       else qualityLabel.removePrefix("Auto").trim().ifBlank { "AUTO" }
    // Single-source chip label: NEVER repeats a second resolution next to the badge!
    val qualityChipLabel: String? = when {
        isAutoQuality && !qualityBadge.equals("AUTO", true) -> "Auto"
        qualityLabel.contains("HDR", ignoreCase = true) -> "HDR"
        else -> null
    }
    val qualityColor: Color? = when (qualityBadge) {
        "144p", "240p" -> Color(0xFFFF5A52)
        "360p", "480p" -> Color(0xFFFFB340)
        "720p" -> Color(0xFF3AE068)
        "1080p" -> Color(0xFF5AC8FA)
        "2K" -> Color(0xFF409CFF)
        "4K" -> Color(0xFF7D7AFF)
        "8K" -> Color(0xFFDA8FFF)
        else -> null
    }

    LaunchedEffect(aspectIndex, zoom) { if (overlay) onFit?.invoke(aspectIndex, zoom) }

    val fitMode = ASPECTS[aspectIndex].second == AspectRatioFrameLayout.RESIZE_MODE_FIT
    // Dedicated live GPU Liquid Glass backdrop for BOTH PlayerActivity & InlinePlayer:
    //  - Never reuses WebView's software `pageBackdrop` (Chromium paints <video> solid black on software Canvas!).
    //  - Standalone PlayerActivity: async PixelCopy from Video SurfaceView (zero UI feedback, 5 Hz live while a
    //    floating window is open so moving video refracts continuously through the Liquid Glass!).
    //  - InlinePlayer (WebView <video>): clean async PixelCopy from Activity.window while controls are up and no
    //    floating panel is drawn (zero feedback loop) so the real playing video refracts vividly from Frame 0!
    val ownedPlayerBackdrop = remember { GlassBackdrop() }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    val copyHandler = remember { Handler(Looper.getMainLooper()) }
    val copyLoc = remember { IntArray(2) }
    val copyPool = remember { arrayOfNulls<Bitmap>(2) }
    var copySlot by remember { mutableIntStateOf(0) }
    var copyInFlight by remember { mutableStateOf(false) }
    val hudActive = hud != null
    val errActive = resolveError != null || error != null

    DisposableEffect(ownedPlayerBackdrop) {
        onDispose {
            ownedPlayerBackdrop.setCustomFrame(null)
            copyPool[0] = null
            copyPool[1] = null
        }
    }

    fun requestVideoGlassFrame() {
        if (copyInFlight || PerfTier.low || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!overlay) {
            val pv = playerViewRef
            val vs = pv?.videoSurfaceView
            if (vs is SurfaceView && vs.holder.surface.isValid && vs.width > 16 && vs.height > 16) {
                runCatching {
                    val aspect = vs.width.toFloat() / vs.height.toFloat().coerceAtLeast(1f)
                    val bw = if (aspect >= 1f) 200 else (200f * aspect).roundToInt().coerceIn(80, 200)
                    val bh = if (aspect >= 1f) (200f / aspect).roundToInt().coerceIn(80, 200) else 200
                    val slot = copySlot and 1
                    copySlot = slot xor 1
                    val existing = copyPool[slot]
                    val bmp = if (existing != null && !existing.isRecycled && existing.width == bw && existing.height == bh) {
                        existing
                    } else {
                        Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565).also { copyPool[slot] = it }
                    }
                    vs.getLocationInWindow(copyLoc)
                    val bounds = androidx.compose.ui.geometry.Rect(
                        copyLoc[0].toFloat(), copyLoc[1].toFloat(),
                        (copyLoc[0] + vs.width).toFloat(), (copyLoc[1] + vs.height).toFloat()
                    )
                    copyInFlight = true
                    PixelCopy.request(vs, bmp, { res ->
                        copyInFlight = false
                        if (res == PixelCopy.SUCCESS) {
                            ownedPlayerBackdrop.setCustomFrame(bmp, bounds)
                        }
                    }, copyHandler)
                }.onFailure { copyInFlight = false }
            } else if (vs is TextureView && vs.isAvailable && vs.width > 16 && vs.height > 16) {
                runCatching {
                    val aspect = vs.width.toFloat() / vs.height.toFloat().coerceAtLeast(1f)
                    val bw = if (aspect >= 1f) 200 else (200f * aspect).roundToInt().coerceIn(80, 200)
                    val bh = if (aspect >= 1f) (200f / aspect).roundToInt().coerceIn(80, 200) else 200
                    val bmp = vs.getBitmap(bw, bh) ?: return
                    vs.getLocationInWindow(copyLoc)
                    val bounds = androidx.compose.ui.geometry.Rect(
                        copyLoc[0].toFloat(), copyLoc[1].toFloat(),
                        (copyLoc[0] + vs.width).toFloat(), (copyLoc[1] + vs.height).toFloat()
                    )
                    ownedPlayerBackdrop.setCustomFrame(bmp, bounds)
                }
            }
        } else {
            val win = activity?.window ?: return
            val decor = win.decorView
            val vw = decor.width
            val vh = decor.height
            if (vw < 32 || vh < 32) return
            runCatching {
                val aspect = vw.toFloat() / vh.toFloat().coerceAtLeast(1f)
                val bw = if (aspect >= 1f) 240 else (240f * aspect).roundToInt().coerceIn(80, 240)
                val bh = if (aspect >= 1f) (240f / aspect).roundToInt().coerceIn(80, 240) else 240
                val slot = copySlot and 1
                copySlot = slot xor 1
                val existing = copyPool[slot]
                val bmp = if (existing != null && !existing.isRecycled && existing.width == bw && existing.height == bh) {
                    existing
                } else {
                    Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565).also { copyPool[slot] = it }
                }
                val bounds = androidx.compose.ui.geometry.Rect(0f, 0f, vw.toFloat(), vh.toFloat())
                PixelCopy.request(win, bmp, { res ->
                    if (res == PixelCopy.SUCCESS) {
                        ownedPlayerBackdrop.setCustomFrame(bmp, bounds)
                    }
                }, copyHandler)
            }
        }
    }

    // Live video glass pump:
    //  - Standalone PlayerActivity: ~5 Hz live video refraction while panel/HUD/error is open, 1 frame when bars open, 0 Hz idle!
    //  - InlinePlayer: captures clean hardware video frame whenever bars are up and panel is closed (0 feedback loop!).
    LaunchedEffect(barsVisible, panel, hudActive, dblTapSide, errActive, overlay, pipMode) {
        if (pipMode || PerfTier.low || PerfTier.thermalWarm || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            ownedPlayerBackdrop.setAmbientVideoFrame(null)
            return@LaunchedEffect
        }
        val panelOpenNow = panel != null
        val anyOverlay = panelOpenNow || hudActive || dblTapSide != 0 || errActive
        if (!overlay) {
            if (!barsVisible && !anyOverlay) return@LaunchedEffect
            requestVideoGlassFrame()
            if (!panelOpenNow && !errActive) return@LaunchedEffect
            val liveMs = if (PerfTier.balanced) 650L else 420L
            while (isActive && !PerfTier.thermalWarm && (panelOpenNow || errActive)) {
                delay(liveMs)
                requestVideoGlassFrame()
            }
        } else {
            // InlinePlayer: 0 Hz PixelCopy while controls are hidden (prevents GPU heating during movies!).
            if (!barsVisible && !anyOverlay) {
                ownedPlayerBackdrop.setAmbientVideoFrame(null)
                return@LaunchedEffect
            } else {
                if (ownedPlayerBackdrop.customBitmap == null) requestVideoGlassFrame()
                val win = activity?.window ?: return@LaunchedEffect
                val decor = win.decorView
                val ambPool = arrayOfNulls<Bitmap>(2)
                var ambSlot = 0
                var ambInFlight = false
                val liveMs = if (PerfTier.balanced) 750L else 520L
                try {
                    while (isActive && !PerfTier.thermalWarm) {
                        val vw = decor.width
                        val vh = decor.height
                        if (!ambInFlight && vw > 100 && vh > 100) {
                            runCatching {
                                // Strictly unobstructed video strip (below top bar, above center Play orb, left of right popover!)
                                val rightEdge = if (panelOpenNow) {
                                    (vw - (256f * context.resources.displayMetrics.density).toInt()).coerceIn((vw * 0.28f).toInt(), (vw * 0.62f).toInt())
                                } else {
                                    (vw * 0.86f).toInt()
                                }
                                val leftEdge = if (panelOpenNow) 0 else (vw * 0.14f).toInt()
                                val srcRect = android.graphics.Rect(leftEdge, (vh * 0.20f).toInt(), rightEdge, (vh * 0.36f).toInt())
                                val bw = 120
                                val bh = 72
                                val slot = ambSlot and 1
                                ambSlot = slot xor 1
                                val ex = ambPool[slot]
                                val bmp = if (ex != null && !ex.isRecycled && ex.width == bw && ex.height == bh) ex
                                          else Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565).also { ambPool[slot] = it }
                                ambInFlight = true
                                PixelCopy.request(win, srcRect, bmp, { res ->
                                    ambInFlight = false
                                    if (res == PixelCopy.SUCCESS) ownedPlayerBackdrop.setAmbientVideoFrame(bmp)
                                }, copyHandler)
                            }.onFailure { ambInFlight = false }
                        }
                        delay(liveMs)
                    }
                } finally {
                    ownedPlayerBackdrop.setAmbientVideoFrame(null)
                }
            }
        }
    }

    val shownShift = if (fitMode && !pipMode && rootWide == shiftWide) shiftPx else 0f
    CompositionLocalProvider(LocalGlassBackdrop provides ownedPlayerBackdrop) {
    Box(Modifier.fillMaxSize().onGloballyPositioned {
        rootAt = it.positionInRoot()
        rootWide = it.size.width > it.size.height
        if (!shiftSet && srcCenterY >= 0f && it.size.height > 0) {
            shiftSet = true
            shiftWide = rootWide
            val loc = IntArray(2)
            hostView.getLocationOnScreen(loc)
            val mine = loc[1] + rootAt.y + it.size.height / 2f
            shiftPx = (srcCenterY - mine).coerceIn(-it.size.height * 0.1f, it.size.height * 0.1f)
        }
    }.then(if (overlay) Modifier else Modifier.background(Color.Black))) {
        if (!overlay) AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    playerViewRef = this
                    this.player = player
                    useController = false
                    // the last picture stays until the new stream shows its first one (no black flash on a quality switch)
                    setKeepContentOnPlayerReset(true)
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    subtitleView?.setUserDefaultStyle()
                    subtitleView?.setUserDefaultTextSize()
                }
            },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom; scaleY = zoom
                translationY = shownShift
            },
            update = { v -> playerViewRef = v; v.player = player; v.useController = false; v.resizeMode = ASPECTS[aspectIndex].second }
        )

        if (!overlay && bridgeUp && bridgeImage != null) Image(
            bitmap = bridgeImage,
            contentDescription = null,
            contentScale = if (ASPECTS[aspectIndex].second == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) ContentScale.Crop else ContentScale.Fit,
            modifier = Modifier.fillMaxSize().background(Color.Black).graphicsLayer {
                scaleX = zoom; scaleY = zoom
                translationY = shownShift
            }
        )

        if (!pipMode) {
            // ---- gesture surface: tap / double-tap / long-press, swipe seek-brightness-volume, pinch ----
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(locked) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (locked) return@awaitEachGesture
                            var mode = 0 // 1 seek, 2 brightness, 3 volume, 4 pinch
                            var tx = 0f; var ty = 0f
                            val slop = viewConfiguration.touchSlop
                            val startX = down.position.x
                            val startPos = player.currentPosition
                            val dur = player.duration
                            val startB = brightnessNow(); val startV = volumeNow()
                            var target = startPos
                            val seekStepMs = if (dur != C.TIME_UNSET && dur in 1L..30_000L) 1_000L else 2_500L
                            var lastStep = Int.MIN_VALUE
                            do {
                                val ev = awaitPointerEvent()
                                val pressed = ev.changes.count { it.pressed }
                                if (pressed >= 2) {
                                    mode = 4
                                    val z = ev.calculateZoom()
                                    if (z != 1f) zoom = (zoom * z).coerceIn(1f, 4f)
                                    ev.changes.forEach { it.consume() }
                                    continue
                                }
                                val c = ev.changes.firstOrNull() ?: break
                                if (mode == 4) continue
                                val d = c.positionChange()
                                tx += d.x; ty += d.y
                                if (mode == 0 && (abs(tx) > slop * 2 || abs(ty) > slop * 2)) {
                                    mode = if (abs(tx) > abs(ty)) 1 else if (startX < size.width / 2f) 2 else 3
                                    dragging = mode == 1
                                    if (mode == 1) lastStep = (startPos / seekStepMs).toInt()
                                    Haptics.fire(null, Hx.DRAG_START)
                                }
                                when (mode) {
                                    1 -> if (dur != C.TIME_UNSET && dur > 0) {
                                        target = (startPos + (tx / size.width * 90_000f).toLong()).coerceIn(0L, dur)
                                        position = target
                                        hud = "${formatDuration(target)}  (${if (target >= startPos) "+" else "-"}${abs(target - startPos) / 1000}s)"
                                        val st = (target / seekStepMs).toInt()
                                        if (st != lastStep) {
                                            lastStep = st
                                            Haptics.fire(null, if (target <= 0L || target >= dur) Hx.THRESHOLD else Hx.TICK)
                                        }
                                    }
                                    2 -> {
                                        val v = (startB - ty / size.height * 1.4f).coerceIn(0f, 1f); setBrightness(v); hud = "Brightness ${(v * 100).roundToInt()}%"
                                        val st = (v * 20f).roundToInt()
                                        if (st != lastStep) { lastStep = st; Haptics.fire(null, if (v <= 0f || v >= 1f) Hx.THRESHOLD else Hx.TICK) }
                                    }
                                    3 -> {
                                        val v = (startV - ty / size.height * 1.4f).coerceIn(0f, 1f); setVolume(v); hud = "Volume ${(v * 100).roundToInt()}%"
                                        val st = (v * 20f).roundToInt()
                                        if (st != lastStep) { lastStep = st; Haptics.fire(null, if (v <= 0f || v >= 1f) Hx.THRESHOLD else Hx.TICK) }
                                    }
                                }
                                if (mode in 1..3) c.consume()
                            } while (ev.changes.any { it.pressed })
                            if (mode == 1 && dur != C.TIME_UNSET && dur > 0) {
                                player.seekTo(target)
                                Haptics.fire(null, Hx.DRAG_END)
                            }
                            dragging = false
                        }
                    }
                    .pointerInput(locked, speed) {
                        var lastTapAt = 0L
                        var lastTapX = 0f
                        var before = controlsVisible
                        detectTapGestures(
                            onTap = { o ->
                                if (locked) { unlockVisible = true; return@detectTapGestures }
                                val now = SystemClock.uptimeMillis()
                                if (lastTapAt != 0L && now - lastTapAt < 280L && abs(o.x - lastTapX) < 140f) {
                                    lastTapAt = 0L; controlsVisible = before
                                    if (player.duration > 0) {
                                        val left = o.x < size.width / 2f
                                        val side = if (left) -1 else 1
                                        Haptics.fire(null, Hx.TAP)
                                        seekBy(if (left) -10_000L else 10_000L)
                                        dblTapAccSec = if (dblTapSide == side) dblTapAccSec + 10 else 10
                                        dblTapSide = side
                                        dblTapToken++
                                    }
                                } else {
                                    before = controlsVisible
                                    controlsVisible = !controlsVisible; panel = null
                                    Haptics.fire(null, Hx.TICK)
                                    lastTapAt = now; lastTapX = o.x
                                }
                            },
                            onLongPress = {
                                if (!locked && error == null) { Haptics.fire(null, Hx.HOLD); player.setPlaybackSpeed(2f); holding = true; hud = "2x - release for normal" }
                            },
                            onPress = {
                                tryAwaitRelease()
                                if (holding) { Haptics.fire(null, Hx.DRAG_END); player.setPlaybackSpeed(speed); holding = false; hud = null }
                            }
                        )
                    }
            )

            // ---- HUD (Mirror Liquid Glass Pill + Fluid Spring Pop) ----
            AnimatedVisibility(
                hud != null,
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp),
                enter = fadeIn(tween(120)) + scaleIn(spring(dampingRatio = 0.66f, stiffness = 520f), initialScale = 0.84f),
                exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.90f)
            ) {
                Box(
                    Modifier
                        .liquidGlass(Capsule, 1.15f, 0.02f, reactive = false)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(hud.orEmpty(), color = TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // ---- Double-Tap Side Seek Pill (positioned cleanly above center so it NEVER overlaps Rotate or Play/Pause) ----
            AnimatedVisibility(
                visible = dblTapSide != 0 && dblTapAccSec > 0,
                modifier = Modifier
                    .align(if (dblTapSide < 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(
                        start = if (dblTapSide < 0) 40.dp else 0.dp,
                        end = if (dblTapSide > 0) 40.dp else 0.dp,
                        bottom = 132.dp
                    ),
                enter = fadeIn(tween(110)) + scaleIn(spring(dampingRatio = 0.66f, stiffness = 540f), initialScale = 0.80f),
                exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.88f)
            ) {
                Row(
                    Modifier
                        .playerLiquidGlass(Capsule, level = 1.16f)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (dblTapSide < 0) {
                        Icon(AppleIcons.ArrowBack, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    Text(
                        if (dblTapSide < 0) "-${dblTapAccSec}s" else "+${dblTapAccSec}s",
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold
                    )
                    if (dblTapSide > 0) {
                        Icon(AppleIcons.ArrowForward, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Quality switch: the buffer screen is there in the same frame as the tap (last picture stays, slightly dimmed,
            // spinner on top). Any other wait still shows the spinner only once it is noticeable.
            var showSpin by remember { mutableStateOf(false) }
            LaunchedEffect(buffering, resolving, switching) {
                if (switching) showSpin = true
                else if (buffering || resolving) { delay(700); showSpin = true }
                else showSpin = false
            }
            val dimState = animateFloatAsState(if (switching) 0.38f else 0f, tween(if (switching) 90 else 160), label = "switchDim")
            Box(Modifier.fillMaxSize().drawBehind {
                val d = dimState.value.coerceIn(0f, 1f)
                if (d > 0.001f) drawRect(Color.Black.copy(alpha = d))
            })
            // `switching` shows the spinner in the SAME frame (no wait for the effect above to run first)
            if (switching || (showSpin && (buffering || resolving))) {
                CircularProgressIndicator(color = accent, strokeWidth = 2.5.dp, modifier = Modifier.align(Alignment.Center).size(34.dp))
            }

            // ---- top bar: Close Liquid Glass Orb · Title · Unified iOS 27 Liquid Glass Action Cluster ----
            AnimatedVisibility(barsVisible, Modifier.align(Alignment.TopCenter), enter = fadeIn(tween(100)), exit = fadeOut(tween(90))) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.62f), Color.Transparent)
                        ))
                        .glassTouchShield { touch() }
                        .statusBarsPadding()
                        .padding(start = PlayerEdge, end = PlayerEdge, top = 8.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayerGlassOrb(AppleIcons.Close, "Close", size = 38.dp, iconSize = 18.dp) { onClose() }
                    Text(
                        title, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
                    )
                    // Unified iOS 27 Liquid Glass Control Cluster (Fit/Crop · Lock · More) — crisp, symmetrical, 100% consistent!
                    Row(
                        Modifier
                            .height(38.dp)
                            .playerLiquidGlass(Capsule, level = 1.12f)
                            .padding(horizontal = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val brightAccent = androidx.compose.ui.graphics.lerp(accent, Color.White, 0.44f)
                        GlowPress(
                            onClick = {
                                touch()
                                val next = (aspectIndex + 1) % ASPECTS.size
                                if (screenLandscape) { aspectLand = next; aspectPrefs.edit().putInt("aspectLand", next).apply() }
                                else { aspectPort = next; aspectPrefs.edit().putInt("aspectPort", next).apply() }
                                zoom = 1f
                                panel = null
                                hud = ASPECTS[next].first
                            },
                            shape = CircleShape,
                            modifier = Modifier
                                .size(32.dp)
                                .then(if (aspectIndex != 0) Modifier.background(accent.copy(alpha = 0.28f), CircleShape) else Modifier)
                        ) {
                            Icon(
                                if (aspectIndex == 0) AppleIcons.FitScreen else AppleIcons.Crop,
                                "Aspect mode",
                                tint = if (aspectIndex != 0) brightAccent else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Box(Modifier.onGloballyPositioned { lockAt = it.positionInRoot() }) {
                            GlowPress(
                                onClick = {
                                    Haptics.fire(null, Hx.TOGGLE_ON)
                                    locked = true; unlockVisible = true; controlsVisible = false; panel = null; hud = "Locked"
                                },
                                shape = CircleShape,
                                haptic = false,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(AppleIcons.Lock, "Lock screen", tint = Color.White, modifier = Modifier.size(17.5.dp))
                            }
                        }
                        val moreLit = panel == PlayerPanel.MORE || sleepMinutes > 0 || audioBoostPct > 0 || nightMode
                        GlowPress(
                            onClick = { panel = if (panel == PlayerPanel.MORE) null else PlayerPanel.MORE },
                            shape = CircleShape,
                            modifier = Modifier
                                .size(32.dp)
                                .then(if (moreLit) Modifier.background(accent.copy(alpha = 0.28f), CircleShape) else Modifier)
                        ) {
                            Icon(
                                AppleIcons.MoreVert,
                                "More options",
                                tint = if (moreLit) brightAccent else Color.White,
                                modifier = Modifier.size(18.5.dp)
                            )
                        }
                    }
                }
            }

            // ---- centre play / pause: authentic Apple iOS 27 Liquid Glass Lens Orb ----
            AnimatedVisibility(
                barsVisible && !buffering && !switching, Modifier.align(Alignment.Center),
                enter = fadeIn(tween(140)) + scaleIn(initialScale = 0.85f, animationSpec = spring(dampingRatio = 0.7f, stiffness = 600f)),
                exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.9f)
            ) {
                val on = playWhenReady && player.playbackState != Player.STATE_ENDED
                PlayPauseDisc(on = on, accent = accent, diameter = 64.dp) { touch(); togglePlay() }
            }

            // ---- bottom bar: Scrubber + Uniform 34dp Liquid Glass Pills & Orbs ----
            AnimatedVisibility(barsVisible, Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(100)), exit = fadeOut(tween(90))) {
                val toolsHalf = ((LocalConfiguration.current.screenWidthDp - 40) * 0.52f).dp
                val stripMax = maxOf(72.dp, toolsHalf)
                Row(
                    Modifier.fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.64f))
                        ))
                        .glassTouchShield { touch() }
                        .navigationBarsPadding()
                        .padding(start = PlayerEdge, end = PlayerEdge, top = 16.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatDuration(position),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                            maxLines = 1
                        )
                        GlassSlider(
                            value = if (duration > 0L) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f,
                            onValueChange = { if (duration > 0L) { dragging = true; position = it.toLong() }; touch() },
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                            bufferedFraction = if (duration > 0) (bufferedPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                            onValueChangeFinished = { player.seekTo(position); dragging = false }
                        )
                        val durDisplay = when {
                            duration <= 0L -> "0:00"
                            showRemaining -> "-" + formatDuration((duration - position).coerceAtLeast(0L))
                            else -> formatDuration(duration)
                        }
                        Text(
                            durDisplay,
                            color = if (duration > 0L) Color.White.copy(alpha = 0.80f) else Color.Transparent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                            maxLines = 1,
                            modifier = if (duration > 0L) Modifier.clickableHaptic { showRemaining = !showRemaining } else Modifier
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Row(
                        Modifier.widthIn(max = stripMax).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlayerChip(speedLabel(speed), AppleIcons.Speed, speed != 1f) {
                            panel = if (panel == PlayerPanel.SPEED) null else PlayerPanel.SPEED
                        }
                        if (!isLocal) {
                            PlayerChip(
                                label = qualityChipLabel,
                                icon = null,
                                active = panel == PlayerPanel.QUALITY,
                                badge = qualityBadge,
                                badgeColor = qualityColor
                            ) {
                                panel = if (panel == PlayerPanel.QUALITY) null else PlayerPanel.QUALITY
                            }
                        }
                        if (audioOpts.size > 1) {
                            PlayerGlassOrb(AppleIcons.Audiotrack, "Audio track", panel == PlayerPanel.AUDIO, size = 34.dp, iconSize = 16.5.dp) {
                                panel = if (panel == PlayerPanel.AUDIO) null else PlayerPanel.AUDIO
                            }
                        }
                        if (textOpts.isNotEmpty()) {
                            PlayerGlassOrb(AppleIcons.ClosedCaption, "Subtitles", !subsOff || panel == PlayerPanel.SUBS, size = 34.dp, iconSize = 17.dp) {
                                panel = if (panel == PlayerPanel.SUBS) null else PlayerPanel.SUBS
                            }
                        }
                    }
                }
            }

            // ---- rotate: consistent Apple iOS 27 Liquid Glass Orb (pure white crisp glyph matching every other button!) ----
            AnimatedVisibility(
                barsVisible, Modifier.align(Alignment.CenterEnd),
                enter = fadeIn(tween(100)), exit = fadeOut(tween(90))
            ) {
                GlowPress(
                    onClick = { touch(); rotate(activity) },
                    modifier = Modifier.padding(end = PlayerEdge)
                        .size(44.dp)
                        .liquidWindowDrag(maxPullDp = 24.dp)
                        .playerLiquidGlass(CircleShape, level = 1.15f),
                    shape = CircleShape
                ) {
                    RotateToggleIcon(Color.White)
                }
            }

            // ---- options panel (100% unified LiquidFloatingWindow — exact same look, size, animation & feel as outside!) ----
            var lastPanel by remember { mutableStateOf<PlayerPanel?>(null) }
            if (panel != null) lastPanel = panel
            LaunchedEffect(panel) {
                if (panel == null && lastPanel != null) {
                    delay(105)
                    lastPanel = null
                }
            }
            val shownPanel = panel ?: lastPanel
            val panelOpen = panel != null
            val fromTop = shownPanel == PlayerPanel.MORE || shownPanel == PlayerPanel.SLEEP || shownPanel == PlayerPanel.BOOST
            val endMargin = if (screenLandscape) PlayerEdge else FloatingPopoverMargin

            if (shownPanel != null) {
                LiquidFloatingWindow(
                    visible = panelOpen,
                    onDismiss = { panel = null },
                    fromTop = fromTop,
                    edgeMargin = endMargin,
                    anchorOffset = if (fromTop) 46.dp else 60.dp,
                    sheenKey = shownPanel,
                    immersive = screenLandscape,
                    contentModifier = Modifier.glassTouchShield { touch() }
                ) {
                    AnimatedContent(
                        targetState = shownPanel,
                        transitionSpec = {
                            ((fadeIn(tween(180, delayMillis = 25)) + scaleIn(tween(180), initialScale = 0.96f)) togetherWith
                                (fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.97f))).using(
                                SizeTransform(clip = true) { _, _ -> spring(dampingRatio = 0.82f, stiffness = 480f) })
                        },
                        label = "playerPanelMorph",
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                    ) { curPanel ->
                        Column(Modifier.fillMaxWidth()) {
                            when (curPanel) {
                                PlayerPanel.QUALITY -> {
                                    val qSubtitle = qualityLabel.ifBlank { "Auto" }
                                    PlayerSheetHeader("Quality", "Current · $qSubtitle", AppleIcons.HighQuality, onClose = { panel = null })
                                    Column(
                                        Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        if (choices.size > 1) {
                                            choices.withIndex().toList().chunked(2).forEachIndexed { rIdx, pair ->
                                                PlayerStaggerRow(rIdx) {
                                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                                        pair.forEach { (i, c) ->
                                                            val lbl = if (c.height > 0) MediaTools.heightText(DeviceCaps.tier(c)) else MediaTools.playerText(c.label)
                                                            PlayerOptionChip(
                                                                label = lbl,
                                                                tag = playerQualityTag(lbl),
                                                                selected = i == choiceIndex,
                                                                modifier = Modifier.weight(1f)
                                                            ) { onChoice(i); panel = null }
                                                        }
                                                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                                                    }
                                                }
                                            }
                                        } else {
                                            val allOpts = listOf<TrackOpt?>(null) + videoOpts
                                            allOpts.chunked(2).forEachIndexed { rIdx, pair ->
                                                PlayerStaggerRow(rIdx) {
                                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                                        pair.forEach { o ->
                                                            val lbl = o?.label ?: "Auto"
                                                            val isSel = if (o == null) (videoOpts.none { it.selected } || videoOpts.size < 2)
                                                                        else (o.selected && videoOpts.size > 1)
                                                            PlayerOptionChip(
                                                                label = lbl,
                                                                tag = playerQualityTag(lbl),
                                                                selected = isSel,
                                                                modifier = Modifier.weight(1f)
                                                            ) { applyTrack(player, C.TRACK_TYPE_VIDEO, o); panel = null }
                                                        }
                                                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                PlayerPanel.SPEED -> {
                                    PlayerSheetHeader("Playback speed", "Current · ${speedLabel(speed)}", AppleIcons.Speed, onClose = { panel = null })
                                    Column(
                                        Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        PLAYER_SPEEDS.chunked(2).forEachIndexed { rIdx, pair ->
                                            PlayerStaggerRow(rIdx) {
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                                    pair.forEach { v ->
                                                        PlayerOptionChip(
                                                            label = speedLabel(v),
                                                            tag = if (v == 1f) "Normal" else "",
                                                            selected = speed == v,
                                                            modifier = Modifier.weight(1f)
                                                        ) { speed = v; player.setPlaybackSpeed(v); panel = null }
                                                    }
                                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                                }
                                            }
                                        }
                                    }
                                }
                                PlayerPanel.AUDIO -> {
                                    PlayerSheetHeader("Audio track", "${audioOpts.size} available", AppleIcons.Audiotrack, onClose = { panel = null })
                                    Column(
                                        Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        audioOpts.forEachIndexed { i, o ->
                                            PlayerStaggerRow(i) {
                                                PanelRow(o.label, o.selected) { applyTrack(player, C.TRACK_TYPE_AUDIO, o); panel = null }
                                            }
                                        }
                                    }
                                }
                                PlayerPanel.SUBS -> {
                                    PlayerSheetHeader("Subtitles", if (subsOff) "Off" else "On", AppleIcons.ClosedCaption, onClose = { panel = null })
                                    Column(
                                        Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        PlayerStaggerRow(0) {
                                            PanelRow("Off", subsOff) { applyTrack(player, C.TRACK_TYPE_TEXT, null); panel = null }
                                        }
                                        textOpts.forEachIndexed { i, o ->
                                            PlayerStaggerRow(i + 1) {
                                                PanelRow(o.label, !subsOff && o.selected) { applyTrack(player, C.TRACK_TYPE_TEXT, o); panel = null }
                                            }
                                        }
                                    }
                                }
                                PlayerPanel.SLEEP -> {
                                    PlayerSheetHeader(
                                        "Sleep timer",
                                        if (sleepMinutes > 0) "$sleepMinutes min active" else "Auto pause playback",
                                        AppleIcons.Bedtime,
                                        onClose = { panel = null },
                                        onBack = { panel = PlayerPanel.MORE }
                                    )
                                    Column(
                                        Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        SLEEP_OPTIONS.chunked(2).forEachIndexed { rIdx, pair ->
                                            PlayerStaggerRow(rIdx) {
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                                    pair.forEach { m ->
                                                        PlayerOptionChip(
                                                            label = if (m == 0) "Off" else "${m}m",
                                                            tag = if (m == 0) "" else "min",
                                                            selected = sleepMinutes == m,
                                                            modifier = Modifier.weight(1f)
                                                        ) { sleepMinutes = m; panel = null }
                                                    }
                                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                                }
                                            }
                                        }
                                    }
                                }
                                PlayerPanel.BOOST -> {
                                    PlayerSheetHeader(
                                        "Audio boost",
                                        if (audioBoostPct > 0) "Boost +$audioBoostPct%" else "Normal volume",
                                        AppleIcons.Audiotrack,
                                        onClose = { panel = null },
                                        onBack = { panel = PlayerPanel.MORE }
                                    )
                                    Column(
                                        Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        BOOST_OPTIONS.chunked(2).forEachIndexed { rIdx, pair ->
                                            PlayerStaggerRow(rIdx) {
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                                    pair.forEach { pct ->
                                                        PlayerOptionChip(
                                                            label = if (pct == 0) "100%" else "+$pct%",
                                                            tag = if (pct == 0) "Normal" else "Loud",
                                                            selected = audioBoostPct == pct,
                                                            modifier = Modifier.weight(1f)
                                                        ) {
                                                            audioBoostPct = pct
                                                            hud = if (pct == 0) "Audio boost off" else "Audio boost +$pct%"
                                                            panel = null
                                                        }
                                                    }
                                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                                }
                                            }
                                        }
                                    }
                                }
                                PlayerPanel.MORE -> {
                                    Column(
                                        Modifier.fillMaxWidth().heightIn(max = 276.dp).verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        var idx = 0
                                        if (!overlay) {
                                            PlayerStaggerRow(idx++) {
                                                PanelRow(
                                                    "Audio boost", audioBoostPct > 0,
                                                    if (audioBoostPct > 0) "+$audioBoostPct%" else "Off",
                                                    icon = AppleIcons.Audiotrack, chevron = true
                                                ) { panel = PlayerPanel.BOOST }
                                            }
                                        }
                                        PlayerStaggerRow(idx++) {
                                            PanelRow(
                                                "Night mode", nightMode, if (nightMode) "On" else "Off",
                                                icon = AppleIcons.DarkMode
                                            ) {
                                                nightMode = !nightMode
                                                aspectPrefs.edit().putBoolean("playerNightMode", nightMode).apply()
                                                hud = if (nightMode) "Night mode on · Extra dim" else "Night mode off"
                                                panel = null
                                            }
                                        }
                                        PlayerStaggerRow(idx++) {
                                            PanelRow(
                                                "Sleep timer", sleepMinutes > 0, if (sleepMinutes > 0) "$sleepMinutes min" else "Off",
                                                icon = AppleIcons.Bedtime, chevron = true
                                            ) { panel = PlayerPanel.SLEEP }
                                        }
                                        PanelDivider()
                                        PlayerStaggerRow(idx++) {
                                            PanelRow("Download", false, icon = AppleIcons.Download) { panel = null; onDownload() }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- locked: one small unlock button ----
            // The unlock button takes the place of the lock button (same spot, same size, lit), so a locked player
            // keeps its one button exactly where the finger just was.
            val at = lockAt
            AnimatedVisibility(
                locked && unlockVisible,
                (if (at != null) Modifier.align(Alignment.TopStart)
                    .offset { IntOffset((at.x - rootAt.x).roundToInt(), (at.y - rootAt.y).roundToInt()) }
                else Modifier.align(Alignment.CenterStart).padding(start = PlayerEdge)),
                enter = fadeIn(tween(140)), exit = fadeOut(tween(200))
            ) {
                PlayerGlassOrb(AppleIcons.LockOpen, "Unlock screen", active = true, size = 38.dp, iconSize = 18.5.dp, haptic = false) {
                    Haptics.fire(null, Hx.TOGGLE_OFF)
                    locked = false; controlsVisible = true; hud = "Unlocked"
                }
            }

            // ---- Night mode: warm extra-dim optical veil for dark-room viewing (0 touch interception!) ----
            val nightDimState = animateFloatAsState(
                targetValue = if (nightMode) 1f else 0f,
                animationSpec = tween(260),
                label = "playerNightDim"
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val a = nightDimState.value.coerceIn(0f, 1f)
                        if (a > 0.004f) {
                            drawRect(Color(0xFF0A0602).copy(alpha = 0.50f * a))
                        }
                    }
            )

            // ---- error / resolver failure (Mirror Liquid Glass) ----
            val problem = resolveError ?: error
            if (problem != null && !resolving) {
                Column(
                    Modifier.align(Alignment.Center).padding(28.dp).widthIn(max = 320.dp)
                        .liquidWindowDrag(maxPullDp = 44.dp)
                        .liquidGlass(FloatingPopoverShape, 1.18f, 0.02f, reactive = true)
                        .liquidEntranceSheen(problem)
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(AppleIcons.ErrorOutline, null, tint = Bad, modifier = Modifier.size(30.dp))
                    Text("Can't play this video", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    Text(problem, color = TextSecondary, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPillButton("Retry", { error = null; onRetry() }, icon = AppleIcons.Refresh, accent = true)
                        GlassPillButton("Close", onClose)
                    }
                }
            }
        }
    }
    }
}

/**
 * Rotation toggle symbol: it shows the orientation a tap switches TO (sideways phone in portrait, upright phone in
 * landscape). When the orientation flips, the old symbol spins out and the new one spins in (about half a second).
 */
/** The main button: accent-tinted glass, the icon morphs between play and pause with a little pop. */
@Composable
private fun PlayPauseDisc(on: Boolean, accent: Color, diameter: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION")
    accent
    val view = LocalView.current
    val pop = remember { Animatable(1f) }
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(on) {
        if (armed) { pop.snapTo(0.88f); pop.animateTo(1f, spring(dampingRatio = 0.46f, stiffness = 540f)) }
        armed = true
    }
    GlowPress(
        onClick = { performToggle(view, !on); onClick() },
        modifier = Modifier
            .size(diameter)
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .liquidGlass(CircleShape, level = 1.10f, extraOpacity = 0.04f, reactive = false, optical = true),
        shape = CircleShape,
        haptic = false,
        glassBody = true
    ) {
        AnimatedContent(
            targetState = on,
            transitionSpec = {
                (fadeIn(tween(150)) + scaleIn(initialScale = 0.58f, animationSpec = spring(dampingRatio = 0.62f, stiffness = 560f))) togetherWith
                    (fadeOut(tween(85)) + scaleOut(targetScale = 0.58f))
            },
            label = "playPauseIcon"
        ) { playing ->
            Icon(
                if (playing) AppleIcons.Pause else AppleIcons.PlayArrow,
                if (playing) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier
                    .size(diameter * 0.44f)
                    .padding(start = if (playing) 0.dp else 2.5.dp)
            )
        }
    }
}

@Composable
private fun RotateToggleIcon(tint: Color) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var prev by remember { mutableStateOf(landscape) }
    val swap = remember { Animatable(1f) }
    LaunchedEffect(landscape) {
        if (landscape == prev) { swap.snapTo(1f); return@LaunchedEffect }
        swap.snapTo(0f)
        swap.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
        prev = landscape
    }
    val e = swap.value
    val s = if (landscape) -1f else 1f            // the symbol turns the same way the screen does
    fun symbol(l: Boolean) = if (l) AppleIcons.RotateToPortrait else AppleIcons.RotateToLandscape
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        if (e < 1f) Icon(
            symbol(prev), null, tint = tint,
            modifier = Modifier.size(22.dp).graphicsLayer {
                rotationZ = s * 90f * e; alpha = (1f - e * 1.4f).coerceIn(0f, 1f); scaleX = 1f - 0.25f * e; scaleY = scaleX
            }
        )
        Icon(
            symbol(landscape), if (landscape) "Switch to portrait" else "Switch to landscape", tint = tint,
            modifier = Modifier.size(22.dp).graphicsLayer {
                rotationZ = -s * 90f * (1f - e); alpha = ((e - 0.15f) / 0.85f).coerceIn(0f, 1f); scaleX = 0.75f + 0.25f * e; scaleY = scaleX
            }
        )
    }
}

private fun rotate(activity: ComponentActivity?) {
    val a = activity ?: return
    a.requestedOrientation = if (a.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0 || ms == C.TIME_UNSET) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}
