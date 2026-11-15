package com.aurex.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Real website icons with a three-tier cache: memory, disk, network.
 * Network order: origin favicon.ico -> DuckDuckGo -> Google s2.
 */
object Favicons {
    private val memory = LruCache<String, Bitmap>(160)
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private fun key(host: String): String =
        host.lowercase().removePrefix("www.").substringBefore(':').trim('.', ' ')

    fun cached(host: String): Bitmap? = memory.get(key(host))

    suspend fun load(context: android.content.Context, host: String): Bitmap? {
        val h = key(host)
        if (h.isBlank() || !h.contains('.')) return null
        memory.get(h)?.let { return it }
        if (h in failed) return null
        return withContext(Dispatchers.IO) {
            memory.get(h)?.let { return@withContext it }
            val disk = File(context.cacheDir, "favicon_${h.hashCode()}.img")
            if (disk.exists()) {
                val bmp = runCatching { BitmapFactory.decodeFile(disk.absolutePath) }.getOrNull()
                if (bmp != null) {
                    memory.put(h, bmp)
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
                    memory.put(h, bmp)
                    runCatching {
                        disk.outputStream().use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
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
                body.byteStream().use { input ->
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    BitmapFactory.decodeStream(input, null, opts)
                }
            }
        }.getOrNull()
    }

}
