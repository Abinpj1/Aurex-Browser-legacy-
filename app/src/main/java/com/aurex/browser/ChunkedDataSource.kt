package com.aurex.browser

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-speed pooled OkHttp DataSource for ExoPlayer / Media3 (replaces legacy HttpURLConnection):
 *  - Reuses warm TCP + TLS sessions across chunks, HLS/DASH segments, audio+video tracks, and quality switches.
 *  - Supports HTTP/2 multiplexing & HTTP/1.1 keep-alive out of the box.
 *  - Cancels in-flight sockets IMMEDIATELY on timeline seek / close (zero scrub stall!).
 */
class OkHttpStreamDataSource(
    private val defaultHeaders: Map<String, String> = emptyMap(),
    private val userAgent: String? = null
) : BaseDataSource(/* isNetwork = */ true) {

    private var currentCall: Call? = null
    private var currentResponse: Response? = null
    private var responseStream: InputStream? = null
    private var opened = false
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var lastUri: Uri? = null
    private var respHeaders: Map<String, List<String>> = emptyMap()

    override fun open(dataSpec: DataSpec): Long {
        lastUri = dataSpec.uri
        transferInitializing(dataSpec)

        val rb = Request.Builder().url(dataSpec.uri.toString())
        val ua = userAgent?.takeIf { it.isNotBlank() } ?: defaultHeaders["User-Agent"]
        if (!ua.isNullOrBlank()) rb.header("User-Agent", ua)
        for ((k, v) in defaultHeaders) {
            if (k.equals("User-Agent", true)) continue
            rb.header(k, v)
        }
        for ((k, v) in dataSpec.httpRequestHeaders) {
            rb.header(k, v)
        }
        if (!dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)) {
            rb.header("Accept-Encoding", "identity")
        }
        if (dataSpec.position > 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
            val start = dataSpec.position.coerceAtLeast(0L)
            val range = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                "bytes=$start-${start + dataSpec.length - 1L}"
            } else {
                "bytes=$start-"
            }
            rb.header("Range", range)
        }
        if (dataSpec.httpMethod == DataSpec.HTTP_METHOD_HEAD) {
            rb.head()
        } else {
            rb.get()
        }

        val call = streamClient.newCall(rb.build())
        currentCall = call
        val response = try {
            call.execute()
        } catch (e: IOException) {
            closeQuietly()
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )
        }
        currentResponse = response
        respHeaders = response.headers.toMultimap()
        lastUri = Uri.parse(response.request.url.toString())

        val code = response.code
        if (code < 200 || code > 299) {
            val bodyBytes = runCatching { response.body?.bytes() ?: ByteArray(0) }.getOrDefault(ByteArray(0))
            closeQuietly()
            throw HttpDataSource.InvalidResponseCodeException(
                code, response.message, null, respHeaders, dataSpec, bodyBytes
            )
        }

        val body = response.body ?: run {
            closeQuietly()
            throw HttpDataSource.HttpDataSourceException(
                "Empty HTTP body", dataSpec,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )
        }
        val ins = body.byteStream()
        var contentLen = body.contentLength()
        // If server ignored Range and returned 200 from byte 0, skip cleanly to dataSpec.position
        if (code == 200 && dataSpec.position > 0L) {
            var toSkip = dataSpec.position
            val scratch = ByteArray(8192)
            while (toSkip > 0L) {
                val r = ins.read(scratch, 0, minOf(toSkip, scratch.size.toLong()).toInt())
                if (r < 0) break
                toSkip -= r
            }
            if (contentLen >= 0L) contentLen = (contentLen - dataSpec.position).coerceAtLeast(0L)
        }
        responseStream = ins
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            contentLen >= 0L -> contentLen
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val stream = responseStream ?: return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), bytesRemaining).toInt()
        val n = try {
            stream.read(buffer, offset, toRead)
        } catch (e: IOException) {
            throw IOException("Stream read failed: ${e.message}", e)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = lastUri

    override fun getResponseHeaders(): Map<String, List<String>> = respHeaders

    override fun close() {
        val wasOpen = opened
        opened = false
        closeQuietly()
        if (wasOpen) transferEnded()
    }

    private fun closeQuietly() {
        runCatching { currentCall?.cancel() }
        currentCall = null
        runCatching { responseStream?.close() }
        responseStream = null
        runCatching { currentResponse?.close() }
        currentResponse = null
    }

    class Factory(
        private val headers: Map<String, String>,
        private val userAgent: String? = headers["User-Agent"]
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = OkHttpStreamDataSource(headers, userAgent)
    }

    companion object {
        private val streamPool = ConnectionPool(
            if (PerfTier.low) 6 else 16,
            3,
            TimeUnit.MINUTES
        )

        val streamClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectionPool(streamPool)
                .dispatcher(Dispatcher().apply {
                    maxRequests = if (PerfTier.low) 16 else 48
                    maxRequestsPerHost = if (PerfTier.low) 8 else 24
                })
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .build()
        }

        fun trim() {
            runCatching { streamPool.evictAll() }
        }
    }
}

