package com.aurex.browser

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.border
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** Video quality label (1080p, 4K ...): clean theme-coloured text, no gold and no glow. */
@Composable
fun GoldQualityText(text: String, modifier: Modifier = Modifier, fontSize: Float = 11f, color: Color = Color.Unspecified) {
    Text(
        text = text,
        modifier = modifier,
        color = if (color == Color.Unspecified) TextPrimary else color,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        style = TextStyle(
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp
        )
    )
}

private class FKind(val top: Color, val bottom: Color, val icon: androidx.compose.ui.graphics.vector.ImageVector, val tag: String)

private fun fileKindOf(name: String, kindLabel: String): FKind {
    val ext = name.lowercase().substringAfterLast('.', "")
    fun k(top: Long, bottom: Long, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String = "") =
        FKind(Color(top), Color(bottom), icon, tag)
    return when {
        kindLabel == "VIDEO" || kindLabel == "HLS" ||
            ext in setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "flv", "ts", "wmv", "mpg", "mpeg", "m3u8") ->
            k(0xFFB57BFF, 0xFF6E3FD8, AppleIcons.Video)
        kindLabel == "AUDIO" || ext in setOf("mp3", "m4a", "aac", "ogg", "flac", "wav", "opus", "wma", "amr") ->
            k(0xFFFB5C74, 0xFFFA233B, AppleIcons.MusicNote)
        ext in setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "heic", "avif", "ico") ->
            k(0xFF5BE585, 0xFF1FA84F, AppleIcons.Image)
        ext == "pdf" -> k(0xFFFF6A5E, 0xFFD4291D, AppleIcons.Doc, "PDF")
        ext in setOf("doc", "docx", "odt", "rtf", "pages") -> k(0xFF5AA9FF, 0xFF1B64D6, AppleIcons.Doc, "DOC")
        ext in setOf("txt", "md", "log") -> k(0xFFA9AEBA, 0xFF6B7180, AppleIcons.Doc, "TXT")
        ext in setOf("xls", "xlsx", "csv", "ods", "numbers", "tsv") -> k(0xFF4BD58A, 0xFF11803F, AppleIcons.Sheet, ext.take(4).uppercase())
        ext in setOf("ppt", "pptx", "odp", "key") -> k(0xFFFFA74A, 0xFFE06A0F, AppleIcons.Slides, "PPT")
        ext in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz") -> k(0xFFFFCB4D, 0xFFE0910F, AppleIcons.Archive, ext.take(4).uppercase())
        ext in setOf("apk", "xapk", "apks", "aab") -> k(0xFF6FE08F, 0xFF21A04A, AppleIcons.Android, "APK")
        ext in setOf("exe", "msi", "dmg", "iso", "deb", "pkg") -> k(0xFF8E94A8, 0xFF4D5366, AppleIcons.Installer, ext.take(4).uppercase())
        ext in setOf("epub", "mobi", "azw3", "azw", "djvu", "cbz", "cbr") -> k(0xFFFFB45E, 0xFFD9731A, AppleIcons.Book, ext.take(4).uppercase())
        ext in setOf("html", "htm", "js", "json", "xml", "py", "kt", "java", "css", "sh", "sql", "yml", "yaml") ->
            k(0xFF6C7BFF, 0xFF3340C9, AppleIcons.Code, ext.take(4).uppercase())
        ext in setOf("ttf", "otf", "woff", "woff2") -> k(0xFFB0B7C8, 0xFF6A7287, AppleIcons.Doc, "FONT")
        else -> k(0xFF7DB8FF, 0xFF2F7BE8, AppleIcons.Doc, ext.take(4).uppercase())
    }
}

