package com.aurex.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

// Compiled once; building a Regex on every call is wasted work on hot paths.
private val QUAD_HD_RE = Regex("(?<![0-9A-Za-z])Quad HD(?![0-9A-Za-z])", RegexOption.IGNORE_CASE)
private val FHD_RE = Regex("(?<![0-9A-Za-z])FHD(?![0-9A-Za-z])")
private val HD_RE = Regex("(?<![0-9A-Za-z])HD(?![0-9A-Za-z])")

/** Width / height / rotation of a local media file. */
class LocalMediaInfo(val width: Int, val height: Int, val durationMs: Long)

object MediaTools {
    private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts")
    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "flac", "wav", "opus")

    fun isVideoFile(f: File) = f.extension.lowercase() in VIDEO_EXT
    fun isAudioFile(f: File) = f.extension.lowercase() in AUDIO_EXT
    fun isPlayable(f: File) = isVideoFile(f) || isAudioFile(f)

    /**
     * "1080p", "720p", "4K" ... from the pixel size. The long side decides for wide / cinema
     * crops (1920x800 is a 1080p video), so portrait and landscape give the same name.
     */
    fun qualityLabel(width: Int, height: Int): String {
        if (width <= 0 && height <= 0) return ""
        val long = max(width, height)
        val short = min(width, height).coerceAtLeast(1)
        return when {
            long >= 7600 -> "8K"
            long >= 3800 -> "4K"
            long >= 2500 -> "2K"
            long >= 1900 -> "1080p"
            long >= 1260 -> "720p"
            long >= 840 -> "480p"
            long >= 630 -> "360p"
            long >= 420 -> "240p"
            else -> "${short}p"
        }
    }

    /**
     * Streams sharper than 4K (8K, 5K ...) are not offered anywhere (player, quality sheets, downloads): no phone decoder
     * plays them, so they only ended in "This phone can't decode this video". Judged by the SHORT side, so a portrait 4K
     * video (2160x3840) still counts as 4K. Raise [MAX_SHORT_SIDE] to bring them back.
     */
    const val MAX_SHORT_SIDE = 2160
    fun tooSharp(width: Int, height: Int): Boolean =
        if (width > 0 && height > 0) min(width, height) > MAX_SHORT_SIDE else max(width, height) > 4096

    /** Label for a known stream height (from the page / playlist). */
    /** Quality heights carry an HDR marker (height + HDR_FLAG) so the picker can offer an HDR variant. */
    const val HDR_FLAG = 100_000
    fun hdrOf(h: Int): Int = h + HDR_FLAG
    fun isHdr(h: Int): Boolean = h >= HDR_FLAG
    fun plainHeight(h: Int): Int = h % HDR_FLAG

    private val QUALITY_TOKEN = Regex("""(?<![0-9A-Za-z])(\d{3,4})p\d{0,3}(?![0-9A-Za-z])""")

    /**
     * THE one naming rule for quality, used everywhere (player, quality sheets, downloads, notices):
     * 4320+ -> "8K", 2160+ -> "4K", 1440+ -> "2K", everything else "<h>p" (1080p, 720p, 480p ...).
     * "FHD" / "HD" / "Quad HD" / "1440p" are never shown as the name of a resolution.
     */
    fun heightText(h: Int): String = when {
        h <= 0 -> ""
        h >= 4320 -> "8K"
        h >= 2160 -> "4K"
        h >= 1440 -> "2K"
        else -> "${h}p"
    }

    /**
     * Rewrites every "<height>p[fps]" token of a label with [heightText]: "1440p60" -> "2K", "2160p" -> "4K",
     * "Auto 1080p" -> "Auto 1080p", "FHD" / "HD" are mapped back to 1080p / 720p. The frame rate is never shown.
     */
    fun playerText(label: String): String {
        var t = QUALITY_TOKEN.replace(label) { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            heightText(h)
        }
        t = t.replace(QUAD_HD_RE, "2K")
        t = t.replace(FHD_RE, "1080p")
        t = t.replace(HD_RE, "720p")
        return t
    }

    fun labelForHeight(h0: Int): String {
        val h = plainHeight(h0)
        val base = heightText(h)
        return if (isHdr(h0) && base.isNotEmpty()) "$base HDR" else base
    }

    fun probe(file: File): LocalMediaInfo? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (rot == 90 || rot == 270) LocalMediaInfo(h, w, dur) else LocalMediaInfo(w, h, dur)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Quality label of a finished video file, or "" when it cannot be read. */
    fun qualityOf(file: File): String {
        if (!isVideoFile(file)) return ""
        val i = probe(file) ?: return ""
        return qualityLabel(i.width, i.height)
    }

    /** Opens a finished download in the built-in player. */
    fun playLocal(ctx: Context, file: File, title: String, accent: Int? = null) {
        val intent = Intent(ctx, PlayerActivity::class.java)
            .putExtra("url", android.net.Uri.fromFile(file).toString())
            .putExtra("title", title)
            .putExtra("local", true)
        if (accent != null) intent.putExtra("accent", accent)
        if (ctx !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
    }
}

