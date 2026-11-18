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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.webkit.WebSettings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.session.MediaSession
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
                if (url.startsWith("file:", true)) r.setDataSource(this, Uri.parse(url)) else r.setDataSource(url)
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
        val h = choices.value.getOrNull(choiceIndex.intValue)?.height ?: 0
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
            when (act) {
                ACTION_PIP_TOGGLE -> togglePlayPause()
                ACTION_PIP_AUDIO -> enterAudioOnly()
                ACTION_PIP_NEXT -> if (player.hasNextMediaItem()) { player.seekToNextMediaItem(); player.play(); refreshPip() }
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
            .putExtra(BrowserMediaService.EXTRA_HOST, "Downloads")
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No slide / fade between the in-app player and this one, and never the light app background in between:
        // that was the 'player opens again + jitter' when a quality above FHD was picked.
        noTransition()
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        applySystemBars()

        val url = intent.getStringExtra("url")
        if (url.isNullOrBlank()) { finish(); return }
        // Quality switched from the in-page / inline player: open in the SAME orientation the screen has right now
        // (landscape stays landscape, portrait stays portrait) and do not re-match it to the video's shape afterwards.
        val keepOrient = intent.getBooleanExtra("keepOrient", false)
        if (keepOrient) {
            orientFor(portrait = !intent.getBooleanExtra("landscape", false))
            needOrient = false
        } else if (intent.getBooleanExtra("landscape", false)) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        isLocal = intent.getBooleanExtra("local", false) || url.startsWith("file:", true)
        if (isLocal && !keepOrient) peekLocalOrientation(url)
        firstUrl = url
        syncBack = intent.getBooleanExtra("sync", false)
        syncSeek = !intent.getBooleanExtra("syncLive", false)
        if (syncBack && intent.getBooleanExtra("handoff", false)) startHandoff()
        firstAudio = intent.getStringExtra("audioUrl")
            ?.takeIf { it.isNotBlank() && it != url && isMediaUrl(it) }
        pageUrl = intent.getStringExtra("pageUrl")?.takeIf { it.isNotBlank() }
        val title = intent.getStringExtra("title").orEmpty()
        introTitle = title
        playUrlState.value = url
        // The page video's own length (sent with a quality switch): the progress bar knows it from the first frame.
        intent.getLongExtra("durMs", 0L).takeIf { it > 0L }?.let { knownDurationMs.longValue = it }

        val uri = Uri.parse(url)
        val origin = if (!uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank())
            "${uri.scheme}://${uri.host}/" else ""
        userAgent = intent.getStringExtra("ua")?.takeIf { it.isNotBlank() }
            ?: runCatching { WebSettings.getDefaultUserAgent(this) }.getOrNull()?.takeIf { it.isNotBlank() }
        referer = (intent.getStringExtra("referer")?.takeIf { it.isNotBlank() } ?: origin).takeIf { it.isNotBlank() }
        cookie = siteCookie(url)
            ?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra("cookie")?.takeIf { it.isNotBlank() }

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
        // A downloaded video gets the app's own card (BrowserMediaService); a second system session would show twice.
        if (!isLocal) mediaSession = MediaSession.Builder(this, player).build()

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
                if (isPlaying) { netRetries = 0; reResolves = 0; endSwitching() }   // a working stream clears the retry budget
                refreshPip()
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

    private fun httpFactory(h: Map<String, String>) = DefaultHttpDataSource.Factory()
        .setDefaultRequestProperties(h)
        .setUserAgent(h["User-Agent"])
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(7_000)     // a hung CDN connection is given up (and retried) after seconds, not half a minute
        .setReadTimeoutMs(10_000)

    /**
     * http(s) with the page headers, plus file:// for downloaded videos. YouTube's CDN is read in ranged chunks
     * (see [ChunkedDataSource]) so it never throttles a 2K / 4K stream; every other host is untouched.
     */
    private fun dataFactory(h: Map<String, String>): androidx.media3.datasource.DefaultDataSource.Factory {
        val http = httpFactory(h)
        return androidx.media3.datasource.DefaultDataSource.Factory(
            this,
            androidx.media3.datasource.DataSource.Factory { ChunkedDataSource(http.createDataSource()) }
        )
    }

    private fun kindMime(u: String): String? = when (mediaKind(u)) {
        "HLS" -> MimeTypes.APPLICATION_M3U8
        "DASH" -> MimeTypes.APPLICATION_MPD
        else -> null
    }

    private fun source(
        videoUrl: String, audioUrl: String?, vHeaders: Map<String, String>, aHeaders: Map<String, String>,
        forceHls: Boolean = false
    ): MediaSource {
        val vItem = MediaItem.Builder().setUri(videoUrl).apply {
            (kindMime(videoUrl) ?: if (forceHls) MimeTypes.APPLICATION_M3U8 else null)?.let { setMimeType(it) }
        }.build()
        val vSrc = DefaultMediaSourceFactory(dataFactory(vHeaders))
            .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(5))
            .createMediaSource(vItem)
        if (audioUrl == null) return vSrc
        val aItem = MediaItem.Builder().setUri(audioUrl).apply { kindMime(audioUrl)?.let { setMimeType(it) } }.build()
        val aSrc = DefaultMediaSourceFactory(dataFactory(aHeaders))
            .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(5))
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
        referer?.let { h["Referer"] = it }
        userAgent?.let { h["User-Agent"] = it }
        cookie?.let { h["Cookie"] = it }
        val ah = LinkedHashMap<String, String>(h)
        firstAudio?.let { a ->
            val c = siteCookie(a).orEmpty()
            if (c.isNotBlank()) ah["Cookie"] = c
            else if (Uri.parse(a).host != Uri.parse(firstUrl).host) ah.remove("Cookie")
        }
        playUrlState.value = firstUrl
        begin(source(firstUrl, firstAudio, h, ah), startMs)
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
            r.choices.indexOfFirst { it.height == want }.takeIf { it >= 0 }
                ?: r.choices.withIndex().filter { it.value.height in 1..want }.maxByOrNull { it.value.height }?.index
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
                val wasQ = MediaTools.playerText(MediaTools.labelForHeight(c.height))
                val nowQ = MediaTools.playerText(MediaTools.labelForHeight(list[lower].height))
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
            val qLabel = MediaTools.playerText(MediaTools.labelForHeight(c.height))
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
            if (!isLocal) {
                runCatching { mediaSession?.release() }
                mediaSession = MediaSession.Builder(this, sb).build()
            }
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
                if (hiResLoad) {
                    // start after 1 s of data, but after a stall wait for 3 s so it does not stall again at once
                    setBufferDurationsMs(18_000, 50_000, 1_000, 3_000)
                    setBackBuffer(5_000, false)
                    setTargetBufferBytes(hiResBytes)
                    setPrioritizeTimeOverSizeThresholds(false)
                } else {
                    setBufferDurationsMs(20_000, 60_000, 800, 1_800)
                    setBackBuffer(20_000, true)        // short rewinds / replays come from memory, not the network
                    setTargetBufferBytes(144 * 1024 * 1024)
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
            this, androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection.Factory(7_000, 25_000, 25_000, 0.75f)
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
        val failedQ = MediaTools.playerText(MediaTools.labelForHeight(now.height))
        val nextQ = MediaTools.playerText(MediaTools.labelForHeight(list[next].height))
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
        val sz = player.videoSize
        val sig = "${wantsPlay()}|${player.hasNextMediaItem()}|${sz.width}x${sz.height}|$autoPip"
        if (sig == lastPipSig) return
        lastPipSig = sig
        runCatching { setPictureInPictureParams(buildPipParams()) }
    }

    /** Where the letter-boxed video sits in the window: the system animates exactly from there into the PiP window. */
    private fun pipSourceRect(): android.graphics.Rect? {
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
                if (Build.VERSION.SDK_INT >= 31) {
                    setAutoEnterEnabled(autoPip && playing)
                    setSeamlessResizeEnabled(false)
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
            window?.decorView?.postDelayed({ pipModeState.value = isInPictureInPictureMode }, 1500L)
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
        if (autoPip && wantsPlay() && Build.VERSION.SDK_INT >= 26 &&
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
                player.pause(); finish(); return
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
        local -> url + "|" + (runCatching { java.io.File(Uri.parse(url).path.orEmpty()).length() }.getOrNull() ?: 0L)
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
        if (!audioOnly && (Build.VERSION.SDK_INT < 26 || (!isInPictureInPictureMode && !pipRequested))) player.pause()
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
        mediaSession?.release()
        player.release()
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
private val PLAYER_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)
private val SLEEP_OPTIONS = listOf(0, 5, 10, 15, 30, 45, 60, 90)
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

private enum class PlayerPanel { SPEED, QUALITY, AUDIO, SUBS, SLEEP, MORE }

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
            if (type == C.TRACK_TYPE_VIDEO && f.height > 0 && !DeviceCaps.supportsHeight(f.height)) continue
            val label = if (type == C.TRACK_TYPE_VIDEO) {
                if (f.height > 0) MediaTools.playerText("${f.height}p")
                else "Video ${out.size + 1}"
            } else {
                f.label?.takeIf { it.isNotBlank() }
                    ?: f.language?.let { l -> runCatching { java.util.Locale(l).displayLanguage }.getOrNull() }?.takeIf { it.isNotBlank() }
                    ?: "Track ${out.size + 1}"
            }
            out.add(TrackOpt(label, g, i, g.isTrackSelected(i), f.height))
        }
    }
    return if (type == C.TRACK_TYPE_VIDEO) out.sortedByDescending { it.sort } else out
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

