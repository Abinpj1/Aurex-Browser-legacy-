package com.aurex.browser

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Add
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

// ── iOS-style building blocks ────────────────────────────────────────────────

private val INSET = 16.dp
private val INSET_ICON = 57.dp
private val cBlue = Color(0xFF0A84FF)
private val cGreen = Color(0xFF34C759)
private val cRed = Color(0xFFFF453A)
private val cOrange = Color(0xFFFF9F0A)
private val cPurple = Color(0xFFAF52DE)
private val cIndigo = Color(0xFF5E5CE6)
private val cTeal = Color(0xFF32ADE6)
private val cGray = Color(0xFF8E8E93)

private val PAGE_TITLES = listOf(
    "Appearance", "Toolbar", "Gestures", "Downloads", "Media",
    "Privacy", "Menu", "General", "Search engine", "About", "Open-source credits"
)

private const val AUTHOR_NAME = "AMAN"
private const val AUTHOR_URL = "https://github.com/aman-nitb-cse"
private const val REPO_URL = "https://github.com/aman-nitb-cse/Aurex-Browser"
private const val ISSUES_URL = "https://github.com/aman-nitb-cse/Aurex-Browser/issues"
private const val TELEGRAM_URL = "https://t.me/aurex_oss"
private const val TELEGRAM_APP = "tg://resolve?domain=aurex_oss"

/** null = normal page; a string = the settings search is on and only rows matching it are drawn. */
private val LocalSettingsQuery = compositionLocalOf<String?> { null }

private class SBuilder {
    val items = mutableListOf<Pair<Dp, @Composable () -> Unit>>()
    /** What the settings search matches for each row ("" = the row is not searchable). */
    val keys = mutableListOf<String>()

    fun row(inset: Dp = INSET, key: String = "", content: @Composable () -> Unit) {
        items.add(inset to content)
        keys.add(key)
    }

    fun nav(title: String, icon: ImageVector?, tint: Color, value: String? = null, onClick: () -> Unit) =
        row(if (icon != null) INSET_ICON else INSET, title) {
            SRow(title, icon, tint, value, chevron = true, onClick = onClick)
        }

    fun act(
        title: String,
        icon: ImageVector? = null,
        tint: Color = cGray,
        value: String? = null,
        destructive: Boolean = false,
        onClick: () -> Unit
    ) = row(if (icon != null) INSET_ICON else INSET, title) {
        SRow(title, icon, tint, value, destructive = destructive, onClick = onClick)
    }

    fun info(title: String, value: String) = row(INSET, "$title $value") { SRow(title, value = value) }

    fun sw(title: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) =
        row(INSET, title + " " + sub.orEmpty()) { SSwitch(title, checked, onChange, sub) }

    fun seg(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) =
        row(INSET, title + " " + options.joinToString(" ")) { SSeg(title, options, selected, onSelect) }

    fun slider(
        title: String,
        valueText: String,
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        steps: Int = 0,
        onChange: (Float) -> Unit,
        onFinish: () -> Unit
    ) = row(INSET, title) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TextPrimary, fontSize = 16.sp, letterSpacing = (-0.3).sp, modifier = Modifier.weight(1f))
                Text(valueText, color = LocalGlassStyle.current.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            GlassSlider(
                value = value,
                onValueChange = onChange,
                valueRange = range,
                steps = steps,
                onValueChangeFinished = onFinish,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** iOS inset-grouped card: optional small caps header above, one-line footer below. */
@Composable
private fun SGroup(header: String? = null, footer: String? = null, build: SBuilder.() -> Unit) {
    val b = SBuilder().apply(build)
    val q = LocalSettingsQuery.current
    // Settings search: only the rows whose text holds every typed word (the group title counts too).
    val shown: List<Int> = if (q == null) b.items.indices.toList() else {
        val words = q.split(' ').filter { it.isNotEmpty() }
        b.items.indices.filter { i ->
            b.keys[i].isNotEmpty() && words.all { w ->
                b.keys[i].contains(w, ignoreCase = true) || header?.contains(w, ignoreCase = true) == true
            }
        }
    }
    if (shown.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                header.uppercase(), color = TextSecondary, fontSize = 12.sp,
                fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)
            )
        }
        Column(Modifier.fillMaxWidth().groupedSurface(RoundedCornerShape(20.dp))) {
            shown.forEachIndexed { n, i ->
                val (inset, content) = b.items[i]
                content()
                if (n < shown.lastIndex) {
                    Box(
                        Modifier
                            .padding(start = inset)
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(Separator)
                    )
                }
            }
        }
        if (footer != null && q == null) {
            Text(
                footer, color = TextTertiary, fontSize = 12.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp)
            )
        }
    }
}

/** iOS app-icon style rounded tile with a white glyph. */
@Composable
private fun Squircle(icon: ImageVector, color: Color, size: Dp = 29.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.27f))
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.82f), color))),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}

@Composable
private fun SRow(
    title: String,
    icon: ImageVector? = null,
    tint: Color = cGray,
    value: String? = null,
    chevron: Boolean = false,
    destructive: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickableHaptic(onClick = onClick) else Modifier)
            .heightIn(min = 46.dp)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Squircle(icon, tint)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title,
            color = if (destructive) BadText else TextPrimary,
            fontSize = 16.sp, letterSpacing = (-0.3).sp,
            modifier = Modifier.weight(1f)
        )
        if (value != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                value, color = TextSecondary, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp)
            )
        }
        trailing?.invoke()
        if (chevron) {
            Spacer(Modifier.width(4.dp))
            Icon(AppleIcons.ChevronRight, null, tint = TextTertiary, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun SSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit, sub: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickableHaptic(kind = if (checked) Hx.TOGGLE_OFF else Hx.TOGGLE_ON) { onChange(!checked) }
            .heightIn(min = 46.dp)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 16.sp, letterSpacing = (-0.3).sp)
            if (sub != null) Text(sub, color = TextSecondary, fontSize = 12.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(12.dp))
        GlassToggle(checked)
    }
}