/** iOS-style app-icon tile for a file type: soft gradient squircle, top gloss, white glyph, short type tag. */
@Composable
fun FileTypeTile(name: String, kindLabel: String, size: Dp, modifier: Modifier = Modifier) {
    val kind = remember(name, kindLabel) { fileKindOf(name, kindLabel) }
    val shape = RoundedCornerShape(size * 0.30f)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(kind.top, kind.bottom))),
        contentAlignment = Alignment.Center
    ) {
        // glass gloss on the upper half
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(0f to Color.White.copy(alpha = 0.32f), 0.5f to Color.White.copy(alpha = 0.04f), 1f to Color.Transparent)
                    )
                )
        )
        if (kind.tag.isEmpty()) {
            Icon(kind.icon, null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(kind.icon, null, tint = Color.White, modifier = Modifier.size(size * 0.40f))
                Text(
                    kind.tag, color = Color.White.copy(alpha = 0.95f), fontSize = (size.value * 0.17f).coerceAtLeast(7f).sp,
                    fontWeight = FontWeight.Black, maxLines = 1, softWrap = false, letterSpacing = 0.3.sp
                )
            }
        }
    }
}

/**
 * Thumbnail of a downloaded video / audio file. While it loads, and for files without a picture, the iOS-style
 * file-type tile is shown (a video tile for every video that has no thumbnail).
 */
