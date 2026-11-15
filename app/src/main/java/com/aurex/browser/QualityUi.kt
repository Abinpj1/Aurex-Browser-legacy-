package com.aurex.browser

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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

private fun qualityNote(label: String, best: Boolean): String {
    val base = label.trim().substringBefore(' ')
    val h = base.removeSuffix("p").toIntOrNull() ?: when { base.equals("8K", true) -> 4320; base.equals("4K", true) -> 2160; base.equals("2K", true) -> 1440; else -> 0 }
    val hdrTag = if (label.contains("HDR", true)) " · HDR" else ""
    val cls = when {
        h >= 4320 -> "8K Ultra HD$hdrTag"
        h >= 2160 -> "Ultra HD$hdrTag"
        h >= 1440 -> "Quad HD"
        h >= 1080 -> "Full HD"
        h >= 720 -> "HD"
        h >= 480 -> "Standard"
        h > 0 -> "Data saver"
        else -> ""
    }
    return if (best) "Best quality" + (if (cls.isNotEmpty()) " · $cls" else "") else cls
}

/** Keeps the last prompt alive while the sheet plays its exit animation (the state itself is already null by then). */
private class LastPrompt { var value: QualityPrompt? = null }

private val SheetShape = RoundedCornerShape(22.dp)
private val SheetOrigin = TransformOrigin(1f, 1f)

/**
 * Compact quality sheet. It sits right above the toolbar (never on top of it), on the right where the
 * floating media / download buttons were - those buttons hide while the sheet is open. Video qualities
 * scroll; the MP3 / M4A buttons are the last item of that scroll list; "Always best quality" stays
 * pinned underneath. Enter / exit are animated (scrim fade, spring rise + scale from the bottom-right corner).
 */
@Composable
fun QualitySheetHost(s: BrowserState) {
    val current = s.qualityPrompt
    val holder = remember { LastPrompt() }
    if (current != null) holder.value = current
    val prompt = holder.value ?: return
    val visible = current != null
    val style = LocalGlassStyle.current
    // Same bottom edge as the floating buttons: just above the toolbar (or near the edge when the bar is on top).
    val bottomGap = if (s.barTop || s.bare) 14.dp else toolbarFootprint(s.toolbarRows) + 12.dp
    val spec = spring<Float>(dampingRatio = 0.82f, stiffness = 420f)
    val slideSpec = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.82f, stiffness = 420f)

    Box(Modifier.fillMaxSize()) {
        // light scrim, tap to dismiss
        AnimatedVisibility(visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.24f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { s.qualityPrompt = null }
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 14.dp, bottom = bottomGap),
            enter = fadeIn(tween(180)) +
                slideInVertically(slideSpec) { it / 4 } +
                scaleIn(spec, initialScale = 0.88f, transformOrigin = SheetOrigin),
            exit = fadeOut(tween(120)) +
                slideOutVertically(tween(170, easing = FastOutSlowInEasing)) { it / 6 } +
                scaleOut(tween(170, easing = FastOutSlowInEasing), targetScale = 0.92f, transformOrigin = SheetOrigin)
        ) {
            Column(
                Modifier
                    .fillMaxWidth(0.56f)
                    .widthIn(min = 208.dp, max = 232.dp)
                    .liquidGlass(SheetShape, 1.15f)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(9.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(26.dp).clip(RoundedCornerShape(9.dp))
                            .background(Brush.verticalGradient(listOf(style.accent.copy(alpha = 0.95f), style.accent.copy(alpha = 0.6f)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(AppleIcons.Download, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Download quality", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(
                            prompt.title.ifBlank { "Choose a resolution" },
                            color = TextTertiary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                // loading -> list: cross-fade, and the sheet grows / shrinks smoothly to the new height
                AnimatedContent(
                    targetState = prompt.loading,
                    transitionSpec = {
                        (fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(110)))
                            .using(SizeTransform(clip = true) { _, _ -> spring(dampingRatio = 0.9f, stiffness = 500f) })
                    },
                    label = "qualityContent"
                ) { loading ->
                    if (loading) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(color = style.accent, strokeWidth = 2.dp, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(9.dp))
                            Text("Checking qualities…", color = TextSecondary, fontSize = 11.5.sp)
                        }
                    } else {
                        // Biggest file first; sizes that are still unknown go last (highest resolution first among them).
                        val bySize = compareByDescending<QualityOption> { it.bytes > 0 }
                            .thenByDescending { it.bytes }
                            .thenByDescending { MediaTools.plainHeight(it.height) }
                        val videos = prompt.options.filter { it.audioQuality == null }.sortedWith(bySize)
                        val audios = prompt.options.filter { it.audioQuality != null }.sortedWith(bySize)
                        // "Best" stays the sharpest picture, whatever its size ends up being
                        val bestVideo = videos.maxWithOrNull(
                            compareBy<QualityOption>({ MediaTools.plainHeight(it.height) }, { it.bytes })
                        )
                        Column(
                            Modifier.fillMaxWidth().heightIn(max = 236.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (videos.isNotEmpty()) SectionLabel("Video", "largest first")
                            videos.forEachIndexed { i, o ->
                                StaggerIn(i) { QualityRow(o, o === bestVideo, style.accent) { prompt.onPick(o) } }
                            }
                            // music options: one under the other, last items of the scroll list
                            if (audios.isNotEmpty()) {
                                Spacer(Modifier.height(2.dp))
                                SectionLabel("Audio only", "largest first")
                                audios.forEachIndexed { i, o ->
                                    StaggerIn(videos.size + i) { AudioRow(o) { prompt.onPick(o) } }
                                }
                            }
                        }
                    }
                }
                if (!prompt.loading) {
                    Spacer(Modifier.height(2.dp))
                    CompactToggleRow("Always best quality", s.alwaysBestQuality) { s.alwaysBestQuality = it; s.save() }
                }
            }
        }
    }
}

/** Each row fades and rises a few dp into place, one after the other. */
@Composable
private fun StaggerIn(index: Int, content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index.coerceAtMost(7) * 30L))
        p.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 520f))
    }
    Box(
        Modifier.graphicsLayer {
            alpha = p.value.coerceIn(0f, 1f)
            translationY = (1f - p.value) * 10.dp.toPx()
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
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        // iOS switch drawn at 80 % so the row stays slim
        Box(Modifier.size(width = 41.dp, height = 25.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.requiredSize(width = 51.dp, height = 31.dp).graphicsLayer { scaleX = 0.8f; scaleY = 0.8f }) {
                GlassToggle(checked)
            }
        }
    }
}

