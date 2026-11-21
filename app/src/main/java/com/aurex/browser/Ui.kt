package com.aurex.browser

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import kotlin.math.abs
import android.os.Build

enum class Panel { Tabs, Bookmarks, History, Downloads, Media, Settings }

/** Toolbar actions users can show, hide and reorder. */
val TOOLBAR_ACTIONS = listOf(
    "back" to "Back",
    "forward" to "Forward",
    "home" to "Home",
    "download" to "Downloads",
    "tabs" to "Tabs",
    "bookmark" to "Bookmark",
    "share" to "Share",
    "private" to "Private tab",
    "reader" to "Reader",
    "desktop" to "Desktop site",
    "screenshot" to "Screenshot",
    "history" to "History",
    "menu" to "Menu"
)

// ── Root screen ──────────────────────────────────────────────────────────────

@Composable
fun MainScreen(s: BrowserState) {
    val act = LocalContext.current as? ComponentActivity
    val tab = s.tabs.getOrNull(s.current) ?: return
    val keyboard = LocalSoftwareKeyboardController.current

    var panel by remember { mutableStateOf<Panel?>(null) }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var shownPanel by remember { mutableStateOf(Panel.Tabs) }
    val openPanel: (Panel) -> Unit = { shownPanel = it; panel = it }
    val dragSign by remember { derivedStateOf { if (s.pageDrag > 0f) 1 else if (s.pageDrag < 0f) -1 else 0 } }

    LaunchedEffect(tab.id) {
        // A tab opened by holding the Tabs button starts with the address bar focused and the keyboard up.
        editing = s.editTabId == tab.id
        if (editing) s.editTabId = -1
        menu = false
        findOpen = false
        findQuery = ""
        s.barVisible = true
        s.cancelShot()
    }
    // Back on a normal tab: the next switch to a private tab asks for the phone lock again.
    LaunchedEffect(tab.incognito) { if (!tab.incognito) s.privateUnlocked = false }
    LaunchedEffect(s.openPanelRequest) {
        s.openPanelRequest?.let { p ->
            openPanel(p)
            s.openPanelRequest = null
        }
    }
    LaunchedEffect(s.closePanelsTick) {
        if (s.closePanelsTick > 0) {
            panel = null
            menu = false
            findOpen = false
        }
    }
    LaunchedEffect(s.openMenuRequest) {
        if (s.openMenuRequest) {
            menu = true
            s.openMenuRequest = false
        }
    }
    LaunchedEffect(editing, menu, panel, findOpen) {
        s.toolbarLocked = editing || menu || panel != null || findOpen
    }
    // PiP starting (Home / gesture / back / PiP button) while something covers the page (Downloads, Settings,
    // Tabs, the menu, the address bar, the find bar, a sheet): close it all, so the PiP window shows the VIDEO
    // and returning from PiP lands on the page where the video plays instead of on that screen again.
    val overlaysOn = !s.bare && !s.pipEntering
    LaunchedEffect(s.pipEntering, s.pipMode) {
        if (s.pipEntering || s.pipMode) {
            panel = null
            menu = false
            editing = false
            if (findOpen) { findOpen = false; findQuery = ""; s.clearFind(tab) }
            s.qualityPrompt = null
            s.screenshot = null
            keyboard?.hide()
        }
    }
    LaunchedEffect(s.snack?.id) {
        if (s.snack != null) {
            delay(2300)
            s.snack = null
        }
    }

    BackHandler {
        when {
            s.inlinePlayer && !s.pipMode -> s.exitInlinePlayer()
            s.shotSession != null -> s.cancelShot()
            s.screenshot != null -> s.screenshot = null
            s.jsDialog != null -> {
                s.jsDialog?.result?.invoke(false, null)
                s.jsDialog = null
            }
            panel != null -> panel = null
            menu -> menu = false
            s.fullscreen != null -> s.exitFullscreen()
            findOpen -> {
                findOpen = false; findQuery = ""; s.clearFind(tab); keyboard?.hide()
            }
            editing -> {
                editing = false; keyboard?.hide()
            }
            s.canGoBack(tab) -> s.navigateBack(tab)
            // first page of a tab: back returns to the start screen (like a normal browser's new-tab page)
            !tab.isHome -> s.navigateBack(tab)
            s.tabs.size > 1 -> s.closeTab(s.current)
            // closing the app while a video plays: the page video goes into PiP instead of being killed
            else -> if ((act as? MainActivity)?.exitWithPip() != true) act?.finish()
        }
    }

    val pageBackdrop = remember { GlassBackdrop() }
    val uiCopyHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    // Never allow 3-dot Menu and Download Quality Sheet to overlap or fight over touches!
    LaunchedEffect(s.qualityPrompt) {
        if (s.qualityPrompt != null && menu) menu = false
    }
    LaunchedEffect(menu) {
        if (menu && s.qualityPrompt != null) s.qualityPrompt = null
    }

    val anyWebWindowOpen = menu || s.qualityPrompt != null || findOpen || s.jsDialog != null || s.screenshot != null || (s.update.visible && s.update.info != null)
    val webCopyPool = remember { arrayOfNulls<android.graphics.Bitmap>(2) }
    var webCopySlot by remember { mutableIntStateOf(0) }

    // While a floating window is open over a webpage playing video (YouTube, Shorts, Reels):
    // continuously copy ONLY the unobstructed video region (strictly OUTSIDE the floating window -> 0 feedback!)
    // so the blurred Liquid Glass live-pulses & moves in real time with the playing video!
    LaunchedEffect(anyWebWindowOpen, tab.videoPlaying, tab.isHome) {
        if (!anyWebWindowOpen || tab.isHome || !tab.videoPlaying || PerfTier.low || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            if (pageBackdrop.ambientBitmap != null) pageBackdrop.setAmbientVideoFrame(null)
            return@LaunchedEffect
        }
        val win = act?.window ?: return@LaunchedEffect
        val decor = win.decorView
        val intervalMs = if (PerfTier.balanced) 340L else 220L
        var inFlight = false
        while (true) {
            val vw = decor.width
            val vh = decor.height
            if (!inFlight && vw > 64 && vh > 64) {
                runCatching {
                    // Unobstructed top video zone (portrait YouTube) or left half (landscape): never overlaps right-bottom popover!
                    val srcRect = if (vh >= vw) {
                        android.graphics.Rect(0, (vh * 0.08f).toInt(), vw, (vh * 0.42f).toInt())
                    } else {
                        android.graphics.Rect(0, 0, (vw * 0.52f).toInt(), vh)
                    }
                    val bw = 120
                    val bh = 80
                    val slot = webCopySlot and 1
                    webCopySlot = slot xor 1
                    val existing = webCopyPool[slot]
                    val bmp = if (existing != null && !existing.isRecycled && existing.width == bw && existing.height == bh) {
                        existing
                    } else {
                        android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.RGB_565).also { webCopyPool[slot] = it }
                    }
                    inFlight = true
                    android.view.PixelCopy.request(win, srcRect, bmp, { res ->
                        inFlight = false
                        if (res == android.view.PixelCopy.SUCCESS) {
                            pageBackdrop.setAmbientVideoFrame(bmp)
                        }
                    }, uiCopyHandler)
                }.onFailure { inFlight = false }
            }
            delay(intervalMs)
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalGlassBackdrop provides pageBackdrop
    ) {
    Box(Modifier.fillMaxSize()) {
        if (!s.bare && tab.isHome) {
            AppBackground()
        } else {
            Box(Modifier.fillMaxSize().background(if (s.bare || LocalPalette.current.dark) Color.Black else LocalPalette.current.bg))
        }

        Box(Modifier.fillMaxSize()) {
            // Live backdrop source: captures WebView webpages AND HomePage into a GPU RenderNode
            // so floating Liquid Glass chrome (toolbar, menu, FAB, quality sheet) blurs & refracts live.
            GlassBackdropHost(
                backdrop = pageBackdrop,
                modifier = Modifier.fillMaxSize(),
                enabled = !s.bare && panel == null && s.fullscreen == null && s.shotSession == null
            ) {
                androidx.compose.runtime.CompositionLocalProvider(LocalGlassBackdrop provides null) {
                    // Current page + (while swiping) its neighbour, each a card. They are keyed by
                    // tab id so a page keeps the very same WebView host when it changes role —
                    // no re-parenting at the end of the swipe, which is what used to blink.
                    val neighbour = if (dragSign != 0 && s.tabs.size > 1)
                        s.tabs[(s.current + (if (dragSign > 0) -1 else 1) + s.tabs.size) % s.tabs.size]
                    else null
                    for (page in listOfNotNull(tab, neighbour).distinct()) {
                        key(page.id) {
                            LaunchedEffect(page.id) { s.ensureLoaded(page) }
                            PageCard(
                                s = s,
                                page = page,
                                isCurrent = page.id == tab.id,
                                swiping = dragSign != 0,
                                liveBackdrop = if (page.id == tab.id && !page.isHome && page.error == null && panel == null && !s.bare && s.fullscreen == null && s.shotSession == null) pageBackdrop else null,
                                onSearch = { editing = true },
                                onPanel = openPanel,
                                onMenu = { menu = true }
                            )
                        }
                    }
                }
            }

            // Edit mode (address bar + keyboard): a touch anywhere except the bar and the keyboard leaves
            // edit mode, like every browser. The keyboard closes and the toolbar drops back down. This
            // catcher sits under the chrome, so the address bar itself keeps working.
            if (editing && !s.bare) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                Haptics.fire(null, Hx.TICK)
                                editing = false
                                keyboard?.hide()
                            }
                        }
                )
            }

            if (!s.bare) {
                val chrome: @Composable () -> Unit = {
                    BrowserChrome(
                        s = s,
                        tab = tab,
                        editing = editing,
                        onEditing = { editing = it },
                        onMenu = { menu = true },
                        onPanel = openPanel
                    )
                }
                if (s.barTop) {
                    ScrollEdgeBlur(
                        isTop = true,
                        active = !tab.isHome && s.shotSession == null && s.fullscreen == null && (s.barVisible || editing),
                        height = 44.dp,
                        strength = 0.28f,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .imePadding()
                    ) { chrome() }
                } else {
                    ScrollEdgeBlur(
                        isTop = false,
                        active = !tab.isHome && s.shotSession == null && s.fullscreen == null,
                        height = 44.dp,
                        strength = if (s.barVisible || editing || s.toolbarLocked) 0.26f else 0.14f,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding()
                    ) { chrome() }
                }
            }

            // In-app player controls over the page's own full-window video.
            AnimatedVisibility(
                visible = s.inlinePlayer && !s.pipMode && !s.pipEntering,
                enter = fadeIn(tween(260, delayMillis = 120)),
                exit = fadeOut(tween(100))
            ) { InlinePlayerHost(s) }

            // Floating in-page media control, above the toolbar on the right.
            // It travels WITH the toolbar: same hide condition and the same spring as BrowserChrome, so when a
            // swipe hides the bar the media / download buttons slide down off the screen together with it
            // (fading and shrinking a little, exactly like the bar) and come back up with it.
            val fabBottom = if (s.barTop) 18.dp else toolbarFootprint(s.toolbarRows) + 20.dp
            val chromeHidden = !s.barVisible && !s.toolbarLocked && !tab.isHome && !editing
            val fabShowState = animateFloatAsState(
                if (chromeHidden) 0f else 1f,
                spring(dampingRatio = 0.82f, stiffness = 420f),
                label = "mediaFabWithBar"
            )
            val fabShown by remember { derivedStateOf { fabShowState.value > 0.04f } }
            AnimatedVisibility(
                visible = (s.mediaFab || (s.downloadFab && s.videoDownload)) &&
                    tabShowsVideo(tab) && !tab.isHome && !s.bare &&
                    s.fullscreen == null &&
                    s.qualityPrompt == null && !menu && (!chromeHidden || fabShown),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 14.dp, bottom = fabBottom)
                    .graphicsLayer {
                        val f = fabShowState.value
                        translationY = (1f - f) * (fabBottom + 110.dp).toPx()
                        alpha = f.coerceIn(0f, 1f)
                        scaleX = 0.92f + 0.08f * f
                        scaleY = 0.92f + 0.08f * f
                        transformOrigin = TransformOrigin(1f, 1f)
                    },
                enter = fadeIn(tween(180)) + slideInVertically { it / 2 } + scaleIn(initialScale = 0.82f),
                exit = fadeOut(tween(150)) + slideOutVertically { it / 2 } + scaleOut(targetScale = 0.86f)
            ) {
                MediaFab(s, tab)
            }

            // Find bar
            if (findOpen && overlaysOn) {
                FindBar(
                    s = s,
                    tab = tab,
                    query = findQuery,
                    onQuery = { findQuery = it; s.find(tab, it) },
                    onClose = {
                        findOpen = false; findQuery = ""; s.clearFind(tab); keyboard?.hide()
                    }
                )
            }

        }

        // Right-side fluid three-dot drawer
        // (not composed at all while PiP starts / is open: removed at once, no exit animation inside the PiP window)
        if (overlaysOn) {
            BrowserMenu(
                visible = menu,
                s = s,
                tab = tab,
                onDismiss = { menu = false },
                onPanel = { p -> openPanel(p); menu = false },
                onFind = { menu = false; findOpen = true }
            )
        }

        // Window bloom + collapse haptics for every full-screen panel (Tabs, Bookmarks, History, Downloads, Media, Settings)
        val panelView = LocalView.current
        var prevPanelOpen by remember { mutableStateOf(false) }
        val isPanelOpen = panel != null
        LaunchedEffect(isPanelOpen) {
            if (isPanelOpen && !prevPanelOpen) Haptics.fire(panelView, Hx.WINDOW_OPEN)
            else if (!isPanelOpen && prevPanelOpen) Haptics.fire(panelView, Hx.WINDOW_CLOSE)
            prevPanelOpen = isPanelOpen
        }

        // Full screen panels — ultra-snappy spring enter + 95ms exit with Frame-0 touch pass-through!
        if (overlaysOn) AnimatedVisibility(
            visible = isPanelOpen,
            enter = if (PerfTier.low) fadeIn(tween(95))
                    else slideInVertically(spring(dampingRatio = 0.86f, stiffness = 580f)) { it / 6 } +
                        fadeIn(tween(115)) +
                        scaleIn(initialScale = 0.98f, animationSpec = spring(dampingRatio = 0.86f, stiffness = 580f)),
            exit  = if (PerfTier.low) fadeOut(tween(75))
                    else slideOutVertically(tween(95)) { it / 10 } +
                        fadeOut(tween(95))
        ) {
            androidx.compose.runtime.CompositionLocalProvider(LocalWindowInteractive provides isPanelOpen) {
                PanelHost(s = s, panel = shownPanel, onClose = { panel = null }, onPanel = { panel = it })
            }
        }

        // Snackbar: drawn AFTER the full-screen panels so toasts (e.g. "Link copied") show on top of them
        AnimatedVisibility(
            visible = s.snack != null && !s.bare,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(
                    bottom = when {
                        panel != null -> 28.dp
                        s.barTop -> 16.dp
                        else -> toolbarFootprint(s.toolbarRows) + 16.dp
                    }
                )
                .padding(horizontal = 24.dp),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 }
        ) {
            Row(
                Modifier
                    .liquidWindowDrag(
                        maxPullDp = 42.dp,
                        dismissDir = LiquidDismissDir.ANY,
                        dismissThresholdDp = 58.dp,
                        onDismiss = { s.snack = null }
                    )
                    .liquidGlass(Capsule, 1.12f, reactive = true)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(7.dp).background(LocalGlassStyle.current.accent, CircleShape))
                Spacer(Modifier.width(9.dp))
                Text(s.snack?.text.orEmpty(), color = TextPrimary, fontSize = 12.sp)
            }
        }

        // Screenshot preview dialog
        s.shotSession?.let { session -> ShotHud(s, session) }
        s.screenshot?.let { shot -> ShotPreview(s, tab, shot) }

        // JS dialogs
        s.jsDialog?.let { d -> JsDialogHost(s, d) }

        // Download quality sheet
        QualitySheetHost(s)

        // Fullscreen video from WebView (never over the in-app player: it would swallow the controls' touches)
        if (!s.inlinePlayer) s.fullscreen?.let { v ->
            val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val fsWindow = (LocalContext.current as? android.app.Activity)?.window
            androidx.compose.runtime.DisposableEffect(landscape, fsWindow) {
                val c = fsWindow?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
                if (landscape) {
                    // landscape video: no status / navigation bar, the whole screen is the video
                    c?.systemBarsBehavior =
                        androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    c?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                } else {
                    c?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                }
                onDispose { c?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
            }
            AndroidView(
                factory = { FrameLayout(it).apply { setBackgroundColor(android.graphics.Color.BLACK) } },
                // Never draw under the status bar / navigation bar / camera cutout.
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .then(if (landscape) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)),
                update = { fl ->
                    if (fl.getChildAt(0) !== v) {
                        fl.removeAllViews()
                        (v.parent as? ViewGroup)?.removeView(v)
                        fl.addView(v, ViewGroup.LayoutParams(-1, -1))
                    }
                }
            )
        }

        // Update prompt rendered in-tree so it live-renders & refracts the exact screen behind it!
        UpdatePrompt(s.update)

        // Private tab lock: drawn last, so nothing of a private tab shows (or can be tapped) before the phone lock.
        PrivateLockCover(s)
    }
    }
}