/** First-frame thumbnails of downloaded videos, cached in memory and on disk. */
object Thumbs {
    private val mem = object : LruCache<String, ImageBitmap>(48) {}

    private fun key(f: File) = f.absolutePath.hashCode().toString() + "_" + f.length()

    /** Remembers a failed YouTube lookup for a minute so a list does not re-request it on every scroll. */
    private val ytFailedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * [sourceUrl] is the page the file came from: for a YouTube link the OFFICIAL thumbnail is used
     * (also while the download is still running); otherwise - or when it cannot be fetched - the
     * picture comes from the finished file itself ([fileReady]).
     */
    suspend fun get(
        ctx: Context,
        f: File,
        sourceUrl: String? = null,
        fileReady: Boolean = true
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        YtThumb.idOf(sourceUrl)?.let { id -> fromYoutube(ctx, id)?.let { return@withContext it } }
        if (!fileReady || !f.exists() || !MediaTools.isPlayable(f)) return@withContext null
        val k = key(f)
        mem.get(k)?.let { return@withContext it }
        val dir = File(ctx.cacheDir, "thumbs").apply { mkdirs() }
        val cached = File(dir, "$k.jpg")
        if (cached.exists() && cached.length() > 0) {
            runCatching { BitmapFactory.decodeFile(cached.absolutePath) }.getOrNull()?.let {
                val ib = it.asImageBitmap()
                mem.put(k, ib)
                return@withContext ib
            }
        }
        val bmp = grab(f) ?: return@withContext null
        runCatching {
            cached.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 82, out) }
        }
        val ib = bmp.asImageBitmap()
        mem.put(k, ib)
        ib
    }

    private fun fromYoutube(ctx: Context, id: String): ImageBitmap? {
        val k = "yt_$id"
        mem.get(k)?.let { return it }
        val dir = File(ctx.cacheDir, "thumbs").apply { mkdirs() }
        val cached = File(dir, "$k.jpg")
        if (cached.exists() && cached.length() > 0) {
            runCatching { BitmapFactory.decodeFile(cached.absolutePath) }.getOrNull()?.let {
                val ib = it.asImageBitmap()
                mem.put(k, ib)
                return ib
            }
        }
        val failed = ytFailedAt[id]
        if (failed != null && System.currentTimeMillis() - failed < 60_000L) return null
        val src = YtThumb.download(id)
        if (src == null) {
            ytFailedAt[id] = System.currentTimeMillis()
            return null
        }
        val scale = min(1f, 400f / max(src.width, src.height).toFloat())
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                src, (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1), true
            )
        } else src
        runCatching {
            cached.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 88, out) }
        }
        val ib = bmp.asImageBitmap()
        mem.put(k, ib)
        return ib
    }

    private fun grab(f: File): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            var frame: Bitmap? = null
            if (MediaTools.isVideoFile(f)) {
                val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                // a moment into the video: the very first frame is often black
                val atUs = if (dur > 4_000L) min(dur * 1000L / 10L, 6_000_000L) else 0L
                frame = r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } else {
                r.embeddedPicture?.let { frame = BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
            val src = frame ?: return null
            val maxSide = 360f
            val scale = min(1f, maxSide / max(src.width, src.height).toFloat())
            if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    src, (src.width * scale).toInt().coerceAtLeast(1),
                    (src.height * scale).toInt().coerceAtLeast(1), true
                )
            } else src
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}