private fun sizeText(o: QualityOption): String =
    if (o.bytes > 0) (if (o.guess) "≈ " else "") + formatBytes(o.bytes) else "Size unknown"

@Composable
private fun QualityRow(o: QualityOption, best: Boolean, accent: Color, onPick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (best) accent.copy(alpha = 0.16f) else TextPrimary.copy(alpha = 0.06f), shape)
            .clickableHaptic { onPick() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(8.dp))
                .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.12f)))),
            contentAlignment = Alignment.Center
        ) {
            Icon(qualityBadge(o.label), null, tint = accent, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            GoldQualityText(o.label, fontSize = 13.5f)
            val note = qualityNote(o.label, best)
            if (note.isNotEmpty()) {
                Text(
                    note, color = if (best) accent else TextTertiary, fontSize = 9.sp,
                    fontWeight = if (best) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(
            sizeText(o),
            color = if (o.bytes > 0) TextPrimary else TextTertiary,
            fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
            style = TextStyle(fontFeatureSettings = "tnum"), maxLines = 1, softWrap = false
        )
    }
}

/** Small caption above a group of rows ("Video" / "Audio only") with a quiet note on the right. */
@Composable
private fun SectionLabel(title: String, note: String) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title.uppercase(), color = TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            style = TextStyle(letterSpacing = 0.8.sp), modifier = Modifier.weight(1f), maxLines = 1
        )
        Text(note, color = TextTertiary, fontSize = 9.sp, maxLines = 1)
    }
}

/** MP3 / M4A: same row shape as the video rows (one per line), in the music colour. */
@Composable
private fun AudioRow(o: QualityOption, onPick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MusicRed.copy(alpha = 0.12f), shape)
            .clickableHaptic { onPick() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(8.dp))
                .background(Brush.verticalGradient(listOf(MusicRed.copy(alpha = 0.30f), MusicRed.copy(alpha = 0.12f)))),
            contentAlignment = Alignment.Center
        ) {
            Icon(AppleIcons.MusicNote, null, tint = MusicRed, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(8.dp))
        GoldQualityText(o.label, modifier = Modifier.weight(1f), fontSize = 13.5f, color = MusicRed)
        Spacer(Modifier.width(4.dp))
        Text(
            sizeText(o),
            color = if (o.bytes > 0) TextPrimary else TextTertiary,
            fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
            style = TextStyle(fontFeatureSettings = "tnum"), maxLines = 1, softWrap = false
        )
    }
}