/** Covers the screen while a private tab is open but the phone lock has not been passed; asks for it right away. */
@Composable
private fun PrivateLockCover(s: BrowserState) {
    val locked = s.privateLocked
    LaunchedEffect(locked) {
        if (locked) s.requirePrivate(onCancel = { s.leavePrivate() }) { }
    }
    if (!locked) return
    val lockBackdrop = remember { GlassBackdrop() }
    Box(
        Modifier
            .fillMaxSize()
            // Swallows every touch so the page underneath can't be used while locked.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AppBackground()
        androidx.compose.runtime.CompositionLocalProvider(
            LocalGlassBackdrop provides lockBackdrop
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(horizontal = 28.dp)
                    .widthIn(max = 360.dp)
                    .liquidWindowDrag(maxPullDp = 44.dp)
                    .liquidGlass(CardShape, 1.2f, 0.04f, reactive = false)
                    .padding(horizontal = 28.dp, vertical = 30.dp)
            ) {
                Box(
                    Modifier.size(76.dp).liquidGlass(CircleShape, 0.95f, reactive = true),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(AppleIcons.Incognito, null, tint = Color(0xFFBF5AF2), modifier = Modifier.size(40.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text("Private tabs are locked", color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Use your phone lock to continue", color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(22.dp))
                GlassPillButton(
                    "Unlock",
                    onClick = { s.requirePrivate(onCancel = { s.leavePrivate() }) { } },
                    icon = AppleIcons.LockOpen,
                    accent = true
                )
                Spacer(Modifier.height(10.dp))
                GlassPillButton("Back to normal tabs", onClick = { s.cancelPrivateUnlock() })
            }
        }
    }
}

private val PageCardGap = 14.dp
private val PageCardRadius = 26.dp

/**
 * One page of the browser. While the link pill is swiped the current page and its
 * neighbour behave like cards: separated by a gap, rounded, the leaving one
 * shrinking slightly and the arriving one growing into place.
 */
@Composable
private fun PageCard(
    s: BrowserState,
    page: Tab,
    isCurrent: Boolean,
    swiping: Boolean = false,
    liveBackdrop: GlassBackdrop? = null,
    onSearch: () -> Unit,
    onPanel: (Panel) -> Unit,
    onMenu: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val d = s.pageDrag
                val span = size.width + PageCardGap.toPx()
                val f = (abs(d) / span).coerceIn(0f, 1f)
                val dir = if (d > 0f) 1f else -1f
                val sc = if (isCurrent) 1f - 0.05f * f else 0.95f + 0.05f * f
                translationX = if (isCurrent) d else d - dir * span
                transformOrigin = TransformOrigin(if (dir > 0f) 0f else 1f, 0.5f)
                scaleX = sc
                scaleY = sc
                // Corners only exist while a swipe is in progress.
                clip = d != 0f
                shape = RoundedCornerShape(PageCardRadius.toPx() * minOf(1f, f * 6f))
            }
    ) {
        val cardBgBackdrop = if (page.isHome) remember { GlassBackdrop() } else null
        // Only paint opaque card backdrop while swiping between tabs so normal resting HomePage stays 0 Hz idle!
        if (page.isHome && (!isCurrent || swiping)) {
            AppBackground()
        }
        WebHost(
            tab = page,
            alpha = if (page.isHome) 0f else 1f,
            liveBackdrop = liveBackdrop,
            modifier = Modifier.statusBarsPadding()
        )
        if (page.isHome && cardBgBackdrop != null) {
            androidx.compose.runtime.CompositionLocalProvider(LocalGlassBackdrop provides cardBgBackdrop) {
                HomePage(s = s, tab = page, onSearch = onSearch, onPanel = onPanel, onMenu = onMenu)
            }
        }
        if (page.error != null && !page.isHome) {
            ErrorPage(
                page.error.orEmpty(),
                onAllow = if (page.error.orEmpty().startsWith("Blocked by the adult") && AdultFilter.canAllow(page.url)) {
                    { s.allowAdultSite(page) }
                } else null
            ) {
                if (page.error.orEmpty().startsWith("Blocked")) {
                    // "Page blocked" screen: Go back to the page before it (or the start page).
                    page.error = null
                    s.navigateBack(page)
                } else {
                    page.error = null
                    page.web?.reload()
                }
            }
        }
    }
}

@Composable
private fun WebHost(
    tab: Tab,
    alpha: Float,
    liveBackdrop: GlassBackdrop? = null,
    modifier: Modifier = Modifier
) {
    val clearBg = LocalPalette.current.bg.toArgb()
    androidx.compose.runtime.DisposableEffect(liveBackdrop, tab.web, alpha, clearBg) {
        val aw = tab.web as? AurexWebView
        if (liveBackdrop != null && alpha > 0f && aw != null) {
            liveBackdrop.attachWebView(aw, clearBg)
            aw.onBackdropDirty = { settleOnly -> liveBackdrop.scheduleWebSnapshot(settleOnly = settleOnly) }
        } else {
            aw?.onBackdropDirty = null
            liveBackdrop?.attachWebView(null)
        }
        onDispose {
            (tab.web as? AurexWebView)?.onBackdropDirty = null
            liveBackdrop?.attachWebView(null)
        }
    }
    key(tab.id) {
        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).apply { setBackgroundColor(android.graphics.Color.TRANSPARENT) }
            },
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha },
            update = { fl ->
                fl.visibility = if (alpha == 0f) View.GONE else View.VISIBLE
                val w = tab.web
                if (w == null) { fl.removeAllViews(); return@AndroidView }
                if (fl.getChildAt(0) !== w) {
                    fl.removeAllViews()
                    (w.parent as? ViewGroup)?.removeView(w)
                    fl.addView(w, ViewGroup.LayoutParams(-1, -1))
                }
                if (liveBackdrop != null && alpha > 0f) {
                    liveBackdrop.attachWebView(w, clearBg)
                    (w as? AurexWebView)?.onBackdropDirty = { settleOnly -> liveBackdrop.scheduleWebSnapshot(settleOnly = settleOnly) }
                }
            }
        )
    }
}

