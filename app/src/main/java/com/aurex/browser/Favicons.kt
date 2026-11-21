package com.aurex.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Real website icons with a three-tier cache: memory, disk, network.
 * Network order: origin favicon.ico -> DuckDuckGo -> Google s2.
 * All decoded bitmaps are bounded to <= 96x96 px (~36 KB max) and trimmed automatically on memory pressure.
 */
object Favicons {
    private const val MAX_ICON_PX = 96
    private val memory = LruCache<String, Bitmap>(160)
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val client by lazy { AurexHttp.shared.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build() }

    private fun key(host: String): String =
        host.lowercase().removePrefix("www.").substringBefore(':').trim('.', ' ')

    private fun maxEntries(): Int = when (PerfTier.tier) {
        PerfTier.TIER_LOW -> 40
        PerfTier.TIER_BALANCED -> 90
        else -> 160
    }

    private fun putMemory(h: String, bmp: Bitmap) {
        memory.put(h, bmp)
        val cap = maxEntries()
        if (memory.size() > cap) memory.trimToSize(cap)
    }

    fun trim(aggressive: Boolean = false) {
        if (aggressive) {
            memory.evictAll()
            failed.clear()
        } else {
            memory.trimToSize(maxEntries() / 2)
        }
    }

    fun cached(host: String): Bitmap? = memory.get(key(host))

    private fun decodeBounded(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / (sample * 2) >= MAX_ICON_PX && h / (sample * 2) >= MAX_ICON_PX) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val maxSide = maxOf(bmp.width, bmp.height)
        if (maxSide <= MAX_ICON_PX) return bmp
        val scale = MAX_ICON_PX.toFloat() / maxSide.toFloat()
        val sw = (bmp.width * scale).toInt().coerceAtLeast(1)
        val sh = (bmp.height * scale).toInt().coerceAtLeast(1)
        return runCatching { Bitmap.createScaledBitmap(bmp, sw, sh, true) }.getOrDefault(bmp)
    }

    suspend fun load(context: android.content.Context, host: String): Bitmap? {
        val h = key(host)
        if (h.isBlank() || !h.contains('.')) return null
        memory.get(h)?.let { return it }
        if (h in failed) return null
        return withContext(Dispatchers.IO) {
            memory.get(h)?.let { return@withContext it }
            val disk = File(context.cacheDir, "favicon_${h.hashCode()}.img")
            if (disk.exists()) {
                val bmp = runCatching { decodeBounded(disk.readBytes()) }.getOrNull()
                if (bmp != null) {
                    putMemory(h, bmp)
                    return@withContext bmp
                }
            }
            val sources = listOf(
                "https://$h/favicon.ico",
                "https://icons.duckduckgo.com/ip3/$h.ico",
                "https://www.google.com/s2/favicons?domain=$h&sz=64"
            )
            for (url in sources) {
                val bmp = fetch(url)
                if (bmp != null) {
                    putMemory(h, bmp)
                    runCatching {
                        disk.outputStream().use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 85, out)
                        }
                    }
                    return@withContext bmp
                }
            }
            failed.add(h)
            null
        }
    }

    private fun fetch(url: String): Bitmap? {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                .header("Accept", "image/avif,image/webp,image/apng,image/png,image/x-icon,image/*,*/*;q=0.8")
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val body = r.body ?: return@use null
                if (body.contentLength() > 2_000_000L) return@use null
                val bytes = body.bytes()
                if (bytes.size > 2_000_000) return@use null
                decodeBounded(bytes)
            }
        }.getOrNull()
    }
}
