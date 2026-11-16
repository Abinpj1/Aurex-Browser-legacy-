package com.aurex.browser

import android.Manifest
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle

class MainActivity : ComponentActivity() {
    private lateinit var state: BrowserState

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!::state.isInitialized) return
            when (intent?.action) {
                ACTION_PIP_PLAY_PAUSE -> {
                    if (!PipSupport.acceptTap(ACTION_PIP_PLAY_PAUSE)) return
                    val wasPlaying = state.tabs.getOrNull(state.current)?.videoPlaying == true
                    aurexWeb()?.togglePlayPauseFromPip()
                    // Optimistic icon flip at once; the page's own <video> events confirm or correct it.
                    pushPipState(playing = !wasPlaying)
                    schedulePipReconcile(450L)
                }
                ACTION_PIP_AUDIO -> {
                    if (!PipSupport.acceptTap(ACTION_PIP_AUDIO)) return
                    enterAudioMode()
                }
                ACTION_PIP_NEXT -> {
                    if (!PipSupport.acceptTap(ACTION_PIP_NEXT)) return
                    if (!PipSupport.hasNextOr(pipHasNext)) return   // greyed out: nothing to skip to
                    aurexWeb()?.playNextFromPip()
                    // A new video starts: show "pause" (it will autoplay); the page reports the truth right after.
                    pushPipState(playing = true)
                    schedulePipReconcile(900L)
                }
                ACTION_PIP_NOOP -> Unit
                ACTION_PIP_CLOSE -> {
                    stopForPipClose()
                    moveTaskToBack(true)
                }
            }
        }
    }

    /** Starting one of our own screens also fires onUserLeaveHint; that must not open PiP. */
    private var suppressPip = false
    private var audioMode = false

    /** Last known mute state of the PiP video (flipped by the left PiP button). */
    private var pipMuted = false

    /** Whether a next video exists; the right PiP button is greyed out while this is false. */
    private var pipHasNext = true

    private fun aurexWeb(): AurexWebView? =
        state.tabs.getOrNull(state.current)?.web as? AurexWebView

    /** Re-sends just the 3 PiP buttons (mute / play-pause / next) to the system. */
    private fun pushPipState(playing: Boolean? = null, muted: Boolean? = null, hasNext: Boolean? = null) {
        if (Build.VERSION.SDK_INT < 26 || !::state.isInitialized) return
        PipSupport.updatePipState(
            this,
            playing ?: (state.tabs.getOrNull(state.current)?.videoPlaying == true),
            muted ?: PipSupport.mutedOr(pipMuted),
            hasNext ?: PipSupport.hasNextOr(pipHasNext)
        )
    }

    /** One delayed re-read of the REAL state after a tap (replaces the old 3-step polling). Coalesced. */
    private var reconcileToken = 0
    private fun schedulePipReconcile(delayMs: Long) {
        val token = ++reconcileToken
        window?.decorView?.postDelayed({
            if (token != reconcileToken || !isInPictureInPictureMode) return@postDelayed
            aurexWeb()?.let { w -> w.evaluateJavascript("window.__aurexReport&&window.__aurexReport()", null) }
            lastPipKey = ""
            PipSupport.resetSent()
            updatePipParams()
        }, delayMs)
    }

    /**
     * PiP left button ("audio only"): the window goes away but the page video keeps running, so only
     * the sound is heard. The foreground service + wake lock keep it alive; the notification and the
     * next app launch bring the picture back (see onResume).
     */
    private fun enterAudioMode() {
        if (!::state.isInitialized) return
        audioMode = true
        state.allowMediaResume()
        aurexWeb()?.let { it.backgroundMediaActive = true; it.armBackgroundTransition() }
        state.startMediaService()
        moveTaskToBack(true)
        val dv = window?.decorView
        // Some launchers keep the PiP window after moveTaskToBack: send the task home as a fallback.
        dv?.postDelayed({
            if (audioMode && isInPictureInPictureMode) {
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }, 450L)
        // The system may pause the page while the window closes: nudge it back (the helper never
        // overrides a pause the user made).
        for (d in longArrayOf(500L, 1500L)) {
            dv?.postDelayed({ if (audioMode) state.pipCommand("resume()") }, d)
        }
    }

    private fun stopForPipClose() {
        state.tabs.getOrNull(state.current)?.web?.evaluateJavascript(
            "window.__lqNoBg=true;setTimeout(function(){window.__lqNoBg=false},6000)", null
        )
        state.pipCommand("pause()")
    }

    override fun startActivity(intent: Intent?, options: Bundle?) {
        suppressPip = true
        // If nothing actually opens (no app found), the flag must not stay stuck.
        window?.decorView?.postDelayed({ suppressPip = false }, 1200)
        super.startActivity(intent, options)
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Full-page screenshots scroll and stitch (BrowserState.captureFullPage): WebView's slow whole-document
        // draw mode is no longer switched on, so scrolling stays light and no restart is needed.
        super.onCreate(savedInstanceState)
        // Rotation / resize: refresh the PiP source rect (cheap: params are only re-sent when the key changes).
        window.decorView.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
            if (r - l != or2 - ol || b - t != ob - ot) updatePipParams()
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        state = BrowserState(this)
        state.initialize(webUrlOf(intent))
        applyDebugIntent(intent)
        if (savedInstanceState == null) handleNotifTap(intent)
        requestHighRefreshRate()
        window.decorView.post { requestHighRefreshRate() }

        val pipFilter = IntentFilter().apply {
            addAction(ACTION_PIP_PLAY_PAUSE)
            addAction(ACTION_PIP_AUDIO)
            addAction(ACTION_PIP_NEXT)
            addAction(ACTION_PIP_CLOSE)
            addAction(ACTION_PIP_NOOP)
        }
        ContextCompat.registerReceiver(
            this, pipReceiver, pipFilter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            val targetAccent = Color(state.effectiveAccent)
            // A moving accent re-themes the whole tree on every frame, which is what made tab
            // switches and page loads stutter. Land on the colour in a single frame instead.
            val accent = targetAccent
            AurexTheme(
                accent = accent,
                schemeAccent = Color(state.accent),
                glass = state.glass,
                haptics = state.haptics,
                glow = state.glow,
                mode = state.themeMode
            ) {
                val dark = LocalPalette.current.dark
                LaunchedEffect(dark, state.darkWeb) {
                    state.syncWebTheme(dark)
                }
                // Keep the PiP window shape and its play/pause button in sync with the video.
                val pipTab    = state.tabs.getOrNull(state.current)
                val vPlaying  = pipTab?.videoPlaying == true
                val vPaused   = pipTab?.videoPaused  == true
                val vw        = pipTab?.videoWidth    ?: 0
                val vh        = pipTab?.videoHeight   ?: 0
                val inlineOn  = state.inlinePlayer
                LaunchedEffect(vPlaying, vPaused, vw, vh, state.autoPip, state.current, inlineOn, state.toolbarLocked) {
                    updatePipParams()
                    // Auto PiP arms itself once the video has played stably for AUTO_PIP_STABLE_MS
                    // (ads / previews never qualify), so the system flag is refreshed right after.
                    if (vPlaying && !inlineOn) {
                        // The arming moment (stable >= 3 s) is time based and no state change happens
                        // exactly then, so one single delayed refresh could miss it (-> no PiP on the first
                        // Home / swipe). Re-check every 500 ms while the video plays; the key check inside
                        // updatePipParams makes this free when nothing changed.
                        delay(AUTO_PIP_STABLE_MS + 150L)
                        while (true) {
                            updatePipParams()
                            delay(500L)
                        }
                    }
                }
                MainScreen(state)
                // Update dialog: optional when one release behind, blocking from two releases behind.
                UpdatePrompt(state.update)
                // Language change veil + start-up language setup page (LangUi.kt)
                LangOverlayHost()
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipReceiver) }
        if (::state.isInitialized) state.release()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        webUrlOf(intent)?.let { state.newTab(it) }
        applyDebugIntent(intent)
        handleNotifTap(intent)
    }

    /** A tap on the download card opens the Downloads list; a tap on the player card opens the page where the video plays. */
    private fun handleNotifTap(i: Intent?) {
        if (i == null || !::state.isInitialized) return
        val what = i.getStringExtra(EXTRA_OPEN) ?: return
        i.removeExtra(EXTRA_OPEN)          // one shot: a later re-delivery of this intent must not reopen it
        when (what) {
            "downloads" -> state.openPanelRequest = Panel.Downloads
            "media" -> state.showMediaPage()
        }
    }

    /**
     * Only web addresses may come in from other apps. An explicit intent can carry any URI
     * (file:, content:, javascript: ...), which the browser would otherwise open in a tab.
     */
    private fun webUrlOf(i: Intent?): String? {
        val d = i?.data ?: return null
        val scheme = d.scheme?.lowercase()
        return if (scheme == "http" || scheme == "https") d.toString() else null
    }

    /** Debug builds only: deterministic test hooks used by automated UI checks. */
    private fun applyDebugIntent(intent: Intent?) {
        if (intent == null) return
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        intent.getStringExtra("liquid_panel")?.let { name ->
            state.openPanelRequest = when (name.lowercase()) {
                "tabs"      -> Panel.Tabs
                "bookmarks" -> Panel.Bookmarks
                "history"   -> Panel.History
                "downloads" -> Panel.Downloads
                "media"     -> Panel.Media
                "settings"  -> Panel.Settings
                else        -> null
            }
        }
        if (intent.getBooleanExtra("liquid_menu", false)) state.openMenuRequest = true
        if (intent.extras?.containsKey("liquid_adult") == true) {
            val want = intent.getBooleanExtra("liquid_adult", false)
            // With the phone lock on, the filter can only be turned on from here, never off.
            if (want || !state.adultLocked) {
                state.blockAdult = want
                state.save()
            }
        }
        intent.getIntExtra("liquid_settings_tab", -1).takeIf { it in 0..6 }?.let { state.settingsTab = it }
        intent.getStringExtra("liquid_toolbar_drag")
            ?.takeIf { it.isNotBlank() }
            ?.let { state.debugTrayDrag = it }
        intent.getStringExtra("liquid_toolbar")?.let { raw ->
            val valid = TOOLBAR_ACTIONS.map { a -> a.first }.filter { it != "menu" }.toSet()
            state.toolbarItems = raw.split(',').map { it.trim() }
                .filter { it in valid }.distinct().take(TOOLBAR_MAX_ITEMS)
            state.save()
        }
        intent.getStringExtra("liquid_url")?.takeIf { it.isNotBlank() }?.let { state.newTab(it) }
        intent.getStringExtra("liquid_download")?.takeIf { it.isNotBlank() }?.let { state.download(it) }
        val scroll = intent.getIntExtra("liquid_scroll", 0)
        if (scroll != 0) {
            state.tabs.getOrNull(state.current)?.web?.evaluateJavascript(
                "window.scrollBy({top:$scroll,behavior:'smooth'})", null
            )
        }
        intent.getStringExtra("liquid_player")?.takeIf { it.isNotBlank() }?.let { url ->
            runCatching {
                startActivity(
                    Intent(this, PlayerActivity::class.java)
                        .putExtra("url", url)
                        .putExtra("title", hostOf(url))
                )
            }
        }
        val theme = intent.getIntExtra("liquid_theme", -1)
        if (theme in 0..2) {
            state.themeMode = theme
            state.save()
        }
    }

    /** Always 3 buttons, bottom centre: [audio/mute, play/pause, next]. See [PipSupport.buildActions]. */
    private fun pipActions(playing: Boolean): List<RemoteAction> =
        PipSupport.buildActions(this, playing, pipMuted, pipHasNext)

    /** What the PiP window currently shows; params are only re-sent when this really changes. */
    private var lastPipKey = ""

    private fun pipKey(): String {
        val cur = state.tabs.getOrNull(state.current)
        val a = PipSupport.aspect(cur?.videoWidth ?: 0, cur?.videoHeight ?: 0)
        val dv = window?.decorView
        return "${cur?.videoPlaying == true}|${a.numerator}/${a.denominator}|$pipMuted|$pipHasNext|${cur != null && autoPipReady(cur)}" +
            "|${state.inlinePlayer}|${dv?.width ?: 0}x${dv?.height ?: 0}"
    }

    private fun buildPipParams(freshHint: Boolean = false): PictureInPictureParams {
        lastPipKey = pipKey()
        PipSupport.resetSent()
        val current  = state.tabs.getOrNull(state.current)
        val isPlaying = current?.videoPlaying == true
        val builder  = PictureInPictureParams.Builder()
            .setAspectRatio(PipSupport.aspect(current?.videoWidth ?: 0, current?.videoHeight ?: 0))
            .setActions(pipActions(isPlaying))
        // Built-in player open: the video fills the window, so the system can animate it straight into the
        // PiP window (smooth hand-over, also for the automatic Home / gesture entry on Android 12+).
        if (state.inlinePlayer) inlineVideoRect(current)?.let { builder.setSourceRectHint(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The system enters PiP by itself when the user leaves the app (Home / gesture / recents),
            // but only if "Auto picture-in-picture" is on and a video has been playing stably.
            builder.setAutoEnterEnabled(current != null && autoPipReady(current))
            builder.setSeamlessResizeEnabled(false)   // video content: system cross-fades the resize
        }
        return builder.build()
    }

    /** Where the letter-boxed video sits inside the window while the built-in player is open (window coordinates). */
    private fun inlineVideoRect(tab: Tab?): Rect? {
        val dv = window?.decorView ?: return null
        val w = dv.width
        val h = dv.height
        if (w <= 0 || h <= 0) return null
        val vw = tab?.videoWidth ?: 0
        val vh = tab?.videoHeight ?: 0
        if (vw <= 0 || vh <= 0) return Rect(0, 0, w, h)
        val ar = vw.toFloat() / vh.toFloat()
        val rw: Int
        val rh: Int
        if (ar > w.toFloat() / h.toFloat()) { rw = w; rh = (w / ar).toInt().coerceAtLeast(1) }
        else { rh = h; rw = (h * ar).toInt().coerceAtLeast(1) }
        val l = (w - rw) / 2
        val t = (h - rh) / 2
        return Rect(l, t, l + rw, t + rh)
    }

    /** Refresh the PiP window (aspect ratio and buttons) while a video plays or changes. */
    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT < 26 || !::state.isInitialized) return
        if (pipKey() == lastPipKey) return
        runCatching { setPictureInPictureParams(buildPipParams()) }
    }

    /** Best-effort 120 fps high performance mode across supported devices. */
    private fun requestHighRefreshRate() {
        runCatching {
            @Suppress("DEPRECATION")
            val d = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
            if (d == null) return
            val cur  = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            val attrs = window.attributes
            attrs.preferredDisplayModeId = best.modeId
            attrs.preferredRefreshRate   = best.refreshRate
            window.attributes = attrs
        }
    }

    /**
     * The one PiP entry point: toolbar button, menu, floating media button and leaving the app.
     * Works the same for YouTube, Instagram and every other website: the page is reduced to the
     * playing <video> (PIP_JS) and this window becomes the PiP window. Only opens while a real video
     * is playing in the current tab.
     */
    fun enterBrowserPip(): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        val current = state.tabs.getOrNull(state.current)
        // Built-in player open: the page is ALREADY reduced to the video (PIP_JS enter() ran when the player
        // opened), so nothing has to be prepared and even a paused video may go to PiP (the user asked for it).
        val inline = state.inlinePlayer
        if (inline) { if (current == null) return false }
        else if (current?.videoPlaying != true) return false
        if (isInPictureInPictureMode) return true
        state.allowMediaResume()
        aurexWeb()?.armBackgroundTransition()
        if (!inline) state.pipCommand("enter()")
        // Controls fade out first so the system animates a clean, video-only frame into the window.
        state.pipEntering = true
        val ok = runCatching { enterPictureInPictureMode(buildPipParams(freshHint = true)) }.getOrDefault(false)
        if (!ok) state.pipEntering = false
        if (!ok && !inline) state.pipCommand("exit()")
        return ok
    }

    /**
     * Android 12+: fired the moment the system starts animating into PiP, also for the automatic
     * Home / gesture entry. Hides the player controls at once so they do not get shrunk into the window.
     */
    @android.annotation.TargetApi(31)
    override fun onPictureInPictureUiStateChanged(uiState: android.app.PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(uiState)
        if (!::state.isInitialized) return
        if (uiState.isTransitioningToPip) {
            state.pipEntering = true
            // If the system backs out of the transition, bring the controls back.
            window?.decorView?.postDelayed({
                if (!isInPictureInPictureMode) state.pipEntering = false
            }, 1500L)
        }
    }

    /** A video has been playing stably for >= [AUTO_PIP_STABLE_MS] and auto PiP is on: leaving the app keeps it alive. */
    private fun autoPipReady(tab: Tab): Boolean {
        // Downloads / History / Tabs / Settings, the menu, the address editor or the find bar may be open in
        // front of the page: that must NOT block PiP. MainScreen closes all of them the moment PiP starts
        // (pipEntering / pipMode), so the window shows the video and coming back lands on the video page.
        // The built-in player owns the video (the user chose to watch it): no ad / preview filtering, ready at once.
        if (state.inlinePlayer) return state.autoPip && tab.videoPlaying
        val stableSince = tab.videoPlayingStableSince
        val stableEnough = stableSince > 0L && System.currentTimeMillis() - stableSince >= AUTO_PIP_STABLE_MS
        return state.autoPip && tab.videoPlaying && stableEnough
    }

    /**
     * Closing the app with Back (last tab, nothing to go back to) while a video plays: instead of
     * killing the video, the page video goes into PiP. Returns true when that started; the caller
     * finishes the activity only when it returns false.
     */
    fun exitWithPip(): Boolean {
        if (Build.VERSION.SDK_INT < 26 || !::state.isInitialized) return false
        if (suppressPip || isInPictureInPictureMode) return false
        val tab = state.tabs.getOrNull(state.current) ?: return false
        val playedFor = if (tab.videoPlayingStableSince > 0L) System.currentTimeMillis() - tab.videoPlayingStableSince else -1L
        if (!(autoPipReady(tab) || (state.autoPip && tab.videoPlaying && playedFor >= 1_200L))) return false
        return enterBrowserPip()
    }

    /**
     * Fix 4: On older Android (< 12) where setAutoEnterEnabled is not available,
     * manually enter PiP when the user presses Home / leaves the app.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (suppressPip) return
        if (!::state.isInitialized || isInPictureInPictureMode) return
        val tab = state.tabs.getOrNull(state.current) ?: return
        // Require the video to have been playing stably for ≥ 3 s before auto-entering PiP.
        // This stops YouTube mid-roll ads (which are real <video> elements) from firing
        // PiP on every home-button press. A genuine watch session always exceeds 3 s.
        val now = System.currentTimeMillis()
        val playedFor = if (tab.videoPlayingStableSince > 0L) now - tab.videoPlayingStableSince else -1L
        // Safety net for the FIRST leave: the strict 3 s clock may have been reset by a buffering gap or an
        // ad -> main video hand-over although the user has been watching. A video playing >= 1.2 s counts.
        // No toolbarLocked check: a panel / menu / address bar in front of the page is closed on PiP entry.
        val autoEnter    = state.autoPip && tab.videoPlaying &&
            (autoPipReady(tab) || playedFor >= 1_200L)
        // Hand-over to the background / PiP: let the page restart a system-paused video for 1.2 s.
        if (tab.videoPlaying) aurexWeb()?.armBackgroundTransition()
        if (autoEnter) enterBrowserPip()
    }

    companion object {
        /** A video must play this long before leaving the app auto-enters PiP (skips ads / previews). */
        const val AUTO_PIP_STABLE_MS = 3_000L
        /** String extra of a notification tap: "downloads" (download card) or "media" (player card). */
        const val EXTRA_OPEN = "aurex_open"
        const val ACTION_PIP_PLAY_PAUSE = "com.aurex.browser.BROWSER_PIP_PLAY_PAUSE"
        const val ACTION_PIP_AUDIO      = "com.aurex.browser.BROWSER_PIP_AUDIO"
        const val ACTION_PIP_CLOSE      = "com.aurex.browser.BROWSER_PIP_CLOSE"
        const val ACTION_PIP_NOOP       = "com.aurex.browser.BROWSER_PIP_NOOP"
        const val ACTION_PIP_NEXT       = "com.aurex.browser.BROWSER_PIP_NEXT"
    }

    /**
     * Fix 4: Called by Android whenever the PiP mode changes.
     *
     * Entering PiP:
     *  1. Hides all browser chrome (top bar, address bar, bottom nav) via [state.pipMode].
     *  2. Injects CSS that makes only the <video> visible and fullscreen inside the PiP window.
     *  3. Resumes playback if the video happened to pause during the transition.
     *
     * Leaving PiP:
     *  1. Removes the injected CSS so the page returns to its normal layout.
     *  2. Restores browser chrome.
     *  3. If the Activity is stopping (user closed PiP with X), pauses the video cleanly.
     */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!::state.isInitialized) return

        // 1. Update Compose state → hides/shows all chrome.
        state.pipMode = isInPictureInPictureMode
        state.pipEntering = false

        if (isInPictureInPictureMode) {
            state.allowMediaResume()
            aurexWeb()?.armBackgroundTransition()
            aurexWeb()?.prepareForPip()
            if (!state.inlinePlayer) state.pipCommand("enter()")   // built-in player: already in video-only layout
            updatePipParams()

            // 2. The page is reduced to the video by PIP_JS (enter()). A second, competing
            //    stylesheet used to be injected here; the two fought over the player's size,
            //    which made the site re-layout / re-buffer and pause the video in a loop.
            if (!state.inlinePlayer) state.togglePipVideoFullscreenCss(true)

            // 3. Continue only if the video stalled during the transition AND the user had not
            //    paused it. The helper decides; a blind play() here used to undo a user pause.
            state.pipCommand("resume()")

            // 4. Audio-only guard: the video picture must really be drawn in the small window.
            //    Checked 3 times while the window settles; each check only acts when frames stopped.
            // NOTE: inside this function `isInPictureInPictureMode` is the PARAMETER (always true here),
            // which used to make revive() run even after the user had returned to the browser and
            // seek the playing video (-> buffering / pause / jitter). Ask the Activity itself.
            for (delayMs in longArrayOf(600L, 1800L)) {
                window?.decorView?.postDelayed({
                    if (this@MainActivity.isInPictureInPictureMode) state.pipCommand("revive()")
                }, delayMs)
            }
        } else {
            // 2. Remove the PiP CSS so the page renders normally again.
            state.togglePipVideoFullscreenCss(false)
            // Back in the browser. A video that was playing in PiP is trusted content from here on:
            // for 4 s a transient "no video" from the page's re-layout is ignored and the stable-play
            // clock is pre-filled, so Home / the PiP button / auto-enter work again at once.
            val retTab = state.tabs.getOrNull(state.current)
            if (retTab?.videoPlaying == true) {
                state.pipTrustUntil = System.currentTimeMillis() + 4_000L
                retTab.videoPlayingStableSince = System.currentTimeMillis() - AUTO_PIP_STABLE_MS
                // The system may pause the page during the hand-over: let the page restart it (1.2 s).
                aurexWeb()?.armBackgroundTransition()
            }
            // Clear PiP flags + resize so YouTube's controls answer the first tap.
            aurexWeb()?.onReturnFromPip(keepTransition = retTab?.videoPlaying == true)
            // Re-arm system auto-enter right away instead of waiting for the next state change.
            lastPipKey = ""
            updatePipParams()
            window?.decorView?.postDelayed({ lastPipKey = ""; updatePipParams() }, 600L)
            window?.decorView?.postDelayed({ lastPipKey = ""; updatePipParams() }, 1800L)

            if (audioMode) return  // audio-only: leave the page as-is so the sound continues

            // Closed with the X (Activity is no longer started) → pause video, like YouTube's own behaviour.
            val closedWithX = lifecycle.currentState == Lifecycle.State.CREATED
            if (closedWithX) stopForPipClose()
            if (state.inlinePlayer && !closedWithX) {
                // PiP -> expanded while the in-app player was open: the video is still full-window, the player
                // controls simply come back. Nothing to restore, nothing to resync.
                return
            }
            state.inlinePlayer = false
            state.pipCommand("exit()")
            state.releaseMediaIfIdle()
        }
    }

    override fun onResume() {
        if (::state.isInitialized) state.update.check()
        super.onResume()
        suppressPip = false
        if (::state.isInitialized) state.pipEntering = false
        if (audioMode && ::state.isInitialized) {
            audioMode = false
            state.pipCommand("exit()")
        }
        if (::state.isInitialized) {
            BrowserState.markLive(state)
            state.pipMode = isInPictureInPictureMode
        }
    }

    /**
     * Fix 2: Do NOT call webView.onPause() or webView.pauseTimers() here.
     *
     * Android Activities remain in the PAUSED lifecycle state while PiP is active.
     * Calling those methods during onPause() freezes the video and kills all JS
     * timers exactly when the PiP window is trying to play. BrowserState.onPause()
     * only persists settings/session — it never touches the WebView.
     */
    override fun onPause() {
        if (::state.isInitialized) state.onPause() // saves session & prefs only
        super.onPause()
        // Note: webView.onPause() and webView.pauseTimers() are intentionally
        // omitted here. The AurexWebView subclass keeps the renderer alive via
        // onWindowVisibilityChanged / onWindowFocusChanged overrides instead.
    }
}