// ── Shared Safari-style chrome ──────────────────────────────────────────────

internal const val TOOLBAR_TOP_CAP = 4
internal val ToolbarBarHeight = 60.dp
internal val ToolbarDoubleHeight = 102.dp
private val ToolbarSlotGap = 3.dp
private const val TOOLBAR_DROP_ID = "__toolbar_drop__"

internal fun toolbarFootprint(rows: Int): Dp =
    if (rows == 1) ToolbarDoubleHeight else ToolbarBarHeight

internal data class ToolbarDrop(val id: String, val index: Int, val leftOfLink: Boolean, val lower: Boolean = false)

/**
 * Shared geometry and transient state for dragging a control from the Settings
 * tray into the live toolbar preview. The preview registers its bounds and slot
 * rectangles; the tray uses a frozen snapshot for the duration of one drag so
 * the insertion target cannot wobble while the placeholder moves the bar.
 */
internal class ToolbarEditState {
    var dragId by mutableStateOf<String?>(null)
        private set
    var dropIndex by mutableIntStateOf(-1)
        private set
    var dropLeft by mutableStateOf(false)
        private set
    var dropLower by mutableStateOf(false)
        private set
    var liveTop = 0
    private var frozenTop = 0
    var pointer by mutableStateOf(Offset.Zero)
        private set

    val slots = mutableStateMapOf<String, Rect>()
    /** Where each toolbox tile sits, so a drag can start from the right spot. */
    val trayOrigins = mutableStateMapOf<String, Rect>()
    var barBounds = Rect.Zero
    var topRowBounds = Rect.Zero
    var extraRowBounds = Rect.Zero
    var addressBounds = Rect.Zero

    private var frozenSlots: Map<String, Rect> = emptyMap()
    private var frozenBar = Rect.Zero
    private var frozenTopRow = Rect.Zero
    private var frozenAddress = Rect.Zero
    private var frozenItems: List<String> = emptyList()
    private var frozenRows = 0
    private var frozenLeft = 0

    fun beginTrayDrag(
        id: String,
        at: Offset,
        items: List<String>,
        rows: Int,
        leftOfLink: Int
    ) {
        dragId = id
        pointer = at
        dropIndex = -1
        dropLeft = false
        frozenSlots = slots.toMap()
        frozenBar = barBounds
        frozenTopRow = topRowBounds
        frozenAddress = addressBounds
        frozenItems = items.toList()
        frozenRows = rows
        frozenLeft = leftOfLink.coerceIn(0, minOf(items.size, TOOLBAR_TOP_CAP))
        frozenTop = liveTop
    }

    fun moveTrayDrag(at: Offset) {
        pointer = at
        val next = resolveDrop(at)
        if (next != dropIndex) dropIndex = next
    }

    fun finishTrayDrag(): ToolbarDrop? {
        val id = dragId
        val index = dropIndex
        val left = dropLeft
        val lower = dropLower
        reset()
        return if (id != null && index >= 0) ToolbarDrop(id, index, left, lower) else null
    }

    fun reset() {
        dragId = null
        dropIndex = -1
        dropLeft = false
        dropLower = false
        pointer = Offset.Zero
        frozenItems = emptyList()
        frozenSlots = emptyMap()
        frozenBar = Rect.Zero
        frozenTopRow = Rect.Zero
        frozenAddress = Rect.Zero
        frozenRows = 0
        frozenLeft = 0
    }

    private fun resolveDrop(pos: Offset): Int {
        val items = frozenItems
        if (frozenBar.width <= 0f || !frozenBar.contains(pos)) {
            dropLeft = false
            dropLower = false
            return -1
        }

        val topCount = frozenTop.coerceIn(0, items.size)
        val leftCount = frozenLeft.coerceIn(0, topCount)
        val lowerRow = frozenRows == 1 &&
            frozenTopRow.height > 0f && pos.y >= frozenTopRow.bottom
        if (lowerRow) {
            dropLeft = false
            dropLower = true
            val lower = items.drop(topCount)
            if (lower.isEmpty()) return topCount
            var insert = nearestInsertion(
                lower.mapIndexedNotNull { i, id ->
                    frozenSlots[id]?.let { topCount + i to it }
                },
                pos
            )
            insert = insert.coerceIn(topCount, items.size)
            return insert
        }

        dropLower = false
        // The address pill itself is a valid target: drop on its left half to
        // place a control before the link, or on its right half to place it
        // after the link. This works even when no left-side control exists yet.
        val overLeftHalf = frozenAddress.width <= 0f || pos.x < frozenAddress.center.x
        if (overLeftHalf) {
            dropLeft = true
            val leftSlots = items.take(leftCount).mapIndexedNotNull { i, id ->
                frozenSlots[id]?.let { i to it }
            }
            return nearestInsertion(leftSlots, pos).coerceIn(0, leftCount)
        }

        dropLeft = false
        val rightSlots = items.drop(leftCount).take(topCount - leftCount)
            .mapIndexedNotNull { i, id -> frozenSlots[id]?.let { leftCount + i to it } }
        if (rightSlots.isEmpty()) return leftCount
        return nearestInsertion(rightSlots, pos).coerceIn(leftCount, topCount)
    }

    private fun nearestInsertion(slots: List<Pair<Int, Rect>>, pos: Offset): Int {
        if (slots.isEmpty()) return 0
        var bestIndex = slots.first().first
        var bestRect = slots.first().second
        var bestDistance = Float.MAX_VALUE
        slots.forEach { (index, rect) ->
            val dx = pos.x - rect.center.x
            val dy = pos.y - rect.center.y
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
                bestRect = rect
            }
        }
        return bestIndex + if (pos.x > bestRect.center.x) 1 else 0
    }
}

private fun toolbarIds(s: BrowserState): List<String> {
    val valid = TOOLBAR_ACTIONS.map { it.first }.filter { it != "menu" }.toSet()
    return s.toolbarItems.filter { it in valid && it != "menu" }.distinct()
}

private class ToolbarDragState {
    var draggingId by mutableStateOf<String?>(null)
    var startIndex by mutableIntStateOf(-1)
    var targetIndex by mutableIntStateOf(-1)
    var dragOffset by mutableStateOf(Offset.Zero)
    var removeArmed by mutableStateOf(false)
    var barOrigin by mutableStateOf(Offset.Zero)
    var barHeight by mutableFloatStateOf(0f)
    /** Bumped after every reorder so the card can glide into its new slot. */
    var snapToken by mutableIntStateOf(0)
    var snapOffset by mutableStateOf(Offset.Zero)
    var snapFrom by mutableStateOf<Rect?>(null)
    var snappingId by mutableStateOf<String?>(null)
    val slots = mutableStateMapOf<String, Rect>()

    fun reset() {
        draggingId = null
        startIndex = -1
        targetIndex = -1
        dragOffset = Offset.Zero
        removeArmed = false
    }
}

