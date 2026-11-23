package com.aurex.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.webkit.URLUtil
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.nio.ByteBuffer
import okhttp3.Call
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.net.URLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

// Compiled once; building a Regex on every call is wasted work on hot paths.
private val EXTINF_RE = Regex("""#EXTINF:([0-9.]+)""")
private val ILLEGAL_FILENAME_RE = Regex("[\\\\/:*?\"<>|\\r\\n\\t]+")
private val WS_RUN_RE = Regex("\\s+")
private val SAFE_ID_NAME_RE = Regex("^[A-Za-z0-9_-]+$")
private val HLS_BANDWIDTH_RE = Regex("""[:,]BANDWIDTH=(\d+)""")
private val HLS_AVG_BANDWIDTH_RE = Regex("""AVERAGE-BANDWIDTH=(\d+)""")
private val HLS_RESOLUTION_RE = Regex("""RESOLUTION=(\d+)x(\d+)""")
private val HLS_ATTR_RE = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

enum class DlStatus { QUEUED, RUNNING, PAUSED, DONE, FAILED }
enum class DlKind { FILE, HLS, YTDLP }

class DlItem(
    val id: Int,
    val url: String,
    val ua: String,
    val cookie: String?,
    val referer: String?,
    var mime: String?,
    var file: File
) {
    /** MediaStore Uri of the public Downloads/AurexBrowser copy (for permanent deletion). */
    @Volatile var publicUri: String? = null
    /** Address of the embed player that loaded the link: sent as Referer in place of the page (hotlink protection). */
    var frameReferer: String? = null
    /** Universal (yt-dlp) downloads: what to fetch and the unique marker in the output name. */
    var ytQuality: YtQuality = YtQuality.BEST
    /** Exact height picked in the quality sheet (0 = not picked). */
    var ytHeight: Int = 0
    var ytTag: String = ""
    /** "1080p", "720p", "4K" ... shown on the card instead of an engine name. */
    var qualityLabel by mutableStateOf("")
    /** Media links the browser saw on the page: tried one by one when the page itself is not understood. */
    var altUrls: List<String> = emptyList()
    /** Embed-player address per media link in [altUrls]: sent as Referer for that link (hotlink protection). */
    var altRefs: Map<String, String> = emptyMap()
    /** The native downloader already handed this link to the universal engine once. */
    var universalTried: Boolean = false
    /** Shown instead of the file name while the real name is still unknown. */
    var displayName by mutableStateOf<String?>(null)
    /** Title of the video as the WEBSITE shows it. Always wins over names the engine / the server invents. */
    var pageTitle: String? = null
    /** Short status ("Trying another source...") shown on its own line: it must never replace the title. */
    var statusNote by mutableStateOf<String?>(null)
    /** 0..1 overall progress for downloads that cannot report bytes (-1 = not used). */
    var pct by mutableFloatStateOf(-1f)
    var total by mutableLongStateOf(-1L)
    /** Best guess of the final size while the real total is still unknown (HLS / yt-dlp), -1 = none. */
    var estTotal by mutableLongStateOf(-1L)
    var done by mutableLongStateOf(0L)
    var speed by mutableLongStateOf(0L)
    var eta by mutableLongStateOf(-1L)
    var status by mutableStateOf(DlStatus.QUEUED)
    var error by mutableStateOf<String?>(null)
    var partsUsed by mutableStateOf(1)
    var kind by mutableStateOf(DlKind.FILE)
    var segmentDone by mutableIntStateOf(0)
    var segmentTotal by mutableIntStateOf(0)
    val partProgress = mutableStateListOf<Float>()
    var ranged = false
    var probed = false
    internal val hlsWorkers = java.util.concurrent.ConcurrentHashMap<Int, Float>()
    /** Range chunks already written to disk, so retries/resumes skip them. */
    internal val completedChunks = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    internal var chunkTotal = -1L
    /** Server ETag / Last-Modified validator captured on first probe to prevent corrupted resumes. */
    internal var etag: String? = null
    /** Final post-redirect CDN URL cached on first probe so parallel workers skip redundant 302 hops. */
    @Volatile internal var resolvedUrl: String? = null
    /** Live connection count of every stream the smart engine runs (keyed by the stream's first part). */
    internal val streamConn = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    internal var job: Job? = null
    val name: String get() = displayName ?: file.name
    /** Total size to show next to the received size: the real one, else an estimate, else -1. */
    val shownTotal: Long
        get() = when {
            total > 0 -> total
            estTotal > 0 -> maxOf(estTotal, done)
            segmentTotal > 0 && segmentDone > 0 && done > 0 ->
                maxOf(done, done * segmentTotal / segmentDone)
            else -> -1L
        }
    /** True when [shownTotal] is only an estimate (shown with a "~"). */
    val totalIsEstimate: Boolean get() = total <= 0 && shownTotal > 0
    val progress: Float
        get() = when {
            status == DlStatus.DONE -> 1f
            pct >= 0f -> pct.coerceIn(0f, 1f)
            segmentTotal > 0 -> (segmentDone.toFloat() / segmentTotal).coerceIn(0f, 1f)
            total > 0 -> (done.toFloat() / total).coerceIn(0f, 1f)
            else -> 0f
        }
    val kindLabel: String
        get() = when {
            kind == DlKind.HLS -> "HLS"
            name.endsWith(".mp3", true) || name.endsWith(".m4a", true) ||
                name.endsWith(".aac", true) || name.endsWith(".ogg", true) -> "AUDIO"
            name.endsWith(".mp4", true) || name.endsWith(".mkv", true) ||
                name.endsWith(".webm", true) -> "VIDEO"
            else -> "FILE"
        }
}