/**
 * High-speed adaptive chunked + double-buffered read-ahead HTTP DataSource for online streaming:
 *  - Splits open-ended streams on throttled video CDNs (YouTube, Meta, TikTok, Reddit) into fast-start
 *    ranged chunks (1 MB -> 2.5 MB -> 6 MB) to bypass single-stream bandwidth throttling.
 *  - Background read-ahead prefetch triggers at 30% of the current chunk over a warm pooled OkHttp socket
 *    so chunk boundaries have 0 ms stall on 2K/4K streams.
 *  - Transparent per-chunk retry absorbs brief mobile/Wi-Fi hiccups without re-preparing ExoPlayer.
 */
class ChunkedDataSource(
    private val upstream: DataSource,
    private val prefetchFactory: DataSource.Factory? = null,
    private val chunkBytes: Long = DEFAULT_CHUNK
) : DataSource {

    private val unset = C.LENGTH_UNSET.toLong()
    private var spec: DataSpec? = null
    private var lastUri: Uri? = null
    private var chunked = false
    private var upOpen = false
    private var pos = 0L
    private var reqEnd = unset
    private var total = unset
    private var gotInChunk = false
    private var chunksDone = 0
    private var curChunkStart = 0L
    private var curChunkEnd = 0L

    private var memBuf: ByteArray? = null
    private var memPos = 0
    private var memLen = 0

    private var prefFuture: Future<PrefetchedChunk?>? = null
    private var prefStart = -1L
    private var prefCancel: AtomicBoolean? = null

    private class PrefetchedChunk(val start: Long, val data: ByteArray, val length: Int)

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    private fun shouldChunkHost(uri: Uri): Boolean {
        val host = (uri.host ?: "").lowercase()
        val path = (uri.path ?: "").lowercase()
        if (path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".vtt") || path.endsWith(".srt")) {
            return false
        }
        return THROTTLED_CDNS.any { host == it || host.endsWith(".$it") }
    }

    override fun open(dataSpec: DataSpec): Long {
        cancelPrefetch()
        clearMemBuf()
        spec = dataSpec
        lastUri = dataSpec.uri
        chunksDone = 0
        chunked = dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET && shouldChunkHost(dataSpec.uri)
        if (!chunked) {
            upOpen = true
            return upstream.open(dataSpec)
        }
        pos = dataSpec.position
        reqEnd = if (dataSpec.length == unset) unset else pos + dataSpec.length
        total = unset
        openChunkWithRetry()
        if (total == unset) {
            runCatching { upstream.close() }
            upOpen = false
            chunked = false
            upOpen = true
            return upstream.open(dataSpec)
        }
        val end = if (reqEnd == unset) total else minOf(reqEnd, total)
        return (end - pos).coerceAtLeast(0L)
    }

    private fun currentChunkSize(index: Int): Long = when (index) {
        0 -> minOf(INITIAL_CHUNK, chunkBytes)
        1 -> minOf(SECOND_CHUNK, chunkBytes)
        else -> chunkBytes
    }

    private fun maxPrefetchBytes(): Long = when {
        PerfTier.low || prefetchFactory == null -> 0L
        PerfTier.balanced -> 2L * 1024 * 1024
        else -> 4L * 1024 * 1024
    }

    private fun clearMemBuf() {
        memBuf = null
        memPos = 0
        memLen = 0
    }

    private fun cancelPrefetch() {
        prefCancel?.set(true)
        prefFuture?.cancel(true)
        prefFuture = null
        prefCancel = null
        prefStart = -1L
    }

    private fun maybeStartPrefetch(nextPos: Long) {
        val cap = maxPrefetchBytes()
        val factory = prefetchFactory ?: return
        val s = spec ?: return
        if (cap <= 0L || total == unset || prefFuture != null) return
        val limit = if (reqEnd == unset) total else minOf(reqEnd, total)
        if (nextPos >= limit) return
        val wantLen = minOf(currentChunkSize(chunksDone + 1), cap, limit - nextPos)
        if (wantLen < 64L * 1024L) return

        val cancelFlag = AtomicBoolean(false)
        prefCancel = cancelFlag
        prefStart = nextPos
        val reqSpec = s.buildUpon().setPosition(nextPos).setLength(wantLen).build()
        prefFuture = runCatching {
            prefetchExec.submit<PrefetchedChunk?> {
                if (cancelFlag.get()) return@submit null
                val ds = factory.createDataSource()
                try {
                    ds.open(reqSpec)
                    val out = ByteArray(wantLen.toInt())
                    var got = 0
                    while (got < out.size && !cancelFlag.get() && !Thread.currentThread().isInterrupted) {
                        val k = ds.read(out, got, out.size - got)
                        if (k == C.RESULT_END_OF_INPUT) break
                        if (k > 0) got += k
                    }
                    if (cancelFlag.get() || got <= 0) null else PrefetchedChunk(nextPos, out, got)
                } catch (_: Throwable) {
                    null
                } finally {
                    runCatching { ds.close() }
                }
            }
        }.getOrNull()
    }

    private fun tryConsumePrefetch(): Boolean {
        val f = prefFuture ?: return false
        val expected = prefStart
        prefFuture = null
        prefStart = -1L
        prefCancel = null
        if (expected != pos) {
            f.cancel(true)
            return false
        }
        val res = runCatching { f.get() }.getOrNull() ?: return false
        if (res.start != pos || res.length <= 0) return false
        memBuf = res.data
        memPos = 0
        memLen = res.length
        curChunkStart = pos
        curChunkEnd = pos + res.length
        gotInChunk = true
        return true
    }

    private fun openChunkWithRetry() {
        var attempt = 0
        while (true) {
            try {
                openChunkOnce()
                return
            } catch (e: IOException) {
                runCatching { if (upOpen) upstream.close() }
                upOpen = false
                if (++attempt > MAX_CHUNK_RETRIES || Thread.currentThread().isInterrupted) throw e
                try {
                    Thread.sleep(180L * (1L shl (attempt - 1)))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
    }

    private fun openChunkOnce() {
        val s = spec ?: return
        var len = currentChunkSize(chunksDone)
        if (reqEnd != unset) len = minOf(len, reqEnd - pos)
        if (total != unset) len = minOf(len, total - pos)
        val safeLen = len.coerceAtLeast(1L)
        val d = s.buildUpon().setPosition(pos).setLength(safeLen).build()
        upstream.open(d)
        upOpen = true
        gotInChunk = false
        curChunkStart = pos
        curChunkEnd = pos + safeLen
        if (total == unset) {
            val cr = upstream.responseHeaders.entries
                .firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull()
            cr?.substringAfterLast('/')?.trim()?.toLongOrNull()?.let { total = it }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!chunked) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        var ioRetries = 0
        while (true) {
            val mb = memBuf
            if (mb != null) {
                val avail = memLen - memPos
                if (avail > 0) {
                    val take = minOf(length, avail)
                    System.arraycopy(mb, memPos, buffer, offset, take)
                    memPos += take
                    pos += take
                    if (memPos >= (memLen * 3) / 10) maybeStartPrefetch(curChunkEnd)
                    return take
                }
                clearMemBuf()
                chunksDone++
                if ((reqEnd != unset && pos >= reqEnd) || pos >= total) return C.RESULT_END_OF_INPUT
            }

            if (!upOpen) {
                if ((reqEnd != unset && pos >= reqEnd) || pos >= total) return C.RESULT_END_OF_INPUT
                if (tryConsumePrefetch()) continue
                openChunkWithRetry()
            }

            // Start prefetching chunk N+1 at 30% of chunk N for zero-stall transitions!
            val chunkLen = (curChunkEnd - curChunkStart).coerceAtLeast(1L)
            if (prefFuture == null && (pos - curChunkStart) * 10L >= chunkLen * 3L) {
                maybeStartPrefetch(curChunkEnd)
            }

            val n = try {
                upstream.read(buffer, offset, length)
            } catch (e: IOException) {
                runCatching { upstream.close() }
                upOpen = false
                if (++ioRetries > MAX_CHUNK_RETRIES || Thread.currentThread().isInterrupted) throw e
                try {
                    Thread.sleep(180L * (1L shl (ioRetries - 1)))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
                continue
            }
            if (n == C.RESULT_END_OF_INPUT) {
                runCatching { upstream.close() }
                upOpen = false
                if (!gotInChunk && !((reqEnd != unset && pos >= reqEnd) || pos >= total)) {
                    throw IOException("Empty range response at byte $pos")
                }
                chunksDone++
                if ((reqEnd != unset && pos >= reqEnd) || pos >= total) return C.RESULT_END_OF_INPUT
                continue
            }
            pos += n
            gotInChunk = true
            return n
        }
    }

    override fun getUri(): Uri? = if (chunked) (upstream.uri ?: lastUri) else upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        cancelPrefetch()
        clearMemBuf()
        spec = null
        if (upOpen) runCatching { upstream.close() }
        upOpen = false
    }

    companion object {
        /** Fast 1 MB first chunk -> 2.5 MB second -> 6 MB steady-state for instant first frame & zero throttling. */
        const val INITIAL_CHUNK = 1_024L * 1024
        const val SECOND_CHUNK = 2_560L * 1024
        const val DEFAULT_CHUNK = 6L * 1024 * 1024
        private const val MAX_CHUNK_RETRIES = 3

        private val THROTTLED_CDNS = arrayOf(
            "googlevideo.com",
            "fbcdn.net",
            "cdninstagram.com",
            "tiktokcdn.com",
            "v.redd.it"
        )

        private val prefetchExec by lazy {
            Executors.newFixedThreadPool(2, ThreadFactory { r ->
                Thread(r, "aurex-stream-prefetch").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1
                }
            })
        }
    }
}