@Composable
private fun BrowserChrome(
    s: BrowserState,
    tab: Tab,
    editing: Boolean,
    onEditing: (Boolean) -> Unit,
    onMenu: () -> Unit,
    onPanel: (Panel) -> Unit
) {
    val style = LocalGlassStyle.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember(tab.id) { FocusRequester() }
    // Built the moment editing starts, so the very first frame already shows the
    // link fully selected (no empty-field flash) like Chrome / Safari.
    var field by remember(tab.id, editing) {
        mutableStateOf(
            if (editing) TextFieldValue(tab.url, selection = TextRange(0, tab.url.length))
            else TextFieldValue("")
        )
    }
    val view = LocalView.current
    val hidden = !s.barVisible && !s.toolbarLocked && !tab.isHome && !editing
    val showCompactPill = hidden && s.shotSession == null && s.fullscreen == null
    val barHeight = if (s.toolbarRows == 1) ToolbarDoubleHeight else ToolbarBarHeight

    // Apple iOS 26 Safari spring: full floating bar recedes into an ultra-compact frosted domain
    // capsule while scrolling down, then fluidly expands back on scroll-up or tap.
    val visibleAnimState = animateFloatAsState(
        if (hidden) 0f else 1f,
        spring(dampingRatio = 0.82f, stiffness = 580f),
        label = "chromeVisibility"
    )
    val compactAnimState = animateFloatAsState(
        if (showCompactPill) 1f else 0f,
        spring(dampingRatio = 0.82f, stiffness = 580f),
        label = "compactPill"
    )
    val showFullBar by remember { derivedStateOf { visibleAnimState.value > 0.01f } }
    val showCompact by remember { derivedStateOf { compactAnimState.value > 0.02f } }

    LaunchedEffect(editing, tab.id) {
        if (editing) {
            runCatching { focus.requestFocus() }.onFailure {
                delay(40)
                runCatching { focus.requestFocus() }
            }
            keyboard?.show()
        }
    }

    fun submit() {
        if (field.text.isNotBlank()) s.load(tab, field.text)
        onEditing(false)
        keyboard?.hide()
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = if (s.barTop) Alignment.TopCenter else Alignment.BottomCenter) {
        if (showCompact) {
            Row(
                Modifier
                    .padding(bottom = if (s.barTop) 0.dp else 4.dp, top = if (s.barTop) 4.dp else 0.dp)
                    .graphicsLayer {
                        val v = compactAnimState.value
                        val c = v.coerceIn(0f, 1f)
                        alpha = c
                        val sc = 0.78f + 0.22f * v
                        scaleX = sc
                        scaleY = sc
                        translationY = (if (s.barTop) -1f else 1f) * (1f - c) * 14.dp.toPx()
                        transformOrigin = TransformOrigin(0.5f, if (s.barTop) 0f else 1f)
                    }
                    .height(32.dp)
                    .widthIn(min = 110.dp, max = 210.dp)
                    .liquidGlass(Capsule, 1.05f, 0f, reactive = false, blurProgress = { 1f })
                    .clickableHaptic {
                        s.barVisible = true
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (tab.url.startsWith("https://")) {
                    SafariGlyph(SafariGlyph.Lock, size = 11.dp, tint = GoodText)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    hostOf(tab.url).ifBlank { prettyUrl(tab.url) },
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (showFullBar || editing) Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 5.dp)
                .graphicsLayer {
                    val v = visibleAnimState.value
                    val c = v.coerceIn(0f, 1f)
                    alpha = c
                    translationY = (if (s.barTop) -1f else 1f) * (1f - c) * (barHeight + 36.dp).toPx()
                    scaleX = 0.86f + 0.14f * v
                    scaleY = 0.84f + 0.16f * v
                    transformOrigin = TransformOrigin(0.5f, if (s.barTop) 0f else 1f)
                },
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = editing,
                transitionSpec = {
                    (fadeIn(tween(180)) + scaleIn(spring(dampingRatio = 0.80f, stiffness = 500f), initialScale = 0.94f)) togetherWith
                        (fadeOut(tween(110)) + scaleOut(tween(110), targetScale = 0.96f))
                },
                label = "chromeEditMorph"
            ) { isEdit ->
                if (isEdit) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .liquidGlassBar(Capsule)
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SafariButton(
                            SafariGlyph.Close, "Cancel editing",
                            onClick = { onEditing(false); keyboard?.hide() },
                            size = 38.dp, glyphSize = 17.dp
                        )
                        BasicTextField(
                            value = field,
                            onValueChange = { field = it },
                            singleLine = true,
                            textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                            cursorBrush = SolidColor(style.accent),
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Go,
                                keyboardType = KeyboardType.Uri
                            ),
                            keyboardActions = KeyboardActions(onGo = { submit() }),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 9.dp)
                                .focusRequester(focus),
                            decorationBox = { inner ->
                                Box {
                                    if (field.text.isEmpty()) {
                                        Text("Search or enter website", color = TextTertiary, fontSize = 15.sp)
                                    }
                                    inner()
                                }
                            }
                        )
                        GlassIconButton(
                            AppleIcons.Search, "Search",
                            onClick = { submit() },
                            size = 38.dp, iconSize = 19.dp, filled = false, accent = true
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                } else {
                    ToolbarBar(
                        s = s,
                        tab = tab,
                        rows = s.toolbarRows,
                        onLinkTap = { onEditing(true) },
                        onPanel = onPanel,
                        onMenu = onMenu
                    )
                }
            }
        }
    }
}

/**
 * One shared toolbar used by both the real chrome and the Settings preview.
 * The real bar and the preview can never drift apart because they are the same
 * composable, with identical sizes and materials.
 */
@Composable
internal fun ToolbarBar(
    s: BrowserState,
    tab: Tab,
    rows: Int,
    onLinkTap: () -> Unit,
    onPanel: (Panel) -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    editable: Boolean = false,
    preview: Boolean = false,
    edit: ToolbarEditState? = null,
    onMove: (Int, Int) -> Unit = { _, _ -> },
    onRemove: (String) -> Unit = {}
) {
    val all = toolbarIds(s)
    val doubleRow = rows == 1
    // A double row parks the overflow on the second row; a single row fits
    // every control on the bar and simply shrinks the slots when it gets busy.
    val maxTop = if (doubleRow) s.topCount(all.size) else all.size
    val cap = minOf(all.size, maxTop)
    edit?.liveTop = cap
    val lowerDrop = doubleRow && edit?.dropLower == true
    val rawDrop = edit?.let {
        if (it.dragId != null && it.dropIndex in 0..all.size) it.dropIndex else -1
    } ?: -1
    // The drop marker always stays on a visible row: a single row never
    // overflows, a double row may park it in the second row.
    val dropAt = when {
        rawDrop < 0 -> -1
        lowerDrop -> rawDrop.coerceIn(cap, all.size)
        doubleRow -> rawDrop.coerceIn(0, cap)
        else -> rawDrop.coerceIn(0, all.size)
    }
    val topItems: List<String> = if (dropAt >= 0 && !lowerDrop && dropAt <= cap) {
        all.take(dropAt) + TOOLBAR_DROP_ID + all.drop(dropAt).take(cap - dropAt)
    } else {
        all.take(cap)
    }
    val extraItems: List<String> = when {
        !doubleRow -> emptyList()
        lowerDrop && dropAt >= 0 -> all.subList(cap, dropAt) + TOOLBAR_DROP_ID + all.drop(dropAt)
        else -> all.drop(cap)
    }
    val topCount = topItems.size
    // Slots shrink a little as the row fills up, so the link always keeps a
    // comfortable width and every control stays visible.
    val slotSize = when {
        topCount <= 4 -> 32.dp
        topCount <= 6 -> 29.dp
        topCount <= 8 -> 26.dp
        else -> 24.dp
    }
    val requestedLeft = s.toolbarLeft.coerceIn(0, TOOLBAR_TOP_CAP)
    val displayLeft = if (dropAt >= 0 && edit?.dropLeft == true) {
        (dropAt + 1).coerceIn(0, topCount)
    } else {
        requestedLeft.coerceIn(0, topCount)
    }
    val left = topItems.take(displayLeft)
    val right = topItems.drop(displayLeft)
    val drag = remember { ToolbarDragState() }

    // Card-style glide: after a reorder the control eases into its new slot
    // instead of jumping, exactly like a card being dropped on a board.
    LaunchedEffect(drag.snapToken) {
        val from = drag.snapOffset
        if (from == Offset.Zero) return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (true) {
            val t = ((System.currentTimeMillis() - start) / 300f).coerceIn(0f, 1f)
            val eased = 1f - (1f - t) * (1f - t)
            drag.snapOffset = Offset(from.x * (1f - eased), from.y * (1f - eased))
            if (t >= 1f) break
            withFrameNanos { }
        }
        drag.snapOffset = Offset.Zero
        drag.snappingId = null
    }

    Box(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                val origin = coords.positionInRoot()
                drag.barOrigin = origin
                drag.barHeight = coords.size.height.toFloat()
                edit?.barBounds = Rect(
                    origin.x, origin.y,
                    origin.x + coords.size.width,
                    origin.y + coords.size.height
                )
            }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .liquidGlassBar(RoundedCornerShape(32.dp))
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .onGloballyPositioned { coords ->
                        val origin = coords.positionInRoot()
                        edit?.topRowBounds = Rect(
                            origin.x, origin.y,
                            origin.x + coords.size.width,
                            origin.y + coords.size.height
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ToolbarSlotGap)
            ) {
                left.forEach { id ->
                    key(id) {
                        if (id == TOOLBAR_DROP_ID) {
                            ToolbarDropSlot()
                        } else {
                            ToolbarIconSlot(
                                s, tab, id, all.indexOf(id), all, all,
                                editable, preview, drag, edit, slotSize,
                                onPanel, onMenu, onMove, onRemove
                            )
                        }
                    }
                }
                AddressPill(
                    s = s,
                    tab = tab,
                    onEditing = onLinkTap,
                    modifier = Modifier
                        .weight(1f)
                        .onGloballyPositioned { coords ->
                            val origin = coords.positionInRoot()
                            edit?.addressBounds = Rect(
                                origin.x, origin.y,
                                origin.x + coords.size.width,
                                origin.y + coords.size.height
                            )
                        }
                )
                right.forEach { id ->
                    key(id) {
                        if (id == TOOLBAR_DROP_ID) {
                            ToolbarDropSlot()
                        } else {
                            ToolbarIconSlot(
                                s, tab, id, all.indexOf(id), all, all,
                                editable, preview, drag, edit, slotSize,
                                onPanel, onMenu, onMove, onRemove
                            )
                        }
                    }
                }
                // The menu (⋮) is always the last control on the right edge.
                Spacer(Modifier.width(5.dp))
                ToolbarActionButton(
                    s, tab, "menu", onPanel, onMenu,
                    size = 36.dp, preview = preview
                )
            }
            if (rows == 1) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .onGloballyPositioned { coords ->
                            val origin = coords.positionInRoot()
                            edit?.extraRowBounds = Rect(
                                origin.x, origin.y,
                                origin.x + coords.size.width,
                                origin.y + coords.size.height
                            )
                        },
                    horizontalArrangement = Arrangement.spacedBy(
                        ToolbarSlotGap, Alignment.CenterHorizontally
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (extraItems.isEmpty() && editable && dropAt < 0) {
                        // Empty second row: a drop target for adding a control below.
                        Box(
                            Modifier.weight(1f).height(38.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier.size(30.dp)
                                    .background(LocalGlassStyle.current.accent.copy(alpha = 0.10f), CircleShape)
                            )
                            Text(
                                "+", color = LocalGlassStyle.current.accent.copy(alpha = 0.6f), fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    extraItems.forEach { id ->
                        key(id) {
                            if (id == TOOLBAR_DROP_ID) {
                                ToolbarDropSlot()
                            } else {
                                ToolbarIconSlot(
                                    s, tab, id, all.indexOf(id), all, all,
                                    editable, preview, drag, edit, slotSize,
                                    onPanel, onMenu, onMove, onRemove
                                )
                            }
                        }
                    }
                }
            }
        }

        // Drop-outside-to-remove hint, drawn in the same liquid glass language.
        AnimatedVisibility(
            visible = drag.removeArmed,
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = 16.dp),
            enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.88f),
            exit = fadeOut(tween(90))
        ) {
            Row(
                Modifier.liquidGlass(Capsule, 1.2f).padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(6.dp).background(Bad, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text("Release to remove", color = BadText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** A soft, borderless landing spot shown while a control is dragged in. */
@Composable
private fun ToolbarDropSlot() {
    val style = LocalGlassStyle.current
    Box(
        Modifier.width(32.dp).height(38.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(31.dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            style.accent.copy(alpha = 0.54f),
                            style.accent.copy(alpha = 0.22f),
                            Color.Transparent
                        )
                    ),
                    CircleShape
                )
        )
        Box(Modifier.size(2.2.dp).background(Color.White.copy(alpha = 0.92f), CircleShape))
    }
}

@Composable
private fun ToolbarIconSlot(
    s: BrowserState,
    tab: Tab,
    id: String,
    index: Int,
    all: List<String>,
    visibleIds: List<String>,
    editable: Boolean,
    preview: Boolean,
    drag: ToolbarDragState,
    edit: ToolbarEditState?,
    slotSize: Dp,
    onPanel: (Panel) -> Unit,
    onMenu: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (String) -> Unit
) {
    val view = LocalView.current
    val dragging = drag.draggingId == id
    val topN = if (s.toolbarRows == 1) s.topCount(all.size) else all.size
    val startRow = if (drag.startIndex < topN) 0 else topN
    val targetRow = if (drag.targetIndex < topN) 0 else topN
    val sameRow = startRow == targetRow
    val shiftTarget = when {
        drag.draggingId == null || dragging || !sameRow -> 0f
        index > drag.startIndex && index <= drag.targetIndex -> -1f
        index < drag.startIndex && index >= drag.targetIndex -> 1f
        else -> 0f
    }
    val shiftState = animateFloatAsState(
        shiftTarget,
        spring(dampingRatio = 0.80f, stiffness = 620f),
        label = "slotShift"
    )
    val scaleState = animateFloatAsState(
        if (dragging) 1.14f else 1f,
        spring(dampingRatio = 0.68f, stiffness = 560f),
        label = "slotScale"
    )

    Box(
        Modifier
            .zIndex(if (dragging) 20f else 0f)
            .graphicsLayer {
                translationX = if (dragging) drag.dragOffset.x
                else shiftState.value * (slotSize + 3.dp).toPx() + drag.snapOffset.x
                translationY = if (dragging) drag.dragOffset.y else drag.snapOffset.y
                val sc = scaleState.value
                scaleX = sc
                scaleY = sc
                shadowElevation = if (dragging) 18.dp.toPx() else 0f
            }
            .onGloballyPositioned { coords ->
                val pos = coords.positionInRoot()
                val rect = Rect(
                    pos.x, pos.y,
                    pos.x + coords.size.width,
                    pos.y + coords.size.height
                )
                drag.slots[id] = rect
                // The tray drag needs the same geometry to work out exactly
                // where the control would land if it were dropped here.
                edit?.slots?.set(id, rect)
                // After a reorder the slot moved: remember the difference so the
                // control can glide into its new place instead of teleporting.
                val from = drag.snapFrom
                if (from != null && drag.snappingId == id) {
                    drag.snapOffset = Offset(from.left - rect.left, from.top - rect.top)
                    drag.snapFrom = null
                    drag.snapToken++
                }
            }
            .then(
                if (editable) Modifier.pointerInput(id, index, all) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            drag.draggingId = id
                            drag.startIndex = index
                            drag.targetIndex = index
                            drag.dragOffset = Offset.Zero
                            drag.removeArmed = false
                            performDragStart(view)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            drag.dragOffset += amount
                            val own = drag.slots[id]
                            if (own != null) {
                                val px = own.left + change.position.x
                                val py = own.top + change.position.y
                                var best = index
                                var bestDist = Float.MAX_VALUE
                                visibleIds.forEachIndexed { i, other ->
                                    val r = drag.slots[other]
                                    if (r != null) {
                                        val dx = px - r.center.x
                                        val dy = py - r.center.y
                                        val d = dx * dx + dy * dy
                                        if (d < bestDist) {
                                            bestDist = d
                                            best = i
                                        }
                                    }
                                }
                                drag.targetIndex = best
                                // Pulling well clear of the bar arms the remove
                                // action, so an accidental nudge never deletes.
                                drag.removeArmed =
                                    py < drag.barOrigin.y - 34f ||
                                        py > drag.barOrigin.y + drag.barHeight + 34f
                            }
                        },
                        onDragEnd = {
                            val from = drag.startIndex
                            val to = drag.targetIndex
                            val remove = drag.removeArmed
                            val own = drag.slots[id]
                            drag.reset()
                            if (remove) {
                                onRemove(id)
                            } else if (from >= 0 && to >= 0 && from != to) {
                                onMove(from, to)
                                // Glide the card into its new slot.
                                drag.snapFrom = own
                                drag.snappingId = id
                                drag.snapToken++
                            }
                        },
                        onDragCancel = { drag.reset() }
                    )
                } else Modifier
            )
    ) {
        ToolbarActionButton(
            s, tab, id, onPanel, onMenu,
            size = slotSize, preview = preview
        )
    }
}