private fun Modifier.glassTouchShield(onTouch: () -> Unit): Modifier = this
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.pressed || it.previousPressed }) onTouch()
            }
        }
    }
    .pointerInput(Unit) { detectTapGestures { } }

/** Small capsule chip for the bottom bar. */
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
    // A coloured badge tints the whole chip (badge, label and glass) in that colour.
    val tone = badgeColor ?: if (active) accent else TextPrimary
    GlowPress(
        onClick = onClick,
        haptic = haptic,
        modifier = Modifier.height(28.dp).glassTile(
            Capsule,
            if (badgeColor != null || active) 0.16f else 0.08f,
            badgeColor ?: if (active) accent else Color.White
        ),
        shape = Capsule
    ) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (badge != null) {
                // Dynamic resolution badge (LD / SD / HD / FHD / 2K / 4K / 8K) in place of a fixed icon.
                val c = tone
                Box(
                    Modifier.border(1.3.dp, c, RoundedCornerShape(4.dp)).padding(horizontal = 3.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(badge, color = c, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, lineHeight = 10.sp, maxLines = 1)
                }
            } else if (icon != null) Icon(icon, null, tint = if (active) accent else TextPrimary, modifier = Modifier.size(15.dp))
            if ((icon != null || badge != null) && label != null) Spacer(Modifier.width(5.dp))
            if (label != null) Text(label, color = tone, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** One option inside the player's floating menu. [sub] is a small value shown on the right. */
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
    val shape = RoundedCornerShape(16.dp)
    GlowPress(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp)
            .then(if (selected && !chevron) Modifier.background(accent.copy(alpha = 0.13f), shape) else Modifier),
        shape = shape,
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                PanelIconTile(icon, selected)
                Spacer(Modifier.width(10.dp))
            } else {
                Spacer(Modifier.width(4.dp))
            }
            Text(
                label,
                color = if (selected && !chevron) accent else TextPrimary,
                fontSize = 13.sp,
                fontWeight = if (selected && !chevron) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (sub != null) {
                Spacer(Modifier.width(8.dp))
                Text(sub, color = if (selected) accent else TextTertiary, fontSize = 11.sp, maxLines = 1)
            }
            if (chevron) {
                Icon(AppleIcons.ChevronRight, null, tint = TextTertiary, modifier = Modifier.size(16.dp))
            } else if (selected) {
                Icon(AppleIcons.Check, null, tint = accent, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun PanelIconTile(icon: ImageVector, active: Boolean) {
    val accent = LocalGlassStyle.current.accent
    Box(
        Modifier.size(30.dp).glassTile(RoundedCornerShape(12.dp), if (active) 0.16f else 0.07f, if (active) accent else Color.White),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = if (active) accent else TextPrimary, modifier = Modifier.size(16.dp))
    }
}

/** Thin separator between groups of menu rows. */
@Composable
private fun PanelDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp).height(0.75.dp).background(Color.White.copy(alpha = 0.10f)))
}

