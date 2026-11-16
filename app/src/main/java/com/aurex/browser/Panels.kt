package com.aurex.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.RowScope
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PanelHost(s: BrowserState, panel: Panel, onClose: () -> Unit, onPanel: (Panel) -> Unit = {}) {
    Box(Modifier.fillMaxSize()) {
        AppBackground()
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            when (panel) {
                Panel.Tabs -> TabsPanel(s, onClose)
                Panel.Bookmarks -> LibraryPanel(s, Panel.Bookmarks, onClose)
                Panel.History -> LibraryPanel(s, Panel.History, onClose)
                Panel.Downloads -> LibraryPanel(s, Panel.Downloads, onClose)
                Panel.Media -> MediaDockPanel(s, onClose, onPanel)
                Panel.Settings -> SettingsPage(s, onClose)
            }
        }
    }
}

// ── Tabs (compact cards) ─────────────────────────────────────────────────────

@Composable
private fun TabsPanel(s: BrowserState, onClose: () -> Unit) {
    val style = LocalGlassStyle.current
    var confirmCloseAll by remember { mutableStateOf(false) }
    var tabMode by remember { mutableIntStateOf(if (s.tabs.getOrNull(s.current)?.incognito == true) 1 else 0) }
    // Whatever the slider reports, only 0 (Normal) and 1 (Private) are ever acted on.
    val mode = tabMode.coerceIn(0, 1)
    val showPrivate = mode == 1

    // Closing the tab list without ending up on a private tab puts the private lock back.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { if (s.tabs.getOrNull(s.current)?.incognito != true) s.privateUnlocked = false }
    }

    // One snapshot of the tab list per composition: the counts, the grid and the keys below all
    // describe the same tabs, so a list that changes mid-switch can't leave them disagreeing.
    val allTabs = s.tabs.toList()
    val normalCount = allTabs.count { !it.incognito }
    val privateCount = allTabs.count { it.incognito }
    val currentTabs = allTabs.filter { it.incognito == showPrivate }

    // LazyGrid throws "Key ... was already used" when two items share a key, and does it the moment
    // that list is shown. Tab ids should be unique, but if one ever repeats (restored session,
    // clashing counters) the repeat gets a suffix instead of taking the whole app down.
    val usedKeys = HashSet<Any>()
    val keyedTabs = currentTabs.map { tab ->
        var k: Any = tab.id
        var n = 1
        while (!usedKeys.add(k)) {
            k = "${tab.id}#${++n}"
        }
        k to tab
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                "Tabs",
                "${allTabs.size} open · $normalCount normal · $privateCount private",
                onClose
            ) {
                GlassIconButton(
                    AppleIcons.DeleteSweep, "Close all tabs",
                    onClick = { confirmCloseAll = true },
                    size = 44.dp, iconSize = 19.dp, filled = false
                )
            }

            // Adaptive liquid slider — Normal ↔ Private switcher.
            // The pill is now a full liquidGlass surface (via updated GlassSegmented)
            // that springs in together with the ScreenHeader above it.
            GlassSegmented(
                options = listOf("Normal  $normalCount", "Private  $privateCount"),
                selected = mode,
                onSelect = { sel ->
                    // Normal -> Private asks for the phone lock (when the private tab lock is on).
                    if (sel == 1 && !showPrivate) {
                        s.requirePrivate { tabMode = 1 }
                    } else {
                        tabMode = sel
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            if (currentTabs.isEmpty()) {
                EmptyState(
                    if (!showPrivate) AppleIcons.Add else AppleIcons.Incognito,
                    if (!showPrivate) "No normal tabs" else "No private tabs",
                    if (!showPrivate) "Tap + to open a new normal tab." else "Tap + to open a new private tab."
                )
            } else {
                // Each list gets its own grid and scroll state. Flipping Normal <-> Private used to reuse
                // one grid state while the list underneath was swapped, so a first-visible index that only
                // made sense for the old list could point past the end of the new, shorter one.
                key(mode) {
                    val gridState = rememberLazyGridState()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = gridState,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 110.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(keyedTabs, key = { it.first }) { entry ->
                            val tab = entry.second
                            val isCurrent = s.tabs.indexOf(tab) == s.current
                            // iOS 27 tab card: concentric radii (26 outer / 18 inner), page preview, selection ring.
                            val inner = RoundedCornerShape(18.dp)
                            val ink = if (tab.incognito) Color(0xFFBF5AF2) else style.accent
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .height(172.dp)
                                    .liquidGlass(CardShape, 0.92f)
                                    .then(if (isCurrent) Modifier.border(2.dp, ink, CardShape) else Modifier)
                                    .clickableHaptic { s.selectTab(tab); onClose() }
                                    .padding(8.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().height(30.dp).padding(start = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        if (tab.isHome) "New tab" else tab.title,
                                        color = TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    GlassIconButton(
                                        AppleIcons.Close, "Close tab",
                                        onClick = {
                                            // indexOf is -1 once the tab is already gone (quick double tap); closeTab(-1) would throw.
                                            val idx = s.tabs.indexOf(tab)
                                            if (idx >= 0) s.closeTab(idx)
                                        },
                                        size = 26.dp, iconSize = 13.dp
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .clip(inner)
                                        .background(ink.copy(alpha = if (tab.incognito) 0.16f else 0.10f), inner),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        SiteBadge(if (tab.isHome) "Aurex" else hostOf(tab.url), 46.dp, bitmap = tab.favicon)
                                        Spacer(Modifier.height(8.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (tab.incognito) {
                                                Icon(
                                                    AppleIcons.Incognito, null,
                                                    tint = ink, modifier = Modifier.size(13.dp)
                                                )
                                                Spacer(Modifier.width(4.dp))
                                            }
                                            Text(
                                                when {
                                                    tab.incognito -> "Private"
                                                    tab.isHome -> "Start page"
                                                    else -> hostOf(tab.url)
                                                },
                                                color = if (tab.incognito) ink else TextSecondary,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(horizontal = 8.dp)
                                            )
                                        }
                                        if (tab.progress < 0.999f) {
                                            Text("${(tab.progress * 100).toInt()}%", color = ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (confirmCloseAll) {
                GlassConfirmDialog(
                    "Close all tabs?",
                    "Every open tab will be closed and a fresh start page will open.",
                    "Close all",
                    destructive = true,
                    onConfirm = { confirmCloseAll = false; s.closeAllTabs(); onClose() },
                    onDismiss = { confirmCloseAll = false }
                )
            }
        }
        // iOS 27 floating glass toolbar.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                .fillMaxWidth()
                .liquidGlass(Capsule, 1.1f, 0.04f)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            GlassPillButton(
                "New tab", onClick = { s.newTab("", showPrivate); onClose() },
                icon = AppleIcons.Add, accent = true
            )
            GlassPillButton("Done", onClick = onClose)
        }
    }
}

// ── Library (bookmarks / history / downloads) ───────────────────────────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LibraryPanel(s: BrowserState, panel: Panel, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DlItem?>(null) }
    val tab = s.tabs.getOrNull(s.current)
    val ctx = LocalContext.current

    // Hold a download to start selecting; tap more to add / remove; share or delete them together.
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    // Hold + drag over the cards selects a range; the list scrolls by itself near the top / bottom edge.
    val dlListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val dragSel = remember { DragSelect() }
    val dlList = s.downloads.items.filter { query.isBlank() || it.name.contains(query, true) }
    val selItems = if (panel == Panel.Downloads) s.downloads.items.filter { it.id in selected } else emptyList()
    val selecting = selItems.isNotEmpty()
    fun toggleSel(id: Int) { selected = if (id in selected) selected - id else selected + id }
    androidx.activity.compose.BackHandler(enabled = selecting) { selected = emptySet() }

    val title = when (panel) {
        Panel.Bookmarks -> "Bookmarks"
        Panel.History -> "History"
        else -> "Downloads"
    }
    val subtitle = when (panel) {
        Panel.Bookmarks -> "${s.bookmarks.size} saved"
        Panel.History -> "${s.history.size} visited"
        else -> {
            val run = s.downloads.items.filter { it.status == DlStatus.RUNNING }
            val bps = run.sumOf { it.speed }
            if (run.isEmpty()) "${s.downloads.items.size} total"
            else "${run.size} active" + (if (bps > 0) " · ${formatSpeed(bps)}" else "") + " · ${s.downloads.items.size} total"
        }
    }

    val searchState = rememberSearchFabState()
    Box(Modifier.fillMaxSize().closeSearchOnEmptyTap(searchState)) {
    Column(Modifier.fillMaxSize().imePadding()) {
        val bs = 44.dp
        val headerActions: @Composable RowScope.() -> Unit = {
            if (panel == Panel.Downloads && s.downloads.items.any { it.status == DlStatus.RUNNING }) {
                GlassIconButton(
                    AppleIcons.Pause, "Pause all",
                    onClick = { s.downloads.pauseAll() },
                    size = bs, iconSize = 20.dp, filled = false
                )
                Spacer(Modifier.width(2.dp))
            }
            if (panel != Panel.Bookmarks) {
                GlassIconButton(
                    AppleIcons.DeleteSweep,
                    if (panel == Panel.History) "Clear history" else "Clear finished",
                    onClick = {
                        if (panel == Panel.History) confirmClear = true
                        else s.downloads.clearFinished()
                    },
                    size = bs, iconSize = 20.dp, filled = false
                )
            }
        }
        if (panel == Panel.Downloads) {
            val run = s.downloads.items.filter { it.status == DlStatus.RUNNING }
            androidx.compose.animation.AnimatedContent(
                targetState = selecting,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                label = "dlHeader"
            ) { sel ->
                if (sel) {
                    SelectionHeader(
                        count = selItems.size,
                        total = dlList.size,
                        allSelected = dlList.isNotEmpty() && dlList.all { it.id in selected },
                        canShare = selItems.any { it.status == DlStatus.DONE },
                        onClose = { selected = emptySet() },
                        onSelectAll = {
                            selected = if (dlList.isNotEmpty() && dlList.all { it.id in selected }) emptySet()
                            else dlList.map { it.id }.toSet()
                        },
                        onShare = {
                            val ready = selItems.filter { it.status == DlStatus.DONE && it.file.exists() }
                            if (ready.isEmpty()) s.notify("Only finished downloads can be shared")
                            else DownloadManager.shareMany(ctx, ready)
                        },
                        onDelete = { confirmBulkDelete = true }
                    )
                } else {
                    DownloadsHeader(title, run.size, run.sumOf { it.speed }, s.downloads.items.size, onClose, headerActions)
                }
            }
        }
        else ScreenHeader(title, subtitle, onClose, headerActions)

        Spacer(Modifier.height(if (panel == Panel.Downloads) 10.dp else 6.dp))

        when (panel) {
            Panel.Bookmarks -> {
                val list = s.bookmarks.filter {
                    query.isBlank() || it.title.contains(query, true) || it.url.contains(query, true)
                }
                if (list.isEmpty()) {
                    EmptyState(
                        AppleIcons.Bookmark,
                        if (query.isBlank()) "No bookmarks yet" else "No matches",
                        if (query.isBlank()) "Long-press a page or use the menu to save it."
                        else "Try a different search."
                    )
                } else {
                    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 100.dp)) {
                        itemsIndexed(list, key = { _, it -> it.url }) { i, bm ->
                            GroupedRow(i, list.size, onClick = {
                                tab?.let { s.load(it, bm.url) }
                                onClose()
                            }) {
                                SiteBadge(hostOf(bm.url), 38.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        bm.title.ifBlank { hostOf(bm.url) },
                                        color = TextPrimary, fontSize = 17.sp, letterSpacing = (-0.41).sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        bm.url, color = TextSecondary, fontSize = 13.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                GlassIconButton(
                                    AppleIcons.Delete, "Remove bookmark",
                                    onClick = { s.bookmarks.remove(bm); s.save() },
                                    size = 34.dp, iconSize = 16.dp, filled = false
                                )
                            }
                        }
                    }
                }
            }

            Panel.History -> {
                val list = s.history.filter {
                    query.isBlank() || it.title.contains(query, true) || it.url.contains(query, true)
                }
                if (list.isEmpty()) {
                    EmptyState(
                        AppleIcons.History,
                        if (query.isBlank()) "History is empty" else "No matches",
                        if (query.isBlank()) "Pages you visit will appear here. Private tabs never save history."
                        else "Try a different search."
                    )
                } else {
                    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 100.dp)) {
                        itemsIndexed(list, key = { _, it -> it.url + it.time }) { i, h ->
                            GroupedRow(i, list.size, dividerInset = 58.dp, onClick = {
                                tab?.let { s.load(it, h.url) }
                                onClose()
                            }) {
                                SiteBadge(hostOf(h.url), 34.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        h.title.ifBlank { hostOf(h.url) },
                                        color = TextPrimary, fontSize = 17.sp, letterSpacing = (-0.41).sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Row {
                                        Text(hostOf(h.url), color = TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Spacer(Modifier.width(8.dp))
                                        Text(timeAgo(h.time), color = TextTertiary, fontSize = 13.sp)
                                    }
                                }
                                GlassIconButton(
                                    AppleIcons.Close, "Remove",
                                    onClick = { s.deleteHistory(h) },
                                    size = 32.dp, iconSize = 15.dp, filled = false
                                )
                            }
                        }
                    }
                }
            }

            else -> {
                val list = dlList
                if (list.isEmpty()) {
                    EmptyState(
                        AppleIcons.Download,
                        if (query.isBlank()) "No downloads" else "No matches",
                        if (query.isBlank()) "Fast parallel downloads with a built-in HLS downloader. Start from any link, video or audio."
                        else "Try a different search."
                    )
                } else {
                    LazyColumn(
                        state = dlListState,
                        modifier = Modifier.dragSelect(
                            ds = dragSel,
                            listState = dlListState,
                            ids = { list.map { it.id } },
                            selection = { selected },
                            onSelection = { selected = it }
                        ),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 100.dp)
                    ) {
                        items(list, key = { it.id }) { d ->
                            Box(
                                Modifier
                                    .animateItemPlacement(spring(dampingRatio = 0.85f, stiffness = 420f))
                                    .trackRow(d.id, dragSel)
                                    .padding(bottom = 12.dp)
                            ) {
                                // swipe right = delete (no confirmation), swipe left = share; both can be switched off in Settings
                                SwipeActionRow(
                                    enabled = !selecting,
                                    deleteOn = s.swipeDlRightDelete,
                                    shareOn = s.swipeDlLeftShare,
                                    canShare = d.status == DlStatus.DONE,
                                    onDelete = { s.downloads.deleteWithFile(d) },
                                    onShare = {
                                        if (d.file.exists()) DownloadManager.share(ctx, d)
                                        else s.notify("File not found")
                                    },
                                    onShareBlocked = { s.notify("Only finished downloads can be shared") }
                                ) {
                                    DownloadCard(
                                        s, d,
                                        onAskDelete = { deleteTarget = it },
                                        selecting = selecting,
                                        selected = d.id in selected,
                                        onToggle = { toggleSel(d.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // search lives bottom-right and morphs into a bar on tap
    StandardSearchFab(
        query = query,
        onQuery = { query = it },
        placeholder = "Search $title",
        state = searchState
    )
    }

    if (confirmClear) {
        GlassConfirmDialog(
            "Clear history?",
            "All visited pages will be removed. Bookmarks and downloads stay.",
            "Clear",
            destructive = true,
            onConfirm = { confirmClear = false; s.clearHistory() },
            onDismiss = { confirmClear = false }
        )
    }

    if (confirmBulkDelete) {
        GlassConfirmDialog(
            "Delete ${selItems.size} ${if (selItems.size == 1) "download" else "downloads"}?",
            "They will be removed from the list and deleted from this device.",
            "Delete",
            destructive = true,
            onConfirm = {
                confirmBulkDelete = false
                val toDelete = selItems.toList()
                selected = emptySet()
                toDelete.forEach { s.downloads.deleteWithFile(it) }
            },
            onDismiss = { confirmBulkDelete = false }
        )
    }

    deleteTarget?.let { target ->
        GlassConfirmDialog(
            "Delete download?",
            "\"${target.name}\" will be removed from the list and deleted from this device.",
            "Delete",
            destructive = true,
            onConfirm = { deleteTarget = null; s.downloads.deleteWithFile(target) },
            onDismiss = { deleteTarget = null }
        )
    }
}

private fun fileTypeIcon(name: String, kindLabel: String): Pair<ImageVector, Color> {
    val ext = name.lowercase().substringAfterLast('.', "")
    return when {
        kindLabel == "VIDEO" || kindLabel == "HLS" || ext in listOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v") ->
            Pair(AppleIcons.Movie, Color(0xFFBF5AF2)) // Purple
        kindLabel == "AUDIO" || ext in listOf("mp3", "m4a", "aac", "ogg", "flac", "wav", "opus") ->
            Pair(AppleIcons.MusicNote, MusicRed) // Apple Music red
        ext in listOf("pdf", "doc", "docx", "txt", "epub") ->
            Pair(AppleIcons.Article, Color(0xFFFF9F0A)) // Amber
        ext in listOf("png", "jpg", "jpeg", "gif", "webp", "svg") ->
            Pair(AppleIcons.Image, Color(0xFF30D158)) // Emerald
        ext in listOf("zip", "rar", "7z", "tar", "gz") ->
            Pair(AppleIcons.FolderZip, Color(0xFF40C8E0)) // Mint
        ext == "apk" ->
            Pair(AppleIcons.Android, Color(0xFF30D158)) // Green
        else ->
            Pair(AppleIcons.Download, Color(0xFF0A84FF)) // Ocean Blue
    }
}

/** Used as TEXT colour, so light mode gets the darker accessible variants. */
@Composable
private fun speedColor(bps: Long): Color {
    val p = LocalPalette.current
    return when {
        bps < 204_800L -> p.badText // Red for < 200 KB/s
        bps < 2_097_152L -> p.warnText // Orange for 200 KB/s - 2 MB/s
        else -> p.goodText // Green for > 2 MB/s
    }
}

/** Time left in whole minutes only ("3 min left"); anything under a minute reads "1 min left". */
private fun etaMinutes(seconds: Long): String {
    val mins = ((seconds + 59L) / 60L).coerceAtLeast(1L)
    return "$mins min left"
}

/** Tabular digits: numbers keep their width while they tick (no jitter in speed / size / percent). */
private val Tnum = TextStyle(fontFeatureSettings = "tnum")

/** Downloads header: roomy back button + large title, with a live "active / speed / total" status line. */
@Composable
private fun DownloadsHeader(
    title: String,
    active: Int,
    bps: Long,
    total: Int,
    onClose: () -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val style = LocalGlassStyle.current
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 460f)) }
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = enter.value.coerceIn(0f, 1f); translationY = -8.dp.toPx() * (1f - enter.value) }
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.liquidGlass(CircleShape, 0.9f, 0.04f)) {
            GlassIconButton(AppleIcons.ArrowBack, "Back", onClick = onClose, size = 44.dp, iconSize = 20.dp, filled = false)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 0.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active > 0) {
                    Row(
                        Modifier
                            .background(style.accent.copy(alpha = 0.16f), Capsule)
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusDot(style.accent, true)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "$active active", color = style.accent, fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false
                        )
                    }
                    if (bps > 0) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            formatSpeed(bps), color = speedColor(bps), fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, style = Tnum, maxLines = 1, softWrap = false
                        )
                    }
                    Text("   ·   ", color = TextTertiary, fontSize = 13.sp, maxLines = 1)
                }
                Text("$total total", color = TextSecondary, fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            Row(
                Modifier
                    .animateContentSize(spring(dampingRatio = 0.80f, stiffness = 440f))
                    .liquidGlass(Capsule, 0.9f, 0.04f)
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) { trailing() }
        }
    }
}

/** Main action (pause / resume / retry) as a tonal circle; progress itself lives in the bar under the row. */
@Composable
private fun ProgressRing(progress: Float, color: Color, icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .background(color.copy(alpha = 0.15f), CircleShape)
            .clip(CircleShape)
            .clickableHaptic { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc, tint = color, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
    val a = if (pulsing) {
        rememberInfiniteTransition(label = "dot").animateFloat(
            initialValue = 0.35f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "dotA"
        ).value
    } else 1f
    Box(Modifier.size(6.dp).graphicsLayer { alpha = a }.background(color, CircleShape))
}

/** True only when this download really is divided into parts (or HLS segments). A single stream has nothing to show. */
private fun hasParts(d: DlItem): Boolean =
    if (d.kind == DlKind.HLS) d.segmentTotal > 1 else d.partProgress.size >= 2

/**
 * Opened with the \/ arrow on a download that is not finished: into how many parts the file is divided,
 * how many of them are done, and a small bar for every part (each fills as that part downloads).
 * Videos that come as many small pieces (HLS) show the piece count and the parallel workers.
 * A download that is NOT divided shows nothing here (the caller does not even draw this block).
 */
@Composable
private fun PartsBlock(d: DlItem, color: Color) {
    if (!hasParts(d)) return
    val parts = d.partProgress.toList()
    val n = parts.size
    val running = d.status == DlStatus.RUNNING
    val finished = parts.count { it >= 0.999f }
    val summary = when {
        d.kind == DlKind.HLS -> "${d.segmentTotal} segments · ${d.segmentDone} done"
        else -> "$n parts · $finished done"
    }
    val detail = when {
        d.kind == DlKind.HLS && n > 0 -> "$n parallel workers"
        n >= 2 && running && d.partsUsed > 0 -> "${d.partsUsed} connections at the moment"
        else -> ""
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Parts", color = TextSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                summary, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                style = Tnum, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (detail.isNotEmpty()) {
            Text(
                detail, color = TextTertiary, fontSize = 12.sp, style = Tnum,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (n > 0) {
            Spacer(Modifier.height(8.dp))
            // up to 48 cells; more parts (a very big file) are grouped, each cell showing the average of its group,
            // so the bar covers the WHOLE file instead of only its first 48 parts
            val shown = if (n <= 48) parts else List(48) { i ->
                val from = i * n / 48
                val to = ((i + 1) * n / 48).coerceAtLeast(from + 1).coerceAtMost(n)
                parts.subList(from, to).average().toFloat()
            }
            val perRow = if (shown.size <= 12) shown.size else 12
            shown.chunked(perRow).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    row.forEach { p -> PartCell(p, color, Modifier.weight(1f)) }
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PartCell(p: Float, color: Color, modifier: Modifier) {
    val fill by animateFloatAsState(p.coerceIn(0f, 1f), tween(350), label = "partFill")
    val tint by animateColorAsState(if (p >= 0.999f) Good else color, tween(250), label = "partTint")
    Box(
        modifier
            .height(8.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(TextPrimary.copy(alpha = 0.10f))
    ) {
        Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(tint))
    }
}

@Composable
private fun DlChip(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(Capsule)
            .background(TextPrimary.copy(alpha = 0.07f), Capsule)
            .clickableHaptic { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun DownloadCard(
    s: BrowserState,
    d: DlItem,
    onAskDelete: (DlItem) -> Unit,
    selecting: Boolean = false,
    selected: Boolean = false,
    onToggle: () -> Unit = {}
) {
    val style = LocalGlassStyle.current
    val ctx = LocalContext.current
    val running = d.status == DlStatus.RUNNING
    val done = d.status == DlStatus.DONE
    var expanded by remember { mutableStateOf(false) }
    val (typeIcon, typeColor) = fileTypeIcon(d.name, d.kindLabel)
    val isAudio = typeIcon == AppleIcons.MusicNote
    val mainColor = if (isAudio) MusicRed else style.accent
    val statusColor by animateColorAsState(
        when (d.status) {
            DlStatus.DONE -> Good
            DlStatus.RUNNING -> mainColor
            DlStatus.PAUSED -> Warn
            DlStatus.FAILED -> Bad
            DlStatus.QUEUED -> TextSecondary
        },
        tween(250), label = "dlStatus"
    )
    val progress by animateFloatAsState(d.progress.coerceIn(0f, 1f), tween(450), label = "dlProgress")
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, spring(dampingRatio = 0.8f, stiffness = 500f), label = "dlChevron")
    val selA by animateFloatAsState(if (selected) 1f else 0f, tween(180), label = "dlSel")

    // entrance: rises and fades in once
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 380f)) }

    // A card that gets selected presses in and springs back; one that is let go swells a touch and settles.
    val pop = remember { Animatable(1f) }
    var firstSelState by remember { mutableStateOf(true) }
    LaunchedEffect(selected) {
        if (firstSelState) { firstSelState = false; return@LaunchedEffect }
        if (selected) {
            pop.snapTo(0.955f)
            pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 520f))
        } else {
            pop.snapTo(1.02f)
            pop.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 600f))
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val e = enter.value
                alpha = e.coerceIn(0f, 1f)
                translationY = 14.dp.toPx() * (1f - e)
                scaleX = (0.97f + 0.03f * e) * pop.value
                scaleY = (0.97f + 0.03f * e) * pop.value
            }
            .groupedSurface(TileShape)
            .background(style.accent.copy(alpha = 0.14f * selA), TileShape)
            .border(1.5.dp, style.accent.copy(alpha = selA), TileShape)
            .animateContentSize(spring(dampingRatio = 0.85f, stiffness = 500f))
            .tapOrHold(
                onHold = {},
                holdEnabled = false,
                tapKind = if (selecting) (if (selected) Hx.TOGGLE_OFF else Hx.TOGGLE_ON) else Hx.TAP,
                onTap = {
                if (selecting) onToggle()
                else if (done) {
                    // videos and audio open in Aurex's own player; everything else in the system
                    if (d.file.exists() && MediaTools.isPlayable(d.file)) {
                        MediaTools.playLocal(ctx, d.file, d.name, style.accent.toArgb())
                    } else {
                        DownloadManager.open(ctx, d)
                    }
                } else expanded = !expanded
                }
            )
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(
                visible = selecting,
                enter = fadeIn(tween(160)) + androidx.compose.animation.expandHorizontally(spring(dampingRatio = 0.85f, stiffness = 500f)),
                exit = fadeOut(tween(100)) + androidx.compose.animation.shrinkHorizontally(tween(160))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SelectDot(selected, mainColor)
                    Spacer(Modifier.width(12.dp))
                }
            }
            FileThumb(
                file = d.file,
                ready = done,
                size = 56.dp,
                fallbackIcon = typeIcon,
                fallbackColor = typeColor,
                showPlay = done,
                sourceUrl = d.url,
                fileName = d.name,
                kindLabel = d.kindLabel
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        d.name, color = TextPrimary, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.2).sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    val tag = MediaTools.playerText(d.qualityLabel)
                    if (tag.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .background(mainColor.copy(alpha = 0.14f), Capsule)
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                tag, color = mainColor, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                                letterSpacing = 0.3.sp, maxLines = 1, softWrap = false
                            )
                        }
                    }
                }
                val note = d.statusNote?.takeIf { running && it.isNotBlank() }
                val detail = when {
                    note != null -> note
                    done -> formatBytes(maxOf(d.total, d.done))
                    d.status == DlStatus.FAILED -> d.error.orEmpty()
                    d.shownTotal > 0 -> formatBytes(d.done) + " of " + (if (d.totalIsEstimate) "~" else "") + formatBytes(d.shownTotal)
                    d.done > 0 -> formatBytes(d.done)
                    else -> ""
                }
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(statusColor, running)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        when (d.status) {
                            DlStatus.RUNNING -> "${(progress * 100).toInt()}%"
                            DlStatus.DONE -> "Completed"
                            DlStatus.PAUSED -> "Paused"
                            DlStatus.FAILED -> "Failed"
                            DlStatus.QUEUED -> "Queued"
                        },
                        color = statusColor, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                        style = Tnum, maxLines = 1, softWrap = false
                    )
                    if (running && d.speed > 0) {
                        Text("   ·   ", color = TextTertiary, fontSize = 13.sp, maxLines = 1)
                        Text(
                            formatSpeed(d.speed), color = speedColor(d.speed), fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold, style = Tnum, maxLines = 1, softWrap = false
                        )
                    }
                    if (detail.isNotEmpty() && (done || d.status == DlStatus.FAILED)) {
                        Text(
                            "   ·   ", color = TextTertiary, fontSize = 13.sp, maxLines = 1
                        )
                        Text(
                            detail, color = TextSecondary, fontSize = 13.sp, style = Tnum, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                if (detail.isNotEmpty() && !done && d.status != DlStatus.FAILED) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        detail, color = TextSecondary, fontSize = 13.sp, style = Tnum, maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            // main action: ring (pause / resume) while it is not finished, a check when it is
            androidx.compose.animation.Crossfade(targetState = done, animationSpec = tween(220), label = "dlAction") { isDone ->
                if (isDone) {
                    Box(
                        Modifier.size(44.dp).background(Good.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(AppleIcons.Check, null, tint = Good, modifier = Modifier.size(20.dp))
                    }
                } else {
                    ProgressRing(
                        progress, if (d.status == DlStatus.FAILED) Bad else mainColor,
                        when (d.status) {
                            DlStatus.RUNNING -> AppleIcons.Pause
                            DlStatus.FAILED -> AppleIcons.Refresh
                            else -> AppleIcons.PlayArrow
                        },
                        if (running) "Pause" else "Resume"
                    ) { if (running) s.downloads.pause(d) else s.downloads.resume(d) }
                }
            }
            Box(
                Modifier.size(36.dp).clickableHaptic(haptic = false) { expanded = !expanded },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    AppleIcons.KeyboardArrowDown, "Details", tint = TextTertiary,
                    modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = chevron }
                )
            }
        }

        if (!done) {
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(Capsule)
                    .background(TextPrimary.copy(alpha = 0.10f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0.01f, 1f))
                        .fillMaxHeight()
                        .background(if (d.status == DlStatus.FAILED) Bad else mainColor, Capsule)
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(180)) + expandVertically(spring(dampingRatio = 0.85f, stiffness = 500f)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(160))
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                d.error?.takeIf { d.status == DlStatus.FAILED }?.let {
                    Text(it, color = BadText, fontSize = 12.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                }
                // The \/ arrow: how this download is divided and how every part is doing.
                // (only when the download really is divided into parts; otherwise nothing is shown)
                if (!done && hasParts(d)) {
                    PartsBlock(d, mainColor)
                    Spacer(Modifier.height(14.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (running && d.eta > 0) etaMinutes(d.eta) else "",
                        color = TextTertiary, fontSize = 13.sp, style = Tnum, maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    DlChip(AppleIcons.ContentCopy, "Copy link", TextPrimary) { s.copy(d.url) }
                    if (done) {
                        Spacer(Modifier.width(8.dp))
                        DlChip(AppleIcons.Share, "Share", TextPrimary) { DownloadManager.share(ctx, d) }
                    }
                    Spacer(Modifier.width(8.dp))
                    // Delete removes the item AND the file on the device, no confirmation
                    DlChip(AppleIcons.Delete, "Delete", Bad) { s.downloads.deleteWithFile(d) }
                }
            }
        }
    }
}

/**
 * One place to save the video or audio of the page that is open, for any site the internal
 * engine understands. Replaces hunting for raw stream links on YouTube / Instagram / etc.
 */
@Composable
internal fun UniversalDownloadCard(s: BrowserState, tab: Tab?) {
    val pageUrl = tab?.url.orEmpty()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    // Starts as the open page; can be replaced by any pasted link (any website).
    var typed by remember(pageUrl) { mutableStateOf(pageUrl) }
    val target = typed.trim()
    val ok = YtDlpEngine.canTry(target)
    val accent = LocalGlassStyle.current.accent
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .liquidGlass(TileShape, 0.9f)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).glassTile(CircleShape, 0.14f, accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(AppleIcons.Download, null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Download video or audio", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Paste a link or use the open page · YouTube, Instagram, X, TikTok and 1000+ more",
                    color = TextTertiary, fontSize = 9.5.sp
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().glassTile(SmallShape, 0.06f).padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                if (typed.isEmpty()) {
                    Text("Paste any video or page link", color = TextTertiary, fontSize = 11.sp)
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 11.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Paste", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickableHaptic {
                    clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { typed = it }
                }
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(YtQuality.BEST, YtQuality.P1080, YtQuality.P720, YtQuality.P480).forEach { q ->
                GlassPillButton(
                    q.label, { s.downloadUniversal(target, q) },
                    icon = if (q == YtQuality.BEST) AppleIcons.Movie else null,
                    accent = q == YtQuality.BEST, enabled = ok
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(YtQuality.MP3, YtQuality.M4A).forEach { q ->
                GlassPillButton(
                    q.label, { s.downloadUniversal(target, q) },
                    icon = AppleIcons.MusicNote, enabled = ok
                )
            }
        }
    }
}

fun launchPlayer(
    ctx: android.content.Context,
    tab: Tab?,
    url: String,
    accent: Int? = null,
    audioUrl: String? = null,
    /** > 0: start at this stream height (quality picked in the in-app player); the page is resolved for it. */
    height: Int = 0,
    /** true: open the player in landscape when the page video is landscape (quality switch must not drop into portrait). */
    keepLandscape: Boolean = false,
    /** Runs on the main thread right after the player activity was started (not before: the caller's UI stays until then). */
    onStarted: (() -> Unit)? = null
) {
    // YouTube changes videos without a page load, so the tab's stored address can be the home page.
    // Ask the page which video is playing: the player needs that watch link to resolve the stream
    // (a raw googlevideo link alone is "not a playable video file").
    VideoPage.resolve(tab) { page, title ->
        val cdn = url.startsWith("blob:") ||
            runCatching { android.net.Uri.parse(url).host.orEmpty().endsWith("googlevideo.com") }.getOrDefault(false)
        if (cdn && YtDlpEngine.isYoutubeUrl(page) && !YtDlpEngine.isItemUrl(page)) {
            android.widget.Toast.makeText(
                ctx, AppLang.translate("Couldn't tell which video is playing. Open the video page and try again."),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return@resolve
        }
        val pageUrl = page.ifBlank { tab?.url.orEmpty() }
        // Take over from the page video: read its position, pause it, and start the player there.
        // 1440p / 4K / 8K load slowly. The old hand-over kept the page playing meanwhile and then seeked the
        // player to where the page had got to, twice: a second buffering stall on a frozen picture. For these the
        // page is paused right now, so the player starts at exactly that second and never has to seek.
        val hiRes = MediaTools.plainHeight(height) >= 1440
        // Launched from the in-app player (quality switch): also take the picture the video shows right now, so the full
        // player opens ON that picture instead of on black (that black swap was the 'player opens again' look).
        PlayerSync.capture(tab, keep = !hiRes, frame = keepLandscape) { sync ->
        PlayerSync.setBridge(sync?.frame)
        val intent = Intent(ctx, PlayerActivity::class.java)
            .putExtra("url", url)
            .putExtra("audioUrl", audioUrl)
            .putExtra("title", title.ifBlank { tab?.title ?: hostOf(url) })
            .putExtra("referer", tab?.mediaRef?.get(url) ?: pageUrl)   // the embed player's address when the link came from one
            .putExtra("pageUrl", pageUrl)
            .putExtra("ua", tab?.web?.settings?.userAgentString)
            .putExtra("cookie", siteCookie(url))
        if (accent != null) intent.putExtra("accent", accent)
        if (height > 0) intent.putExtra("height", height)
        // A switch made from a landscape screen / landscape video must not drop into portrait first.
        val screenLandscape = ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (keepLandscape && tab != null && (screenLandscape || tab.videoWidth <= 0 || tab.videoWidth >= tab.videoHeight))
            intent.putExtra("landscape", true)
        if (sync != null) {
            intent.putExtra("sync", true).putExtra("syncLive", sync.live)
                // The page keeps playing (sound continues) until the player has a picture; then it hands over.
                .putExtra("handoff", !hiRes && sync.playing && !sync.live && sync.ms >= 0)
            if (sync.ms >= 0) intent.putExtra("startMs", sync.ms)
        }
        // no slide animation: the full player must appear in place of the in-app one, not open 'again' on top of it
        val started = runCatching {
            ctx.startActivity(intent, android.app.ActivityOptions.makeCustomAnimation(ctx, 0, 0).toBundle())
        }.isSuccess
        // Player did not open: the page video was never paused, nothing to give back.
        if (!started) { PlayerSync.clearBridge(); PlayerSync.release(-1L, sync?.playing == true, false) }
        else onStarted?.invoke()
        }
    }
}