/**
 * The exact glyph a toolbar control draws. The Settings toolbox renders the
 * very same artwork so a control looks identical in both places.
 */
@Composable
internal fun ToolbarControlGlyph(
    id: String,
    size: Dp,
    tint: Color = TextPrimary,
    strokeWidth: Dp = 1.9.dp
) {
    when (id) {
        "back" -> SafariGlyph(SafariGlyph.Back, size = size, tint = tint, strokeWidth = strokeWidth)
        "forward" -> SafariGlyph(SafariGlyph.Forward, size = size, tint = tint, strokeWidth = strokeWidth)
        "home" -> SafariGlyph(SafariGlyph.Home, size = size, tint = tint, strokeWidth = strokeWidth)
        "download" -> SafariGlyph(SafariGlyph.Download, size = size, tint = tint, strokeWidth = strokeWidth)
        "bookmark" -> SafariGlyph(SafariGlyph.Bookmark, size = size, tint = tint, strokeWidth = strokeWidth)
        "share" -> SafariGlyph(SafariGlyph.Share, size = size, tint = tint, strokeWidth = strokeWidth)
        "menu" -> SafariGlyph(SafariGlyph.More, size = size, tint = tint, strokeWidth = strokeWidth)
        "tabs" -> Icon(AppleIcons.Tabs, null, tint = tint, modifier = Modifier.size(size))
        "private" -> Icon(AppleIcons.Incognito, null, tint = tint, modifier = Modifier.size(size))
        "reader" -> Icon(AppleIcons.Article, null, tint = tint, modifier = Modifier.size(size))
        "desktop" -> Icon(AppleIcons.DesktopWindows, null, tint = tint, modifier = Modifier.size(size))
        "screenshot" -> Icon(AppleIcons.Screenshot, null, tint = tint, modifier = Modifier.size(size))
        "history" -> Icon(AppleIcons.History, null, tint = tint, modifier = Modifier.size(size))
        else -> Icon(AppleIcons.MoreVert, null, tint = tint, modifier = Modifier.size(size))
    }
}

@Composable
internal fun ToolbarActionButton(
    s: BrowserState,
    tab: Tab,
    id: String,
    onPanel: (Panel) -> Unit,
    onMenu: () -> Unit,
    enabled: Boolean = true,
    size: Dp = 36.dp,
    preview: Boolean = false,
    forceEnabled: Boolean = false
) {
    val activeDownloads = s.downloads.items.count { it.status == DlStatus.RUNNING }
    val glyph = size * 0.55f
    val icon = size * 0.53f
    fun tap(action: () -> Unit): () -> Unit = if (preview) ({}) else action

    when (id) {
        "back" -> SafariButton(
            SafariGlyph.Back, "Back",
            onClick = tap { s.navigateBack(tab) },
            enabled = (forceEnabled || tab.canBack || s.canGoBack(tab) || !tab.isHome) && enabled,
            size = size, glyphSize = glyph
        )
        "forward" -> SafariButton(
            SafariGlyph.Forward, "Forward",
            onClick = tap { tab.web?.goForward() },
            enabled = (forceEnabled || tab.canForward) && enabled, size = size, glyphSize = glyph
        )
        "home" -> SafariButton(
            SafariGlyph.Home, "Home",
            onClick = tap {
                tab.web?.loadUrl("about:blank")
                tab.url = ""
                s.barVisible = true
            },
            size = size, glyphSize = glyph
        )
        "download" -> Box(
            Modifier.pointerInput(s) {
                var totalY = 0f
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (s.swipeDownloadDelete && totalY < -30f) {
                            Haptics.fire(null, Hx.WARN)
                            s.deleteRecentDownload()
                        }
                        totalY = 0f
                    },
                    onDragCancel = { totalY = 0f },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        totalY += amount
                    }
                )
            }
        ) {
            SafariButton(
                SafariGlyph.Download, "Downloads",
                onClick = tap { onPanel(Panel.Downloads) },
                size = size, glyphSize = glyph,
                badge = if (activeDownloads > 0) activeDownloads.toString() else null
            )
        }
        "tabs" -> Box(
            Modifier.pointerInput(s) {
                var totalY = 0f
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (s.swipeTabClose && totalY < -30f) {
                            Haptics.fire(null, Hx.WARN)
                            s.closeCurrentTab()
                        }
                        totalY = 0f
                    },
                    onDragCancel = { totalY = 0f },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        totalY += amount
                    }
                )
            }
        ) {
            TabCounterButton(
                count = s.tabs.size,
                onClick = tap { onPanel(Panel.Tabs) },
                size = size,
                // hold: a fresh tab, address bar focused and keyboard up (same kind of tab as the current one)
                onLongClick = if (!preview && s.holdTabsNewTab) ({
                    val before = s.tabs.getOrNull(s.current)
                    val fresh = s.newTab("", tab.incognito)
                    if (fresh !== before) s.editTabId = fresh.id
                }) else null
            )
        }
        "bookmark" -> SafariButton(
            SafariGlyph.Bookmark, if (s.isBookmarked(tab.url)) "Remove bookmark" else "Bookmark",
            onClick = tap { if (!tab.isHome) s.toggleBookmark(tab) },
            size = size, glyphSize = glyph
        )
        "share" -> SafariButton(
            SafariGlyph.Share, "Share",
            onClick = tap { if (!tab.isHome) s.share(tab.url) },
            size = size, glyphSize = glyph
        )
        "private" -> GlassIconButton(
            AppleIcons.Incognito, "Private tab",
            onClick = tap { s.newTab("", true) },
            size = size, iconSize = icon, filled = true, enabled = enabled
        )
        "reader" -> GlassIconButton(
            AppleIcons.Article, "Reader",
            onClick = tap { s.readerMode(tab) },
            size = size, iconSize = icon, filled = true, enabled = enabled
        )
        "desktop" -> GlassIconButton(
            AppleIcons.DesktopWindows, "Desktop site",
            onClick = tap { s.toggleDesktop() },
            size = size, iconSize = icon, filled = true, enabled = enabled
        )
        "history" -> GlassIconButton(
            AppleIcons.History, "History",
            onClick = tap { onPanel(Panel.History) },
            size = size, iconSize = icon, filled = true, enabled = enabled
        )
        "screenshot" -> GlassIconButton(
            AppleIcons.Screenshot, "Long screenshot",
            onClick = tap { s.captureScreenshot(tab) },
            size = size, iconSize = icon, filled = true, enabled = enabled
        )
        "menu" -> MenuDotsButton(
            onClick = tap(onMenu),
            size = size,
            // hold: straight into Settings
            onLongClick = if (!preview && s.holdMenuSettings) ({ onPanel(Panel.Settings) }) else null
        )
    }
}