/** Title + back arrow for the sub-page (sleep timer) of the three-dots menu. */
@Composable
private fun PanelHeader(title: String, onBack: () -> Unit) {
    GlowPress(
        onClick = onBack,
        modifier = Modifier.fillMaxWidth().height(38.dp),
        shape = RoundedCornerShape(12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppleIcons.ArrowBack, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
    PanelDivider()
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
    LaunchedEffect(Unit) {
        while (isActive) {
            if (!dragging) {
                val d = player.duration
                if (d != C.TIME_UNSET && d > 0) duration = d              // length unknown while a new stream loads: keep the last one
                else if (duration <= 0L && knownDur.value > 0L) duration = knownDur.value   // ... or the length the site reported
                val p = player.currentPosition.coerceAtLeast(0)
                // a stream that is still loading reports 0: do not drag the bar back to the start
                if (!(p == 0L && position > 1_000L && player.playbackState != Player.STATE_READY)) position = p
            }
            bufferedPosition = player.bufferedPosition.coerceAtLeast(0)
            delay(250)
        }
    }
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
    val videoOpts = remember(tracks) { trackOptions(tracks, C.TRACK_TYPE_VIDEO) }
    val audioOpts = remember(tracks) { trackOptions(tracks, C.TRACK_TYPE_AUDIO) }
    val textOpts = remember(tracks) { trackOptions(tracks, C.TRACK_TYPE_TEXT) }
    val subsOff = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || textOpts.none { it.selected }
    val qualityLabel = when {
        choices.size > 1 && choiceIndex in choices.indices -> MediaTools.playerText(choices[choiceIndex].label)
        videoH > 0 -> MediaTools.playerText("${videoH}p")
        else -> "Auto"
    }
    // Resolution class of what is really playing (short side, so portrait videos are judged correctly);
    // falls back to the chosen stream's height until the first frame size is known.
    val shortSide = when {
        videoH > 0 && videoW > 0 -> minOf(videoH, videoW)
        videoH > 0 -> videoH
        choices.size > 1 && choiceIndex in choices.indices -> choices[choiceIndex].height
        else -> 0
    }
    val qualityBadge = when {
        shortSide <= 0 -> "AUTO"
        shortSide <= 300 -> "240p"
        shortSide <= 576 -> "480p"
        shortSide <= 900 -> "720p"
        shortSide <= 1200 -> "1080p"
        shortSide <= 1800 -> "2K"
        shortSide <= 3000 -> "4K"
        else -> "8K"
    }
    // Colour follows the quality, like the download-speed colours: low = red, then warmer to cooler as it rises.
    val qualityColor: Color? = when (qualityBadge) {
        "240p" -> Color(0xFFFF453A)
        "480p" -> Color(0xFFFF9F0A)
        "720p" -> Color(0xFF30D158)
        "1080p" -> Color(0xFF40C8E0)
        "2K" -> Color(0xFF0A84FF)
        "4K" -> Color(0xFF5E5CE6)
        "8K" -> Color(0xFFBF5AF2)
        else -> null
    }

    LaunchedEffect(aspectIndex, zoom) { if (overlay) onFit?.invoke(aspectIndex, zoom) }

    val fitMode = ASPECTS[aspectIndex].second == AspectRatioFrameLayout.RESIZE_MODE_FIT
    val shownShift = if (fitMode && !pipMode && rootWide == shiftWide) shiftPx else 0f
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
            update = { v -> v.player = player; v.useController = false; v.resizeMode = ASPECTS[aspectIndex].second }
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
                            var lastStep = -1
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
                                    Haptics.fire(null, Hx.DRAG_START)
                                }
                                when (mode) {
                                    1 -> if (dur != C.TIME_UNSET && dur > 0) {
                                        target = (startPos + (tx / size.width * 90_000f).toLong()).coerceIn(0L, dur)
                                        position = target
                                        hud = "${formatDuration(target)}  (${if (target >= startPos) "+" else "-"}${abs(target - startPos) / 1000}s)"
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
                            if (mode == 1 && dur != C.TIME_UNSET) { player.seekTo(target) }
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
                                        Haptics.fire(null, Hx.TAP)
                                        seekBy(if (left) -10_000L else 10_000L)
                                        hud = if (left) "-10 s" else "+10 s"
                                    }
                                } else {
                                    before = controlsVisible
                                    controlsVisible = !controlsVisible; panel = null
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

            // ---- HUD ----
            AnimatedVisibility(hud != null, Modifier.align(Alignment.TopCenter).padding(top = 64.dp), enter = fadeIn(tween(80)), exit = fadeOut(tween(200))) {
                Text(
                    hud.orEmpty(), color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.liquidGlass(Capsule, 1f, 0.05f).padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }

            // Quality switch: the buffer screen is there in the same frame as the tap (last picture stays, slightly dimmed,
            // spinner on top). Any other wait still shows the spinner only once it is noticeable.
            var showSpin by remember { mutableStateOf(false) }
            LaunchedEffect(buffering, resolving, switching) {
                if (switching) showSpin = true
                else if (buffering || resolving) { delay(700); showSpin = true }
                else showSpin = false
            }
            val dim by animateFloatAsState(if (switching) 0.38f else 0f, tween(if (switching) 90 else 160), label = "switchDim")
            if (dim > 0.001f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
            // `switching` shows the spinner in the SAME frame (no wait for the effect above to run first)
            if (switching || (showSpin && (buffering || resolving))) {
                CircularProgressIndicator(color = accent, strokeWidth = 2.5.dp, modifier = Modifier.align(Alignment.Center).size(34.dp))
            }

            // ---- top bar (compact) ----
            AnimatedVisibility(barsVisible, Modifier.align(Alignment.TopCenter), enter = fadeIn(tween(140)), exit = fadeOut(tween(140))) {
                // Same style as the bottom controls: no glass container, just a soft scrim.
                Row(
                    Modifier.fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                        ))
                        .glassTouchShield { touch() }
                        .statusBarsPadding()
                        .padding(start = PlayerEdge, end = PlayerEdge, top = 8.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayerChip(null, AppleIcons.Close, false) { onClose() }
                    // Close · name · size · lock · more: all in this single row, name on one line
                    Text(
                        title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                    )
                    // Same capsule style as the bottom chips; the name gives up space first.
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        // one tap = next picture mode (Fit -> Crop -> Fit ...), no panel; the centre text names the mode
                        PlayerChip(null, if (aspectIndex == 0) AppleIcons.FitScreen else AppleIcons.Crop, aspectIndex != 0) {
                            touch()
                            val next = (aspectIndex + 1) % ASPECTS.size
                            if (screenLandscape) { aspectLand = next; aspectPrefs.edit().putInt("aspectLand", next).apply() }
                            else { aspectPort = next; aspectPrefs.edit().putInt("aspectPort", next).apply() }
                            zoom = 1f
                            panel = null
                            hud = ASPECTS[next].first
                        }
                        Box(Modifier.onGloballyPositioned { lockAt = it.positionInRoot() }) {
                            // own haptic (a firm "click on"), the button's default tap would be swallowed by it
                            PlayerChip(null, AppleIcons.Lock, false, haptic = false) {
                                Haptics.fire(null, Hx.TOGGLE_ON)
                                locked = true; unlockVisible = true; controlsVisible = false; panel = null; hud = "Locked"
                            }
                        }
                        // lit while the sleep timer (which lives in this menu now) is running
                        PlayerChip(null, AppleIcons.MoreVert, panel == PlayerPanel.MORE || sleepMinutes > 0) { panel = if (panel == PlayerPanel.MORE) null else PlayerPanel.MORE }
                    }
                }
            }

            // ---- centre play / pause (one button, no skip buttons) ----
            AnimatedVisibility(
                barsVisible && !buffering && !switching, Modifier.align(Alignment.Center),
                enter = fadeIn(tween(140)) + scaleIn(initialScale = 0.85f, animationSpec = spring(dampingRatio = 0.7f, stiffness = 600f)),
                exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.9f)
            ) {
                val on = playWhenReady && player.playbackState != Player.STATE_ENDED
                PlayPauseDisc(on = on, accent = accent, diameter = 56.dp) { touch(); togglePlay() }
            }

            // ---- bottom bar: ONE row. Progress bar on the left, tools on the right, rotate last ----
            AnimatedVisibility(barsVisible, Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(140)), exit = fadeOut(tween(140))) {
                // The tools (plus rotate) get at most half of the bar; the progress bar keeps the rest.
                // When the tools do not fit (portrait), they scroll inside their half and rotate stays put.
                val toolsHalf = ((LocalConfiguration.current.screenWidthDp - 44) * 0.5f).dp
                val stripMax = maxOf(60.dp, toolsHalf)
                // No master pill any more: the controls float on a soft bottom scrim, the
                // chips keep their own glass, so the bar is lower and lighter.
                Row(
                    Modifier.fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                        ))
                        .glassTouchShield { touch() }
                        .navigationBarsPadding()
                        .padding(start = PlayerEdge, end = PlayerEdge, top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // left: position - progress bar - duration (takes all the space the tools leave)
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatDuration(position), color = TextPrimary, fontSize = 10.sp, maxLines = 1)
                        GlassSlider(
                            value = if (duration > 0L) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f,
                            onValueChange = { if (duration > 0L) { dragging = true; position = it.toLong() }; touch() },
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                            bufferedFraction = if (duration > 0) (bufferedPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                            onValueChangeFinished = { player.seekTo(position); dragging = false }
                        )
                        // length not known yet: the slot keeps its width but shows nothing (no "00:00" flash)
                        Text(formatDuration(duration), color = if (duration > 0L) TextTertiary else Color.Transparent, fontSize = 10.sp, maxLines = 1)
                    }
                    Spacer(Modifier.width(10.dp))
                    // right: tools
                    Row(
                        Modifier.widthIn(max = stripMax).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlayerChip(speedLabel(speed), AppleIcons.Speed, speed != 1f) { panel = if (panel == PlayerPanel.SPEED) null else PlayerPanel.SPEED }
                        // quality switching only makes sense for online streams, not downloaded files
                        if (!isLocal) PlayerChip(qualityLabel.replace(qualityBadge, "").trim().ifBlank { null }, null, false, badge = qualityBadge, badgeColor = qualityColor) { panel = if (panel == PlayerPanel.QUALITY) null else PlayerPanel.QUALITY }
                        if (audioOpts.size > 1) PlayerChip(null, AppleIcons.Audiotrack, false) { panel = if (panel == PlayerPanel.AUDIO) null else PlayerPanel.AUDIO }
                        if (textOpts.isNotEmpty()) PlayerChip(null, AppleIcons.ClosedCaption, !subsOff) { panel = if (panel == PlayerPanel.SUBS) null else PlayerPanel.SUBS }
                    }
                }
            }

            // ---- rotate: big round button on the right edge, easy to reach with the thumb ----
            AnimatedVisibility(
                barsVisible, Modifier.align(Alignment.CenterEnd),
                enter = fadeIn(tween(140)), exit = fadeOut(tween(140))
            ) {
                GlowPress(
                    onClick = { touch(); rotate(activity) },
                    modifier = Modifier.padding(end = PlayerEdge)
                        .size(46.dp).liquidGlass(CircleShape, 1f, 0.05f),
                    shape = CircleShape
                ) {
                    RotateToggleIcon(accent)
                }
            }

            // ---- options panel ----
            if (panel != null) {
                Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { panel = null })
                // The menu opens right next to the button it came from: the three-dots / aspect buttons are in the top
                // bar, so their menu hangs just below it; the speed / quality / audio / subtitle chips are in the
                // bottom bar, so theirs opens just above it. It grows out of the corner nearest to that button.
                val fromTop = panel == PlayerPanel.MORE || panel == PlayerPanel.SLEEP
                val pop = remember(fromTop) { Animatable(0f) }
                LaunchedEffect(fromTop) { pop.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 520f)) }
                Column(
                    Modifier.align(if (fromTop) Alignment.TopEnd else Alignment.BottomEnd)
                        .then(
                            // top bar: status bar + 8 dp + the 28 dp button, then a small gap
                            if (fromTop) Modifier.statusBarsPadding().padding(end = PlayerEdge, top = 42.dp)
                            else Modifier.navigationBarsPadding().padding(end = PlayerEdge, bottom = 60.dp)
                        )
                        .graphicsLayer {
                            val sc = 0.90f + 0.10f * pop.value
                            scaleX = sc; scaleY = sc
                            alpha = pop.value.coerceIn(0f, 1f)
                            transformOrigin = TransformOrigin(1f, if (fromTop) 0f else 1f)
                        }
                        .width(236.dp).heightIn(max = 290.dp)
                        .liquidGlass(RoundedCornerShape(26.dp), 1f, 0.12f).glassTouchShield { touch() }
                        .verticalScroll(rememberScrollState()).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    when (panel) {
                        PlayerPanel.SPEED -> PLAYER_SPEEDS.forEach { v ->
                            PanelRow(speedLabel(v), speed == v) { speed = v; player.setPlaybackSpeed(v); panel = null }
                        }
                        PlayerPanel.QUALITY -> {
                            if (choices.size > 1) {
                                choices.forEachIndexed { i, c ->
                                    PanelRow(MediaTools.playerText(c.label), i == choiceIndex) { onChoice(i); panel = null }
                                }
                            } else {
                                PanelRow("Auto", videoOpts.none { it.selected } || videoOpts.size < 2) { applyTrack(player, C.TRACK_TYPE_VIDEO, null); panel = null }
                                videoOpts.forEach { o -> PanelRow(o.label, o.selected && videoOpts.size > 1) { applyTrack(player, C.TRACK_TYPE_VIDEO, o); panel = null } }
                            }
                        }
                        PlayerPanel.AUDIO -> audioOpts.forEach { o -> PanelRow(o.label, o.selected) { applyTrack(player, C.TRACK_TYPE_AUDIO, o); panel = null } }
                        PlayerPanel.SUBS -> {
                            PanelRow("Off", subsOff) { applyTrack(player, C.TRACK_TYPE_TEXT, null); panel = null }
                            textOpts.forEach { o -> PanelRow(o.label, !subsOff && o.selected) { applyTrack(player, C.TRACK_TYPE_TEXT, o); panel = null } }
                        }
                        PlayerPanel.SLEEP -> {
                            PanelHeader("Sleep timer") { panel = PlayerPanel.MORE }
                            SLEEP_OPTIONS.forEach { m ->
                                PanelRow(if (m == 0) "Off" else "$m minutes", sleepMinutes == m) { sleepMinutes = m; panel = null }
                            }
                        }
                        PlayerPanel.MORE -> {
                            PanelRow(
                                "Sleep timer", sleepMinutes > 0, if (sleepMinutes > 0) "$sleepMinutes min" else "Off",
                                icon = AppleIcons.Bedtime, chevron = true
                            ) { panel = PlayerPanel.SLEEP }
                            PanelDivider()
                            PanelRow("Share", false, icon = AppleIcons.Share) {
                                panel = null
                                runCatching {
                                    context.startActivity(Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, pageUrl ?: playUrl), null))
                                }
                            }
                            PanelRow("Download", false, icon = AppleIcons.Download) { panel = null; onDownload() }
                        }
                        null -> Unit
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
                PlayerChip(null, AppleIcons.LockOpen, true, haptic = false) {
                    Haptics.fire(null, Hx.TOGGLE_OFF)
                    locked = false; controlsVisible = true; hud = "Unlocked"
                }
            }

            // ---- error / resolver failure ----
            val problem = resolveError ?: error
            if (problem != null && !resolving) {
                Column(
                    Modifier.align(Alignment.Center).padding(28.dp).widthIn(max = 320.dp)
                        .liquidGlass(RoundedCornerShape(26.dp), 1f, 0.06f).padding(18.dp),
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

/**
 * Rotation toggle symbol: it shows the orientation a tap switches TO (sideways phone in portrait, upright phone in
 * landscape). When the orientation flips, the old symbol spins out and the new one spins in (about half a second).
 */
/** The main button: accent-tinted glass, the icon morphs between play and pause with a little pop. */
@Composable
private fun PlayPauseDisc(on: Boolean, accent: Color, diameter: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val view = LocalView.current
    val pop = remember { Animatable(1f) }
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(on) {
        if (armed) { pop.snapTo(0.9f); pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f)) }
        armed = true
    }
    GlowPress(
        onClick = { performToggle(view, !on); onClick() },
        modifier = Modifier
            .size(diameter)
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .liquidGlass(CircleShape, 1f, 0.05f)
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(accent.copy(alpha = 0.36f), accent.copy(alpha = 0.16f))),
                CircleShape
            ),
        shape = CircleShape,
        haptic = false
    ) {
        AnimatedContent(
            targetState = on,
            transitionSpec = {
                (fadeIn(tween(160)) + scaleIn(initialScale = 0.55f, animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f))) togetherWith
                    (fadeOut(tween(90)) + scaleOut(targetScale = 0.55f))
            },
            label = "playPauseIcon"
        ) { playing ->
            Icon(
                if (playing) AppleIcons.Pause else AppleIcons.PlayArrow,
                if (playing) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(diameter * 0.46f)
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
    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
        if (e < 1f) Icon(
            symbol(prev), null, tint = tint,
            modifier = Modifier.size(26.dp).graphicsLayer {
                rotationZ = s * 90f * e; alpha = (1f - e * 1.4f).coerceIn(0f, 1f); scaleX = 1f - 0.25f * e; scaleY = scaleX
            }
        )
        Icon(
            symbol(landscape), if (landscape) "Switch to portrait" else "Switch to landscape", tint = tint,
            modifier = Modifier.size(26.dp).graphicsLayer {
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
