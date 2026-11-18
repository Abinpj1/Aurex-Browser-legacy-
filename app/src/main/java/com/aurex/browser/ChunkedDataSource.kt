package com.aurex.browser

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * YouTube's CDN (googlevideo.com) throttles a single long, open-ended range request: after the first burst the
 * download drops to a trickle, which is exactly what makes 2K / 4K stop and buffer even on a fast connection.
 * yt-dlp itself downloads YouTube in 10 MB ranges for that reason. This wrapper does the same for the player:
 * the caller sees one continuous stream, while the CDN sees a series of [chunkBytes] ranged requests.
 *
 * Anything that is not a plain GET to googlevideo.com, and any server that does not report the total size
 * (Content-Range), goes through completely untouched.
 */
class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkBytes: Long = DEFAULT_CHUNK
) : DataSource {

    private val unset = C.LENGTH_UNSET.toLong()
    private var spec: DataSpec? = null
    private var lastUri: Uri? = null
    private var chunked = false
    private var upOpen = false
    private var pos = 0L          // next absolute byte handed to the caller
    private var reqEnd = unset    // exclusive end the caller asked for (unset = to the end of the file)
    private var total = unset     // size of the whole file once the CDN told it
    private var gotInChunk = false // the chunk that is open right now has delivered at least one byte

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        lastUri = dataSpec.uri
        chunked = dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET &&
            (dataSpec.uri.host ?: "").endsWith("googlevideo.com")
        if (!chunked) {
            upOpen = true
            return upstream.open(dataSpec)
        }
        pos = dataSpec.position
        reqEnd = if (dataSpec.length == unset) unset else pos + dataSpec.length
        total = unset
        openChunk()
        if (total == unset) {
            // the CDN did not say how big the file is: chunking cannot be done safely, read the normal way
            upstream.close()
            upOpen = false
            chunked = false
            upOpen = true
            return upstream.open(dataSpec)
        }
        val end = if (reqEnd == unset) total else minOf(reqEnd, total)
        return (end - pos).coerceAtLeast(0L)
    }

    private fun openChunk() {
        val s = spec ?: return
        var len = chunkBytes
        if (reqEnd != unset) len = minOf(len, reqEnd - pos)
        if (total != unset) len = minOf(len, total - pos)
        val d = s.buildUpon().setPosition(pos).setLength(len.coerceAtLeast(1L)).build()
        upstream.open(d)
        upOpen = true
        gotInChunk = false
        if (total == unset) {
            // "Content-Range: bytes 0-8388607/123456789" -> 123456789
            val cr = upstream.responseHeaders.entries
                .firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull()
            cr?.substringAfterLast('/')?.trim()?.toLongOrNull()?.let { total = it }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!chunked) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        while (true) {
            if (!upOpen) {
                if ((reqEnd != unset && pos >= reqEnd) || pos >= total) return C.RESULT_END_OF_INPUT
                openChunk()
            }
            val n = upstream.read(buffer, offset, length)
            if (n == C.RESULT_END_OF_INPUT) {
                upstream.close()
                upOpen = false
                // A range that ends without a single byte (misbehaving CDN / proxy) would be re-requested forever in a tight
                // loop. Fail it as an I/O error instead: the player's normal retry + back-off takes over.
                if (!gotInChunk && !((reqEnd != unset && pos >= reqEnd) || pos >= total)) {
                    throw java.io.IOException("Empty range response at byte $pos")
                }
                if ((reqEnd != unset && pos >= reqEnd) || pos >= total) return C.RESULT_END_OF_INPUT
                continue                      // this chunk is used up: the next range continues seamlessly
            }
            pos += n
            gotInChunk = true
            return n
        }
    }

    override fun getUri(): Uri? = if (chunked) (upstream.uri ?: lastUri) else upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        spec = null
        if (upOpen) upstream.close()
        upOpen = false
    }

    companion object {
        /** Below YouTube's ~10 MB throttle threshold, large enough that a 4K stream makes only one request every few seconds. */
        const val DEFAULT_CHUNK = 8L * 1024 * 1024
    }
}