/** Official YouTube thumbnails (i.ytimg.com) - the same picture YouTube itself shows for a video. */
object YtThumb {
    private val ID_RX = Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})""")

    /** Biggest first; the large ones only exist for HD uploads, hqdefault exists for every video. */
    private val SIZES = listOf("maxresdefault", "hq720", "sddefault", "hqdefault")

    /** Video id of a youtube.com / youtu.be link, or null for any other link. */
    fun idOf(url: String?): String? {
        if (url.isNullOrBlank() || !YtDlpEngine.isYoutubeUrl(url)) return null
        return ID_RX.find(url)?.groupValues?.get(1)
    }

    /** Blocking network call: run off the main thread. */
    fun download(id: String): Bitmap? {
        for (size in SIZES) {
            val bmp = fetch("https://i.ytimg.com/vi/$id/$size.jpg") ?: continue
            // anything tiny is YouTube's grey "no picture" placeholder
            if (bmp.width >= 320) return trimBars(bmp)
        }
        return null
    }

    private fun fetch(url: String): Bitmap? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 6_000
                c.readTimeout = 8_000
                c.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
                )
                if (c.responseCode != 200) null
                else c.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally {
                runCatching { c.disconnect() }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** sddefault / hqdefault are 4:3 with black bars above and below the 16:9 picture: cut the bars off. */
    private fun trimBars(b: Bitmap): Bitmap {
        if (b.width.toFloat() / b.height > 1.4f) return b          // already 16:9
        val h = (b.width * 9f / 16f).toInt()
        val top = (b.height - h) / 2
        if (h <= 0 || top <= 0) return b

        fun isDark(y: Int): Boolean {
            var sum = 0L
            var n = 0
            var x = 0
            while (x < b.width) {
                val p = b.getPixel(x, y)
                sum += (android.graphics.Color.red(p) + android.graphics.Color.green(p) + android.graphics.Color.blue(p)) / 3
                n++
                x += 8
            }
            return n > 0 && sum / n < 18
        }
        return if (isDark(2) && isDark(b.height - 3)) {
            runCatching { Bitmap.createBitmap(b, 0, top, b.width, h) }.getOrDefault(b)
        } else b
    }
}

/**
 * Joins a video-only and an audio-only MP4 into one file WITHOUT re-encoding, using Android's own
 * MediaMuxer (fast, no extra engine). Throws when the streams cannot go into an MP4 - the caller
 * then falls back to the full engine.
 */
object Remux {
    fun mux(video: File, audio: File?, out: File, webm: Boolean = false, onProgress: ((Float) -> Unit)? = null) {
        val vEx = MediaExtractor()
        val aEx = if (audio != null) MediaExtractor() else null
        var muxer: MediaMuxer? = null
        var started = false
        try {
            vEx.setDataSource(video.absolutePath)
            val vTrack = firstTrack(vEx, "video/") ?: throw IllegalStateException("no video track")
            vEx.selectTrack(vTrack)
            val vFormat = vEx.getTrackFormat(vTrack)

            var aTrack = -1
            var aFormat: MediaFormat? = null
            if (audio != null && aEx != null) {
                aEx.setDataSource(audio.absolutePath)
                aTrack = firstTrack(aEx, "audio/") ?: throw IllegalStateException("no audio track")
                aEx.selectTrack(aTrack)
                aFormat = aEx.getTrackFormat(aTrack)
            }

            muxer = MediaMuxer(
                out.absolutePath,
                if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            val durUs = if (vFormat.containsKey(MediaFormat.KEY_DURATION)) vFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            var tick = 0
            val rotation = runCatching {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(video.absolutePath)
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                } finally { runCatching { r.release() } }
            }.getOrDefault(0)
            if (rotation != 0 && !webm) muxer.setOrientationHint(rotation)

            val vOut = muxer.addTrack(vFormat)
            val aOut = if (aFormat != null) muxer.addTrack(aFormat) else -1
            muxer.start()
            started = true

            val buf = ByteBuffer.allocate(3 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var vAlive = true
            var aAlive = aEx != null
            while (vAlive || aAlive) {
                // always write the stream that is further behind, so the file is interleaved
                val takeVideo = when {
                    !aAlive -> true
                    !vAlive -> false
                    else -> vEx.sampleTime <= aEx!!.sampleTime
                }
                val ex = if (takeVideo) vEx else aEx!!
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) {
                    if (takeVideo) vAlive = false else aAlive = false
                    continue
                }
                info.set(0, size, ex.sampleTime.coerceAtLeast(0L), ex.sampleFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                muxer.writeSampleData(if (takeVideo) vOut else aOut, buf, info)
                if (takeVideo && durUs > 0 && onProgress != null && (++tick and 0x3F) == 0)
                    onProgress((info.presentationTimeUs.toFloat() / durUs).coerceIn(0f, 1f))
                ex.advance()
            }
        } catch (e: Throwable) {
            runCatching { out.delete() }
            throw e
        } finally {
            if (muxer != null) {
                runCatching { if (started) muxer.stop() }
                runCatching { muxer.release() }
            }
            runCatching { vEx.release() }
            runCatching { aEx?.release() }
        }
        if (!out.exists() || out.length() < 2_048L) {
            runCatching { out.delete() }
            throw IllegalStateException("merged file is empty")
        }
    }

    private fun firstTrack(ex: MediaExtractor, prefix: String): Int? {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(prefix)) return i
        }
        return null
    }
}