private fun isMostlyDark(b: ImageBitmap): Boolean = runCatching {
    val s = android.graphics.Bitmap.createScaledBitmap(b.asAndroidBitmap(), 12, 12, true)
    var sum = 0L
    for (y in 0 until 12) for (x in 0 until 12) {
        val p = s.getPixel(x, y)
        sum += (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
    }
    sum / 144 < 14
}.getOrDefault(false)

@Composable
fun FileThumb(
    file: File,
    ready: Boolean,
    size: Dp,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector,
    fallbackColor: Color,
    showPlay: Boolean,
    /** Page link the file came from: a YouTube link gives the official YouTube thumbnail. */
    sourceUrl: String? = null,
    fileName: String = file.name,
    kindLabel: String = ""
) {
    val ctx = LocalContext.current
    val thumb by produceState<ImageBitmap?>(null, file.absolutePath, ready, sourceUrl) {
        value = if (ready || YtThumb.idOf(sourceUrl) != null) {
            Thumbs.get(ctx, file, sourceUrl, ready)
        } else null
    }
    val bmp = thumb
    // A black / near-black frame is no thumbnail: show the video symbol instead of a dark box.
    val tooDark = androidx.compose.runtime.remember(bmp) { bmp != null && isMostlyDark(bmp) }
    if (bmp == null || tooDark) {
        FileTypeTile(fileName, kindLabel, size)
        return
    }
    val shape = RoundedCornerShape(size * 0.30f)
    Box(Modifier.size(size).clip(shape), contentAlignment = Alignment.Center) {
        Image(
            bitmap = bmp, contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (showPlay) {
            Box(
                Modifier.size(24.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(AppleIcons.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** One row of the quality sheet. */
class QualityOption(
    /** "1080p", "720p", "MP3" ... */
    val label: String,
    /** Small grey line (kept for other callers). */
    val detail: String,
    val height: Int = 0,
    val audioQuality: YtQuality? = null,
    val hlsUrl: String? = null,
    /** Real or predicted size in bytes (0 = unknown). Shown in MB / GB. */
    val bytes: Long = 0L,
    /** True when [bytes] is a prediction (shown with "≈"). */
    val guess: Boolean = true
)

/** What the quality sheet shows; filled in asynchronously by [BrowserState]. */
class QualityPrompt(val title: String) {
    var loading by androidx.compose.runtime.mutableStateOf(true)
    var options by androidx.compose.runtime.mutableStateOf<List<QualityOption>>(emptyList())
    var onPick: (QualityOption) -> Unit = {}
}

private fun qualityBadge(label: String): androidx.compose.ui.graphics.vector.ImageVector {
    val base = label.trim().substringBefore(' ')
    if (base.equals("4K", true) || base.equals("8K", true) || base.equals("2K", true)) return AppleIcons.Quality4K
    val h = base.removeSuffix("p").toIntOrNull() ?: return AppleIcons.HighQuality
    return if (h >= 720) AppleIcons.HighQuality else AppleIcons.QualitySD
}

private fun qualityTierTag(label: String): String {
    val base = label.trim().substringBefore(' ')
    val h = base.removeSuffix("p").toIntOrNull() ?: when {
        base.equals("8K", true) -> 4320
        base.equals("4K", true) -> 2160
        base.equals("2K", true) -> 1440
        else -> 0
    }
    val hdr = if (label.contains("HDR", true)) " HDR" else ""
    return when {
        h >= 4320 -> "8K$hdr"
        h >= 2160 -> "UHD$hdr"
        h >= 1440 -> "QHD$hdr"
        h >= 1080 -> "FHD$hdr"
        h >= 720 -> "HD"
        h >= 480 -> "SD"
        h > 0 -> "Low"
        else -> ""
    }
}

private class LastPrompt { var value by androidx.compose.runtime.mutableStateOf<QualityPrompt?>(null) }

private val SheetShape = FloatingPopoverShape
private val SheetOrigin = TransformOrigin(1f, 1f)

/**
 * Redesigned Apple iOS 27 Liquid Glass Download Quality Sheet:
 *  - Compact floating frosted + liquid glass card anchored cleanly above the toolbar.
 *  - Instant Video / Audio liquid pill switcher when both formats are available.
 *  - Highlighted 1-tap "Best Quality" hero card + compact 2-column resolution chip grid
 *    so all video qualities fit at a single glance without tedious scrolling.
 *  - Slim pinned "Always best" toggle footer + liquid drag-down dismiss.
 */
@Composable
fun QualitySheetHost(s: BrowserState) {
    val current = s.qualityPrompt
    val holder = remember { LastPrompt() }
    if (current != null) holder.value = current
    LaunchedEffect(current) {
        if (current == null && holder.value != null) {
            delay(105)
            holder.value = null
        }
    }
    val prompt = holder.value ?: return
    val visible = current != null
    val style = LocalGlassStyle.current
    val dark = LocalPalette.current.dark
    val view = LocalView.current
    val bottomGap = if (s.barTop || s.bare) 12.dp else toolbarFootprint(s.toolbarRows) + 10.dp

    // 0 = Video, 1 = Audio
    var tabMode by remember(prompt) { mutableIntStateOf(0) }

    LiquidFloatingWindow(
        visible = visible,
        onDismiss = { s.qualityPrompt = null },
        fromTop = false,
        anchorOffset = bottomGap,
        sheenKey = prompt
    ) {

                // ── Compact Header: Glossy Download Orb + Title + Close Pill ──
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val headerTint = if (tabMode == 1) MusicRed else style.accent
                    Box(
                        Modifier
                            .size(25.dp)
                            .tintedGlass(CircleShape, headerTint, swell = 0.06f),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (tabMode == 1) AppleIcons.MusicNote else AppleIcons.Download,
                            null, tint = Color.White, modifier = Modifier.size(13.5.dp)
                        )
                    }
                    Spacer(Modifier.width(7.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Download",
                            color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.2).sp
                        )
                        Text(
                            prompt.title.ifBlank { "Select quality" },
                            color = TextSecondary, fontSize = 10.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(5.dp))
                    GlassIconButton(
                        AppleIcons.Close, "Close",
                        onClick = { s.qualityPrompt = null },
                        size = 24.dp, iconSize = 12.dp
                    )
                }

                // ── Loading / Redesigned Content ──
                AnimatedContent(
                    targetState = prompt.loading,
                    transitionSpec = {
                        (fadeIn(tween(200, delayMillis = 50)) togetherWith fadeOut(tween(100)))
                            .using(SizeTransform(clip = true) { _, _ -> spring(dampingRatio = 0.86f, stiffness = 500f) })
                    },
                    label = "qualityContent"
                ) { loading ->
                    if (loading) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .glassTile(RoundedCornerShape(16.dp), 0.09f)
                                .padding(vertical = 16.dp, horizontal = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                color = style.accent, strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Finding best qualities…", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    } else {
                        val byHeight = compareByDescending<QualityOption> { MediaTools.plainHeight(it.height) }
                            .thenByDescending { it.bytes }
                        val bySize = compareByDescending<QualityOption> { it.bytes > 0 }
                            .thenByDescending { it.bytes }
                        val videos = prompt.options.filter { it.audioQuality == null }.sortedWith(byHeight)
                        val audios = prompt.options.filter { it.audioQuality != null }.sortedWith(bySize)
                        val hasBoth = videos.isNotEmpty() && audios.isNotEmpty()
                        val effectiveMode = when {
                            videos.isEmpty() && audios.isNotEmpty() -> 1
                            !hasBoth -> 0
                            else -> tabMode.coerceIn(0, 1)
                        }

                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            // Compact Liquid Video / Audio Mode Switcher
                            if (hasBoth) {
                                QualityModeBar(
                                    mode = effectiveMode,
                                    videoCount = videos.size,
                                    audioCount = audios.size,
                                    accent = style.accent,
                                    onSelect = { next ->
                                        if (next != tabMode) {
                                            Haptics.fire(view, Hx.TAP)
                                            tabMode = next
                                        }
                                    }
                                )
                            }

                            AnimatedContent(
                                targetState = effectiveMode,
                                transitionSpec = {
                                    (fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.96f)) togetherWith
                                        (fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.96f))
                                },
                                label = "qualityModeSwitch"
                            ) { m ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 174.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    if (m == 0) {
                                        val best = videos.firstOrNull()
                                        val rest = if (videos.size > 1) videos.drop(1) else emptyList()
                                        if (best != null) {
                                            StaggerIn(0) {
                                                HeroQualityCard(best, style.accent, dark) { if (s.qualityPrompt != null) prompt.onPick(best) }
                                            }
                                        }
                                        if (rest.isNotEmpty()) {
                                            rest.chunked(2).forEachIndexed { rowIdx, pair ->
                                                StaggerIn(rowIdx + 1) {
                                                    Row(
                                                        Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                                    ) {
                                                        pair.forEach { opt ->
                                                            QualityGridChip(
                                                                o = opt,
                                                                accent = style.accent,
                                                                modifier = Modifier.weight(1f),
                                                                onPick = { if (s.qualityPrompt != null) prompt.onPick(opt) }
                                                            )
                                                        }
                                                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        audios.forEachIndexed { idx, opt ->
                                            StaggerIn(idx) {
                                                AudioQualityCard(opt, dark) { if (s.qualityPrompt != null) prompt.onPick(opt) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Slim Pinned Footer ──
                if (!prompt.loading) {
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Separator))
                    Spacer(Modifier.height(3.dp))
                    CompactToggleRow("Always best", s.alwaysBestQuality) { s.alwaysBestQuality = it; s.save() }
                }
    }
}

@Composable
private fun QualityModeBar(
    mode: Int,
    videoCount: Int,
    audioCount: Int,
    accent: Color,
    onSelect: (Int) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .glassTile(Capsule, 0.12f)
            .padding(2.5.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val items = listOf(
            Triple(0, "Video ($videoCount)", accent),
            Triple(1, "Audio ($audioCount)", MusicRed)
        )
        items.forEach { (idx, label, col) ->
            val selected = mode == idx
            GlowPress(
                onClick = { onSelect(idx) },
                shape = Capsule,
                haptic = false,
                glassBody = selected,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .then(
                        if (selected) Modifier.tintedGlass(Capsule, col, swell = 0.03f)
                        else Modifier
                    )
            ) {
                Text(
                    label,
                    color = if (selected) Color.White else TextPrimary.copy(alpha = 0.86f),
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

/** Highlighted 1-tap Best Quality Liquid Glass hero card. */
@Composable
private fun HeroQualityCard(o: QualityOption, accent: Color, dark: Boolean, onPick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val rim = Brush.verticalGradient(
        0f to accent.copy(alpha = if (dark) 0.65f else 0.55f),
        1f to accent.copy(alpha = 0.18f)
    )
    val lightAccent = if (dark) androidx.compose.ui.graphics.lerp(accent, Color.White, 0.36f) else accent
    GlowPress(
        onClick = onPick,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (dark) Modifier.background(Color(0xFF0A0D16).copy(alpha = 0.48f), shape) else Modifier)
            .background(
                Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = if (dark) 0.30f else 0.18f),
                        accent.copy(alpha = if (dark) 0.14f else 0.08f)
                    )
                ),
                shape
            )
            .border(0.9.dp, rim, shape)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .tintedGlass(RoundedCornerShape(9.dp), accent, swell = 0.05f),
                contentAlignment = Alignment.Center
            ) {
                Icon(qualityBadge(o.label), null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    GoldQualityText(o.label, fontSize = 13.5f)
                    Box(
                        Modifier
                            .clip(Capsule)
                            .background(accent, Capsule)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text("BEST", color = onAccent(accent), fontSize = 8.5.sp, fontWeight = FontWeight.Black, letterSpacing = 0.4.sp)
                    }
                }
                val tag = qualityTierTag(o.label)
                Text(
                    if (tag.isNotEmpty()) "$tag · ${sizeText(o)}" else sizeText(o),
                    color = TextPrimary.copy(alpha = 0.86f),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .size(26.dp)
                    .glassTile(CircleShape, 0.18f, accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(AppleIcons.Download, null, tint = lightAccent, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** Compact 2-column Liquid Glass chip for secondary video resolutions. */
@Composable
private fun QualityGridChip(o: QualityOption, accent: Color, modifier: Modifier = Modifier, onPick: () -> Unit) {
    val dark = LocalPalette.current.dark
    val tagTint = if (dark) androidx.compose.ui.graphics.lerp(accent, Color.White, 0.38f) else accent
    val shape = RoundedCornerShape(13.dp)
    GlowPress(
        onClick = onPick,
        shape = shape,
        modifier = modifier.glassTile(shape, 0.11f)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.5.dp),
            verticalArrangement = Arrangement.spacedBy(1.5.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                GoldQualityText(o.label, fontSize = 12.5f)
                val tag = qualityTierTag(o.label)
                if (tag.isNotEmpty()) {
                    Text(
                        tag.substringBefore(' '),
                        color = tagTint, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1
                    )
                }
            }
            Text(
                sizeText(o),
                color = if (o.bytes > 0) TextPrimary.copy(alpha = 0.88f) else TextSecondary,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                style = TextStyle(fontFeatureSettings = "tnum"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Apple Music Red frosted glass card for MP3 / M4A audio downloads. */
@Composable
private fun AudioQualityCard(o: QualityOption, dark: Boolean, onPick: () -> Unit) {
    val shape = RoundedCornerShape(15.dp)
    val rim = Brush.verticalGradient(
        0f to MusicRed.copy(alpha = if (dark) 0.50f else 0.42f),
        1f to MusicRed.copy(alpha = 0.14f)
    )
    GlowPress(
        onClick = onPick,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (dark) Modifier.background(Color(0xFF0A0D16).copy(alpha = 0.50f), shape) else Modifier)
            .background(MusicRed.copy(alpha = if (dark) 0.22f else 0.12f), shape)
            .border(0.8.dp, rim, shape)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .tintedGlass(RoundedCornerShape(9.dp), MusicRed, swell = 0.05f),
                contentAlignment = Alignment.Center
            ) {
                Icon(AppleIcons.MusicNote, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                GoldQualityText(o.label, fontSize = 13.5f, color = TextPrimary)
                Text(
                    o.detail.ifBlank { "Audio track" },
                    color = TextSecondary, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .clip(Capsule)
                    .background(MusicRed.copy(alpha = 0.18f), Capsule)
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    sizeText(o),
                    color = MusicRed,
                    fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                    style = TextStyle(fontFeatureSettings = "tnum"), maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun StaggerIn(index: Int, content: @Composable () -> Unit) {
    if (PerfTier.low) { content(); return }
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index.coerceAtMost(6) * 12L))
        p.animateTo(1f, spring(dampingRatio = 0.84f, stiffness = 680f))
    }
    Box(
        Modifier.graphicsLayer {
            alpha = p.value.coerceIn(0f, 1f)
            translationY = (1f - p.value) * 8.dp.toPx()
        }
    ) { content() }
}

@Composable
private fun CompactToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickableHaptic(kind = if (checked) Hx.TOGGLE_OFF else Hx.TOGGLE_ON) { onChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Box(Modifier.size(width = 38.dp, height = 23.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.requiredSize(width = 51.dp, height = 31.dp).graphicsLayer { scaleX = 0.74f; scaleY = 0.74f }) {
                GlassToggle(checked)
            }
        }
    }
}

private fun sizeText(o: QualityOption): String =
    if (o.bytes > 0) (if (o.guess) "≈ " else "") + formatBytes(o.bytes) else "Size unknown"