@Composable
private fun SSeg(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            title, color = TextPrimary, fontSize = 16.sp, letterSpacing = (-0.3).sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        GlassSegmented(options, selected, onSelect, Modifier.fillMaxWidth())
    }
}

@Composable
private fun SearchButton(onSearch: (() -> Unit)?) {
    if (onSearch == null) {
        Spacer(Modifier.size(40.dp))
        return
    }
    Box(Modifier.liquidGlass(CircleShape, 0.9f, 0.04f)) {
        GlassIconButton(
            AppleIcons.Search, "Search settings", onSearch,
            size = 40.dp, iconSize = 19.dp, filled = false
        )
    }
}

/** Compact iOS navigation bar: glass back button; large title on the root, inline title below it. */
@Composable
private fun SHeader(title: String, root: Boolean, onBack: () -> Unit, onSearch: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.liquidGlass(CircleShape, 0.9f, 0.04f)) {
                GlassIconButton(
                    AppleIcons.ArrowBack, "Back", onBack,
                    size = 40.dp, iconSize = 19.dp, filled = false
                )
            }
            if (!root) {
                Text(
                    title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                SearchButton(onSearch)
            } else {
                Spacer(Modifier.weight(1f))
                SearchButton(onSearch)
            }
        }
        if (root) {
            Text(
                "Settings", color = TextPrimary, fontSize = 32.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp,
                modifier = Modifier.padding(start = 6.dp, top = 2.dp, bottom = 10.dp)
            )
        }
    }
}

@Composable
private fun SettingsScroll(content: @Composable ColumnScope.() -> Unit) {
    if (LocalSettingsQuery.current != null) {
        // Inside the settings search every page is a plain block (the search screen scrolls).
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content
    )
}

@Composable
private fun appVersion(): String {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return remember {
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}

// ── Entry ────────────────────────────────────────────────────────────────────

@Composable
fun SettingsPage(s: BrowserState, onClose: () -> Unit) {
    // -1 = root list, 0..10 = a section (same numbering the old tabs used).
    var page by remember { mutableIntStateOf(if (s.settingsTab in 1..10) s.settingsTab else -1) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    // Search is the standard floating button (same as Downloads); results show while it holds a query.
    var query by remember { mutableStateOf("") }
    // Bumped to fold the floating button back into a circle once a result was opened.
    var fabKey by remember { mutableIntStateOf(0) }
    val searchState = rememberSearchFabState()
    val searching = query.isNotBlank()

    fun go(p: Int) {
        page = p
        s.settingsTab = if (p < 0) 0 else p
    }
    fun endSearch() { query = ""; searchState.expanded = false; fabKey++ }
    BackHandler(enabled = page >= 0) { go(if (page == 10) 9 else -1) }

    Box(Modifier.fillMaxSize().closeSearchOnEmptyTap(searchState)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            if (searching) {
                SettingsSearchView(
                    s = s,
                    query = query,
                    onBack = { endSearch() },
                    open = { p -> endSearch(); go(p) },
                    onClearData = { confirmClear = true },
                    onReset = { confirmReset = true }
                )
            } else AnimatedContent(
                targetState = page,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    // iOS push / pop: the new page slides in, the old one drifts back a little.
                    val push = targetState > initialState
                    val ease = androidx.compose.animation.core.FastOutSlowInEasing
                    if (push) {
                        (slideInHorizontally(tween(320, easing = ease)) { it } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(320, easing = ease)) { -it / 4 } + fadeOut(tween(220)))
                    } else {
                        (slideInHorizontally(tween(320, easing = ease)) { -it / 4 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(320, easing = ease)) { it } + fadeOut(tween(220)))
                    }
                },
                label = "settingsPage"
            ) { p ->
                Column(Modifier.fillMaxSize()) {
                    SHeader(
                        title = if (p < 0) "Settings" else PAGE_TITLES[p],
                        root = p < 0,
                        onBack = { if (p < 0) onClose() else go(if (p == 10) 9 else -1) }
                    )
                    when (p) {
                        -1 -> SettingsRoot(s) { go(it) }
                        0 -> AppearanceSettings(s)
                        1 -> ToolbarSettings(s)
                        2 -> SwipeSettings(s)
                        3 -> DownloadSettings(s)
                        4 -> MediaSettings(s)
                        5 -> PrivacySettings(s, onClearData = { confirmClear = true })
                        6 -> MenuSettings(s)
                        7 -> GeneralSettings(s, onReset = { confirmReset = true })
                        9 -> AboutSettings(s, onClose) { go(10) }
                        10 -> CreditsSettings(s, onClose)
                        else -> SearchSettings(s)
                    }
                }
            }
        }
        // Registered after the page BackHandler on purpose: back folds the search first, then leaves the page.
        key(fabKey) {
            StandardSearchFab(
                query = query,
                onQuery = { query = it },
                placeholder = "Search settings",
                state = searchState
            )
        }
    }

    if (confirmClear) {
        GlassConfirmDialog(
            "Clear browsing data?",
            "History, cookies, cache and form data. Bookmarks, downloads and your Instagram / Facebook login stay (a fresh login on a \"new device\" can lock the account).",
            "Clear",
            destructive = true,
            onConfirm = { confirmClear = false; s.clearBrowsingData() },
            onDismiss = { confirmClear = false }
        )
    }
    if (confirmReset) {
        GlassConfirmDialog(
            "Reset all settings?",
            "Every preference returns to its default. Browsing data is kept.",
            "Reset",
            destructive = true,
            onConfirm = { confirmReset = false; s.resetSettings() },
            onDismiss = { confirmReset = false }
        )
    }
}