/**
 * Is there a video on this tab worth offering a button for? Normal sites: the page script reports it
 * (videoActive). Instagram runs none of our scripts, so there the address of a reel / post / story
 * itself says it (pure string test, nothing is read from the page).
 */
private fun tabShowsVideo(tab: Tab): Boolean {
    if (tab.hasVideo) return true
    val u = tab.url
    // ok.ru plays through a blob / MSE stream that only reports "playing" after the user starts it, and it opens a clicked
    // video as a layer over the feed. The address already names the video, so the buttons do not wait for the script.
    if (YtDlpEngine.isOkVideoPage(u)) return true
    // Meta pages run none of our scripts, so only the address can tell: an Instagram reel / post / story, or a
    // Facebook video page the engine knows. The download then uses the address alone (signed out).
    return isHandsOffHost(u) && (VideoPage.instagramPermalink(u) != null || YtDlpEngine.isItemUrl(u))
}

@Composable
private fun MediaFab(s: BrowserState, tab: Tab) {
    val ctx = LocalContext.current
    // Instagram: only the page address goes to the engine (its CDN links are signed halves of a stream),
    // and the play / PiP button stays away because it would run our scripts inside the page.
    val handsOff = isHandsOffHost(tab.url)
    val url = if (handsOff) null else s.playableMedia(tab)
    val audio = s.playableAudio(tab, url)
    val mime = if (url != null && url.contains(".m3u8", true)) "application/x-mpegURL" else null
    // Two independent switches: the download button (Settings -> Downloads, needs "Video download")
    // and the play / picture-in-picture buttons (Settings -> Media).
    val allowDl = s.videoDownload && s.downloadFab
    val allowPlay = s.mediaFab && !handsOff
    // Blob / MSE streams have no direct link; the page itself can be handed to the engine when it is
    // a video page it knows (YouTube watch page, Instagram reel / story, ...).
    // Any page that plays a video but shows no link (blob / MSE, embed player) can still hand the page to the engine.
    val blobDl = url == null && allowDl &&
        (YtDlpEngine.isInstagramUrl(tab.url) || YtDlpEngine.isItemUrl(tab.url) || tab.hasVideo)
    val showDl = allowDl && (url != null || blobDl)
    if (!showDl && !allowPlay) return        // nothing to offer: no empty glass capsule
    Row(
        Modifier
            .liquidWindowDrag(maxPullDp = 36.dp)
            .liquidGlass(Capsule, 1.18f, 0f, reactive = false)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showDl) {
            GlassIconButton(
                AppleIcons.Download, "Download video",
                onClick = { if (url != null) s.download(url, mime) else s.downloadPlayingPage() },
                size = 38.dp, iconSize = 18.dp, filled = false
            )
            if (allowPlay) Spacer(Modifier.width(3.dp))
        }
        if (allowPlay) {
            if (url != null) {
                GlassIconButton(
                    AppleIcons.PlayArrow, "Open in player",
                    onClick = { s.openPlayer(ctx, tab, url, s.effectiveAccent, audio) },
                    size = 40.dp, iconSize = 23.dp, accent = true
                )
            } else {
                // Blob / MSE sites such as YouTube have no direct stream URL. The
                // page video can still be watched in system PiP.
                GlassIconButton(
                    AppleIcons.PlayArrow, "Open in player",
                    onClick = { s.openPlayer(ctx, tab, "blob:aurex-page", s.effectiveAccent, null) },
                    size = 40.dp, iconSize = 23.dp, accent = true
                )
            }
        }
    }
}

@Composable
internal fun AddressPill(
    s: BrowserState,
    tab: Tab,
    onEditing: () -> Unit,
    modifier: Modifier = Modifier
) {
    val style = LocalGlassStyle.current
    val p by animateFloatAsState(
        if (s.downloads.items.any { it.status == DlStatus.RUNNING })
            s.downloads.items.firstOrNull { it.status == DlStatus.RUNNING }?.progress
                ?: tab.progress.coerceIn(0f, 1f)
        else tab.progress.coerceIn(0f, 1f),
        tween(260),
        label = "pageProgress"
    )
    val url = tab.url
    val view = LocalView.current
    val density = LocalDensity.current.density
    val pageW = LocalConfiguration.current.screenWidthDp * density
    val scope = rememberCoroutineScope()
    val settle = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    val velocity = remember { VelocityTracker() }
    var pillW by remember { mutableFloatStateOf(1f) }
    var dragPx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var crossed by remember { mutableStateOf(false) }
    val canSwipe = s.swipeLinkTabSwitch && s.tabs.size > 1
    val cardGapPx = 10f * density          // gap between link cards in the pill
    val pageGapPx = PageCardGap.value * density   // gap between page cards (matches PageCard)

    /** v = pill offset in px. Pill and page share one progress so they move as a pair. */
    fun setDrag(v: Float) {
        dragPx = v
        val f = v / (pillW + cardGapPx)
        s.pageDrag = f * (pageW + pageGapPx)
    }

    /** Gained distance after which letting go switches tab. Deliberately tiny. */
    fun commitDistance() = minOf((pillW + cardGapPx) * 0.10f, 20f * density)

    fun startDrag() {
        settle[0]?.cancel()
        dragging = true
        crossed = false
    }

    fun dragBy(amount: Float) {
        val travel = pillW + cardGapPx
        // 1.8x gain: a short thumb movement already carries the card most of the way.
        var v = dragPx + amount * 1.8f
        // Circular carousel: carrying a card all the way over lands that tab and the finger keeps
        // going into the next one, and after the last tab comes the first again.
        var hops = 0
        while (abs(v) >= travel && s.tabs.size > 1 && hops < s.tabs.size) {
            val sign = if (v > 0f) 1f else -1f
            if (sign > 0f) s.switchToPrevTab() else s.switchToNextTab()
            v -= sign * travel
            hops++
            performTap(view)
        }
        setDrag(v.coerceIn(-travel, travel))
        val now = abs(dragPx) > commitDistance()
        if (now != crossed) { crossed = now; if (hops == 0) performThreshold(view) }
    }

    fun finishDrag(vx: Float, allowCommit: Boolean) {
        val v = dragPx
        val travel = pillW + cardGapPx
        val sign = if (v > 0f) 1f else -1f
        val flick = abs(vx) > 150f * density && vx * sign > 0f && abs(v) > 4f * density
        val commit = allowCommit && (abs(v) > commitDistance() || flick)
        dragging = false
        settle[0] = scope.launch {
            if (commit) {
                animate(
                    v, sign * travel,
                    initialVelocity = if (vx * sign > 0f) vx else 0f,
                    animationSpec = spring(dampingRatio = 1f, stiffness = 700f)
                ) { x, _ -> setDrag(x) }
                // Same frame: tab switches and the offsets reset, so nothing blinks.
                if (sign > 0f) s.switchToPrevTab() else s.switchToNextTab()
                setDrag(0f)
            } else {
                animate(
                    v, 0f,
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 460f)
                ) { x, _ -> setDrag(if (v > 0f) x.coerceAtLeast(0f) else x.coerceAtMost(0f)) }
            }
        }
    }

    // Only the sign matters for composition; the offset itself is read in draw lambdas.
    val dragSign by remember { derivedStateOf { if (dragPx > 0f) 1 else if (dragPx < 0f) -1 else 0 } }
    val neighbor = if (dragSign != 0 && s.tabs.size > 1)
        s.tabs[(s.current + (if (dragSign > 0) -1 else 1) + s.tabs.size) % s.tabs.size]
    else null

    Box(
        modifier
            .height(40.dp)
            .onSizeChanged { pillW = it.width.toFloat().coerceAtLeast(1f) }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(Capsule)
                .pointerInput(canSwipe, s.tabs.size > 1) {
                    if (!canSwipe) return@pointerInput
                    // Own gesture loop with a small slop (6dp instead of the default 8dp+)
                    // so the card starts following the finger almost immediately.
                    val slop = viewConfiguration.touchSlop * 1.15f
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        velocity.resetTracking()
                        velocity.addPosition(down.uptimeMillis, down.position)
                        var started = false
                        var accX = 0f
                        var accY = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val ch = event.changes.firstOrNull { it.id == down.id }
                            if (ch == null) {
                                if (started) finishDrag(0f, false)
                                return@awaitEachGesture
                            }
                            if (ch.changedToUpIgnoreConsumed()) {
                                if (started) finishDrag(velocity.calculateVelocity().x, true)
                                return@awaitEachGesture
                            }
                            velocity.addPosition(ch.uptimeMillis, ch.position)
                            val dx = ch.position.x - ch.previousPosition.x
                            val dy = ch.position.y - ch.previousPosition.y
                            if (!started) {
                                if (ch.isConsumed) return@awaitEachGesture
                                accX += dx
                                accY += dy
                                if (abs(accX) > slop && abs(accX) > abs(accY) * 1.2f) {
                                    started = true
                                    startDrag()
                                    dragBy(accX)
                                    ch.consume()
                                } else if (abs(accY) > slop * 1.6f) {
                                    return@awaitEachGesture
                                }
                            } else {
                                ch.consume()
                                dragBy(dx)
                            }
                        }
                    }
                }
                .pointerInput(url, view) {
                    detectTapGestures(
                        // Like every other browser: tap = edit the address, link pre-selected.
                        onTap = {
                            performTap(view)
                            onEditing()
                        },
                        onLongPress = {
                            if (url.isNotBlank()) { performHold(view); s.copy(url) }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // Each link is its own glass card; the next one replaces the current one.
            for (t in listOfNotNull(tab, neighbor).distinct()) {
                key(t.id) {
                    val isCurrent = t.id == tab.id
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val d = dragPx
                                val span = pillW + cardGapPx
                                val f = (abs(d) / span).coerceIn(0f, 1f)
                                val dir = if (d > 0f) 1f else -1f
                                // The pill keeps its exact shape: it only slides and fades.
                                translationX = if (isCurrent) d else d - dir * span
                                alpha = if (isCurrent) 1f - 0.45f * f else 0.55f + 0.45f * f
                            }
                            .glassTile(Capsule, 0.085f)
                    ) { PillContent(t) }
                }
            }
            if (p < 0.999f) {
                GlassProgressBar(
                    p,
                    Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = 2.dp),
                    height = 1.5.dp
                )
            }
        }
    }
}