class DownloadManager(private val ctx: Context) {
    val items = mutableStateListOf<DlItem>()
    private val nextId = AtomicInteger(1)
    @Volatile private var metaLoaded = false
    @Volatile private var pendingPersist = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val meta = File(ctx.filesDir, "downloads.tsv")
    private val dlPool = ConnectionPool(
        when (PerfTier.tier) { PerfTier.TIER_LOW -> 8; PerfTier.TIER_BALANCED -> 20; else -> 48 },
        3,
        TimeUnit.MINUTES
    )
    private val http = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            val maxReq = when (PerfTier.tier) { PerfTier.TIER_LOW -> 16; PerfTier.TIER_BALANCED -> 40; else -> 80 }
            maxRequests = maxReq
            maxRequestsPerHost = maxReq
        })
        .connectionPool(dlPool)
        // Parallel byte ranges get their own TCP connections. HTTP/2 multiplexing
        // funnels every part through one flow-controlled stream, which is slower
        // than true parallel HTTP/1.1 connections on most CDNs.
        .protocols(listOf(Protocol.HTTP_1_1))
        .followRedirects(true)
        .followSslRedirects(true)
        .addNetworkInterceptor(CookieGuard.interceptor)
        .retryOnConnectionFailure(true)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    /** Fast-fail segment client sharing the same connection pool (dead HLS edge recycles in 15s, not 45s). */
    private val hlsSegHttp = http.newBuilder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    init {
        current = this
        // Load saved download history off the main thread so Application.onCreate() stays 0ms disk I/O!
        scope.launch(Dispatchers.IO) {
            val loaded = ArrayList<DlItem>()
            runCatching {
                if (meta.exists()) {
                    val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
                    meta.readLines().forEach { line ->
                        val p = line.split('\t')
                        if (p.size >= 2 && p[0].isNotBlank()) {
                            val f = File(dir, p[1])
                            val st = p.getOrNull(3) ?: "DONE"
                            val knd = runCatching { DlKind.valueOf(p.getOrNull(4) ?: "FILE") }.getOrDefault(DlKind.FILE)
                            val savedTotal = p.getOrNull(5)?.toLongOrNull() ?: -1L
                            val savedDone = p.getOrNull(6)?.toLongOrNull() ?: 0L
                            val savedRef = p.getOrNull(7)?.takeIf { it.isNotBlank() }
                            val hasPartial = f.exists() || File(dir, ".${f.name}.parts").exists() || File(dir, ".${f.name}.ckpt").exists()
                            if (st == "DONE" && f.exists()) {
                                loaded.add(
                                    DlItem(
                                        id = nextId.getAndIncrement(),
                                        url = p[0],
                                        ua = DEFAULT_UA,
                                        cookie = null,
                                        referer = savedRef,
                                        mime = p.getOrNull(2)?.takeIf { it.isNotBlank() },
                                        file = f
                                    ).apply {
                                        total = f.length()
                                        done = total
                                        status = DlStatus.DONE
                                        partsUsed = 1
                                        probed = true
                                        publicUri = p.getOrNull(8)?.takeIf { it.isNotBlank() }
                                        partProgress.add(1f)
                                    }
                                )
                            } else if (st == "PAUSED" && hasPartial && knd != DlKind.YTDLP) {
                                loaded.add(
                                    DlItem(
                                        id = nextId.getAndIncrement(),
                                        url = p[0],
                                        ua = DEFAULT_UA,
                                        cookie = siteCookie(p[0]),
                                        referer = savedRef,
                                        mime = p.getOrNull(2)?.takeIf { it.isNotBlank() },
                                        file = f
                                    ).apply {
                                        kind = knd
                                        total = savedTotal
                                        done = savedDone.coerceAtLeast(0L)
                                        status = DlStatus.PAUSED
                                        probed = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
            withContext(Dispatchers.Main.immediate) {
                if (loaded.isNotEmpty()) {
                    val existing = items.map { it.file.absolutePath }.toHashSet()
                    loaded.forEach { d -> if (d.file.absolutePath !in existing) items.add(d) }
                }
                metaLoaded = true
                if (pendingPersist) {
                    pendingPersist = false
                    persist()
                }
            }
            if (loaded.isNotEmpty()) {
                loaded.forEach { labelInBackground(it) }
            }
            MediaVisibility.catchUpAsync(ctx)
        }
    }

    /** Reads the real resolution of a finished video file (cheap, off the main thread). */
    private fun labelInBackground(d: DlItem) {
        if (d.status != DlStatus.DONE || d.qualityLabel.isNotBlank() || !MediaTools.isVideoFile(d.file)) return
        scope.launch {
            val q = MediaTools.qualityOf(d.file)
            if (q.isNotBlank()) d.qualityLabel = q
        }
    }

    private fun persist() {
        if (!metaLoaded) {
            pendingPersist = true
            return
        }
        runCatching {
            val keep = items.toList().filter {
                (it.status == DlStatus.DONE && it.file.exists()) ||
                    ((it.status == DlStatus.PAUSED || it.status == DlStatus.RUNNING) && it.kind != DlKind.YTDLP)
            }
            meta.writeText(
                keep.joinToString("\n") {
                    val st = if (it.status == DlStatus.DONE) "DONE" else "PAUSED"
                    listOf(
                        it.url,
                        it.file.name,
                        it.mime ?: "",
                        st,
                        it.kind.name,
                        it.total.toString(),
                        it.done.toString(),
                        it.referer ?: "",
                        it.publicUri ?: ""
                    ).joinToString("\t")
                }
            )
        }
    }

    // ── Ghost-Downloader-3 style persistent chunk checkpoint (.ckpt sidecar) ──
    private fun ckptFile(dest: File): File = File(dest.parentFile, ".${dest.name}.ckpt")

    private fun loadCheckpoint(dest: File, total: Long, etag: String?, resume: MutableSet<Long>) {
        val f = ckptFile(dest)
        if (!f.exists() || !dest.exists() || dest.length() <= 0L || dest.length() > total) return
        runCatching {
            val lines = f.readLines()
            if (lines.size < 2) return
            val savedTotal = lines[0].toLongOrNull() ?: return
            val savedTag = lines[1]
            if (savedTotal != total) return
            if (!etag.isNullOrBlank() && savedTag.isNotBlank() && savedTag != etag) return
            for (i in 2 until lines.size) {
                lines[i].toLongOrNull()?.let { resume.add(it) }
            }
        }
    }

    private fun saveCheckpoint(dest: File, total: Long, etag: String?, resume: Set<Long>) {
        if (total <= 0L) return
        runCatching {
            val snapshot = synchronized(resume) { resume.toList() }
            if (snapshot.isEmpty()) return
            val tmp = File(dest.parentFile, ".${dest.name}.ckpt.tmp")
            val sb = StringBuilder(snapshot.size * 12 + 64)
            sb.append(total).append('\n').append(etag ?: "").append('\n')
            for (off in snapshot) sb.append(off).append('\n')
            tmp.writeText(sb.toString())
            val dst = ckptFile(dest)
            if (!tmp.renameTo(dst)) {
                dst.writeText(sb.toString())
                tmp.delete()
            }
        }
    }

    /**
     * Starts a download. The number of parallel ranges (max 6) is chosen automatically
     * after probing the file size, so there is no manual part selector.
     *
     *  - blob: links cannot be fetched; when [referer] is the page URL the page is handed to the
     *    universal engine, otherwise the item fails with a clear message.
     *  - .mpd (DASH) links go to the universal engine.
     *  - [cookieOverride] / [title] come from the stream sniffer (valid cookies, readable name).
     */
    fun start(
        url: String,
        ua: String?,
        contentDisposition: String?,
        mime: String?,
        referer: String? = null,
        cookieOverride: String? = null,
        title: String? = null,
        qualityLabel: String? = null,
        frameReferer: String? = null,
        targetHeight: Int = 0
    ) {
        val agent = ua?.takeIf { it.isNotBlank() } ?: DEFAULT_UA

        if (url.startsWith("blob:", true)) {
            if (!referer.isNullOrBlank() && referer.startsWith("http", true)) {
                if (VideoDl.enabled) startUniversal(referer, YtQuality.BEST, agent, true, title)
                else addFailed(url, agent, VideoDl.OFF_HINT, title)
            } else {
                addFailed(
                    url, agent,
                    "Blob links can't be downloaded directly. Open the video page and try again",
                    title
                )
            }
            return
        }
        if (isDashUrl(url, mime)) {
            if (VideoDl.enabled) startUniversal(url, YtQuality.BEST, agent, true, title, cookieOverride)
            else addFailed(url, agent, VideoDl.OFF_HINT, title)
            return
        }

        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.apply { mkdirs() }
            ?: ctx.filesDir
        val isHls = isHlsUrl(url, mime)
        var guessed = runCatching { URLUtil.guessFileName(url, contentDisposition, mime) }
            .getOrDefault("download_${System.currentTimeMillis()}.bin")
        if (guessed.isBlank() || guessed == "downloadfile.bin") {
            guessed = "download_${System.currentTimeMillis()}.bin"
        }
        val srvNamed = contentDisposition?.contains("filename", true) == true
        if (isHls) {
            val clean = cleanTitle(title)
            val base = if (!srvNamed && clean.isNotBlank()) clean
            else guessed.substringBefore('?').substringBeforeLast('.', guessed).removeSuffix(".m3u8")
                .ifBlank { "stream_${System.currentTimeMillis()}" }
            guessed = "$base.ts"
        } else {
            guessed = betterName(guessed, title, serverNamed = srvNamed)
            guessed = fixBinName(guessed, mime, url)
        }
        val f = uniqueFile(dir, guessed)
        val cookie = cookieOverride?.takeIf { it.isNotBlank() }
            ?: siteCookie(url)
        val item = DlItem(
            id = nextId.getAndIncrement(),
            url = url,
            ua = agent,
            cookie = cookie,
            referer = referer,
            mime = if (isHls) "video/mp2t" else mime,
            file = f
        ).apply {
            if (isHls) {
                kind = DlKind.HLS
                probed = true
            }
            if (targetHeight > 0) this.ytHeight = targetHeight
            this.qualityLabel = qualityLabel?.takeIf { it.isNotBlank() }
                ?: guessLabelFromUrl(url)
            pageTitle = cleanTitle(title).ifBlank { null }
            this.frameReferer = frameReferer?.takeIf { it.startsWith("http", true) }
        }
        items.add(0, item)
        startService()
        run(item)
    }

    // ── File extension: never leave a video as ".bin" ──────────────────────────────
    private val KNOWN_EXT = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "flv", "3gp", "ts", "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav",
        "wmv", "mpg", "mpeg", "wma", "jpg", "jpeg", "png", "gif", "webp", "pdf", "zip", "rar", "7z", "apk"
    )

    private fun extForMime(mime: String?): String? {
        val m = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (m.isEmpty() || m == "application/octet-stream" || m == "binary/octet-stream") return null
        return when (m) {
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/x-matroska" -> "mkv"
            "video/quicktime" -> "mov"
            "video/mp2t" -> "ts"
            "video/3gpp" -> "3gp"
            "audio/mpeg" -> "mp3"
            "audio/mp4", "audio/x-m4a" -> "m4a"
            "audio/aac" -> "aac"
            "audio/ogg" -> "ogg"
            "application/vnd.android.package-archive" -> "apk"
            else -> android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(m)?.takeIf { it != "bin" }
        }
    }

    private val OTHER_EXT = setOf(
        "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "json", "epub", "mobi", "iso", "exe", "msi", "dmg",
        "srt", "vtt", "ass", "torrent", "tar", "gz", "tgz", "bz2", "xz", "html", "htm", "svg", "bmp", "heic", "avif",
        "apks", "xapk", "obb", "jar", "m2ts", "mts", "ogv", "mka"
    )

    /** True when [ext] is a genuine file extension (so "DDP5.1" in a title is not mistaken for one). */
    private fun isRealExt(ext: String): Boolean =
        ext in KNOWN_EXT || ext in OTHER_EXT ||
            (ext != "bin" && ext.length in 2..5 && ext.all { it.isLetterOrDigit() } && ext.any { it.isLetter() })

    /** [name] with its ".bin" (or missing / fake) extension replaced by [e]. */
    private fun withExt(name: String, e: String): String {
        val cur = name.substringAfterLast('.', "").lowercase()
        val stem = if (cur == "bin") name.dropLast(4) else name
        return stem.trimEnd('.') + "." + e
    }

    /** A file in [dir] called [name], or "name(1).ext", "name(2).ext" ... when it already exists. */
    private fun uniqueFile(dir: File?, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val ext = name.substringAfterLast('.', "")
        val base = if (ext.isEmpty()) name else name.dropLast(ext.length + 1)
        var i = 1
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base($i)" else "$base($i).$ext"); i++
        }
        return f
    }

    /** A name that ends in .bin (or has no real extension) gets the extension its type / link says. */
    private fun fixBinName(name: String, mime: String?, url: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (isRealExt(ext)) return name
        val fromUrl = extOfName(url.substringBefore('?').substringBefore('#').substringAfterLast('/'))
        val e = extForMime(mime) ?: fromUrl ?: return name
        return withExt(name, e)
    }

    /** The real type is in the first bytes of the file. */
    private fun sniffExtension(f: File): String? {
        val b = ByteArray(256)
        val n = runCatching { f.inputStream().use { it.read(b) } }.getOrDefault(-1)
        return sniffBytes(b, n)
    }

    /** Same check on bytes that are already in memory (so the name is right before the file is created). */
    private fun sniffBytes(b: ByteArray, n: Int): String? {
        if (n < 12) return null
        fun at(o: Int, s: String) = o + s.length <= n && String(b, o, s.length, Charsets.ISO_8859_1) == s
        fun u(i: Int) = if (i < n) b[i].toInt() and 0xFF else -1
        return when {
            at(4, "ftyp") -> {
                val brand = String(b, 8, 4, Charsets.ISO_8859_1)
                if (brand.startsWith("M4A") || brand == "M4B ") "m4a" else if (brand.startsWith("qt")) "mov" else "mp4"
            }
            // MP4 / MOV whose first box is not ftyp
            at(4, "moov") || at(4, "mdat") || at(4, "free") || at(4, "wide") || at(4, "skip") || at(4, "styp") -> "mp4"
            u(0) == 0x1A && u(1) == 0x45 && u(2) == 0xDF && u(3) == 0xA3 ->
                if (String(b, 0, n, Charsets.ISO_8859_1).contains("webm")) "webm" else "mkv"
            at(0, "OggS") -> "ogg"
            at(0, "fLaC") -> "flac"
            at(0, "ID3") -> "mp3"
            at(0, "RIFF") && at(8, "WAVE") -> "wav"
            at(0, "RIFF") && at(8, "AVI ") -> "avi"
            at(0, "RIFF") && at(8, "WEBP") -> "webp"
            at(0, "FLV") -> "flv"
            at(0, "%PDF") -> "pdf"
            at(0, "GIF8") -> "gif"
            u(0) == 0x89 && at(1, "PNG") -> "png"
            u(0) == 0xFF && u(1) == 0xD8 -> "jpg"
            u(0) == 0x30 && u(1) == 0x26 && u(2) == 0xB2 && u(3) == 0x75 -> "wmv"
            u(0) == 0x00 && u(1) == 0x00 && u(2) == 0x01 && u(3) == 0xBA -> "mpg"
            // MPEG transport stream: sync byte 0x47 every 188 bytes (checked when enough bytes are known)
            u(0) == 0x47 && n >= 189 && u(188) == 0x47 -> "ts"
            at(0, "PK") -> "zip"
            u(0) == 0xFF && (u(1) and 0xF6) == 0xF0 -> "aac"
            u(0) == 0xFF && (u(1) and 0xE0) == 0xE0 -> "mp3"
            else -> null
        }
    }

    /** File name from a Content-Disposition header (filename*=UTF-8''x.mkv or filename="x.mkv"), null when absent. */
    private fun nameFromDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val star = Regex("""filename\*\s*=\s*[^']*'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
        val plain = Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
        val raw = (star ?: plain)?.trim() ?: return null
        return runCatching { java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
            .substringAfterLast('/').substringAfterLast('\\').trim().takeIf { it.isNotBlank() }
    }

    private fun extOfName(name: String?): String? =
        name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it in KNOWN_EXT }

    /** Called when a download is complete: a ".bin" / extension-less file is renamed to what it really is. */
    private fun fixExtension(d: DlItem) {
        val f = d.file
        val ext = f.name.substringAfterLast('.', "").lowercase()
        if (isRealExt(ext)) return
        if (!f.exists()) return
        val real = sniffExtension(f) ?: extForMime(d.mime) ?: return
        val out = uniqueFile(f.parentFile, withExt(f.name, real))
        if (f.renameTo(out)) d.file = out
    }

    /** "…_1080p.mp4", "/720/…" and similar hints in a media link. */
    private fun guessLabelFromUrl(url: String): String {
        val m = Regex("""(?<![0-9])(2160|1440|1080|720|480|360|240)p?(?![0-9a-z])""", RegexOption.IGNORE_CASE)
            .find(url.substringBefore('?').substringAfterLast("//"))
            ?: return ""
        return MediaTools.labelForHeight(m.groupValues[1].toInt())
    }

    class HlsVariant(val url: String, val width: Int, val height: Int, val bandwidth: Long, val avgBandwidth: Long = 0L) {
        /** Predicted file size in bytes (0 = unknown). */
        var estBytes: Long = 0L
    }

    /** Total seconds of a media playlist (sum of #EXTINF), 0 when unknown. Blocking. */
    private fun hlsDuration(url: String, ua: String?, referer: String?, cookie: String?): Double = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua?.takeIf { it.isNotBlank() } ?: DEFAULT_UA)
            .apply {
                val c = cookie ?: siteCookie(url)
                if (!c.isNullOrBlank()) {
                    header("Cookie", c)
                    tag(CookieHome::class.java, CookieHome.of(url))
                }
                if (!referer.isNullOrBlank()) {
                    header("Referer", referer)
                    runCatching {
                        val u = URI(referer)
                        if (!u.scheme.isNullOrBlank() && !u.host.isNullOrBlank()) {
                            header("Origin", "${u.scheme}://${u.host}")
                        }
                    }
                }
            }.build()
        val text = http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string().orEmpty() else "" }
        EXTINF_RE.findAll(text).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
    } catch (_: Exception) { 0.0 }

    /**
     * The quality ladder of an HLS master playlist. Empty when [url] is a plain media playlist or
     * cannot be read. Blocking: call from a background thread.
     */
    fun listHlsVariants(url: String, ua: String?, referer: String?, cookie: String?): List<HlsVariant> {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", ua?.takeIf { it.isNotBlank() } ?: DEFAULT_UA)
                .header("Accept", "*/*")
                .apply {
                    val c = cookie ?: siteCookie(url)
                    if (!c.isNullOrBlank()) {
                        header("Cookie", c)
                        tag(CookieHome::class.java, CookieHome.of(url))
                    }
                    if (!referer.isNullOrBlank()) {
                    header("Referer", referer)
                    runCatching {
                        val u = URI(referer)
                        if (!u.scheme.isNullOrBlank() && !u.host.isNullOrBlank()) {
                            header("Origin", "${u.scheme}://${u.host}")
                        }
                    }
                }
                }.build()
            val text = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return emptyList()
                r.body?.string().orEmpty()
            }
            if (!text.contains("#EXT-X-STREAM-INF")) return emptyList()
            val lines = text.lines()
            val out = ArrayList<HlsVariant>()
            lines.forEachIndexed { i, line ->
                if (line.trim().startsWith("#EXT-X-STREAM-INF")) {
                    val bw = HLS_BANDWIDTH_RE.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val avg = HLS_AVG_BANDWIDTH_RE.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val res = HLS_RESOLUTION_RE.find(line)
                    val w = res?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val h = res?.groupValues?.get(2)?.toIntOrNull() ?: 0
                    val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.trim().startsWith("#") }
                    // 8K and above are never offered (no phone decodes them)
                    if (next != null && h > 0 && !MediaTools.tooSharp(w, h) && DeviceCaps.supportsHeight(h)) out.add(HlsVariant(resolve(url, next.trim()), w, h, bw, avg))
                }
            }
            // one entry per quality: the richest stream of each height
            val list = out.groupBy { it.height }.mapNotNull { (_, l) -> l.maxByOrNull { it.bandwidth } }
                .sortedByDescending { it.height }
            // size prediction: the duration comes from one media playlist (all qualities share it)
            val dur = list.firstOrNull()?.let { hlsDuration(it.url, ua, referer, cookie) } ?: 0.0
            if (dur > 0) list.forEach { v ->
                val bps = if (v.avgBandwidth > 0) v.avgBandwidth else (v.bandwidth * 0.85).toLong()
                v.estBytes = (bps / 8.0 * dur).toLong()
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Universal downloader: hands a PAGE url (YouTube, Instagram, X, TikTok, Facebook, Vimeo,
     * Reddit ... or any page with a video) to the internal engine, which finds the real
     * streams, merges video + audio and names the file after the title.
     */
    fun startUniversal(
        pageUrl: String,
        quality: YtQuality,
        ua: String? = null,
        useCookies: Boolean = true,
        title: String? = null,
        cookieOverride: String? = null,
        altUrls: List<String> = emptyList(),
        height: Int = 0,
        altRefs: Map<String, String> = emptyMap()
    ): Boolean {
        // "Video download" is off: the engine must not be started (no memory, no background work).
        if (!VideoDl.enabled) return false
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.apply { mkdirs() }
            ?: ctx.filesDir
        val host = runCatching { URI(pageUrl).host }.getOrNull()
            ?.removePrefix("www.")?.removePrefix("m.") ?: "media"
        val tag = "~ax" + System.currentTimeMillis().toString(36)
        val cookie = when {
            !useCookies -> null
            !cookieOverride.isNullOrBlank() -> cookieOverride
            else -> siteCookie(pageUrl)
        }
        val shown = cleanTitle(title).ifBlank { host }
        val item = DlItem(
            id = nextId.getAndIncrement(),
            url = pageUrl,
            ua = ua?.takeIf { it.isNotBlank() } ?: DEFAULT_UA,
            cookie = cookie,
            referer = null,
            mime = null,
            file = File(dir, "$host.${quality.ext}")
        ).apply {
            kind = DlKind.YTDLP
            probed = true
            ytQuality = quality
            ytHeight = height
            ytTag = tag
            this.altUrls = altUrls
            this.altRefs = altRefs
            qualityLabel = when {
                quality == YtQuality.MP3 -> "MP3"
                quality == YtQuality.M4A -> "M4A"
                height > 0 -> MediaTools.labelForHeight(height)
                quality == YtQuality.P1080 -> "1080p"
                quality == YtQuality.P720 -> "720p"
                quality == YtQuality.P480 -> "480p"
                else -> ""
            }
            displayName = shown
            pageTitle = cleanTitle(title).ifBlank { null }
            pct = 0f
        }
        items.add(0, item)
        startService()
        run(item)
        return true
    }

    /** Adds a failed entry (e.g. a bare blob: link) so the user sees why it cannot start. */
    private fun addFailed(url: String, ua: String, message: String, title: String?) {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.apply { mkdirs() }
            ?: ctx.filesDir
        val item = DlItem(
            id = nextId.getAndIncrement(),
            url = url,
            ua = ua,
            cookie = null,
            referer = null,
            mime = null,
            file = File(dir, "stream_${System.currentTimeMillis()}.mp4")
        ).apply {
            probed = true
            status = DlStatus.FAILED
            error = message
            displayName = cleanTitle(title).ifBlank { "Blob video" }
        }
        items.add(0, item)
    }

    private fun isDashUrl(url: String, mime: String?): Boolean {
        val p = url.substringBefore('#').substringBefore('?').lowercase()
        return p.endsWith(".mpd") || mime?.contains("dash+xml", true) == true
    }

    /** Page title made safe for a file name. */
    private fun cleanTitle(t: String?): String =
        (t ?: "").replace(ILLEGAL_FILENAME_RE, " ")
            .replace(WS_RUN_RE, " ").trim().trim('.').take(80)

    private val TRAILING_EXT = KNOWN_EXT + setOf("av1", "vp9", "vp8", "h264", "h265", "hevc", "avc", "aac")

    /** "Movie.av1.mp4" -> "Movie": engines / servers sometimes put the file extension inside the title. */
    private fun stripMediaExt(name: String): String {
        var n = name.trim()
        while (true) {
            val i = n.lastIndexOf('.')
            if (i <= 0 || n.substring(i + 1).lowercase() !in TRAILING_EXT) break
            n = n.substring(0, i).trimEnd()
        }
        return n.ifBlank { name.trim() }
    }

    /**
     * The website's video title becomes the file name. A name the server sent in Content-Disposition
     * is kept ([serverNamed]); every other name for a video / audio file (link tail like "_TPL_.av1.mp4",
     * hashes, "master", "videoplayback") is replaced by the title.
     */
    private fun betterName(guessed: String, title: String?, serverNamed: Boolean = false): String {
        val clean = cleanTitle(title)
        if (clean.isBlank()) return guessed
        val gExt = guessed.substringAfterLast('.', "").lowercase()
        val notMedia = setOf("jpg", "jpeg", "png", "gif", "webp", "pdf", "zip", "rar", "7z", "apk")
        if (!serverNamed && ((gExt in KNOWN_EXT && gExt !in notMedia) || gExt == "m3u8")) {
            return "$clean.$gExt"
        }
        val base = guessed.substringBeforeLast('.', guessed).lowercase()
        val ext = guessed.substringAfterLast('.', "bin")
        val generic = base in GENERIC_NAMES || base.startsWith("download_") || base.startsWith("stream_") ||
            // random-looking names (hashes, ids): the video title is a better name
            (base.length >= 16 && !base.contains(' ') && base.any { it.isDigit() } &&
                SAFE_ID_NAME_RE.matches(base))
        return if (generic) "$clean.$ext" else guessed
    }

    fun pause(d: DlItem) {
        if (d.status == DlStatus.RUNNING) {
            if (d.kind == DlKind.YTDLP) YtDlpEngine.cancel(d.id)
            d.job?.cancel()
            d.status = DlStatus.PAUSED
            d.speed = 0
            d.eta = -1
            if (d.kind == DlKind.FILE && d.chunkTotal > 0L) {
                saveCheckpoint(d.file, d.chunkTotal, d.etag, d.completedChunks)
            }
            persist()
            updateNotification()
        }
    }

    fun resume(d: DlItem) {
        if (d.status == DlStatus.DONE) return
        if (d.job?.isActive == true) return
        run(d)
    }

    fun cancel(d: DlItem) {
        if (d.kind == DlKind.YTDLP && d.status == DlStatus.RUNNING) YtDlpEngine.cancel(d.id)
        val job = d.job
        d.job = null
        job?.cancel()
        MediaVisibility.onDeleted(ctx, d.file, d.publicUri)
        wipeFiles(d)
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(DownloadService.doneNotifId(d.id)) }
        items.remove(d)
        persist()
        updateNotification()
        stopServiceIfIdle()
        if (job != null) {
            scope.launch {
                runCatching { job.join() }
                wipeFiles(d)
                MediaVisibility.onDeleted(ctx, d.file, d.publicUri)
            }
        }
    }

    /**
     * Delete button: stops the download if it is still running, takes the item off the list AND
     * permanently deletes the file from private app storage AND public local storage.
     */
    fun deleteWithFile(d: DlItem) {
        deleteMany(listOf(d))
    }

    /** Batched permanent deletion (single haptic, single metadata write, zero UI jank). */
    fun deleteMany(targets: List<DlItem>) {
        if (targets.isEmpty()) return
        Haptics.fire(null, Hx.WARN)
        val nm = runCatching { ctx.getSystemService(NotificationManager::class.java) }.getOrNull()
        val runningJobs = ArrayList<Pair<DlItem, Job>>()
        for (d in targets) {
            if (d.kind == DlKind.YTDLP && d.status == DlStatus.RUNNING) YtDlpEngine.cancel(d.id)
            val job = d.job
            d.job = null
            job?.cancel()
            if (job != null) runningJobs.add(d to job)
            // Mark deleted BEFORE removing private file so any in-flight MediaStore copy aborts cleanly!
            MediaVisibility.onDeleted(ctx, d.file, d.publicUri)
            wipeFiles(d)
            runCatching { nm?.cancel(DownloadService.doneNotifId(d.id)) }
        }
        items.removeAll(targets.toSet())
        persist()
        updateNotification()
        stopServiceIfIdle()
        if (runningJobs.isNotEmpty()) {
            scope.launch {
                for ((d, job) in runningJobs) {
                    runCatching { job.join() }
                    wipeFiles(d)
                    MediaVisibility.onDeleted(ctx, d.file, d.publicUri)
                }
            }
        }
    }

    private fun wipeFiles(d: DlItem) {
        if (d.ytTag.isNotBlank()) YtDlpEngine.deleteLeftovers(d.file.parentFile, d.ytTag)
        runCatching { if (d.file.exists()) d.file.delete() }
        runCatching { File(d.file.parentFile, ".${d.file.name}.parts").deleteRecursively() }
        runCatching { ckptFile(d.file).delete() }
    }

    fun remove(d: DlItem) {
        if (d.status != DlStatus.RUNNING) deleteWithFile(d)
    }

    /** Permanently deletes all completed and failed downloads from the app list AND local device storage. */
    fun clearFinished() {
        val finished = items.filter { it.status == DlStatus.DONE || it.status == DlStatus.FAILED }
        deleteMany(finished)
    }

    fun pauseAll() {
        items.forEach { if (it.status == DlStatus.RUNNING) pause(it) }
    }

    /** Pauses the running video downloads (engine / HLS). Plain file downloads keep going. */
    fun pauseVideo(): Int {
        var n = 0
        items.toList().forEach {
            if (it.status == DlStatus.RUNNING && (it.kind == DlKind.YTDLP || it.kind == DlKind.HLS)) {
                pause(it); n++
            }
        }
        return n
    }

    /**
     * Cookies for one request. The item's own cookie is only sent to the host it was captured for;
     * any other host (CDN, segment server, key server) gets what CookieManager holds for that URL.
     */
    private fun cookieFor(d: DlItem, url: String): String? {
        val same = runCatching {
            URI(url).host.equals(URI(d.url).host, ignoreCase = true)
        }.getOrDefault(false)
        if (same && !d.cookie.isNullOrBlank()) return d.cookie
        return siteCookie(url)
            ?: if (same) d.cookie else null
    }

    private fun buildRequest(d: DlItem, url: String, range: String?): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", d.ua)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .header("Connection", "keep-alive")
            .apply {
                cookieFor(d, url)?.let {
                    header("Cookie", it)
                    tag(CookieHome::class.java, CookieHome.of(url))
                }
                (d.frameReferer ?: d.referer)?.let { ref ->
                    header("Referer", ref)
                    runCatching {
                        val u = URI(ref)
                        if (!u.scheme.isNullOrBlank() && !u.host.isNullOrBlank()) {
                            header("Origin", "${u.scheme}://${u.host}")
                        }
                    }
                }
                range?.let { header("Range", it) }
            }
            .build()

    private fun request(d: DlItem, range: String? = null): Request = buildRequest(d, d.resolvedUrl ?: d.url, range)

    private fun requestFor(d: DlItem, url: String, range: String? = null): Request =
        buildRequest(d, url, range)

    private fun run(d: DlItem) {
        d.status = DlStatus.RUNNING
        d.error = null
        d.speed = 0
        d.eta = -1
        startService()
        d.job = scope.launch {
            val ticker = launch { tickerLoop(d) }
            try {
                // A video item that was paused (or left over) while "Video download" was off must not
                // wake the engine up again: it says why and can be retried once the switch is on.
                if (!VideoDl.enabled && (d.kind == DlKind.YTDLP || d.kind == DlKind.HLS)) {
                    throw IllegalStateException(VideoDl.OFF_HINT)
                }
                persist()
                var netRetry = 0
                while (true) {
                    try {
                        if (!d.probed) probe(d)
                        if (d.kind == DlKind.YTDLP) {
                            ytdlpDownload(d)
                        } else if (d.kind == DlKind.HLS) {
                            hlsDownload(d)
                        } else if (d.ranged && d.total >= MIN_CHUNKED_BYTES) {
                            try {
                                chunkedDownload(d)
                            } catch (e: RangeUnsupportedException) {
                                singleStream(d)
                            }
                        } else {
                            singleStream(d)
                        }
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val transient = d.kind != DlKind.YTDLP && !isPermanentError(e.message) && (
                            e is java.io.IOException ||
                            e.message?.contains("timeout", true) == true ||
                            e.message?.contains("reset", true) == true ||
                            e.message?.contains("ended early", true) == true
                        )
                        if (transient && netRetry < 2) {
                            netRetry++
                            d.resolvedUrl = null
                            d.statusNote = "Reconnecting ($netRetry/2)…"
                            delay(1_100L * netRetry)
                            d.statusNote = null
                            continue
                        }
                        throw e
                    }
                }
                runCatching { ckptFile(d.file).delete() }
                d.speed = 0
                d.eta = -1
                d.done = if (d.total > 0) d.total else d.done
                if (d.segmentTotal > 0) d.segmentDone = d.segmentTotal
                d.partProgress.indices.forEach { d.partProgress[it] = 1f }
                fixExtension(d)
                d.status = DlStatus.DONE
                AppLog.info("Download", "done id=${d.id} kind=${d.kind} ${AppLog.url(d.url)} (${d.total / 1024} KB)")
                Haptics.fire(null, Hx.SUCCESS)
                persist()
                MediaVisibility.onFinished(ctx, d.file) { uri ->
                    d.publicUri = uri
                    scope.launch(Dispatchers.Main.immediate) { persist() }
                }
                runCatching { DownloadService.notifyFinished(ctx, d) }
                if (d.qualityLabel.isBlank() || d.kind == DlKind.YTDLP) {
                    MediaTools.qualityOf(d.file).takeIf { it.isNotBlank() }?.let { d.qualityLabel = it }
                }
            } catch (e: CancellationException) {
                d.speed = 0
                d.eta = -1
                d.status = DlStatus.PAUSED
            } catch (e: Exception) {
                d.speed = 0
                d.eta = -1
                d.status = DlStatus.FAILED
                Haptics.fire(null, Hx.ERROR)
                d.error = e.message ?: "Download failed"
                AppLog.warn("Download", "FAILED id=${d.id} kind=${d.kind} ${AppLog.url(d.url)}: ${d.error}")
                val willAutoFallback = VideoDl.enabled && d.kind != DlKind.YTDLP && !d.universalTried &&
                    isPermanentError(d.error) && d.referer?.startsWith("http", true) == true && YtDlpEngine.canTry(d.referer)
                if (!willAutoFallback) runCatching { DownloadService.notifyFailed(ctx, d) }
                // A refused link or an error page will never turn into the file,
                // so the stub is removed instead of pretending to be a download.
                if (isPermanentError(d.error)) {
                    runCatching { d.file.delete() }
                }
                // The direct link was refused / was not a file: the universal engine knows how to
                // get media out of the page it came from (cookies, headers, manifests, embeds).
                val page = d.referer
                if (VideoDl.enabled && d.kind != DlKind.YTDLP && !d.universalTried && isPermanentError(d.error) &&
                    page != null && page.startsWith("http", true) && YtDlpEngine.canTry(page) &&
                    // a YouTube home page / feed is not a video: the engine could only say "Unsupported URL"
                    !(YtDlpEngine.isYoutubeUrl(page) && !YtDlpEngine.isItemUrl(page))
                ) {
                    d.universalTried = true
                    items.remove(d)
                    startUniversal(
                        page, YtQuality.BEST, d.ua, true, d.pageTitle ?: d.displayName, d.cookie,
                        altUrls = listOf(d.url),
                        altRefs = d.frameReferer?.takeIf { it.isNotBlank() }?.let { mapOf(d.url to it) } ?: emptyMap()
                    )
                }
                // Keep temporary HLS segments / completed range chunks on disk
                // so a retry can continue instead of starting a huge file again.
            } finally {
                ticker.cancel()
                updateNotification()
                stopServiceIfIdle()
            }
        }
    }

    /** yt-dlp download: one process, progress parsed from its output, file renamed when done. */
    private suspend fun ytdlpDownload(d: DlItem) = withContext(Dispatchers.IO) {
        val dir = d.file.parentFile ?: ctx.filesDir

        // YouTube changes faster than anything else: the start-up check for a newer yt-dlp gets a
        // bounded chance to finish first, so the very first download does not run on a stale engine.
        AppLog.info("Download", "video start id=${d.id} ${AppLog.url(d.url)} quality=${d.ytQuality} height=${d.ytHeight}")
        if (YtDlpEngine.isYoutubeUrl(d.url)) YtDlpEngine.awaitWarm(2_500L)

        // GHOST-DOWNLOADER-3 FAST PATH: works for ALL supported websites (YouTube, social, streaming,
        // direct video, HLS, and M4A audio!) — fetches with parallel dynamic-split ranges & native muxing.
        if (d.ytQuality != YtQuality.MP3 && (YtDlpEngine.isQualitySite(d.url) || YtDlpEngine.peekCached(d.url) != null)) {
            if (fastDownload(d, dir)) return@withContext
        }

        YtDlpEngine.ensureReady(ctx)

        val streams = if (d.ytQuality.audio) 1 else 2
        var lastParsedAt = 0L   // when yt-dlp last gave a usable progress line (the size watcher below fills the gaps)
        var stream = 0
        var carry = 0L
        var curTotal = 0L
        val progressRx = Regex(
            """(\d+(?:\.\d+)?)%\s+of\s+~?\s*(\d+(?:\.\d+)?)\s*(B|KiB|MiB|GiB|TiB)"""
        )
        val destRx = Regex("""(?:Destination:|Merging formats into)\s*"?([^"\n]+?)"?\s*$""")

        fun onLine(p: Float, eta: Long, line: String) {
            destRx.find(line.trim())?.let { m ->
                val shown = File(m.groupValues[1]).name.replace(d.ytTag, "")
                // the website's own title stays on the card; yt-dlp's file name is only a fallback
                if (shown.isNotBlank() && d.pageTitle.isNullOrBlank()) d.displayName = stripMediaExt(shown)
                if (line.contains("Destination:") && !line.contains("[ExtractAudio]")) {
                    stream++
                    carry += curTotal
                    curTotal = 0L
                }
            }
            var pct = p
            progressRx.find(line)?.let { m ->
                val frac = m.groupValues[1].toFloatOrNull() ?: p
                val size = m.groupValues[2].toDoubleOrNull() ?: 0.0
                val mult = when (m.groupValues[3]) {
                    "KiB" -> 1024.0
                    "MiB" -> 1024.0 * 1024
                    "GiB" -> 1024.0 * 1024 * 1024
                    "TiB" -> 1024.0 * 1024 * 1024 * 1024
                    else -> 1.0
                }
                curTotal = (size * mult).toLong()
                d.done = carry + (curTotal * (frac / 100.0)).toLong()
                // video + audio are two streams: until the last one starts, add ~12% for the audio
                d.estTotal = carry + curTotal +
                    (if (streams > 1 && stream <= 1) curTotal / 8 else 0L)
                pct = frac
            }
            lastParsedAt = System.currentTimeMillis()
            if (pct < 0f) return
            val overall = ((maxOf(stream, 1) - 1) + pct / 100f) / streams
            val clamped = overall.coerceIn(0f, 0.99f)
            if (clamped > d.pct) d.pct = clamped
            if (eta >= 0) d.eta = eta
        }

        // Sources, best first: the page itself (its extractor or the generic one), then - only for
        // sites WITHOUT their own extractor - the media links the browser sniffed on it, fetched
        // with the page as Referer. Signed CDN links (YouTube googlevideo "videoplayback" ...) are
        // never used as a backup: yt-dlp can only save a few junk bytes from them.
        data class Attempt(val url: String, val profile: Int, val useCookies: Boolean, val backup: Boolean)
        val attempts = ArrayList<Attempt>()
        if (YtDlpEngine.isYoutubeUrl(d.url)) {
            // YouTube: public videos work best WITHOUT the browser's cookies. With cookies yt-dlp
            // switches to its "signed-in" client list, and a cookie jar copied out of a WebView is often
            // rotated / from another session, which makes the stream links answer HTTP 403. So the plain
            // defaults go first, then two other cookie-less client sets, and the cookies are the LAST
            // resort (age-gated / signed-in-only videos).
            attempts += Attempt(d.url, 0, false, false)
            attempts += Attempt(d.url, 1, false, false)
            attempts += Attempt(d.url, 2, false, false)
            if (!d.cookie.isNullOrBlank()) attempts += Attempt(d.url, 0, true, false)
        } else {
            attempts += Attempt(d.url, 0, true, false)
        }
        // (YtDlpEngine.writeCookies never writes a cookie file for Instagram.)
        d.altUrls.filter { it != d.url && YtDlpEngine.isBackupSource(d.url, it) }
            .forEach { attempts += Attempt(it, 0, true, true) }

        // yt-dlp prints no usable progress while it merges, or for some big (4K / 8K) streams. Then the size of the
        // files it is writing is the progress: bytes (-> speed) and, with the predicted size, the percentage.
        val predicted: Long = YtDlpEngine.peekCached(d.url)?.let { m ->
            val h = MediaTools.plainHeight(d.ytHeight)
            if (h > 0) (m.sizeByHeight[h] ?: 0L) else (m.sizeByHeight.maxByOrNull { it.key }?.value ?: 0L)
        } ?: 0L
        val sizeWatcher = launch {
            while (isActive) {
                delay(700)
                if (System.currentTimeMillis() - lastParsedAt < 3_000L) continue   // yt-dlp's own numbers are fresh
                val bytes = runCatching {
                    dir.listFiles { f -> f.name.contains(d.ytTag) }?.sumOf { it.length() } ?: 0L
                }.getOrDefault(0L)
                if (bytes > d.done) d.done = bytes
                if (predicted > 0) {
                    if (d.estTotal <= 0) d.estTotal = predicted
                    val f = (d.done.toFloat() / maxOf(predicted, d.done)).coerceIn(0f, 0.98f)
                    if (f > d.pct) d.pct = f
                }
            }
        }

        var retried = false
        d.statusNote = null   // status texts ("Trying another source...") have their own line; the title stays
        var primaryError: String? = null   // why the PAGE itself failed: this is what the user sees
        var lastError: String? = null
        var out: File? = null
        var cookieFile: File? = null
        try {
            var idx = 0
            while (idx < attempts.size) {
                currentCoroutineContext().ensureActive()
                val a = attempts[idx]
                cookieFile?.delete()
                cookieFile = if (a.useCookies) YtDlpEngine.writeCookies(ctx, d.id, d.url, d.cookie) else null
                stream = 0; carry = 0L; curTotal = 0L
                d.estTotal = -1L
                d.pct = 0f
                try {
                    val req = YtDlpEngine.buildRequest(
                        a.url, dir, d.ytTag, d.ytQuality, cookieFile, d.ua,
                        if (a.backup) (d.altRefs[a.url] ?: d.url) else null, a.profile, d.ytHeight
                    )
                    YtDlpEngine.execute(req, YtDlpEngine.processId(d.id), ::onLine)
                    // Exit code 0 is not enough: the file must really exist and be more than a stub.
                    val found = YtDlpEngine.findOutput(dir, d.ytTag)
                    if (YtDlpEngine.isUsableOutput(found)) {
                        out = found
                        break
                    }
                    throw IllegalStateException(
                        "No video was downloaded (the server returned only ${found?.length() ?: 0} bytes)"
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val msg = e.message
                    lastError = msg
                    AppLog.warn("Download", "attempt failed (profile ${a.profile}, ${if (a.backup) "backup" else "page"}, cookies=${a.useCookies}) ${AppLog.url(a.url)}: ${msg?.take(600)}")
                    if (!a.backup && primaryError == null) primaryError = msg
                    YtDlpEngine.deleteLeftovers(dir, d.ytTag)
                    if (!retried && YtDlpEngine.looksOutdated(msg)) {
                        // Sites change constantly: fetch the newest yt-dlp and repeat this attempt on it.
                        // (Refused while other downloads use the engine: it then updates by itself the
                        // moment they end, and the remaining attempts below still run on the old one.)
                        d.statusNote = "Updating downloader…"
                        if (YtDlpEngine.updateOnce(ctx)) {
                            retried = true
                            continue
                        }
                        d.statusNote = null
                    }
                    // Nothing can fix a removed / private video: skip the rest of the page attempts.
                    if (!a.backup && YtDlpEngine.isTerminalError(msg)) {
                        while (idx + 1 < attempts.size && !attempts[idx + 1].backup) idx++
                    }
                    idx++
                    if (idx < attempts.size) {
                        d.statusNote = if (attempts[idx].backup) "Trying another source…" else "Trying another method…"
                    }
                }
            }
            if (out == null) throw IllegalStateException(YtDlpEngine.friendlyError(primaryError ?: lastError))
        } finally {
            sizeWatcher.cancel()
            cookieFile?.delete()
            d.statusNote = null
        }

        currentCoroutineContext().ensureActive()
        val produced: File = out ?: throw IllegalStateException("The download finished but no file was produced")
        // Strip the marker, keep the title; never overwrite an existing file.
        val rawExt = produced.extension
        val ext = YtDlpEngine.normalizeExt(rawExt)
        val engineBase = stripMediaExt(
            produced.name.removeSuffix(".$rawExt").replace(d.ytTag, "").trim()
        )
        val base = (d.pageTitle?.takeIf { it.isNotBlank() } ?: engineBase).ifBlank { "video" }
        var target = File(dir, "$base.$ext")
        var n = 1
        while (target.exists()) {
            target = File(dir, "$base($n).$ext"); n++
        }
        val moved = produced.renameTo(target) || runCatching {
            produced.copyTo(target, overwrite = false); produced.delete()
        }.getOrDefault(false)
        d.file = if (moved && target.exists()) target else produced
        d.displayName = null
        d.mime = YtDlpEngine.mimeFor(ext)
        d.total = d.file.length()
        d.done = d.total
        d.pct = 1f
        // Only sweep leftovers when the finished file no longer carries the marker.
        if (moved) YtDlpEngine.deleteLeftovers(dir, d.ytTag)
    }

    // ── Fast path: parallel ranges on the real stream links ──────────────────

    private fun rangeRequest(url: String, headers: Map<String, String>, ua: String, range: String): Request =
        Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Range", range)
            .build()

    /** Exact size of a stream through a 1-byte range request (-1 when the server won't say). */
    private fun probeSize(url: String, headers: Map<String, String>, ua: String): Long = try {
        http.newCall(rangeRequest(url, headers, ua, "bytes=0-0")).execute().use { r ->
            if (r.code == 206) r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
            else -1L
        }
    } catch (_: Exception) { -1L }

    // ── Smart range engine ──────────────────────────────────────────────────────────
    private class RPlan(val index: Int, val start: Long, val end: Long)

    /**
     * Splits [total] bytes into parts and downloads them over a shared queue with a self-tuning number of
     * connections:
     *   - the PART count follows the file size (about 2 MB per part, 4..48 parts); every part is shown on the card;
     *   - the CONNECTION count starts at 2 and climbs while every extra connection still gives >= 10 % more speed,
     *     steps back when it does not, backs off on 429 / 503 (server throttling) and re-probes every few seconds.
     * Parts already in [resume] are skipped. Throws on failure (callers fall back to the full engine).
     */
    private suspend fun smartFetch(
        d: DlItem,
        dest: File,
        total: Long,
        counter: AtomicLong,
        makeRequest: (String) -> Request,
        maxPart: Long,
        resume: MutableSet<Long>?,
        retries: Int
    ) = withContext(Dispatchers.IO) {
        if (resume != null) loadCheckpoint(dest, total, d.etag, resume)
        val maxPartsTier = when (PerfTier.tier) { PerfTier.TIER_LOW -> 16; PerfTier.TIER_BALANCED -> 32; else -> MAX_PARTS }
        val wanted = (total / PART_TARGET_BYTES).toInt().coerceIn(MIN_PARTS, maxPartsTier)
        val size = ((total + wanted - 1) / wanted).coerceIn(MIN_PART_BYTES, maxPart)
        val plan = ArrayList<RPlan>()
        var c = 0L
        while (c < total) {
            val e = min(c + size - 1, total - 1)
            plan.add(RPlan(plan.size, c, e)); c = e + 1
        }
        val n = plan.size
        val partDone = java.util.concurrent.atomic.AtomicLongArray(n)
        val base = synchronized(d.partProgress) {
            val b = d.partProgress.size
            repeat(n) { d.partProgress.add(0f) }
            b
        }
        val queue = java.util.concurrent.ConcurrentLinkedQueue<RPlan>()
        var already = 0L
        plan.forEach { p ->
            val len = p.end - p.start + 1
            if (resume != null && p.start in resume) { partDone.set(p.index, len); already += len } else queue.add(p)
        }
        counter.addAndGet(already)
        d.done = counter.get().coerceAtMost(d.total)
        // ZERO-DELAY START: never call setLength(total) upfront (on Android FUSE that zero-fills 1-5 GB files
        // and stalls download start for 3-8s!). FileChannel positional pwrite grows the file sparsely at 0ms!
        if (!dest.exists()) runCatching { dest.createNewFile() }

        val pending = AtomicInteger(queue.size)
        // Hands-off hosts (Instagram/Meta): max 2 connections; all other hosts hit full speed immediately!
        val tierConn = when (PerfTier.tier) { PerfTier.TIER_LOW -> 4; PerfTier.TIER_BALANCED -> 10; else -> SMART_MAX_CONN }
        val bigFile = total >= 25L * 1024L * 1024L
        val startConn = when (PerfTier.tier) {
            PerfTier.TIER_LOW -> 2
            PerfTier.TIER_BALANCED -> if (bigFile) 6 else 4
            else -> if (bigFile) 8 else 6
        }
        val connCap = if (isHandsOffHost(d.url)) 2 else tierConn
        val maxConn = min(connCap, (queue.size + 4).coerceAtLeast(1))
        val target = AtomicInteger(min(startConn, maxConn))
        val ceiling = AtomicInteger(maxConn)
        val throttled = java.util.concurrent.atomic.AtomicBoolean(false)
        val active = ConcurrentHashMap.newKeySet<Int>()
        val lastCkptAt = AtomicLong(System.currentTimeMillis())
        fun publish() { d.streamConn[base] = target.get(); d.partsUsed = d.streamConn.values.sum().coerceAtLeast(1) }

        // Ghost-Downloader-3 live chunk: tracks dynamic tail bounds, active socket, stall timer & worker speed (for ETA-weighted splitting!)
        class LiveChunk(
            val index: Int,
            val start: Long,
            val cursor: AtomicLong,
            val end: AtomicLong,
            val isStolen: Boolean = false
        ) {
            @Volatile var activeCall: Call? = null
            val startedAt = System.currentTimeMillis()
            val lastByteAt = AtomicLong(startedAt)
            val bytesRead = AtomicLong(0L)
            fun speedBps(now: Long): Long {
                val dt = (now - startedAt).coerceAtLeast(350L)
                return (bytesRead.get() * 1000L / dt).coerceAtLeast(64L * 1024L)
            }
        }
        val inflight = ConcurrentHashMap<Int, LiveChunk>()
        val splitMinBytes = 384L * 1024L

        fun tryStealTail(): LiveChunk? {
            if (isHandsOffHost(d.url) || throttled.get()) return null
            val now = System.currentTimeMillis()
            var best: LiveChunk? = null
            var bestEtaMs = -1L
            for (lc in inflight.values) {
                val rem = lc.end.get() - lc.cursor.get() + 1L
                if (rem >= splitMinBytes * 2L) {
                    // Ghost-Downloader-3 core heuristic: always split the chunk with the LONGEST remaining ETA!
                    val etaMs = rem * 1000L / lc.speedBps(now)
                    if (etaMs > bestEtaMs) {
                        bestEtaMs = etaMs
                        best = lc
                    }
                }
            }
            val victim = best ?: return null
            synchronized(victim) {
                val cur = victim.cursor.get()
                val curEnd = victim.end.get()
                val rem = curEnd - cur + 1L
                if (rem < splitMinBytes * 2L) return null
                val mid = cur + rem / 2L
                victim.end.set(mid - 1L)
                pending.incrementAndGet()
                return LiveChunk(victim.index, mid, AtomicLong(mid), AtomicLong(curEnd), isStolen = true)
            }
        }

        suspend fun fetchLiveChunk(raf: RandomAccessFile, lc: LiveChunk, id: Int) {
            val ch = raf.channel
            val bufBytes = when (PerfTier.tier) {
                PerfTier.TIER_LOW -> 64 * 1024
                PerfTier.TIER_BALANCED -> 128 * 1024
                else -> 256 * 1024
            }
            val buf = ByteArray(bufBytes)
            var attempt = 0
            while (true) {
                val reqStart = lc.cursor.get()
                val reqEnd = lc.end.get()
                if (reqStart > reqEnd) return
                lc.lastByteAt.set(System.currentTimeMillis())
                val call = http.newCall(makeRequest("bytes=$reqStart-$reqEnd"))
                lc.activeCall = call
                try {
                    call.execute().use { r ->
                        if (r.code == 200) throw RangeUnsupportedException()
                        if (r.code != 206) throw IllegalStateException("Server refused range (${r.code})")
                        val body = r.body ?: throw IllegalStateException("Empty response")
                        body.byteStream().use { input ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val curPos = lc.cursor.get()
                                val dynamicEnd = lc.end.get()
                                val remaining = dynamicEnd - curPos + 1L
                                if (remaining <= 0L) break
                                val toRead = min(buf.size.toLong(), remaining).toInt()
                                val k = input.read(buf, 0, toRead)
                                if (k < 0) {
                                    if (lc.cursor.get() > lc.end.get()) break
                                    throw IllegalStateException("Connection ended early")
                                }
                                lc.lastByteAt.set(System.currentTimeMillis())
                                val allowed = min(k.toLong(), (lc.end.get() - lc.cursor.get() + 1L).coerceAtLeast(0L)).toInt()
                                if (allowed <= 0) break
                                // Positional FileChannel write (atomic pwrite: zero extra lseek syscall!)
                                val bb = ByteBuffer.wrap(buf, 0, allowed)
                                var writeOff = curPos
                                while (bb.hasRemaining()) {
                                    val w = ch.write(bb, writeOff)
                                    if (w <= 0) break
                                    writeOff += w
                                }
                                lc.cursor.addAndGet(allowed.toLong())
                                lc.bytesRead.addAndGet(allowed.toLong())
                                partDone.addAndGet(lc.index, allowed.toLong())
                                d.done = counter.addAndGet(allowed.toLong()).coerceAtMost(d.total)
                                if (allowed < k) break
                            }
                        }
                    }
                    return
                } catch (e: CancellationException) {
                    call.cancel()
                    throw e
                } catch (e: RangeUnsupportedException) {
                    throw e
                } catch (e: Exception) {
                    val msg = e.message.orEmpty()
                    // If a cached redirect edge URL expired (403/410), clear it so retries use the original URL!
                    if ((msg.contains("(403") || msg.contains("(410")) && d.resolvedUrl != null) {
                        d.resolvedUrl = null
                    }
                    val limited = msg.contains("(429") || msg.contains("(503")
                    if (limited && id > 0) throw ConnLimitException()
                    if (limited) throttled.set(true)
                    else if (msg.contains("(4") && !msg.contains("(408")) {
                        val code = msg.substringAfter("(", "").trimEnd(')')
                        if (id > 0 && code != "404" && code != "410") throw ConnLimitException()
                        throw IllegalStateException("The server refused this link ($code)")
                    }
                    attempt++
                    if (attempt > retries) {
                        if (id > 0) throw ConnLimitException()
                        throw e
                    }
                    val backoff = min(2_500L, 260L * (1L shl (attempt - 1).coerceAtMost(4)))
                    delay(backoff + (id * 37L % 150L))
                } finally {
                    lc.activeCall = null
                }
            }
        }

        coroutineScope {
            fun spawn(id: Int) {
                if (!active.add(id)) return
                launch {
                    try {
                        RandomAccessFile(dest, "rw").use { raf ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                if (id >= target.get()) break
                                val polled = queue.poll()
                                val lc = if (polled != null) {
                                    LiveChunk(polled.index, polled.start, AtomicLong(polled.start), AtomicLong(polled.end))
                                } else {
                                    tryStealTail() ?: break
                                }
                                inflight[id] = lc
                                try {
                                    fetchLiveChunk(raf, lc, id)
                                } catch (e: ConnLimitException) {
                                    inflight.remove(id)
                                    val remStart = lc.cursor.get()
                                    val remEnd = lc.end.get()
                                    if (remStart <= remEnd) {
                                        queue.add(RPlan(lc.index, remStart, remEnd))
                                    } else {
                                        pending.decrementAndGet()
                                    }
                                    ceiling.updateAndGet { v -> min(v, id) }
                                    target.updateAndGet { v -> min(v, id) }
                                    publish()
                                    break
                                } finally {
                                    inflight.remove(id)
                                }
                                if (resume != null && !lc.isStolen && partDone.get(lc.index) >= (plan[lc.index].end - plan[lc.index].start + 1L)) {
                                    resume.add(plan[lc.index].start)
                                    val now = System.currentTimeMillis()
                                    if (now - lastCkptAt.get() > 2_500L) {
                                        lastCkptAt.set(now)
                                        saveCheckpoint(dest, total, d.etag, resume)
                                    }
                                }
                                pending.decrementAndGet()
                            }
                        }
                    } finally { active.remove(id) }
                }
            }
            publish()
            for (id in 0 until target.get()) spawn(id)

            // Adaptive hill-climbing connection tuner + Stall-Watchdog (kills >4.5s hung sockets immediately!)
            launch {
                var prevBytes = counter.get()
                var prevSpeed = 0L
                var climbing = true
                var lastAdded = 0
                var tick = 0
                while (pending.get() > 0) {
                    delay(650)
                    tick++
                    val nowMs = System.currentTimeMillis()
                    // Recycle any stalled socket (>4.5s with zero bytes) so a dead CDN edge never hangs a download!
                    for (lc in inflight.values) {
                        val rem = lc.end.get() - lc.cursor.get() + 1L
                        if (rem > 0L && nowMs - lc.lastByteAt.get() > 3_500L) {
                            lc.lastByteAt.set(nowMs)
                            runCatching { lc.activeCall?.cancel() }
                        }
                    }
                    val nowBytes = counter.get()
                    val speed = (nowBytes - prevBytes).coerceAtLeast(0)
                    prevBytes = nowBytes
                    val t = target.get()
                    if (throttled.getAndSet(false)) {
                        val nt = (t - 2).coerceAtLeast(1)
                        ceiling.set(nt); target.set(nt); climbing = false; lastAdded = 0; publish()
                        prevSpeed = speed; continue
                    }
                    if (queue.isEmpty() && inflight.size < 2) { prevSpeed = speed; continue }
                    val cap = min(ceiling.get(), maxConn)
                    if (climbing) {
                        if (prevSpeed == 0L || speed >= prevSpeed * 1.06) {
                            if (t < cap) {
                                val nt = min(cap, t + if (t < 8) 2 else 1)
                                lastAdded = nt - t
                                target.set(nt)
                                for (id in t until nt) spawn(id)
                                publish()
                            } else climbing = false
                        } else {
                            if (lastAdded > 0 && speed < prevSpeed * 0.90) target.set((t - lastAdded).coerceAtLeast(1))
                            climbing = false; lastAdded = 0; publish()
                        }
                    } else if (tick % 6 == 0 && t < cap) {
                        climbing = true
                    }
                    prevSpeed = speed
                }
            }

            // Live progress of every single part.
            launch {
                fun sync() {
                    for (i in 0 until n) {
                        val len = (plan[i].end - plan[i].start + 1).toFloat()
                        val f = (partDone.get(i) / len).coerceIn(0f, 1f)
                        val idx = base + i
                        if (idx < d.partProgress.size && kotlin.math.abs(d.partProgress[idx] - f) >= 0.01f) d.partProgress[idx] = f
                    }
                }
                while (pending.get() > 0) { sync(); delay(350) }
                sync()
            }
        }
        for (i in 0 until n) if (base + i < d.partProgress.size) d.partProgress[base + i] = 1f
    }

    /**
     * Returns true when the file was fully produced. Returns false (without side effects) when
     * the fast path does not apply, so the caller runs the normal engine.
     */
    /** Set once if this device cannot write WebM: 4K / 8K then go straight to the full engine. */
    @Volatile private var webmMuxBroken = false

    private suspend fun fastDownload(d: DlItem, dir: File): Boolean {
        // HDR keeps its colour data only through the full engine (ffmpeg), never the native joiner.
        if (MediaTools.isHdr(d.ytHeight)) return false
        val media = try {
            withContext(Dispatchers.IO) { YtDlpEngine.resolveCached(ctx, d.url, d.cookie) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.warn("Fast", "lookup failed ${AppLog.url(d.url)}: ${e.message?.take(500)}")
            return false
        }

        val title = (d.pageTitle?.takeIf { it.isNotBlank() }
            ?: stripMediaExt(cleanTitle(media.title))).ifBlank { "video" }

        // ── FAST PATH A: Direct M4A audio via Ghost-Downloader-3 parallel ranges (1-sec downloads!) ──
        if (d.ytQuality == YtQuality.M4A) {
            val aUrl = media.directM4aUrl ?: return false
            val aHdrs = media.directM4aHeaders
            val aUa = aHdrs["User-Agent"] ?: media.userAgent ?: d.ua
            val aTotal = if (media.directM4aExactBytes > 0L) media.directM4aExactBytes else withContext(Dispatchers.IO) { probeSize(aUrl, aHdrs, aUa) }
            if (aTotal <= 0L) return false
            val out = uniqueFile(dir, "$title.m4a")
            val tmp = File(dir, ".${out.name}.a.tmp")
            return try {
                d.displayName = title
                d.qualityLabel = "M4A"
                d.total = aTotal
                d.done = 0
                d.pct = -1f
                d.partProgress.clear(); d.streamConn.clear(); d.partsUsed = 1
                val counter = AtomicLong(0L)
                val maxPart = if (aUrl.contains("googlevideo.com", true)) 3L * 1024 * 1024 else 16L * 1024 * 1024
                smartFetch(d, tmp, aTotal, counter, { r -> rangeRequest(aUrl, aHdrs, aUa, r) }, maxPart, null, 3)
                if (!tmp.renameTo(out)) tmp.copyTo(out, overwrite = false)
                d.file = out
                d.displayName = null
                d.mime = "audio/mp4"
                d.total = out.length()
                d.done = d.total
                d.pct = 1f
                true
            } catch (e: CancellationException) {
                runCatching { out.delete() }
                throw e
            } catch (e: Throwable) {
                runCatching { out.delete() }
                d.done = 0; d.total = -1L; d.pct = 0f
                d.partProgress.clear(); d.streamConn.clear(); d.partsUsed = 1
                false
            } finally {
                runCatching { tmp.delete() }
            }
        }

        if (d.ytQuality.audio) return false

        val target = when {
            d.ytHeight > 0 -> MediaTools.plainHeight(d.ytHeight)
            d.ytQuality == YtQuality.P1080 -> 1080
            d.ytQuality == YtQuality.P720 -> 720
            d.ytQuality == YtQuality.P480 -> 480
            else -> Int.MAX_VALUE
        }
        val fastOk: (YtDlpEngine.StreamChoice) -> Boolean = { it.mp4Safe || (it.webmSafe && !webmMuxBroken) }
        val choice = if (target == Int.MAX_VALUE) {
            val top = media.choices.filter { !it.hls }.maxByOrNull { DeviceCaps.tier(it) }
            val fast = media.choices.filter(fastOk).maxByOrNull { DeviceCaps.tier(it) }
            if (fast != null && top != null && DeviceCaps.tier(top) > DeviceCaps.tier(fast)) null else fast
        } else {
            media.choices.filter { !it.hls && DeviceCaps.tier(it) in 1..target }.maxByOrNull { DeviceCaps.tier(it) }
        }

        // ── FAST PATH B: Native 16-Worker HLS Engine + TS->MP4 Remux for HLS-only sites! ──
        if (choice == null || !fastOk(choice)) {
            val hlsChoice = if (target == Int.MAX_VALUE) {
                media.choices.filter { it.hls && it.video.startsWith("http", true) }.maxByOrNull { DeviceCaps.tier(it) }
            } else {
                media.choices.filter { it.hls && it.video.startsWith("http", true) && DeviceCaps.tier(it) in 1..target }.maxByOrNull { DeviceCaps.tier(it) }
                    ?: media.choices.filter { it.hls && it.video.startsWith("http", true) }.maxByOrNull { DeviceCaps.tier(it) }
            }
            if (hlsChoice != null && !YtDlpEngine.isYoutubeUrl(d.url)) {
                val outTs = uniqueFile(dir, "$title.ts")
                val hlsUa = hlsChoice.vHeaders["User-Agent"] ?: media.userAgent ?: d.ua
                val hlsRef = hlsChoice.vHeaders["Referer"] ?: d.url
                val prevFile = d.file
                val prevKind = d.kind
                return try {
                    val proxy = DlItem(d.id, hlsChoice.video, hlsUa, d.cookie, hlsRef, "video/mp2t", outTs).apply {
                        pageTitle = title
                        ytHeight = d.ytHeight
                        qualityLabel = if (hlsChoice.height > 0) MediaTools.labelForHeight(DeviceCaps.tier(hlsChoice)) else d.qualityLabel
                    }
                    d.displayName = title
                    if (proxy.qualityLabel.isNotBlank()) d.qualityLabel = proxy.qualityLabel
                    // Mirror live HLS progress straight onto the user's visible Download card
                    coroutineScope {
                        val syncer = launch {
                            while (isActive) {
                                d.done = proxy.done
                                d.segmentDone = proxy.segmentDone
                                d.segmentTotal = proxy.segmentTotal
                                d.partsUsed = proxy.partsUsed
                                d.statusNote = proxy.statusNote
                                if (proxy.segmentTotal > 0) d.pct = (proxy.segmentDone.toFloat() / proxy.segmentTotal).coerceIn(0f, 0.99f)
                                delay(220)
                            }
                        }
                        try {
                            hlsDownload(proxy)
                        } finally {
                            syncer.cancel()
                        }
                    }
                    d.file = proxy.file
                    d.displayName = null
                    d.statusNote = null
                    d.mime = proxy.mime ?: "video/mp4"
                    d.total = proxy.file.length()
                    d.done = d.total
                    d.pct = 1f
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLog.warn("FastHLS", "native HLS fallback ${AppLog.url(d.url)}: ${e.message?.take(220)}")
                    runCatching { outTs.delete() }
                    runCatching { File(dir, ".${outTs.name}.parts").deleteRecursively() }
                    d.file = prevFile
                    d.kind = prevKind
                    d.done = 0; d.total = -1L; d.pct = 0f; d.segmentDone = 0; d.segmentTotal = 0; d.statusNote = null
                    d.partProgress.clear(); d.streamConn.clear(); d.partsUsed = 1
                    false
                }
            }
            return false
        }

        // ── FAST PATH C: Ghost-Downloader-3 Parallel Range Video (+ Audio) + Hardware Remux ──
        val webm = !choice.mp4Safe
        val outExt = if (webm) "webm" else "mp4"
        val ua = choice.vHeaders["User-Agent"] ?: media.userAgent ?: d.ua
        val out = uniqueFile(dir, "$title.$outExt")
        val vTmp = File(dir, ".${out.name}.v.tmp")
        val aTmp = File(dir, ".${out.name}.a.tmp")
        try {
            val (vTotal, aTotal) = coroutineScope {
                val v = async(Dispatchers.IO) {
                    if (choice.vExactBytes > 0L) choice.vExactBytes else probeSize(choice.video, choice.vHeaders, ua)
                }
                val a = async(Dispatchers.IO) {
                    when {
                        choice.audio == null -> 0L
                        choice.aExactBytes > 0L -> choice.aExactBytes
                        else -> probeSize(choice.audio, choice.aHeaders, ua)
                    }
                }
                v.await() to a.await()
            }
            if (vTotal <= 0 || (choice.audio != null && aTotal <= 0)) return false

            d.displayName = title
            d.qualityLabel = MediaTools.labelForHeight(DeviceCaps.tier(choice))
            d.total = vTotal + aTotal
            d.done = 0
            d.pct = -1f
            d.partProgress.clear(); d.streamConn.clear(); d.partsUsed = 1
            val counter = AtomicLong(0L)
            fun maxPartFor(u: String) = if (u.contains("googlevideo.com", true)) 3L * 1024 * 1024 else 24L * 1024 * 1024
            coroutineScope {
                val a = launch {
                    smartFetch(d, vTmp, vTotal, counter, { r -> rangeRequest(choice.video, choice.vHeaders, ua, r) }, maxPartFor(choice.video), null, 3)
                }
                val b = if (choice.audio != null) launch {
                    smartFetch(d, aTmp, aTotal, counter, { r -> rangeRequest(choice.audio, choice.aHeaders, ua, r) }, maxPartFor(choice.audio), null, 3)
                } else null
                val ticker = launch {
                    while (true) {
                        if (d.total > 0) d.pct = (d.done.toFloat() / d.total).coerceIn(0f, 1f) * 0.9f
                        delay(250)
                    }
                }
                a.join(); b?.join()
                ticker.cancel()
            }
            currentCoroutineContext().ensureActive()

            if (choice.audio != null) {
                d.pct = 0.9f
                try {
                    withContext(Dispatchers.IO) {
                        Remux.mux(vTmp, aTmp, out, webm) { f -> d.pct = 0.9f + 0.09f * f }
                    }
                } catch (e: Throwable) {
                    if (webm && e !is CancellationException) webmMuxBroken = true
                    throw e
                }
            } else if (!vTmp.renameTo(out)) {
                vTmp.copyTo(out, overwrite = false)
            }
            d.file = out
            d.displayName = null
            d.mime = if (webm) "video/webm" else "video/mp4"
            d.total = out.length()
            d.done = d.total
            d.pct = 1f
            return true
        } catch (e: CancellationException) {
            runCatching { out.delete() }
            throw e
        } catch (e: Throwable) {
            AppLog.warn("Fast", "fast path failed ${AppLog.url(d.url)}: ${e.javaClass.simpleName} ${e.message?.take(300)}")
            runCatching { out.delete() }
            d.done = 0; d.total = -1L; d.pct = 0f
            d.partProgress.clear(); d.streamConn.clear(); d.partsUsed = 1
            return false
        } finally {
            runCatching { vTmp.delete() }
            runCatching { aTmp.delete() }
        }
    }

    /** Internal signal that a server stopped honouring Range requests. */
    private class RangeUnsupportedException : Exception("Server ignored byte ranges")

    /** Internal signal: the site does not allow this many parallel connections, so this one steps aside. */
    private class ConnLimitException : Exception("Server limits parallel connections")

    /** True when retrying the same link can never work. */
    private fun isPermanentError(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return m.contains("refused") || m.contains("web page") ||
            m.contains("only sent") || m.contains("http 4") || m.contains("protected")
    }

    private suspend fun probe(d: DlItem) = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            try {
                // 1 KB instead of 1 byte: enough to see what the file really is (mp4 / mkv / ts ...) before it is created
                http.newCall(request(d, "bytes=0-1023")).execute().use { r ->
                    val type = r.header("Content-Type").orEmpty().lowercase()
                    val head = ByteArray(1024)
                    var headN = 0
                    if (r.isSuccessful) {
                        runCatching {
                            val input = r.body?.byteStream()
                            while (input != null && headN < head.size) {
                                val k = input.read(head, headN, head.size - headN)
                                if (k < 0) break
                                headN += k
                            }
                        }
                    }
                    val headText = if (headN >= 7) String(head, 0, min(headN, 256), Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n') else ""
                    if (type.contains("mpegurl") || headText.startsWith("#EXTM3U")) {
                        d.kind = DlKind.HLS
                        d.mime = "video/mp2t"
                        val baseName = cleanTitle(d.pageTitle).ifBlank {
                            d.file.name.substringBeforeLast('.', d.file.name)
                        }
                        if (!d.file.exists()) {
                            d.file = uniqueFile(d.file.parentFile, "$baseName.ts")
                        }
                        d.probed = true
                        return@use
                    }
                    if (!d.file.exists() && !isRealExt(d.file.name.substringAfterLast('.', "").lowercase())) {
                        val sniffed = sniffBytes(head, headN)
                        val dispName = nameFromDisposition(r.header("Content-Disposition"))
                        val fromServer = extOfName(dispName)
                            ?: extForMime(type)
                            ?: extOfName(r.request.url.encodedPath.substringAfterLast('/'))
                        val e = if (sniffed != null && sniffed != "zip") sniffed else fromServer ?: sniffed
                        if (e != null) {
                            val mediaExts = setOf("mp4", "m4v", "mkv", "webm", "mov", "ts", "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav")
                            val titled = if (dispName.isNullOrBlank() && e in mediaExts && !d.pageTitle.isNullOrBlank()) {
                                "${cleanTitle(d.pageTitle)}.$e"
                            } else withExt(d.file.name, e)
                            d.file = uniqueFile(d.file.parentFile, titled)
                        }
                    }
                    val tag = r.header("ETag")?.takeIf { it.isNotBlank() } ?: r.header("Last-Modified")?.takeIf { it.isNotBlank() }
                    if (tag != null) {
                        if (d.etag != null && d.etag != tag) {
                            d.completedChunks.clear()
                            runCatching { d.file.delete() }
                        }
                        d.etag = tag
                    }
                    // Cache post-redirect CDN URL so parallel workers skip redundant 302 redirect hops!
                    val finalUrl = r.request.url.toString()
                    if (finalUrl != d.url && (finalUrl.startsWith("https://", true) || finalUrl.startsWith("http://", true))) {
                        d.resolvedUrl = finalUrl
                    }
                    if (r.code == 206) {
                        d.ranged = true
                        d.total = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                    } else if (r.code == 200) {
                        d.ranged = false
                        d.total = r.header("Content-Length")?.toLongOrNull() ?: -1L
                    } else {
                        d.ranged = false
                        d.total = -1L
                        if (r.code == 416 || r.code == 405 || r.code == 501) {
                            runCatching {
                                http.newCall(request(d).newBuilder().head().build()).execute().use { hr ->
                                    val ht = hr.header("Content-Type").orEmpty().lowercase()
                                    if (ht.contains("mpegurl")) d.kind = DlKind.HLS
                                    else d.total = hr.header("Content-Length")?.toLongOrNull() ?: -1L
                                }
                            }
                        }
                    }
                }
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                if (attempt >= 2) {
                    d.ranged = false
                    d.total = -1L
                    break
                }
                delay(400)   // one quiet retry: a slow first handshake should not cost the multi-connection speed
            }
        }
        if (d.kind != DlKind.HLS) refineProbe(d)
        d.probed = true
    }

    /**
     * Second look at servers that did not answer the first range request clearly. Many sites do support
     * parallel ranges but only show it on a later slice, or do not put the total size in Content-Range.
     */
    private fun refineProbe(d: DlItem) {
        try {
            if (d.total <= 0) {
                d.total = http.newCall(request(d).newBuilder().head().build()).execute().use { r ->
                    if (r.isSuccessful) r.header("Content-Length")?.toLongOrNull() ?: -1L else -1L
                }
            }
            if (!d.ranged && d.total >= MIN_CHUNKED_BYTES) {
                // a plain 200 to "bytes=0-1023": some servers skip only ranges that start at 0
                val ok = http.newCall(request(d, "bytes=1-1024")).execute().use { r -> r.code == 206 }
                if (ok) d.ranged = true
            }
            if (d.ranged && d.total <= 0) d.ranged = false   // parallel parts need a known size
        } catch (_: Exception) {
        }
    }

    /** Big file with byte-range support: the smart engine picks the parts and the connections. */
    private suspend fun chunkedDownload(d: DlItem) = withContext(Dispatchers.IO) {
        val total = d.total
        if (total <= 0) return@withContext
        d.kind = DlKind.FILE
        d.ranged = true
        if (d.chunkTotal != total) {
            d.chunkTotal = total
            d.completedChunks.clear()
        }
        d.partProgress.clear(); d.streamConn.clear(); d.partsUsed = 1
        val counter = AtomicLong(0L)
        smartFetch(d, d.file, total, counter, { range -> request(d, range) }, MAX_PART_BYTES, d.completedChunks, 4)
        d.done = total
    }

    private suspend fun singleStream(d: DlItem) = withContext(Dispatchers.IO) {
        runCatching { d.file.delete() }
        d.completedChunks.clear()
        d.chunkTotal = -1L
        d.done = 0
        http.newCall(request(d)).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            val body = r.body ?: throw IllegalStateException("Empty response")
            val type = r.header("Content-Type").orEmpty().lowercase()
            if (d.total <= 0) d.total = body.contentLength()
            val head = ByteArray(4096)
            var headLen = 0
            body.byteStream().use { input ->
                while (headLen < head.size) {
                    currentCoroutineContext().ensureActive()
                    val k = input.read(head, headLen, head.size - headLen)
                    if (k < 0) break
                    headLen += k
                }
                val ssHead = if (headLen >= 7) String(head, 0, min(headLen, 256), Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n') else ""
                if (type.contains("mpegurl") || ssHead.startsWith("#EXTM3U")) {
                    d.kind = DlKind.HLS
                    d.mime = "video/mp2t"
                    val baseName = cleanTitle(d.pageTitle).ifBlank {
                        d.file.name.substringBeforeLast('.', d.file.name)
                    }
                    d.file = uniqueFile(d.file.parentFile, "$baseName.ts")
                    d.probed = true
                    return@withContext hlsDownload(d)
                }
                // the real name is known from the first bytes, before the file is created
                if (!isRealExt(d.file.name.substringAfterLast('.', "").lowercase())) {
                    val e = sniffBytes(head, headLen)?.takeIf { it != "zip" }
                        ?: extOfName(nameFromDisposition(r.header("Content-Disposition")))
                        ?: extForMime(type)
                        ?: sniffBytes(head, headLen)
                    if (e != null) d.file = uniqueFile(d.file.parentFile, withExt(d.file.name, e))
                }
                val ioBuf = if (PerfTier.low) 64 * 1024 else 128 * 1024
                d.file.outputStream().buffered(ioBuf).use { out ->
                    out.write(head, 0, headLen)
                    d.done = headLen.toLong()
                    val buf = ByteArray(ioBuf)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        d.done = d.done + n
                    }
                }
            }
            val snippet = String(head, 0, headLen, Charsets.ISO_8859_1)
            val reason = errorStubReason(snippet, type, d.total, d.done)
            if (reason != null) throw IllegalStateException(reason)
            if (d.total > 0 && d.done < d.total) throw IllegalStateException("Connection ended early")
        }
    }

    /**
     * Servers sometimes answer a media link with a tiny error page instead of
     * the file (YouTube's "malformed_config", sign-in walls, expired links).
     * Those must fail loudly instead of showing up as a finished download.
     */
    private fun errorStubReason(
        snippet: String,
        contentType: String,
        expected: Long,
        received: Long
    ): String? {
        val head = snippet.trimStart()
        if (contentType.contains("text/html") || contentType.contains("application/xml") ||
            contentType.contains("application/json") || contentType.contains("text/xml")
        ) {
            return "The server sent a web page instead of the file"
        }
        if (received <= 1024L * 512L) {
            val markers = listOf(
                "malformed_config",
                "sign in to confirm",
                "video unavailable",
                "this video is private",
                "error 403",
                "forbidden",
                "not found"
            )
            val lower = head.lowercase()
            markers.firstOrNull { lower.contains(it) }?.let {
                return "The link was refused by the server"
            }
            if (head.startsWith("<")) return "The server sent a web page instead of the file"
        }
        // Advertised size but almost nothing arrived — a stub, not the file.
        if (expected > 1024L * 1024L && received < expected / 100) {
            return "The server only sent ${received} of $expected bytes"
        }
        return null
    }

    // ── HLS / media playlist downloader ──────────────────────────────────────
    /** One media segment: its URL, optional byte range, and the key that decrypts it. */
    private data class HlsSeg(
        val url: String,
        val range: String?,
        val keyUri: String?,
        val keyIv: String?,
        val seq: Long
    )

    private suspend fun hlsDownload(d: DlItem) = withContext(Dispatchers.IO) {
        var playlistUrl = d.url
        var text = fetchText(d, playlistUrl).trimStart('\uFEFF', ' ', '\t', '\r', '\n')

        // Master playlist (supports up to 2 nested hops): pick target/highest variant + separate #EXT-X-MEDIA:TYPE=AUDIO!
        var audioPlaylistUrl: String? = null
        var masterHops = 0
        while (text.contains("#EXT-X-STREAM-INF") && masterHops < 2) {
            masterHops++
            val lines = text.lines()
            val audioGroups = HashMap<String, String>()
            var fallbackAudioUrl: String? = null
            for (raw in lines) {
                val l = raw.trim()
                if (l.startsWith("#EXT-X-MEDIA:")) {
                    val attrs = parseAttrs(l.substringAfter(':'))
                    if (attrs["TYPE"].equals("AUDIO", true)) {
                        val u = attrs["URI"]?.takeIf { it.isNotBlank() }?.let { resolve(playlistUrl, it) }
                        val grp = attrs["GROUP-ID"]?.takeIf { it.isNotBlank() }
                        val isDefault = attrs["DEFAULT"].equals("YES", true)
                        if (u != null) {
                            if (fallbackAudioUrl == null || isDefault) fallbackAudioUrl = u
                            if (grp != null && (!audioGroups.containsKey(grp) || isDefault)) {
                                audioGroups[grp] = u
                            }
                        }
                    }
                }
            }
            val wantH = MediaTools.plainHeight(d.ytHeight)
            class MasterVar(val url: String, val bw: Long, val w: Int, val h: Int, val audioGroup: String?)
            val vars = ArrayList<MasterVar>()
            lines.forEachIndexed { i, line ->
                if (line.trim().startsWith("#EXT-X-STREAM-INF")) {
                    val bw = HLS_BANDWIDTH_RE.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.trim().startsWith("#") }
                    val res = HLS_RESOLUTION_RE.find(line)
                    val vw = res?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val vh = res?.groupValues?.get(2)?.toIntOrNull() ?: 0
                    val aGrp = parseAttrs(line.substringAfter(':'))["AUDIO"]
                    if (next != null) vars.add(MasterVar(resolve(playlistUrl, next.trim()), bw, vw, vh, aGrp))
                }
            }
            val chosen = if (wantH > 0) {
                vars.filter { it.h in 1..wantH }.maxWithOrNull(compareBy<MasterVar> { it.h }.thenBy { it.bw })
                    ?: vars.maxWithOrNull(compareBy<MasterVar> { it.h }.thenBy { it.bw })
            } else {
                vars.maxWithOrNull(compareBy<MasterVar> { it.h }.thenBy { it.bw })
            }
            val bestUrl = chosen?.url
            val bestAudioGroup = chosen?.audioGroup
            if (chosen != null && chosen.w > 0 && chosen.h > 0 && d.qualityLabel.isBlank()) {
                val q = MediaTools.qualityLabel(chosen.w, chosen.h)
                if (q.isNotBlank()) d.qualityLabel = q
            }
            if (audioPlaylistUrl == null) {
                audioPlaylistUrl = bestAudioGroup?.let { audioGroups[it] } ?: if (bestAudioGroup != null) fallbackAudioUrl else null
            }
            playlistUrl = bestUrl ?: throw IllegalStateException("No HLS variant found")
            text = fetchText(d, playlistUrl).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        }

        // Media playlist: segments, init map, byte ranges, per-segment keys.
        val segments = mutableListOf<HlsSeg>()
        var initUrl: String? = null
        var initRange: String? = null
        var keyUri: String? = null
        var keyIv: String? = null
        var mediaSeq = 0L
        var pendingRange: String? = null
        val lastEnd = HashMap<String, Long>()

        fun toRange(url: String, spec: String): String? {
            val parts = spec.trim().split('@')
            val len = parts[0].trim().toLongOrNull() ?: return null
            val off = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: (lastEnd[url] ?: 0L)
            lastEnd[url] = off + len
            return "bytes=$off-${off + len - 1}"
        }

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE") -> {
                    mediaSeq = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                }
                line.startsWith("#EXT-X-MAP") -> {
                    val attrs = parseAttrs(line.substringAfter(':'))
                    attrs["URI"]?.let { initUrl = resolve(playlistUrl, it) }
                    initRange = attrs["BYTERANGE"]?.let { spec -> initUrl?.let { toRange(it, spec) } }
                }
                line.startsWith("#EXT-X-KEY") -> {
                    val attrs = parseAttrs(line.substringAfter(':'))
                    when (attrs["METHOD"].orEmpty().uppercase()) {
                        "AES-128" -> {
                            keyUri = attrs["URI"]?.let { resolve(playlistUrl, it) }
                            keyIv = attrs["IV"]
                        }
                        "NONE", "" -> {
                            keyUri = null
                            keyIv = null
                        }
                        else -> throw IllegalStateException(
                            "This stream is protected (DRM) and can't be downloaded"
                        )
                    }
                }
                line.startsWith("#EXT-X-BYTERANGE") -> {
                    pendingRange = line.substringAfter(':').trim()
                }
                !line.startsWith("#") -> {
                    val segUrl = resolve(playlistUrl, line)
                    val range = pendingRange?.let { toRange(segUrl, it) }
                    pendingRange = null
                    segments.add(HlsSeg(segUrl, range, keyUri, keyIv, mediaSeq + segments.size))
                }
            }
        }
        if (segments.isEmpty()) throw IllegalStateException("No media segments found in playlist")
        val isVod = text.contains("#EXT-X-ENDLIST") || text.contains("PLAYLIST-TYPE:VOD", true)
        val totalDurSec = EXTINF_RE.findAll(text).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
        if (!isVod && segments.size < 12 && totalDurSec < 45.0) {
            throw IllegalStateException("Live streams can't be saved as a file while broadcasting")
        }

        // fMP4 streams (init map) are valid as .mp4 once concatenated; MPEG-TS stays .ts.
        if (initUrl != null && d.file.extension.equals("ts", true)) {
            val dir = d.file.parentFile
            val base = d.file.name.removeSuffix(".ts")
            var f = File(dir, "$base.mp4")
            var i = 1
            while (f.exists()) {
                f = File(dir, "$base($i).mp4"); i++
            }
            d.file = f
            d.mime = "video/mp4"
        }

        val keyCache = ConcurrentHashMap<String, ByteArray>()
        val rateLimitUntil = AtomicLong(0L)
        val hlsCap = when (PerfTier.tier) { PerfTier.TIER_LOW -> 4; PerfTier.TIER_BALANCED -> 8; else -> min(HLS_WORKERS, 10) }
        val n = min(hlsCap, segments.size).coerceAtLeast(1)
        d.partsUsed = n
        d.kind = DlKind.HLS
        d.segmentTotal = segments.size
        d.segmentDone = 0
        d.ranged = false
        d.total = -1
        d.partProgress.clear()
        repeat(n) { d.partProgress.add(0f) }
        d.hlsWorkers.clear()

        val tempDir = File(d.file.parentFile, ".${d.file.name}.parts").apply { mkdirs() }
        val initBytes = initUrl?.let { url ->
            runCatching {
                fetchBytes(d, url, initRange)
            }.getOrNull()
        }
        runCatching { d.file.delete() }

        val completedSegments = AtomicInteger(0)
        val bytesDone = AtomicLong(0L)
        val nextSegment = AtomicInteger(0)
        val workerTotal = ((segments.size + n - 1) / n).coerceAtLeast(1)
        // Segment files left by an earlier attempt are already decrypted and
        // can be reused, so pausing or a dropped connection never restarts a
        // multi-hour stream from zero.
        segments.forEachIndexed { index, _ ->
            val part = File(tempDir, "%05d.part".format(index))
            if (part.exists() && part.length() > 0L) {
                completedSegments.incrementAndGet()
                bytesDone.addAndGet(part.length())
            }
        }
        d.done = bytesDone.get()
        d.segmentDone = completedSegments.get()
        coroutineScope {
            (0 until n).map { worker ->
                launch {
                    var localDone = 0
                    while (true) {
                        val index = nextSegment.getAndIncrement()
                        if (index >= segments.size) break
                        currentCoroutineContext().ensureActive()
                        val part = File(tempDir, "%05d.part".format(index))
                        if (!part.exists() || part.length() == 0L) {
                            val rawBytes = fetchSegment(d, segments[index], keyCache, rateLimitUntil)
                            val bytes = if (initUrl == null) stripDisguisedTsPrefix(rawBytes) else rawBytes
                            val tmp = File(tempDir, "%05d.tmp".format(index))
                            tmp.writeBytes(bytes)
                            if (!tmp.renameTo(part)) {
                                part.writeBytes(bytes)
                                tmp.delete()
                            }
                            bytesDone.addAndGet(bytes.size.toLong())
                            d.done = bytesDone.get()
                            completedSegments.incrementAndGet()
                            d.segmentDone = completedSegments.get()
                        }
                        localDone++
                        d.hlsWorkers[worker] = (localDone.toFloat() / workerTotal).coerceIn(0f, 1f)
                    }
                }
            }.joinAll()
        }

        // If the master playlist specified a separate HLS audio rendition, fetch and mux it seamlessly!
        val audioFile: File? = audioPlaylistUrl?.let { aUrl ->
            runCatching {
                d.statusNote = "Downloading audio track…"
                val aText = fetchText(d, aUrl)
                val aSegs = mutableListOf<HlsSeg>()
                var aInitUrl: String? = null; var aInitRange: String? = null
                var aKeyUri: String? = null; var aKeyIv: String? = null
                var aSeq = 0L; var aPendRange: String? = null
                val aLastEnd = HashMap<String, Long>()
                for (raw in aText.lines()) {
                    val l = raw.trim()
                    if (l.isEmpty()) continue
                    when {
                        l.startsWith("#EXT-X-MEDIA-SEQUENCE") -> aSeq = l.substringAfter(':').trim().toLongOrNull() ?: 0L
                        l.startsWith("#EXT-X-MAP") -> {
                            val attrs = parseAttrs(l.substringAfter(':'))
                            attrs["URI"]?.let { aInitUrl = resolve(aUrl, it) }
                            aInitRange = attrs["BYTERANGE"]?.let { spec ->
                                val parts = spec.trim().split('@')
                                val len = parts[0].trim().toLongOrNull()
                                val off = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
                                if (len != null) "bytes=$off-${off + len - 1}" else null
                            }
                        }
                        l.startsWith("#EXT-X-KEY") -> {
                            val attrs = parseAttrs(l.substringAfter(':'))
                            if (attrs["METHOD"].equals("AES-128", true)) {
                                aKeyUri = attrs["URI"]?.let { resolve(aUrl, it) }
                                aKeyIv = attrs["IV"]
                            } else if (attrs["METHOD"].equals("NONE", true)) {
                                aKeyUri = null; aKeyIv = null
                            }
                        }
                        l.startsWith("#EXT-X-BYTERANGE") -> aPendRange = l.substringAfter(':').trim()
                        !l.startsWith("#") -> {
                            val su = resolve(aUrl, l)
                            val rng = aPendRange?.let { spec ->
                                val parts = spec.trim().split('@')
                                val len = parts[0].trim().toLongOrNull()
                                val off = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: (aLastEnd[su] ?: 0L)
                                if (len != null) { aLastEnd[su] = off + len; "bytes=$off-${off + len - 1}" } else null
                            }
                            aPendRange = null
                            aSegs.add(HlsSeg(su, rng, aKeyUri, aKeyIv, aSeq + aSegs.size))
                        }
                    }
                }
                if (aSegs.isEmpty()) return@runCatching null
                val aDir = File(tempDir, "audio").apply { mkdirs() }
                val aInit = aInitUrl?.let { u -> runCatching { fetchBytes(d, u, aInitRange) }.getOrNull() }
                val aNext = AtomicInteger(0)
                val aWorkers = min(n, aSegs.size).coerceAtLeast(1)
                coroutineScope {
                    (0 until aWorkers).map {
                        launch {
                            while (true) {
                                val idx = aNext.getAndIncrement()
                                if (idx >= aSegs.size) break
                                currentCoroutineContext().ensureActive()
                                val p = File(aDir, "%05d.part".format(idx))
                                if (!p.exists() || p.length() == 0L) {
                                    val bRaw = fetchSegment(d, aSegs[idx], keyCache, rateLimitUntil)
                                    val b = if (aInitUrl == null) stripDisguisedTsPrefix(bRaw) else bRaw
                                    p.writeBytes(b)
                                    bytesDone.addAndGet(b.size.toLong())
                                    d.done = bytesDone.get()
                                }
                            }
                        }
                    }.joinAll()
                }
                val mergedAudio = File(tempDir, "audio_track.m4a")
                mergedAudio.outputStream().buffered(256 * 1024).use { out ->
                    aInit?.let { out.write(it) }
                    for (i in aSegs.indices) {
                        val p = File(aDir, "%05d.part".format(i))
                        if (p.exists()) p.inputStream().use { it.copyTo(out, 256 * 1024) }
                    }
                }
                mergedAudio.takeIf { it.exists() && it.length() > 1024L }
            }.getOrNull()
        }

        val rawVideoFile = if (audioFile != null) File(tempDir, "video_track.mp4") else d.file
        rawVideoFile.outputStream().buffered(512 * 1024).use { out ->
            initBytes?.let { out.write(it) }
            for (i in segments.indices) {
                val part = File(tempDir, "%05d.part".format(i))
                if (!part.exists()) throw IllegalStateException("Segment ${i + 1} is missing")
                part.inputStream().use { it.copyTo(out, 512 * 1024) }
            }
        }
        if (audioFile != null) {
            d.statusNote = "Merging audio & video…"
            val mp4Target = if (d.file.extension.equals("ts", true)) {
                uniqueFile(d.file.parentFile, d.file.name.removeSuffix(".ts") + ".mp4")
            } else d.file
            val muxOk = runCatching {
                Remux.mux(rawVideoFile, audioFile, mp4Target, webm = false)
            }.isSuccess
            if (muxOk && mp4Target.exists() && mp4Target.length() > 2_048L) {
                if (mp4Target != d.file) {
                    runCatching { d.file.delete() }
                    d.file = mp4Target
                }
                d.mime = "video/mp4"
            } else {
                if (mp4Target != d.file) runCatching { mp4Target.delete() }
                rawVideoFile.copyTo(d.file, overwrite = true)
            }
            d.statusNote = null
        } else if (initUrl == null && d.file.extension.equals("ts", true)) {
            // Losslessly remux MPEG-TS -> standard seekable .mp4 (proper duration, thumbnails & fast seeking!)
            d.statusNote = "Converting to MP4…"
            val mp4Out = uniqueFile(d.file.parentFile, d.file.name.removeSuffix(".ts") + ".mp4")
            val ok = runCatching { Remux.remuxTsToMp4(d.file, mp4Out) }.isSuccess
            if (ok && mp4Out.exists() && mp4Out.length() > 2_048L) {
                runCatching { d.file.delete() }
                d.file = mp4Out
                d.mime = "video/mp4"
            } else {
                runCatching { mp4Out.delete() }
            }
            d.statusNote = null
        }
        runCatching { tempDir.deleteRecursively() }
        d.total = d.file.length()
        d.done = d.total
        d.segmentDone = segments.size
        d.partProgress.indices.forEach { d.partProgress[it] = 1f }
    }

    private fun fetchText(d: DlItem, url: String): String {
        http.newCall(requestFor(d, url)).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Playlist HTTP ${r.code}")
            return r.body?.string() ?: throw IllegalStateException("Empty playlist")
        }
    }

    private fun fetchBytes(d: DlItem, url: String, range: String? = null): ByteArray {
        hlsSegHttp.newCall(requestFor(d, url, range)).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            return r.body?.bytes() ?: throw IllegalStateException("Empty response")
        }
    }

    /**
     * Streaming CDNs (anime / movie embeds) frequently disguise MPEG-TS segments by prepending a fake
     * PNG, GIF, BMP, or random header before the first 0x47 MPEG-TS sync byte. Stripping that junk prefix
     * guarantees clean MediaMuxer MP4 remuxing and smooth seeking across every player.
     */
    private fun stripDisguisedTsPrefix(raw: ByteArray): ByteArray {
        if (raw.size < 376) return raw
        if ((raw[0].toInt() and 0xFF) == 0x47 && (raw[188].toInt() and 0xFF) == 0x47) return raw
        val limit = min(raw.size - 376, 2048)
        for (i in 1..limit) {
            if ((raw[i].toInt() and 0xFF) == 0x47 &&
                (raw[i + 188].toInt() and 0xFF) == 0x47 &&
                (i + 376 >= raw.size || (raw[i + 376].toInt() and 0xFF) == 0x47)
            ) {
                return raw.copyOfRange(i, raw.size)
            }
        }
        return raw
    }

    /** Fetches one segment (up to 7 attempts with shared 429/503 cooldown + HTML error rejection) and decrypts AES-128. */
    private suspend fun fetchSegment(
        d: DlItem,
        seg: HlsSeg,
        keyCache: ConcurrentHashMap<String, ByteArray>,
        rateLimitUntil: AtomicLong? = null
    ): ByteArray {
        var raw: ByteArray
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val waitMs = (rateLimitUntil?.get() ?: 0L) - System.currentTimeMillis()
            if (waitMs > 0L) delay(min(waitMs, 4_000L) + ((seg.seq * 31L) % 250L))
            try {
                raw = fetchBytes(d, seg.url, seg.range)
                if (raw.isEmpty()) throw IllegalStateException("Empty segment")
                if (seg.keyUri == null && raw.size < 65_536) {
                    val head = String(raw, 0, min(raw.size, 64), Charsets.ISO_8859_1).trimStart().lowercase()
                    if (head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("{\"error")) {
                        throw IllegalStateException("Server returned error page for segment")
                    }
                }
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                val isRateLimit = e.message?.contains("429") == true || e.message?.contains("503") == true
                if (isRateLimit) {
                    rateLimitUntil?.set(System.currentTimeMillis() + 1_600L)
                }
                if (attempt > (if (isRateLimit) 7 else 5)) throw e
                val baseMs = if (isRateLimit) 950L else 280L
                val backoff = min(if (isRateLimit) 6_000L else 3_000L, baseMs * (1L shl (attempt - 1).coerceAtMost(4)))
                delay(backoff + ((seg.seq * 47L) % 240L))
            }
        }
        val keyUri = seg.keyUri ?: return raw
        val key = keyCache[keyUri] ?: fetchBytes(d, keyUri).also { keyCache[keyUri] = it }
        return try {
            val iv = if (!seg.keyIv.isNullOrBlank()) hexBytes(seg.keyIv) else sequenceIv(seg.seq)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(raw)
        } catch (e: Exception) {
            throw IllegalStateException("Could not decrypt a stream segment")
        }
    }

    private fun sequenceIv(seq: Long): ByteArray {
        val iv = ByteArray(16)
        for (i in 0 until 8) iv[15 - i] = ((seq ushr (8 * i)) and 0xFF).toByte()
        return iv
    }

    private fun hexBytes(hex: String): ByteArray {
        val clean = hex.removePrefix("0x").removePrefix("0X").filter { it.isLetterOrDigit() }
        val padded = if (clean.length % 2 == 1) "0$clean" else clean
        val out = ByteArray(padded.length / 2)
        for (i in out.indices) {
            out[i] = padded.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        // An IV shorter than 16 bytes is left-padded with zeros.
        if (out.size >= 16) return out
        val full = ByteArray(16)
        System.arraycopy(out, 0, full, 16 - out.size, out.size)
        return full
    }

    private fun parseAttrs(input: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        HLS_ATTR_RE.findAll(input).forEach { m ->
            val key = m.groupValues[1]
            val value = m.groupValues[2].removeSurrounding("\"").trim()
            map[key] = value
        }
        return map
    }

    private fun resolve(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        return runCatching { URI(base).resolve(relative).toString() }.getOrDefault(relative)
    }

    private suspend fun tickerLoop(d: DlItem) {
        var lastDone = d.done
        var lastTime = System.currentTimeMillis()
        while (scope.coroutineContext.isActive) {
            delay(600)
            if (d.status != DlStatus.RUNNING) break
            if (d.kind == DlKind.HLS) {
                d.hlsWorkers.forEach { (i, p) ->
                    if (i in d.partProgress.indices) d.partProgress[i] = p
                }
            }
            val now = System.currentTimeMillis()
            val dt = (now - lastTime).coerceAtLeast(1)
            val instSpeed = ((d.done - lastDone).coerceAtLeast(0) * 1000L / dt)
            // Exponential moving average gives rock-steady IDM-style speed & accurate ETA
            d.speed = if (d.speed <= 0L) instSpeed else ((d.speed * 58L + instSpeed * 42L) / 100L)
            lastDone = d.done
            lastTime = now
            if (d.kind != DlKind.YTDLP) {
                val targetTotal = d.shownTotal
                d.eta = if (d.speed > 0 && targetTotal > d.done) (targetTotal - d.done) / d.speed else -1L
            }
            updateNotification()
        }
    }

    // ── Foreground service + notification ────────────────────────────────────
    private fun startService() {
        runCatching { DownloadService.start(ctx) }
    }

    private fun stopServiceIfIdle() {
        if (items.none { it.status == DlStatus.RUNNING }) {
            scope.launch {
                delay(1200)
                if (items.none { it.status == DlStatus.RUNNING }) {
                    runCatching { ctx.stopService(Intent(ctx, DownloadService::class.java)) }
                }
            }
        }
    }

    private fun updateNotification() {
        runCatching { DownloadService.update(ctx, items) }
    }

    companion object {
        /** The live manager: the notification buttons (pause / resume / cancel) act on it. */
        @Volatile var current: DownloadManager? = null

        fun trimSockets() { runCatching { current?.dlPool?.evictAll() } }

        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
        private const val MIN_PART_BYTES = 512L * 1024L
        private const val MIN_CHUNKED_BYTES = 1L * 1024L * 1024L
        /** Smart range engine: part size target, part count limits, connection limits. */
        private const val PART_TARGET_BYTES = 2L * 1024L * 1024L
        private const val MIN_PARTS = 4
        private const val MAX_PARTS = 48
        private const val MAX_PART_BYTES = 64L * 1024L * 1024L
        private const val SMART_MAX_CONN = 20
        private const val HLS_WORKERS = 16

        private val GENERIC_NAMES = setOf(
            "master", "index", "playlist", "manifest", "video", "videoplayback", "stream",
            "chunklist", "media", "download", "downloadfile", "file", "main", "hls"
        )

        private fun resolvedMime(d: DlItem): String {
            val ext = d.file.extension.lowercase()
            if (ext == "apk") return "application/vnd.android.package-archive"
            val cur = d.mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if (cur.isNotEmpty() && cur != "application/octet-stream" && cur != "binary/octet-stream") return cur
            val fromMap = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            if (!fromMap.isNullOrBlank()) return fromMap
            return URLConnection.guessContentTypeFromName(d.file.name) ?: "application/octet-stream"
        }

        fun open(ctx: Context, d: DlItem) {
            if (!d.file.exists()) return
            runCatching {
                val mime = resolvedMime(d)
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", d.file)
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                if (mime == "application/vnd.android.package-archive") {
                    ctx.startActivity(intent)
                } else {
                    ctx.startActivity(
                        Intent.createChooser(intent, "Open ${d.name}")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }.onFailure { share(ctx, d) }
        }

        /** Share several finished files in one go (single file falls back to [share]). */
        fun shareMany(ctx: Context, list: List<DlItem>) {
            val files = list.filter { it.file.exists() }
            if (files.isEmpty()) return
            if (files.size == 1) { share(ctx, files[0]); return }
            runCatching {
                val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it.file) })
                val mimes = files.map { resolvedMime(it) }
                val type = when {
                    mimes.distinct().size == 1 -> mimes[0]
                    mimes.all { it.startsWith("video/") } -> "video/*"
                    mimes.all { it.startsWith("audio/") } -> "audio/*"
                    mimes.all { it.startsWith("image/") } -> "image/*"
                    else -> "*/*"
                }
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setType(type)
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val clip = android.content.ClipData.newRawUri("", uris[0])
                for (i in 1 until uris.size) clip.addItem(android.content.ClipData.Item(uris[i]))
                intent.clipData = clip
                ctx.startActivity(
                    Intent.createChooser(intent, "Share ${files.size} files")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        fun share(ctx: Context, d: DlItem) {
            if (!d.file.exists()) return
            runCatching {
                val mime = resolvedMime(d)
                val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", d.file)
                val intent = Intent(Intent.ACTION_SEND)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData = android.content.ClipData.newRawUri("", uri)
                ctx.startActivity(
                    Intent.createChooser(intent, "Share ${d.name}")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        val action = intent?.action ?: return
        Haptics.fireDetached(Hx.TAP)
        val targetId = intent.getIntExtra(DownloadService.EXTRA_ITEM_ID, -1)
        val m = DownloadManager.current
        if (m == null) {
            if (action == DownloadService.ACTION_CANCEL || action == DownloadService.ACTION_RETRY) {
                runCatching {
                    val nm = ctx.getSystemService(NotificationManager::class.java)
                    nm.cancel(DownloadService.NOTIF_ID)
                    if (targetId >= 0) nm.cancel(DownloadService.doneNotifId(targetId))
                }
            }
            return
        }
        when (action) {
            DownloadService.ACTION_PAUSE -> m.pauseAll()
            DownloadService.ACTION_RESUME -> {
                m.items.filter { it.status == DlStatus.PAUSED }.forEach { m.resume(it) }
            }
            DownloadService.ACTION_RETRY -> {
                val item = m.items.firstOrNull { it.id == targetId }
                    ?: m.items.firstOrNull { it.status == DlStatus.FAILED }
                if (item != null) {
                    runCatching {
                        ctx.getSystemService(NotificationManager::class.java)
                            .cancel(DownloadService.doneNotifId(item.id))
                    }
                    m.resume(item)
                }
            }
            DownloadService.ACTION_CANCEL -> {
                val d = m.items.firstOrNull { it.id == targetId }
                    ?: m.items.firstOrNull { it.status == DlStatus.RUNNING }
                    ?: m.items.firstOrNull { it.status == DlStatus.PAUSED }
                if (d != null) m.cancel(d)
                else runCatching {
                    ctx.getSystemService(NotificationManager::class.java).cancel(DownloadService.NOTIF_ID)
                }
            }
        }
    }
}

class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build(this, DownloadManager.current?.items?.toList().orEmpty()) ?: buildStarting(this)
        val promoted = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, n)
            }
            true
        }.getOrDefault(false)
        if (!promoted) {
            runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n) }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        const val CHANNEL_LIVE = "aurex_downloads_live"
        const val CHANNEL_DONE = "aurex_downloads_done"
        const val NOTIF_ID = 4310
        const val EXTRA_ITEM_ID = "aurex_dl_id"

        const val ACTION_PAUSE = "com.aurex.browser.DL_PAUSE"
        const val ACTION_RESUME = "com.aurex.browser.DL_RESUME"
        const val ACTION_CANCEL = "com.aurex.browser.DL_CANCEL"
        const val ACTION_RETRY = "com.aurex.browser.DL_RETRY"

        private const val ACCENT = 0xFF0A84FF.toInt()
        private const val SEP = " · "
        @Volatile private var lastPostAt = 0L
        @Volatile private var lastSig = ""

        fun doneNotifId(itemId: Int): Int = 4320 + (itemId and 0x0F)

        fun start(ctx: Context) {
            val i = Intent(ctx, DownloadService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }.onFailure {
                runCatching {
                    ensureChannels(ctx)
                    val n = build(ctx, DownloadManager.current?.items?.toList().orEmpty()) ?: buildStarting(ctx)
                    ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n)
                }
            }
        }

        fun ensureChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_LIVE,
                    AppLang.translate("Active Downloads"),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = AppLang.translate("Live download progress and status-bar pill")
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_DONE,
                    AppLang.translate("Completed Downloads"),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = AppLang.translate("Alerts when a download finishes or fails")
                    setShowBadge(true)
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }

        private fun openDownloadsIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(MainActivity.EXTRA_OPEN, "downloads"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun actionIntent(ctx: Context, action: String, code: Int, itemId: Int = -1): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, code,
                Intent(ctx, DownloadActionReceiver::class.java)
                    .setAction(action)
                    .putExtra(EXTRA_ITEM_ID, itemId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun openFileIntent(ctx: Context, d: DlItem): PendingIntent {
            val mime = d.mime ?: URLConnection.guessContentTypeFromName(d.name) ?: "application/octet-stream"
            val actIntent = runCatching {
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", d.file)
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.getOrElse {
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(MainActivity.EXTRA_OPEN, "downloads")
            }
            return PendingIntent.getActivity(
                ctx, 500 + (d.id and 0xFF), actIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun shareFileIntent(ctx: Context, d: DlItem): PendingIntent {
            val mime = d.mime ?: URLConnection.guessContentTypeFromName(d.name) ?: "application/octet-stream"
            val share = runCatching {
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", d.file)
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND)
                        .setType(mime)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    AppLang.translate("Share")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }.getOrElse {
                Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "downloads")
            }
            return PendingIntent.getActivity(
                ctx, 800 + (d.id and 0xFF), share,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun base(ctx: Context, channel: String = CHANNEL_LIVE, ongoing: Boolean = true): NotificationCompat.Builder {
            ensureChannels(ctx)
            return NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_notif_download)
                .setColor(ACCENT)
                .setContentIntent(openDownloadsIntent(ctx))
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(!ongoing)
                .setCategory(if (ongoing) NotificationCompat.CATEGORY_PROGRESS else NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        private fun percent(d: DlItem): Int = (d.progress * 100).toInt().coerceIn(0, 100)

        private fun etaText(seconds: Long): String? = when {
            seconds <= 0L -> null
            seconds < 60L -> AppLang.translate("${seconds}s left")
            seconds < 3600L -> AppLang.translate("${(seconds + 30L) / 60L}m left")
            else -> AppLang.translate("${seconds / 3600L}h ${(seconds % 3600L) / 60L}m left")
        }

        private fun sizeText(d: DlItem): String = buildString {
            append(formatBytes(d.done))
            if (d.shownTotal > 0) {
                append(" / ").append(if (d.totalIsEstimate) "~" else "").append(formatBytes(d.shownTotal))
            }
        }

        private fun chipLabel(pct: Int, speedBps: Long, unknown: Boolean, paused: Boolean): String = when {
            paused -> "Paused"
            unknown && speedBps > 0L -> compactSpeed(speedBps)
            unknown -> "DL..."
            else -> "$pct%"
        }

        private fun compactSpeed(bps: Long): String = when {
            bps >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1fM", bps / (1024.0 * 1024.0))
            bps >= 1024L -> "${bps / 1024L}K"
            else -> "${bps}B"
        }

        private fun pageOf(d: DlItem): String =
            d.referer?.takeIf { it.startsWith("http") } ?: d.url

        private fun thumbReady(ctx: Context) {
            lastPostAt = 0L
            update(ctx, DownloadManager.current?.items?.toList().orEmpty())
        }

        private fun buildDownloadNotification(
            ctx: Context,
            pageUrl: String?,
            title: String,
            line: String,
            detail: String,
            subText: String?,
            chipText: String,
            progress: Int,
            indeterminate: Boolean,
            ongoing: Boolean,
            roundIcon: Int,
            primaryLabel: String,
            primaryPi: PendingIntent,
            secondaryLabel: String?,
            secondaryIcon: Int,
            secondaryPi: PendingIntent?
        ): Notification {
            ensureChannels(ctx)
            val card = NotifArt.card(ctx, pageUrl) { thumbReady(ctx) }

            if (NotifIsland.supportsAndroid16LiveUpdates) {
                val combinedLine = if (detail.isNotBlank() && !detail.contains('\n')) "$line$SEP$detail" else line
                NotifIsland.buildAndroid16LiveDownload(
                    ctx = ctx,
                    channelId = CHANNEL_LIVE,
                    openIntent = openDownloadsIntent(ctx),
                    title = title,
                    line = combinedLine,
                    subText = subText,
                    chipText = chipText,
                    thumb = card,
                    progress = progress,
                    indeterminate = indeterminate,
                    ongoing = ongoing,
                    accent = ACCENT,
                    primaryLabel = primaryLabel,
                    primaryIcon = roundIcon,
                    primaryPi = primaryPi,
                    secondaryLabel = secondaryLabel,
                    secondaryIcon = secondaryIcon,
                    secondaryPi = secondaryPi
                )?.let { return it }
            }

            val b = base(ctx, CHANNEL_LIVE, ongoing)
                .setContentTitle(title)
                .setContentText(line)
                .setSubText(subText)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(
                    NotifViews.small(ctx, card, title, line, progress, indeterminate, roundIcon, primaryPi)
                )
                .setCustomBigContentView(
                    NotifViews.big(
                        ctx, card, title, line, detail, progress, indeterminate,
                        NotifViews.Btn(primaryLabel, primaryPi),
                        if (secondaryLabel != null && secondaryPi != null) NotifViews.Btn(secondaryLabel, secondaryPi) else null
                    )
                )
            val extras = android.os.Bundle()
            NotifIsland.applyIslandExtras(ctx, extras, title, line, chipText, progress, indeterminate, ongoing, ACCENT)
            b.addExtras(extras)
            return b.build()
        }

        fun buildStarting(ctx: Context): Notification =
            buildDownloadNotification(
                ctx = ctx,
                pageUrl = null,
                title = AppLang.translate("Preparing download…"),
                line = AppLang.translate("Connecting to server"),
                detail = "",
                subText = null,
                chipText = "DL...",
                progress = -1,
                indeterminate = true,
                ongoing = true,
                roundIcon = R.drawable.ic_notif_close,
                primaryLabel = AppLang.translate("Cancel"),
                primaryPi = actionIntent(ctx, ACTION_CANCEL, 302),
                secondaryLabel = null,
                secondaryIcon = 0,
                secondaryPi = null
            )

        fun build(ctx: Context, items: List<DlItem>): Notification? {
            val running = items.filter { it.status == DlStatus.RUNNING }
            if (running.isNotEmpty()) return buildRunning(ctx, running)
            val paused = items.filter { it.status == DlStatus.PAUSED }
            if (paused.isNotEmpty()) return buildPaused(ctx, paused)
            return null
        }

        private fun buildRunning(ctx: Context, running: List<DlItem>): Notification {
            val pause = actionIntent(ctx, ACTION_PAUSE, 301)
            val pauseLabel = AppLang.translate("Pause")
            return if (running.size == 1) {
                val d = running.first()
                val pct = percent(d)
                val quality = MediaTools.playerText(d.qualityLabel)
                val note = d.statusNote?.takeIf { it.isNotBlank() }?.let { AppLang.translate(it) }
                val line = note ?: buildString {
                    append(pct).append('%')
                    if (d.speed > 0) append(SEP).append(formatSpeed(d.speed))
                    if (quality.isNotBlank()) append(SEP).append(quality)
                }
                val unknown = d.shownTotal <= 0 && d.pct < 0f && d.segmentTotal <= 0
                val eta = etaText(d.eta)
                val detail = buildString {
                    append(sizeText(d))
                    if (!eta.isNullOrBlank()) append(SEP).append(eta)
                }
                buildDownloadNotification(
                    ctx = ctx,
                    pageUrl = pageOf(d),
                    title = d.name,
                    line = line,
                    detail = detail,
                    subText = eta,
                    chipText = chipLabel(pct, d.speed, unknown, paused = false),
                    progress = pct,
                    indeterminate = unknown,
                    ongoing = true,
                    roundIcon = R.drawable.ic_pip_pause,
                    primaryLabel = pauseLabel,
                    primaryPi = pause,
                    secondaryLabel = AppLang.translate("Cancel"),
                    secondaryIcon = R.drawable.ic_notif_close,
                    secondaryPi = actionIntent(ctx, ACTION_CANCEL, 302, d.id)
                )
            } else {
                val avg = (running.map { it.progress }.average() * 100).toInt().coerceIn(0, 100)
                val speed = running.sumOf { it.speed }
                val line = buildString {
                    append(avg).append('%')
                    if (speed > 0) append(SEP).append(formatSpeed(speed))
                    append(SEP).append("${running.size} ").append(AppLang.translate("files"))
                }
                val names = running.take(2).joinToString("\n") { "${percent(it)}%  ${it.name}" } +
                    if (running.size > 2) "\n" + AppLang.translate("+${running.size - 2} more") else ""
                buildDownloadNotification(
                    ctx = ctx,
                    pageUrl = pageOf(running.first()),
                    title = AppLang.translate("Downloading ${running.size} files"),
                    line = line,
                    detail = names,
                    subText = "${running.size} active",
                    chipText = "$avg%",
                    progress = avg,
                    indeterminate = false,
                    ongoing = true,
                    roundIcon = R.drawable.ic_pip_pause,
                    primaryLabel = AppLang.translate("Pause all"),
                    primaryPi = pause,
                    secondaryLabel = null,
                    secondaryIcon = 0,
                    secondaryPi = null
                )
            }
        }

        private fun buildPaused(ctx: Context, paused: List<DlItem>): Notification {
            val resume = actionIntent(ctx, ACTION_RESUME, 303)
            return if (paused.size == 1) {
                val d = paused.first()
                val pct = percent(d)
                buildDownloadNotification(
                    ctx = ctx,
                    pageUrl = pageOf(d),
                    title = d.name,
                    line = AppLang.translate("Paused") + SEP + "$pct%",
                    detail = sizeText(d),
                    subText = AppLang.translate("Paused"),
                    chipText = "Paused",
                    progress = pct,
                    indeterminate = false,
                    ongoing = false,
                    roundIcon = R.drawable.ic_pip_play,
                    primaryLabel = AppLang.translate("Resume"),
                    primaryPi = resume,
                    secondaryLabel = AppLang.translate("Cancel"),
                    secondaryIcon = R.drawable.ic_notif_close,
                    secondaryPi = actionIntent(ctx, ACTION_CANCEL, 302, d.id)
                )
            } else {
                buildDownloadNotification(
                    ctx = ctx,
                    pageUrl = pageOf(paused.first()),
                    title = AppLang.translate("${paused.size} downloads paused"),
                    line = AppLang.translate("Tap Resume to continue"),
                    detail = "",
                    subText = AppLang.translate("Paused"),
                    chipText = "Paused",
                    progress = -1,
                    indeterminate = false,
                    ongoing = false,
                    roundIcon = R.drawable.ic_pip_play,
                    primaryLabel = AppLang.translate("Resume all"),
                    primaryPi = resume,
                    secondaryLabel = null,
                    secondaryIcon = 0,
                    secondaryPi = null
                )
            }
        }

        fun notifyFinished(ctx: Context, d: DlItem) {
            ensureChannels(ctx)
            val quality = MediaTools.playerText(d.qualityLabel)
            val sub = buildString {
                append(AppLang.translate("Download complete"))
                if (d.file.exists() && d.file.length() > 0) append(SEP).append(formatBytes(d.file.length()))
                if (quality.isNotBlank()) append(SEP).append(quality)
            }
            val thumb = NotifArt.card(ctx, pageOf(d)) {}
            val openPi = openFileIntent(ctx, d)
            val sharePi = shareFileIntent(ctx, d)
            val n = base(ctx, CHANNEL_DONE, ongoing = false)
                .setSmallIcon(R.drawable.ic_notif_check)
                .setLargeIcon(thumb)
                .setContentTitle(d.name)
                .setContentText(sub)
                .setContentIntent(openPi)
                .setAutoCancel(true)
                .addAction(R.drawable.ic_notif_check, AppLang.translate("Open"), openPi)
                .addAction(R.drawable.ic_notif_share, AppLang.translate("Share"), sharePi)
                .build()
            runCatching {
                ctx.getSystemService(NotificationManager::class.java).notify(doneNotifId(d.id), n)
            }
        }

        fun notifyFailed(ctx: Context, d: DlItem) {
            ensureChannels(ctx)
            val retryPi = actionIntent(ctx, ACTION_RETRY, 600 + (d.id and 0xFF), d.id)
            val reason = d.error?.take(90) ?: AppLang.translate("Download failed")
            val n = base(ctx, CHANNEL_DONE, ongoing = false)
                .setSmallIcon(R.drawable.ic_notif_close)
                .setColor(0xFFFF453A.toInt())
                .setContentTitle(d.name)
                .setContentText(reason)
                .setAutoCancel(true)
                .addAction(R.drawable.ic_notif_retry, AppLang.translate("Retry"), retryPi)
                .build()
            runCatching {
                ctx.getSystemService(NotificationManager::class.java).notify(doneNotifId(d.id), n)
            }
        }

        fun update(ctx: Context, items: List<DlItem>) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            val n = build(ctx, items)
            if (n == null) {
                lastSig = ""
                runCatching { nm.cancel(NOTIF_ID) }
                return
            }
            val running = items.filter { it.status == DlStatus.RUNNING }
            val paused = items.filter { it.status == DlStatus.PAUSED }
            val sig = "${running.size}|${paused.size}|${running.firstOrNull()?.let { percent(it) } ?: -1}|${running.firstOrNull()?.statusNote.orEmpty()}"
            val now = android.os.SystemClock.uptimeMillis()
            if (sig == lastSig && now - lastPostAt < 450L) return
            lastSig = sig
            lastPostAt = now
            runCatching { nm.notify(NOTIF_ID, n) }
        }
    }
}