// ── Settings search ──────────────────────────────────────────────────────────

/** Pages searched, in the order their results appear (Toolbar and Menu are drag editors: their root rows lead there). */
private val SEARCH_PAGES = listOf(0, 5, 3, 4, 2, 8, 7)

@Composable
private fun SettingsSearchView(
    s: BrowserState,
    query: String,
    onBack: () -> Unit,
    open: (Int) -> Unit,
    onClearData: () -> Unit,
    onReset: () -> Unit
) {
    val q = query.trim()
    Column(Modifier.fillMaxSize()) {
        SHeader(title = "Search", root = false, onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 100.dp)
        ) {
            CompositionLocalProvider(LocalSettingsQuery provides q) {
                SearchResultsHost(q) {
                    SearchSection(null, null) { SettingsRoot(s) { open(it) } }
                    SEARCH_PAGES.forEach { p ->
                        SearchSection(PAGE_TITLES[p], { open(p) }) {
                            when (p) {
                                0 -> AppearanceSettings(s)
                                5 -> PrivacySettings(s, onClearData)
                                3 -> DownloadSettings(s)
                                4 -> MediaSettings(s)
                                2 -> SwipeSettings(s)
                                8 -> SearchSettings(s)
                                else -> GeneralSettings(s, onReset)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A page's matching rows under its title; collapses to nothing when no row of that page matches. */
@Composable
private fun SearchSection(title: String?, onOpen: (() -> Unit)?, content: @Composable () -> Unit) {
    Layout(content = {
        if (title != null) {
            Text(
                title, color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(start = 6.dp, bottom = 8.dp)
                    .then(if (onOpen != null) Modifier.clickableHaptic(onClick = onOpen) else Modifier)
            )
        }
        content()
    }) { ms, c ->
        val loose = c.copy(minWidth = 0, minHeight = 0)
        val body = ms.last().measure(c.copy(minHeight = 0))
        if (body.height == 0) {
            layout(0, 0) {}
        } else {
            val head = if (ms.size > 1) ms.first().measure(loose) else null
            val gap = 18.dp.roundToPx()
            val h = (head?.height ?: 0) + body.height + gap
            layout(c.maxWidth, h) {
                head?.placeRelative(6, 0)
                body.placeRelative(0, head?.height ?: 0)
            }
        }
    }
}

/** Stacks the sections; shows "No settings found" when every section collapsed to nothing. */
@Composable
private fun SearchResultsHost(q: String, sections: @Composable () -> Unit) {
    Layout(content = {
        sections()
        Text(
            "No settings found for \"$q\"",
            color = TextTertiary, fontSize = 15.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 40.dp)
        )
    }) { ms, c ->
        val loose = c.copy(minHeight = 0)
        val ps = ms.map { it.measure(loose) }
        val body = ps.dropLast(1)
        val bodyH = body.sumOf { it.height }
        if (bodyH == 0) {
            val m = ps.last()
            layout(c.maxWidth, m.height) { m.placeRelative(0, 0) }
        } else {
            layout(c.maxWidth, bodyH) {
                var y = 0
                body.forEach { it.placeRelative(0, y); y += it.height }
            }
        }
    }
}

// ── Root list ────────────────────────────────────────────────────────────────

@Composable
private fun SettingsRoot(s: BrowserState, open: (Int) -> Unit) {
    val style = LocalGlassStyle.current
    val themeName = listOf("Auto", "Light", "Dark").getOrElse(s.themeMode) { "Auto" }
    val engineName = ENGINES[s.engine.coerceIn(0, ENGINES.lastIndex)].name
    val swipesOn = listOf(
        s.swipeLinkTabSwitch, s.swipeTabClose, s.swipeDownloadDelete,
        s.swipeDlRightDelete, s.swipeDlLeftShare, s.holdTabsNewTab, s.holdMenuSettings
    ).count { it }

    SettingsScroll {
        SGroup {
            row {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    AurexLogo(52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Aurex", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("${s.blocked} threats blocked", color = TextSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
        SGroup {
            nav("Appearance", AppleIcons.Palette, cPurple, themeName) { open(0) }
            nav("Toolbar", AppleIcons.ToolbarBar, cTeal, "${s.toolbarItems.size + 1}") { open(1) }
            nav("Menu", AppleIcons.MoreVert, cGray, "${s.menuPinned.size}") { open(6) }
            nav("Gestures", AppleIcons.Swipe, cOrange, "$swipesOn on") { open(2) }
        }
        SGroup {
            nav("Privacy", AppleIcons.Shield, cGreen, if (s.adblock) "Adblock on" else "Adblock off") { open(5) }
            nav("Downloads", AppleIcons.Download, cBlue, if (s.videoDownload) null else "Video off") { open(3) }
            nav("Media", AppleIcons.PlayCircle, cRed) { open(4) }
        }
        SGroup {
            nav("Search engine", AppleIcons.Search, cIndigo, engineName) { open(8) }
            nav("General", AppleIcons.Settings, cGray) { open(7) }
        }
        SGroup {
            nav("About", AppleIcons.Info, cBlue) { open(9) }
        }
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                append("Aurex · Built by $AUTHOR_NAME ")
                withStyle(androidx.compose.ui.text.SpanStyle(color = cRed)) { append("\u2665") }
            },
            color = TextTertiary, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
    }
}


// ── About ────────────────────────────────────────────────────────────────────

@Composable
private fun AboutSettings(s: BrowserState, onClose: () -> Unit, openCredits: () -> Unit) {
    val version = appVersion()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Web links open as a normal tab of the browser itself; Settings folds away so the tab is visible.
    fun inApp(url: String) { onClose(); s.newTab(url) }
    fun openTelegram() {
        val ok = runCatching {
            ctx.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(TELEGRAM_APP))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!ok) inApp(TELEGRAM_URL)       // no Telegram app: the public page in a tab
    }
    SettingsScroll {
        SGroup {
            row {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 22.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AurexLogo(76.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("Aurex", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    if (version.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text("Version $version", color = TextSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
        SGroup(
            header = "Updates",
            footer = "Updates come from the GitHub releases of the project. Checking contacts api.github.com only."
        ) {
            val u = s.update
            act(
                "Check for updates", AppleIcons.Refresh, cGreen,
                value = when {
                    u.checking -> "Checking…"
                    u.info != null -> "v${u.info?.version} available"
                    else -> u.status
                }
            ) {
                if (!u.checking) {
                    u.check(manual = true) { r ->
                        if (r is Updater.Result.UpToDate) s.notify("Aurex is up to date")
                        else if (r is Updater.Result.Failed) s.notify("Couldn't check for updates")
                    }
                }
            }
            sw("Check automatically", u.auto) { u.toggleAuto(it) }
        }
        SGroup(header = "Developer") {
            act("Developer", AppleIcons.Star, cOrange, value = AUTHOR_NAME) { inApp(AUTHOR_URL) }
        }
        SGroup(
            header = "Open source",
            footer = "Aurex is open source. Bug reports, ideas and pull requests are welcome."
        ) {
            act("Source code", AppleIcons.Code, cGray, value = "GitHub") { inApp(REPO_URL) }
            act("Report an issue", AppleIcons.ErrorOutline, cRed) { inApp(ISSUES_URL) }
            act("Telegram community", AppleIcons.Share, cBlue, value = "@aurex_oss") { openTelegram() }
        }
        SGroup(footer = "Aurex stands on the shoulders of these open-source projects.") {
            nav("Open-source credits", AppleIcons.Book, cPurple) { openCredits() }
        }
    }
}


// ── Open-source credits ──────────────────────────────────────────────────────

private class Credit(val name: String, val what: String, val license: String, val url: String)

private val CREDIT_ENGINES = listOf(
    Credit("yt-dlp", "Video & audio extraction engine", "Unlicense", "https://github.com/yt-dlp/yt-dlp"),
    Credit("youtubedl-android", "Runs yt-dlp inside Android (JunkFood02, yausername)", "GPL-3.0", "https://github.com/JunkFood02/youtubedl-android"),
    Credit("FFmpeg", "Merging, converting and HDR handling", "LGPL / GPL", "https://ffmpeg.org"),
    Credit("Python", "Runtime that yt-dlp runs on", "PSF", "https://www.python.org"),
    Credit("Chromium", "The web engine behind Android System WebView", "BSD-3-Clause", "https://www.chromium.org")
)

private val CREDIT_LIBS = listOf(
    Credit("AndroidX Media3 (ExoPlayer)", "Built-in video player", "Apache-2.0", "https://github.com/androidx/media"),
    Credit("Jetpack Compose & AndroidX", "The whole interface, activities, WebKit", "Apache-2.0", "https://developer.android.com/jetpack"),
    Credit("Material Icons", "Part of the icon set", "Apache-2.0", "https://github.com/google/material-design-icons"),
    Credit("OkHttp", "Networking, list and thumbnail downloads", "Apache-2.0", "https://github.com/square/okhttp"),
    Credit("Kotlin & Coroutines", "Language and async runtime", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines")
)

private val CREDIT_LISTS = listOf(
    Credit("EasyList & EasyPrivacy", "Ad and tracker filter rules", "GPL-3.0 / CC BY-SA", "https://easylist.to"),
    Credit("uBlock Origin filters (uAssets)", "Filters, badware and unbreak lists", "GPL-3.0", "https://github.com/uBlockOrigin/uAssets"),
    Credit("AdGuard filters", "Base and Mobile Ads lists", "GPL-3.0", "https://github.com/AdguardTeam/AdguardFilters"),
    Credit("StevenBlack hosts", "Unified blocklist, also the adult-site list", "MIT", "https://github.com/StevenBlack/hosts"),
    Credit("Peter Lowe's ad-server list", "Ad-server host list (pgl.yoyo.org)", "Free to use", "https://pgl.yoyo.org/adservers/")
)

@Composable
private fun CreditRow(c: Credit, onOpen: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickableHaptic(onClick = { onOpen(c.url) })
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.name, color = TextPrimary, fontSize = 16.sp, letterSpacing = (-0.3).sp)
            Text(c.what, color = TextSecondary, fontSize = 13.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            c.license, color = TextTertiary, fontSize = 12.sp, textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 92.dp)
        )
        Spacer(Modifier.width(4.dp))
        Icon(AppleIcons.ChevronRight, null, tint = TextTertiary, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun CreditsSettings(s: BrowserState, onClose: () -> Unit) {
    fun inApp(url: String) { onClose(); s.newTab(url) }
    SettingsScroll {
        SGroup(header = "Engines") {
            CREDIT_ENGINES.forEach { c -> row(key = c.name + " " + c.license) { CreditRow(c) { inApp(it) } } }
        }
        SGroup(header = "Libraries") {
            CREDIT_LIBS.forEach { c -> row(key = c.name + " " + c.license) { CreditRow(c) { inApp(it) } } }
        }
        SGroup(
            header = "Filter lists",
            footer = "Downloaded at runtime and refreshed regularly. Thank you to every maintainer."
        ) {
            CREDIT_LISTS.forEach { c -> row(key = c.name + " " + c.license) { CreditRow(c) { inApp(it) } } }
        }
        Text(
            "Each project belongs to its authors and stays under its own license. Tap a row to open it.",
            color = TextTertiary, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        )
    }
}

// ── Appearance ───────────────────────────────────────────────────────────────

@Composable
private fun AppearanceSettings(s: BrowserState) {
    SettingsScroll {
        SGroup {
            seg("Theme", listOf("Auto", "Light", "Dark"), s.themeMode) { s.themeMode = it; s.save() }
            seg("Dark websites", listOf("Auto", "Always", "Off"), s.darkWeb) { s.setDarkWebMode(it) }
        }
        SGroup(footer = "Clear to tinted glass.") {
            slider(
                "Transparency", "${((1f - s.glass) * 100).toInt()}%",
                s.glass, 0.35f..1f,
                onChange = { s.glass = it }, onFinish = { s.save() }
            )
            slider(
                "Text size", "${s.textZoom}%",
                s.textZoom.toFloat(), 60f..180f, steps = 11,
                onChange = { s.textZoom = it.roundToInt() }, onFinish = { s.applySettings(); s.save() }
            )
        }
        SGroup(header = "Start page") {
            sw("Favorites", s.showHomeSites) { s.showHomeSites = it; s.save() }
            sw("Recent sites", s.showRecent) { s.showRecent = it; s.save() }
        }
        SGroup {
            sw("Haptic feedback", s.haptics) { s.haptics = it; s.save() }
            if (s.haptics) seg("Haptic strength", listOf("Light", "Medium", "Strong"), s.hapticLevel) { s.updateHapticLevel(it) }
        }
    }
}

// ── Toolbar ──────────────────────────────────────────────────────────────────

@Composable
private fun ToolbarSettings(s: BrowserState) {
    val chosen = s.toolbarItems.filter { it != "menu" }
    val rest = TOOLBAR_ACTIONS.filter { it.first != "menu" && it.first !in chosen }
    val tab = s.tabs.getOrNull(s.current)
    val edit = remember { ToolbarEditState() }
    var pageOrigin by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { pageOrigin = it.positionInRoot() }
    ) {
        // Debug builds: play back a toolbox drag & drop so the placement logic
        // can be verified without touching the screen.
        LaunchedEffect(s.debugTrayDrag) {
            val raw = s.debugTrayDrag ?: return@LaunchedEffect
            val id = raw.substringBefore(":")
            val side = raw.substringAfter(":", "left")
            delay(700)
            val tray = edit.trayOrigins[id]
            if (tray != null) {
                edit.beginTrayDrag(
                    id = id,
                    at = tray.center,
                    items = chosen,
                    rows = s.toolbarRows,
                    leftOfLink = s.toolbarLeft
                )
                delay(250)
                // Left half of the address lands before the link, right half after.
                val box = edit.addressBounds
                val target = if (side == "right") box.right - box.width * 0.2f
                else box.left + box.width * 0.2f
                edit.moveTrayDrag(Offset(target, box.center.y))
                delay(250)
                edit.finishTrayDrag()?.let { drop ->
                    s.addToolbarControl(drop.id, drop.index, drop.leftOfLink, drop.lower)
                    s.barVisible = true
                }
            }
            s.debugTrayDrag = null
        }

        SettingsScroll {
            SGroup(
                header = "Preview",
                footer = "Drag a control onto the bar, or tap one to add it. The menu stays fixed."
            ) {
                row {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        if (tab != null) {
                            ToolbarBar(
                                s = s,
                                tab = tab,
                                rows = s.toolbarRows,
                                onLinkTap = {},
                                onPanel = {},
                                onMenu = {},
                                editable = true,
                                preview = true,
                                edit = edit,
                                onMove = { from, to ->
                                    s.moveToolbarControl(from, to)
                                },
                                onRemove = { id ->
                                    s.removeToolbarControl(id)
                                    val name = TOOLBAR_ACTIONS.firstOrNull { it.first == id }?.second ?: id
                                    s.notify("$name removed")
                                }
                            )
                            Spacer(Modifier.height(10.dp))
                        } else {
                            Text("Open a tab to preview the toolbar.", color = TextTertiary, fontSize = 12.sp)
                        }
                        if (rest.isEmpty()) {
                            Text("Every control is on the bar.", color = TextTertiary, fontSize = 12.sp)
                        } else {
                            Text("Available · max $TOOLBAR_MAX_ITEMS icons on the bar", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(7.dp))
                            rest.chunked(4).forEachIndexed { rowIndex, chunk ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    chunk.forEach { (id, label) ->
                                        ToolbarTrayControl(
                                            s = s,
                                            id = id,
                                            label = label,
                                            rows = s.toolbarRows,
                                            edit = edit,
                                            onDrop = { drop ->
                                                s.addToolbarControl(drop.id, drop.index, drop.leftOfLink, drop.lower)
                                                s.barVisible = true
                                            },
                                            onTap = {
                                                s.addToolbarControl(id, chosen.size, false, s.toolbarRows == 1)
                                                s.barVisible = true
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    repeat(4 - chunk.size) { Spacer(Modifier.weight(1f)) }
                                }
                                if (rowIndex < rest.chunked(4).lastIndex) Spacer(Modifier.height(9.dp))
                            }
                        }
                    }
                }
            }

            SGroup(header = "Behavior") {
                sw("Auto-hide on scroll", s.autoHide) {
                    s.autoHide = it; s.barVisible = true; s.save()
                }
            }
        }

        if (edit.dragId != null) {
            TrayDragGhost(edit = edit, pageOrigin = pageOrigin)
        }
    }
}

/** A draggable control tile in the Settings tray. Tapping adds it to the bar. */
@Composable
private fun ToolbarTrayControl(
    s: BrowserState,
    id: String,
    label: String,
    rows: Int,
    edit: ToolbarEditState,
    onDrop: (ToolbarDrop) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val style = LocalGlassStyle.current
    val origin = remember { mutableStateOf(Offset.Zero) }
    val items = s.toolbarItems.filter { it != "menu" }
    val latestDrop by rememberUpdatedState(onDrop)
    val dragging = edit.dragId == id

    val lift by animateFloatAsState(
        if (dragging) -6f else 0f,
        spring(dampingRatio = 0.75f, stiffness = 700f),
        label = "trayLift"
    )
    val scale by animateFloatAsState(
        if (dragging) 0.94f else 1f,
        spring(dampingRatio = 0.8f, stiffness = 600f),
        label = "trayScale"
    )

    Column(
        modifier
            .onGloballyPositioned {
                val p = it.positionInRoot()
                origin.value = p
                edit.trayOrigins[id] = Rect(p.x, p.y, p.x + it.size.width, p.y + it.size.height)
            }
            .graphicsLayer {
                translationY = lift
                scaleX = scale
                scaleY = scale
                alpha = if (dragging) 0.3f else 1f
                shadowElevation = if (dragging) 14.dp.toPx() else 0f
            }
            .pointerInput(id, items, rows) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { local ->
                        performDragStart(view)
                        edit.beginTrayDrag(
                            id = id,
                            at = origin.value + local,
                            items = items,
                            rows = rows,
                            leftOfLink = s.toolbarLeft
                        )
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        edit.moveTrayDrag(origin.value + change.position)
                    },
                    onDragEnd = {
                        edit.finishTrayDrag()?.let { latestDrop(it) }
                    },
                    onDragCancel = { edit.reset() }
                )
            }
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(44.dp)
                .glassTile(RoundedCornerShape(16.dp), 0.12f, style.accent),
            contentAlignment = Alignment.Center
        ) {
            // The very same artwork the toolbar shows, so both places match.
            ToolbarControlGlyph(
                id = id,
                size = 21.dp,
                tint = style.accent,
                strokeWidth = 1.8.dp
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = TextSecondary,
            fontSize = 9.5.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

/** Floating copy of a tray control while it is being dragged. */
@Composable
private fun TrayDragGhost(edit: ToolbarEditState, pageOrigin: Offset) {
    val style = LocalGlassStyle.current
    val id = edit.dragId ?: return
    Box(
        Modifier
            .offset {
                val p = edit.pointer
                IntOffset(
                    (p.x - pageOrigin.x - 25.dp.toPx()).roundToInt(),
                    (p.y - pageOrigin.y - 25.dp.toPx()).roundToInt()
                )
            }
            .size(50.dp)
            .liquidGlass(CircleShape, 1.2f)
            .padding(13.dp),
        contentAlignment = Alignment.Center
    ) {
        ToolbarControlGlyph(
            id = id,
            size = 24.dp,
            tint = style.accent,
            strokeWidth = 1.9.dp
        )
    }
}

// ── Downloads ────────────────────────────────────────────────────────────────

@Composable
private fun DownloadSettings(s: BrowserState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val on = s.videoDownload
    // The engine is only touched while the switch is on (even reading its version loads the library).
    LaunchedEffect(on) { if (on) YtDlpEngine.loadState(ctx) }
    var auto by remember { mutableStateOf(YtDlpEngine.autoUpdate(ctx)) }
    val checked = YtDlpEngine.lastChecked
    val checkedText = if (checked == 0L) "Never" else
        android.text.format.DateUtils.getRelativeTimeSpanString(
            checked, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
        ).toString()
    val updateValue = if (YtDlpEngine.updating) "Checking…" else YtDlpEngine.updateStatus.ifBlank { null }
    var mediaVisible by remember { mutableStateOf(MediaVisibility.isOn(ctx)) }

    SettingsScroll {
        SGroup(
            footer = if (on) "Finds videos on the pages you open and saves them with the built-in engine."
            else "Off: the video engine is not loaded, pages are not scanned for videos and no download " +
                "buttons appear, so it uses no memory. Normal file downloads still work."
        ) {
            sw("Video download", on, if (on) "On" else "Off · saves memory") { s.setVideoDownload(it) }
        }
        if (on) {
            SGroup(footer = "Parallel ranges and HLS are handled automatically.") {
                sw("Always best quality", s.alwaysBestQuality, "Skip the quality sheet") {
                    s.alwaysBestQuality = it; s.save()
                }
            }
            SGroup(
                header = "Video engine",
                footer = "Sites change often; an up-to-date engine keeps downloads working."
            ) {
                info("Version", YtDlpEngine.engineVersion.ifBlank { "…" })
                info("Last checked", checkedText)
                sw("Auto-update", auto, "Every 6 hours in the background") {
                    auto = it; YtDlpEngine.setAutoUpdate(ctx, it)
                }
                if (YtDlpEngine.engineProblem.isNotBlank()) info("Problem", YtDlpEngine.engineProblem)
                act("Repair engine", AppleIcons.Refresh, cBlue, value = if (YtDlpEngine.repairing) "Repairing…" else null) {
                    if (!YtDlpEngine.repairing) {
                        YtDlpEngine.repairNow(ctx) { ok ->
                            s.notify(if (ok) "Video engine repaired" else "Couldn't repair · close and reopen Aurex, then try again")
                        }
                    }
                }
                act("Update now", AppleIcons.Refresh, cBlue, value = updateValue) {
                    if (!YtDlpEngine.updating) {
                        YtDlpEngine.updateNow(ctx) { r ->
                            s.notify(
                                when (r) {
                                    "updated" -> "Video engine updated"
                                    "current" -> "Video engine is up to date"
                                    "busy" -> "Finish or pause the running downloads first"
                                    else -> "Couldn't update · check your connection"
                                }
                            )
                        }
                    }
                }
            }
        }
        SGroup(
            footer = if (mediaVisible) "Downloaded videos, music and images show up in your Gallery and music apps."
            else "Hidden from Gallery and music apps. They stay in the Downloads list here."
        ) {
            sw("Media visibility", mediaVisible, if (mediaVisible) "Visible in Gallery" else "Hidden") {
                mediaVisible = it; MediaVisibility.set(ctx, it)
            }
        }
        SGroup(footer = "Files are saved in the app's Download folder.") {
            act("Clear finished", AppleIcons.DeleteSweep, cGray) { s.downloads.clearFinished() }
            act("Pause all", AppleIcons.Pause, cOrange) { s.downloads.pauseAll() }
        }
    }
}

// ── Privacy ──────────────────────────────────────────────────────────────────

@Composable
private fun PrivacySettings(s: BrowserState, onClearData: () -> Unit) {
    val siteHost = s.currentSiteHost()
    val filterStatus = when {
        s.blocker.updating -> "Updating…"
        s.blocker.lastUpdate == 0L -> "Not yet"
        else -> android.text.format.DateUtils.getRelativeTimeSpanString(s.blocker.lastUpdate).toString()
    }
    SettingsScroll {
        SGroup(header = "Blocking") {
            sw("Adblock", s.adblock, "Ads, trackers and YouTube ads") {
                s.adblock = it; s.applySettings(); s.save()
            }
            sw("Popup & redirect blocker", s.popupBlock, "Logins and payments keep working") {
                s.popupBlock = it; s.applySettings(); s.save()
            }
            if (siteHost.isNotEmpty()) {
                sw("Trust " + siteHost, s.isSiteTrusted(siteHost), "Allow popups on this site") {
                    s.setSiteTrusted(siteHost, it)
                }
            }
            if (s.trustedSites.isNotEmpty()) {
                act("Clear trusted sites", value = "${s.trustedSites.size}") { s.clearTrustedSites() }
            }
        }
        SGroup(header = "Connection") {
            sw("Block 3rd-party cookies", !s.thirdPartyCookies, "Some logins may break") {
                s.thirdPartyCookies = !it; s.applySettings(); s.save()
            }
        }
        SGroup(
            header = "Private tabs",
            footer = "Switching to a private tab asks for your phone lock (fingerprint, face, PIN, pattern or password). " +
                "Needs a screen lock on the phone; turning this off asks too."
        ) {
            sw("Lock private tabs", s.incognitoLock, "Ask for the phone lock") { s.toggleIncognitoLock(it) }
        }
        SGroup(
            header = "Adult content",
            footer = "${s.adultBlocked} blocked · turning off asks for your phone lock"
        ) {
            sw("Block adult content", s.blockAdult, "Adult sites, searches, leak and illegal adult sites, SafeSearch") {
                s.setAdultFilter(it)
            }
            if (s.adultAllowed.isNotEmpty()) {
                act("Clear allowed sites", value = "${s.adultAllowed.size}") { s.clearAdultAllowed() }
            }
            act("Update site list", value = "${AdultFilter.size}") {
                AdultFilter.loadBigList(true); s.notify("Updating the adult-site list")
            }
        }
        SGroup(header = "Filters", footer = "Lists update every 2 days.") {
            info("Threats blocked", "${s.blocked}")
            info("Popups & redirects", "${s.popupsBlocked + s.redirectsBlocked}")
            info("Filter rules", "${s.blocker.ruleCount}")
            act("Update now", value = filterStatus) { s.updateFilters() }
        }
        SGroup(header = "Data") {
            act("Clear cache", AppleIcons.DeleteSweep, cGray) { s.clearCache() }
            act("Clear cookies & site data", AppleIcons.Cookie, cGray) { s.clearCookies() }
            act("Clear browsing data", destructive = true, onClick = onClearData)
        }
    }
}

// ── Gestures ─────────────────────────────────────────────────────────────────

@Composable
private fun SwipeSettings(s: BrowserState) {
    SettingsScroll {
        SGroup(footer = "Shortcuts on the toolbar buttons.") {
            sw("Swipe the address bar", s.swipeLinkTabSwitch, "Left or right switches tabs") {
                s.swipeLinkTabSwitch = it; s.save()
            }
            sw("Swipe up on Tabs", s.swipeTabClose, "Closes the current tab") {
                s.swipeTabClose = it; s.save()
            }
            sw("Swipe up on Downloads", s.swipeDownloadDelete, "Deletes the latest download") {
                s.swipeDownloadDelete = it; s.save()
            }
        }
        SGroup(header = "Press and hold", footer = "Press and hold a toolbar button.") {
            sw("Hold Tabs for a new tab", s.holdTabsNewTab, "Opens a new tab with the keyboard ready to type") {
                s.holdTabsNewTab = it; s.save()
            }
            sw("Hold the menu for Settings", s.holdMenuSettings, "The three-dot button opens Settings") {
                s.holdMenuSettings = it; s.save()
            }
        }
        SGroup(header = "Downloads list", footer = "Swipe a download in the Downloads list. Delete also removes the file, with no confirmation.") {
            sw("Swipe right to delete", s.swipeDlRightDelete, "Deletes the download and its file") {
                s.swipeDlRightDelete = it; s.save()
            }
            sw("Swipe left to share", s.swipeDlLeftShare, "Shares the finished file") {
                s.swipeDlLeftShare = it; s.save()
            }
        }
    }
}

// ── Menu ─────────────────────────────────────────────────────────────────────

@Composable
private fun MenuSettings(s: BrowserState) {
    val style = LocalGlassStyle.current
    val pinned = s.menuPinned.mapNotNull { MenuAction.byId(it) }
    val rest = MenuAction.values().filter { it !in pinned }
    val reorder = remember { ReorderState() }
    val ids = pinned.map { it.id }

    SettingsScroll {
        SGroup(header = "In menu", footer = "Long-press a row to reorder.") {
            if (pinned.isEmpty()) {
                row { Text("Nothing here yet.", color = TextTertiary, fontSize = 15.sp, modifier = Modifier.padding(16.dp)) }
            }
            pinned.forEachIndexed { index, action ->
                row(INSET_ICON) {
                    DragReorderRow(
                        item = action.id,
                        index = index,
                        items = ids,
                        state = reorder,
                        onReorder = { from, to -> s.movePinned(pinned[from], to - from) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
                        content = { _: String, _: Boolean ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Squircle(menuActionIcon(action), style.accent)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    action.label, color = TextPrimary, fontSize = 16.sp,
                                    letterSpacing = (-0.3).sp, modifier = Modifier.weight(1f)
                                )
                                GlassIconButton(
                                    AppleIcons.VisibilityOff, "Hide", { s.togglePinned(action) },
                                    size = 32.dp, iconSize = 17.dp, filled = false
                                )
                            }
                        }
                    )
                }
            }
        }

        if (rest.isNotEmpty()) {
            SGroup(header = "Hidden") {
                rest.forEach { action ->
                    row(INSET_ICON) {
                        SRow(
                            action.label, menuActionIcon(action), cGray,
                            trailing = {
                                Icon(AppleIcons.Add, null, tint = style.accent, modifier = Modifier.size(19.dp))
                            },
                            onClick = { s.togglePinned(action) }
                        )
                    }
                }
            }
        }

        SGroup {
            if (rest.isNotEmpty()) {
                act("Show all") {
                    MenuAction.values().forEach { if (!s.isPinned(it)) s.menuPinned.add(it.id) }
                    s.save()
                }
            }
            act("Reset to default") { s.resetMenu() }
        }
    }
}

// ── Media ────────────────────────────────────────────────────────────────────

@Composable
private fun MediaSettings(s: BrowserState) {
    SettingsScroll {
        SGroup(footer = "Picture-in-picture starts when you leave the app during playback.") {
            sw("Floating media button", s.mediaFab, "Play and picture-in-picture over a video") {
                s.mediaFab = it; s.save()
            }
            sw("Background playback", s.backgroundPlay, "Keep playing when you leave") {
                s.backgroundPlay = it; s.save()
            }
            sw("Auto picture-in-picture", s.autoPip) {
                s.autoPip = it; s.save()
            }
            sw("Autoplay", s.autoplay, "Videos start without a tap") {
                s.autoplay = it; s.applySettings(); s.save()
            }
        }
    }
}

// ── Search engine ────────────────────────────────────────────────────────────

@Composable
private fun SearchSettings(s: BrowserState) {
    val style = LocalGlassStyle.current
    SettingsScroll {
        SGroup(footer = "Used from the address bar and start page.") {
            ENGINES.forEachIndexed { i, eng ->
                row(58.dp, eng.name + " search engine") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickableHaptic { s.engine = i; s.save() }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SearchEngineIcon(eng.name, 30.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            eng.name, color = TextPrimary, fontSize = 16.sp,
                            letterSpacing = (-0.3).sp, modifier = Modifier.weight(1f)
                        )
                        if (s.engine == i) {
                            Icon(AppleIcons.Check, null, tint = style.accent, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchEngineIcon(name: String, size: Dp = 32.dp) {
    val (bg, letter) = when (name.lowercase()) {
        "google" -> Pair(Color(0xFF4285F4), "G")
        "duckduckgo" -> Pair(Color(0xFFDE5833), "DDG")
        "brave" -> Pair(Color(0xFFFF5000), "B")
        "bing" -> Pair(Color(0xFF00838F), "b")
        "startpage" -> Pair(Color(0xFF6554AF), "S")
        "ecosia" -> Pair(Color(0xFF00897B), "E")
        "yahoo" -> Pair(Color(0xFF6001D2), "Y!")
        else -> Pair(Color(0xFF0A84FF), name.take(1).uppercase())
    }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(bg, bg.copy(alpha = 0.78f))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            letter,
            color = Color.White,
            fontSize = if (letter.length > 2) 10.sp else 14.sp,
            fontWeight = FontWeight.Black
        )
    }
}

// ── General ──────────────────────────────────────────────────────────────────

@Composable
private fun GeneralSettings(s: BrowserState, onReset: () -> Unit) {
    val version = appVersion()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var logBusy by remember { mutableStateOf(false) }
    SettingsScroll {
        SGroup(header = "Browsing") {
            sw("Data saver", s.blockImages, "Block images to save data") {
                s.blockImages = it; s.applySettings(); s.save()
            }
            sw("Desktop site", s.desktop) { s.toggleDesktop() }
        }
        SGroup(
            header = "Support",
            footer = "Copies device info, the state of the video engine and recent errors (no cookies, no page " +
                "addresses beyond the site and path). Paste it into a bug report."
        ) {
            act("Copy log", AppleIcons.Copy, cBlue, value = if (logBusy) "Collecting…" else null) {
                if (!logBusy) {
                    logBusy = true
                    AppLog.collect(ctx) { text ->
                        logBusy = false
                        s.notify(if (AppLog.copy(ctx, text)) "Log copied" else "Couldn't copy the log")
                    }
                }
            }
            act("Clear log", AppleIcons.DeleteSweep, cGray) { AppLog.clear(); s.notify("Log cleared") }
        }
        SGroup {
            act("Reset all settings", AppleIcons.Refresh, cRed, destructive = true, onClick = onReset)
        }
        SGroup {
            info("Version", version.ifBlank { "Aurex" })
        }
    }
}