@Composable
private fun PillContent(tab: Tab) {
    val view = LocalView.current
    val loading = tab.progress < 0.999f
    Row(
        Modifier.fillMaxSize().padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (tab.isHome) {
            SafariGlyph(SafariGlyph.Search, size = 15.dp, tint = TextSecondary)
        } else {
            SiteBadge(hostOf(tab.url), 19.dp, bitmap = tab.favicon)
        }
        Spacer(Modifier.width(7.dp))
        Text(
            if (tab.isHome) "Search or enter website" else prettyUrl(tab.url),
            color = if (tab.isHome) TextSecondary else TextPrimary,
            fontSize = 13.5.sp,
            fontWeight = if (tab.isHome) FontWeight.Normal else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!tab.isHome) {
            if (tab.url.startsWith("https://")) {
                SafariGlyph(SafariGlyph.Lock, size = 12.dp, tint = GoodText)
            }
            // Reload — turns into stop while the page is loading, like every browser.
            Box(
                Modifier
                    .padding(start = 2.dp)
                    .size(32.dp)
                    .pointerInput(tab.id, loading) {
                        detectTapGestures(onTap = {
                            performTap(view)
                            if (loading) tab.web?.stopLoading()
                            else {
                                tab.error = null
                                tab.web?.reload()
                            }
                        })
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (loading) AppleIcons.Close else AppleIcons.Refresh,
                    if (loading) "Stop loading" else "Reload",
                    tint = TextPrimary,
                    modifier = Modifier.size(17.5.dp)
                )
            }
        }
    }
}

// ── Three-dot fluid drawer ───────────────────────────────────────────────────

fun menuActionIcon(action: MenuAction): ImageVector = when (action) {
    MenuAction.DOWNLOADS -> AppleIcons.Download
    MenuAction.TABS -> AppleIcons.Tabs
    MenuAction.NEW_TAB -> AppleIcons.Add
    MenuAction.PRIVATE -> AppleIcons.Incognito
    MenuAction.BOOKMARK -> AppleIcons.Bookmark
    MenuAction.HISTORY -> AppleIcons.History
    MenuAction.SHARE -> AppleIcons.Share
    MenuAction.FIND -> AppleIcons.Search
    MenuAction.HOME -> AppleIcons.Home
    MenuAction.DESKTOP -> AppleIcons.DesktopWindows
    MenuAction.DARKSITE -> AppleIcons.DarkMode
    MenuAction.READER -> AppleIcons.Article
    MenuAction.SCREENSHOT -> AppleIcons.Screenshot
    MenuAction.SETTINGS -> AppleIcons.Settings
}

@Composable
private fun BrowserMenu(
    visible: Boolean,
    s: BrowserState,
    tab: Tab,
    onDismiss: () -> Unit,
    onPanel: (Panel) -> Unit,
    onFind: () -> Unit
) {
    val scroll = rememberScrollState()
    val atTop = s.barTop
    val pinned = s.menuPinned.mapNotNull { MenuAction.byId(it) }
    val dismiss = onDismiss

    LiquidFloatingWindow(
        visible = visible,
        onDismiss = dismiss,
        fromTop = atTop,
        anchorOffset = toolbarFootprint(s.toolbarRows) + 10.dp
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            pinned.forEachIndexed { idx, action ->
                key(action.id) {
                    StaggerItem(idx) {
                        MenuRow(
                            icon = menuActionIcon(action),
                            label = actionLabel(s, tab, action),
                            subtitle = null,
                            value = actionValue(s, action),
                            active = actionActive(s, tab, action),
                            accent = true,
                            onClick = { if (visible) performMenuAction(s, tab, action, dismiss, onPanel, onFind) }
                        )
                    }
                }
            }

            if (pinned.isEmpty()) {
                Text(
                    "No actions selected yet.", color = TextTertiary, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun StaggerItem(index: Int, content: @Composable () -> Unit) {
    if (PerfTier.low) { content(); return }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(12L + (index.coerceAtMost(6) * 10L))
        shown = true
    }
    val aState = animateFloatAsState(if (shown) 1f else 0f, tween(120), label = "rowAlpha")
    val dxState = animateFloatAsState(
        if (shown) 0f else 14f,
        spring(dampingRatio = 0.82f, stiffness = 680f),
        label = "rowSlide"
    )
    Box(Modifier.graphicsLayer { alpha = aState.value.coerceIn(0f, 1f); translationX = dxState.value }) { content() }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    value: String? = null,
    active: Boolean = false,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    val style = LocalGlassStyle.current
    val dark = LocalPalette.current.dark
    val iconTint = when {
        active || accent -> if (dark) androidx.compose.ui.graphics.lerp(style.accent, Color.White, 0.48f) else style.accent
        else -> TextPrimary
    }
    val rowShape = RoundedCornerShape(13.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .then(if (active) Modifier.background(style.accent.copy(alpha = if (dark) 0.22f else 0.14f), rowShape) else Modifier)
            .clickableHaptic(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(31.dp)
                .glassTile(CircleShape, if (active || accent) 0.20f else 0.12f, if (active || accent) style.accent else Color.White),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, null,
                tint = iconTint,
                modifier = Modifier.size(17.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label, color = TextPrimary, fontSize = 13.5.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(subtitle, color = TextSecondary, fontSize = 10.5.sp, maxLines = 1)
            }
        }
        if (value != null) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .clip(Capsule)
                    .background(
                        if (active) style.accent else (if (dark) Color(0xFF0A0D16).copy(alpha = 0.56f) else Color.Black.copy(alpha = 0.09f)),
                        Capsule
                    )
                    .padding(horizontal = 7.dp, vertical = 2.5.dp)
            ) {
                Text(
                    value,
                    color = if (active) Color.White else TextPrimary,
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun actionLabel(s: BrowserState, tab: Tab, action: MenuAction): String = when (action) {
    MenuAction.BOOKMARK -> if (s.isBookmarked(tab.url)) "Remove bookmark" else "Add bookmark"
    MenuAction.DARKSITE -> if (s.darkOnFor(tab.url)) "Dark mode · on" else "Dark mode · off"
    else -> action.label
}

private fun actionValue(s: BrowserState, action: MenuAction): String? = when (action) {
    MenuAction.DOWNLOADS -> s.downloads.items.count { it.status == DlStatus.RUNNING }
        .takeIf { it > 0 }?.toString()
    MenuAction.TABS -> s.tabs.size.toString()
    MenuAction.DESKTOP -> if (s.desktop) "On" else "Off"
    else -> null
}

private fun actionActive(s: BrowserState, tab: Tab, action: MenuAction): Boolean = when (action) {
    MenuAction.DESKTOP -> s.desktop
    MenuAction.DARKSITE -> s.darkOnFor(tab.url)
    MenuAction.BOOKMARK -> s.isBookmarked(tab.url)
    else -> false
}

private fun performMenuAction(
    s: BrowserState,
    tab: Tab,
    action: MenuAction,
    dismiss: () -> Unit,
    onPanel: (Panel) -> Unit,
    onFind: () -> Unit
) {
    when (action) {
        MenuAction.DOWNLOADS -> onPanel(Panel.Downloads)
        MenuAction.TABS -> onPanel(Panel.Tabs)
        MenuAction.NEW_TAB -> { dismiss(); s.newTab() }
        MenuAction.PRIVATE -> { dismiss(); s.newTab("", true) }
        MenuAction.BOOKMARK -> { if (!tab.isHome) s.toggleBookmark(tab) }
        MenuAction.HISTORY -> onPanel(Panel.History)
        MenuAction.SHARE -> { if (!tab.isHome) s.share(tab.url); dismiss() }
        MenuAction.FIND -> onFind()
        MenuAction.HOME -> {
            tab.web?.loadUrl("about:blank")
            tab.url = ""
            s.barVisible = true
            dismiss()
        }
        MenuAction.DESKTOP -> { dismiss(); s.toggleDesktop() }
        MenuAction.DARKSITE -> { dismiss(); if (!tab.isHome) s.toggleDarkForSite(tab) }
        MenuAction.READER -> { dismiss(); s.readerMode(tab) }
        MenuAction.SCREENSHOT -> {
            dismiss()
            s.captureScreenshot(tab)
        }
        MenuAction.SETTINGS -> onPanel(Panel.Settings)
    }
}

// ── Find bar ─────────────────────────────────────────────────────────────────

@Composable
private fun FindBar(
    s: BrowserState,
    tab: Tab,
    query: String,
    onQuery: (String) -> Unit,
    onClose: () -> Unit
) {
    val fbView = LocalView.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        Haptics.fire(fbView, Hx.WINDOW_OPEN)
        onDispose { Haptics.fire(fbView, Hx.WINDOW_CLOSE) }
    }
    GlassLiveWindowLease(active = true)
    // Spring entrance: the find bar glass pill slides down from above.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        enter.animateTo(1f, spring(dampingRatio = 0.80f, stiffness = 460f))
    }
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = if (s.barTop) 76.dp else 6.dp, start = 12.dp, end = 12.dp)
            .graphicsLayer {
                alpha = enter.value.coerceIn(0f, 1f)
                translationY = -16.dp.toPx() * (1f - enter.value)
                val sc = 0.97f + 0.03f * enter.value
                scaleX = sc; scaleY = sc
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .liquidWindowDrag(
                    maxPullDp = 40.dp,
                    dismissDir = LiquidDismissDir.UP,
                    dismissThresholdDp = 54.dp,
                    onDismiss = onClose
                )
                .liquidGlass(Capsule, 1.14f, reactive = false, blurProgress = { 1f })
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(AppleIcons.Search, null, tint = TextSecondary, modifier = Modifier.padding(start = 8.dp).size(17.dp))
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(LocalGlassStyle.current.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { s.findNext(tab, true) }),
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text("Find in page", color = TextTertiary, fontSize = 14.5.sp)
                        inner()
                    }
                }
            )
            if (tab.findTotal > 0) {
                Box(
                    Modifier
                        .clip(Capsule)
                        .background(LocalGlassStyle.current.accent.copy(alpha = 0.16f), Capsule)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        "${tab.findCurrent}/${tab.findTotal}",
                        color = LocalGlassStyle.current.accent, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            SafariButton(
                SafariGlyph.Back, "Previous match",
                { s.findNext(tab, false) }, size = 30.dp, glyphSize = 14.dp
            )
            SafariButton(
                SafariGlyph.Forward, "Next match",
                { s.findNext(tab, true) }, size = 30.dp, glyphSize = 14.dp
            )
            SafariButton(
                SafariGlyph.Close, "Close find",
                onClose, size = 30.dp, glyphSize = 14.dp
            )
        }
    }
}

// ── Home page ────────────────────────────────────────────────────────────────

data class HomeSite(val name: String, val url: String)

private val DEFAULT_SITES = listOf(
    HomeSite("Google", "https://www.google.com"),
    HomeSite("YouTube", "https://youtube.com"),
    HomeSite("Wikipedia", "https://www.wikipedia.org"),
    HomeSite("GitHub", "https://github.com"),
    HomeSite("Reddit", "https://www.reddit.com"),
    HomeSite("X", "https://x.com"),
    HomeSite("Instagram", "https://www.instagram.com"),
    HomeSite("ChatGPT", "https://chatgpt.com"),
    HomeSite("Maps", "https://maps.google.com"),
    HomeSite("News", "https://news.google.com"),
    HomeSite("Amazon", "https://www.amazon.com"),
    HomeSite("Stack Overflow", "https://stackoverflow.com")
)

@Composable
private fun HomePage(
    s: BrowserState,
    tab: Tab,
    onSearch: () -> Unit,
    onPanel: (Panel) -> Unit,
    onMenu: () -> Unit
) {
    val style = LocalGlassStyle.current
    val scroll = rememberScrollState()
    val liquidScroll = rememberGlassScrollState()
    val homeScrolled by remember { derivedStateOf { scroll.value > 10 } }
    val favorites = remember(s.bookmarks.size) {
        (s.bookmarks.take(6).map { HomeSite(hostOf(it.url), it.url) } + DEFAULT_SITES)
            .distinctBy { hostOf(it.url) }
            .take(12)
    }
    val engineName = ENGINES[s.engine.coerceIn(0, ENGINES.lastIndex)].name

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .liquidOverscrollJelly()
                .liquidScrollTrack(liquidScroll)
                .verticalScroll(scroll)
                .statusBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(
                    top = 14.dp,
                    bottom = when {
                        s.barTop -> 32.dp
                        s.toolbarRows == 1 -> 158.dp
                        else -> 108.dp
                    }
                )
        ) {
            // ── iOS 27 Top Brand Bar + Floating Glass Control Cluster ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                AurexMark(44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Aurex", color = TextPrimary, fontSize = 24.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp
                    )
                    Text(
                        if (tab.incognito) "Private Browsing" else "Liquid Glass · Private",
                        color = if (tab.incognito) Color(0xFFBF5AF2) else TextSecondary,
                        fontSize = 12.sp, fontWeight = FontWeight.Medium
                    )
                }
                Row(
                    Modifier
                        .liquidGlass(Capsule, 1.04f, 0f, reactive = false)
                        .padding(horizontal = 3.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    GlassIconButton(
                        AppleIcons.Settings, "Settings",
                        onClick = { onPanel(Panel.Settings) },
                        size = 38.dp, iconSize = 19.dp, filled = false
                    )
                    GlassIconButton(
                        AppleIcons.MoreVert, "Menu",
                        onClick = onMenu,
                        size = 38.dp, iconSize = 19.dp, accent = true
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // ── Hero Greeting ──
            Text(
                if (tab.incognito) "Private Start Page" else "Where to next?",
                color = TextPrimary, fontSize = 26.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp
            )
            Spacer(Modifier.height(3.dp))
            Text(
                if (tab.incognito) "History and cookies stay off this device." else "Private by design. Fast by default.",
                color = TextSecondary, fontSize = 13.5.sp
            )
            Spacer(Modifier.height(14.dp))

            // ── Hero Floating Safari iOS 27 Liquid Glass Search Capsule ──
            GlowPress(
                onClick = onSearch,
                shape = Capsule,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .liquidScrollCard(liquidScroll, edgeScale = 0.025f, edgeTilt = 1.8f)
                    .liquidGlass(Capsule, 1.05f, 0.04f, reactive = true)
            ) {
                Row(
                    Modifier.fillMaxSize().padding(start = 10.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .glassTile(CircleShape, 0.16f, style.accent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(AppleIcons.Search, null, tint = style.accent, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(11.dp))
                    Text(
                        "Search or enter website",
                        color = TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        Modifier
                            .clip(Capsule)
                            .background(TextPrimary.copy(alpha = 0.08f), Capsule)
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(engineName, color = TextSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Apple Safari Privacy Report Glass Card ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .liquidScrollCard(liquidScroll, edgeScale = 0.03f, edgeTilt = 2.0f)
                    .liquidGlass(CardShape, 0.96f, 0.04f, reactive = true)
                    .clickableHaptic { onPanel(Panel.Settings) }
                    .padding(horizontal = 13.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(42.dp).glassTile(CircleShape, 0.18f, Good),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(AppleIcons.Shield, null, tint = Good, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${s.blocked} threats blocked",
                        color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.2).sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${s.blocker.hostCount} hosts · ${s.tabs.size} tab${if (s.tabs.size > 1) "s" else ""}",
                        color = TextSecondary, fontSize = 12.sp
                    )
                }
                Box(
                    Modifier
                        .clip(Capsule)
                        .background(Good.copy(alpha = 0.16f), Capsule)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text("Shield on", color = GoodText, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // ── Favorites (Apple iOS 27 Concentric Glass Squircles) ──
            if (s.showHomeSites) {
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Favorites", color = TextPrimary, fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "Bookmarks", color = style.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(Capsule).clickableHaptic { onPanel(Panel.Bookmarks) }.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))

                val squircle = RoundedCornerShape(17.dp)
                favorites.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { site ->
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clip(TileShape)
                                    .clickableHaptic { s.load(tab, site.url) }
                                    .padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    Modifier
                                        .size(50.dp)
                                        .liquidGlass(squircle, 0.92f, 0f, reactive = true, optical = true),
                                    contentAlignment = Alignment.Center
                                ) { SiteBadge(hostOf(site.url), 34.dp, site.name) }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    site.name, color = TextPrimary, fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            // ── Recent Sites Glass Strip ──
            if (s.showRecent && s.history.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Recent", color = TextPrimary, fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "History", color = style.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(Capsule).clickableHaptic { onPanel(Panel.History) }.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    s.history.take(8).forEach { h ->
                        GlowPress(
                            onClick = { s.load(tab, h.url) },
                            shape = Capsule,
                            modifier = Modifier.liquidGlass(Capsule, 0.92f, 0f, reactive = true, optical = true)
                        ) {
                            Row(
                                Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SiteBadge(hostOf(h.url), 28.dp)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(hostOf(h.url), color = TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text(timeAgo(h.time), color = TextSecondary, fontSize = 10.5.sp)
                                }
                            }
                        }
                    }
                }
            }

            // ── Quick Actions Glass Dock ──
            Spacer(Modifier.height(22.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .liquidScrollCard(liquidScroll, edgeScale = 0.025f, edgeTilt = 1.6f),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HomeAction("New tab", AppleIcons.Add, style.accent, Modifier.weight(1f)) { s.newTab() }
                HomeAction("Private", AppleIcons.Incognito, Color(0xFFBF5AF2), Modifier.weight(1f)) { s.newTab("", true) }
                HomeAction("Downloads", AppleIcons.Download, Color(0xFF32ADE6), Modifier.weight(1f)) { onPanel(Panel.Downloads) }
            }
        }
        ScrollEdgeBlur(
            isTop = true,
            active = homeScrolled,
            height = 56.dp,
            strength = 0.82f,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

@Composable
private fun HomeAction(
    label: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    GlowPress(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .height(42.dp)
            .liquidGlass(RoundedCornerShape(16.dp), 0.95f, 0f, reactive = true, optical = true)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                label, color = TextPrimary, fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ── Error + dialog overlays ──────────────────────────────────────────────────

@Composable
private fun ErrorPage(message: String, onAllow: (() -> Unit)? = null, onRetry: () -> Unit) {
    val blocked = message.startsWith("Blocked")
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        AppBackground()
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 370.dp)
                .liquidWindowDrag(maxPullDp = 42.dp)
                .liquidGlass(CardShape, 1.18f, 0.04f, reactive = false)
                .padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.size(76.dp).liquidGlass(CircleShape, 0.9f, reactive = true), contentAlignment = Alignment.Center) {
                Icon(if (blocked) AppleIcons.Shield else AppleIcons.ErrorOutline, null, tint = Bad, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text(if (blocked) "Page blocked" else "Can't open this page", color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                message.ifBlank { "Check your connection and try again." },
                color = TextTertiary, fontSize = 12.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            GlassPillButton(
                if (blocked) "Go back" else "Try again", onRetry,
                icon = if (blocked) null else AppleIcons.Refresh, accent = true
            )
            if (onAllow != null) {
                Spacer(Modifier.height(10.dp))
                GlassPillButton("Allow this site", onAllow)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Blocked by mistake? You can allow this site.",
                    color = TextTertiary, fontSize = 11.sp, textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun JsDialogHost(s: BrowserState, d: JsDialogState) {
    val jsView = LocalView.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        Haptics.fire(jsView, Hx.WINDOW_OPEN)
        onDispose { Haptics.fire(jsView, Hx.WINDOW_CLOSE) }
    }
    GlassLiveWindowLease(active = true)
    val style = LocalGlassStyle.current
    var input by remember(d) { mutableStateOf(d.defaultValue) }
    val enter = remember(d) { Animatable(0f) }
    LaunchedEffect(d) { enter.animateTo(1f, spring(dampingRatio = 0.76f, stiffness = 440f)) }
    val scrimColor = scrim(0.58f)
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(scrimColor.copy(alpha = scrimColor.alpha * enter.value.coerceIn(0f, 1f))) }
            .clickable(remember { MutableInteractionSource() }, null) { s.jsDialog = null; d.result(false, null) },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(max = 390.dp)
                .graphicsLayer {
                    val p = enter.value
                    alpha = p.coerceIn(0f, 1f)
                    val sc = 0.90f + 0.10f * p
                    scaleX = sc
                    scaleY = sc
                }
                .liquidWindowDrag(maxPullDp = 52.dp)
                .liquidGlass(CardShape, 1.22f, reactive = false, blurProgress = { 1f })
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).glassTile(CircleShape, 0.14f, style.accent), contentAlignment = Alignment.Center) {
                    Icon(AppleIcons.Search, null, tint = style.accent, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(d.title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(d.source, color = TextTertiary, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(d.message, color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
            if (d.isPrompt) {
                Spacer(Modifier.height(12.dp))
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(style.accent),
                    visualTransformation = if (d.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = if (d.secret) KeyboardOptions(keyboardType = KeyboardType.NumberPassword) else KeyboardOptions.Default,
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassTile(SmallShape, 0.09f)
                        .padding(12.dp)
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                GlassPillButton("Cancel", {
                    s.jsDialog = null; d.result(false, null)
                })
                GlassPillButton("OK", {
                    s.jsDialog = null; d.result(true, input)
                }, accent = true)
            }
        }
    }
}


