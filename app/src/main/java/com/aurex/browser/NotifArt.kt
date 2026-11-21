package com.aurex.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.util.LruCache
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Thumbnail for the download / media notifications: the video's own picture as a rounded 16:9 card.
 *
 *  - YouTube link  -> official i.ytimg.com thumbnail.
 *  - any other page -> the page's og:image / twitter:image (what every site shows when its link is shared).
 *  - nothing found (yet) -> a calm dark card with the Aurex glyph.
 *
 * [card] never blocks: it returns what is cached right now and, when a picture is still missing,
 * loads it in the background and calls [onReady] so the caller can refresh its notification.
 */
object NotifArt {
    const val W = 480
    const val H = 270
    private const val RADIUS = 40f
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    private val cards = LruCache<String, Bitmap>(8)
    private val squares = LruCache<String, Bitmap>(8)
    private val loading: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val io = Executors.newFixedThreadPool(2)
    @Volatile private var fallback: Bitmap? = null
    @Volatile private var fallbackSquare: Bitmap? = null

    private fun keyOf(url: String): String = YtThumb.idOf(url)?.let { "yt:$it" } ?: url

    /** True when the real picture of [pageUrl] is already loaded (the calm placeholder card does not count). */
    fun has(pageUrl: String?): Boolean {
        val url = pageUrl.orEmpty().trim().substringBefore('#')
        if (!(url.startsWith("http") || url.startsWith("file:", true))) return false
        return cards.get(keyOf(url)) != null
    }

    fun card(ctx: Context, pageUrl: String?, onReady: () -> Unit): Bitmap {
        val url = pageUrl.orEmpty().trim().substringBefore('#')
        if (url.startsWith("http") || url.startsWith("file:", true)) {
            val key = keyOf(url)
            cards.get(key)?.let { return it }
            val failed = failedAt[key]
            val retryOk = failed == null || System.currentTimeMillis() - failed > 90_000L
            if (retryOk && loading.add(key)) {
                io.execute {
                    val raw = runCatching { loadThumb(url) }.getOrNull()
                    val bmp = raw?.let { runCatching { bake(it) }.getOrNull() }
                    val sq = raw?.let { runCatching { bakeSquare(it) }.getOrNull() }
                    loading.remove(key)
                    if (bmp != null) {
                        cards.put(key, bmp)
                        if (sq != null) squares.put(key, sq)
                        runCatching { onReady() }
                    } else {
                        failedAt[key] = System.currentTimeMillis()
                    }
                }
            }
        }
        return fallbackCard(ctx)
    }

    /** Full-bleed 512x512 square artwork for native Notification.MediaStyle & OS Dynamic Islands. */
    fun mediaArt(ctx: Context, pageUrl: String?, onReady: () -> Unit): Bitmap {
        val url = pageUrl.orEmpty().trim().substringBefore('#')
        if (url.startsWith("http") || url.startsWith("file:", true)) {
            val key = keyOf(url)
            squares.get(key)?.let { return it }
            card(ctx, pageUrl, onReady)
            squares.get(key)?.let { return it }
        }
        return fallbackSquareCard(ctx)
    }

    // ── Loading ──────────────────────────────────────────────────────────────

    /** A downloaded video: its own first picture. */
    private fun fileFrame(url: String): Bitmap? {
        val path = Uri.parse(url).path ?: return null
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            val dur = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            r.getFrameAtTime((dur * 1000L / 10L).coerceAtLeast(0L), android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0L)
        } catch (_: Throwable) { null } finally { runCatching { r.release() } }
    }

    private fun loadThumb(url: String): Bitmap? {
        if (url.startsWith("file:", true)) return fileFrame(url)
        YtThumb.idOf(url)?.let { return YtThumb.download(it) }
        val html = httpHtml(url) ?: return null
        val img = ogImage(html, url) ?: return null
        return httpBitmap(img)
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6_000
        c.readTimeout = 8_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "en")
        return c
    }

    /** First ~300 KB of an HTML page (the <head> is all that is needed); null for anything that is not HTML. */
    private fun httpHtml(url: String): String? {
        val c = runCatching { open(url) }.getOrNull() ?: return null
        return try {
            if (c.responseCode != 200) return null
            val type = c.contentType.orEmpty().lowercase()
            if (!type.contains("html")) return null
            c.inputStream.use { ins ->
                val buf = ByteArray(300 * 1024)
                var n = 0
                while (n < buf.size) {
                    val r = ins.read(buf, n, buf.size - n)
                    if (r <= 0) break
                    n += r
                }
                String(buf, 0, n, Charsets.UTF_8)
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { c.disconnect() }
        }
    }

    private fun httpBitmap(url: String): Bitmap? {
        val c = runCatching { open(url) }.getOrNull() ?: return null
        return try {
            if (c.responseCode != 200) null
            else c.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { c.disconnect() }
        }
    }

    private val META_RX = Regex("<meta\\s[^>]*>", RegexOption.IGNORE_CASE)
    private val KEY_RX = Regex("(?:property|name)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val CONTENT_RX = Regex("content\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)

    private fun ogImage(html: String, base: String): String? {
        var twitter: String? = null
        for (m in META_RX.findAll(html)) {
            val tag = m.value
            val key = KEY_RX.find(tag)?.groupValues?.get(1)?.lowercase() ?: continue
            val content = CONTENT_RX.find(tag)?.groupValues?.get(1)?.trim().orEmpty()
            if (content.isEmpty()) continue
            val abs = absolute(base, content.replace("&amp;", "&")) ?: continue
            when (key) {
                "og:image", "og:image:url", "og:image:secure_url" -> return abs
                "twitter:image", "twitter:image:src" -> if (twitter == null) twitter = abs
            }
        }
        return twitter
    }

    private fun absolute(base: String, ref: String): String? =
        runCatching { URL(URL(base), ref).toString() }.getOrNull()?.takeIf { it.startsWith("http") }

    // ── Drawing ──────────────────────────────────────────────────────────────

    /** Centre-crops [src] to 16:9 and rounds the corners (with a hairline edge, like Apple's thumbnails). */
    private fun bake(src: Bitmap): Bitmap {
        val sr = src.width.toFloat() / src.height
        val cw: Float
        val ch: Float
        if (sr > 16f / 9f) { ch = src.height.toFloat(); cw = ch * 16f / 9f } else { cw = src.width.toFloat(); ch = cw * 9f / 16f }
        val sx = ((src.width - cw) / 2f).toInt()
        val sy = ((src.height - ch) / 2f).toInt()
        val srcRect = Rect(sx, sy, sx + cw.toInt(), sy + ch.toInt())
        val dst = RectF(0f, 0f, W.toFloat(), H.toFloat())

        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        c.drawRoundRect(dst, RADIUS, RADIUS, p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        c.drawBitmap(src, srcRect, dst, p)
        p.xfermode = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = 0x2EFFFFFF
        c.drawRoundRect(RectF(1f, 1f, W - 1f, H - 1f), RADIUS, RADIUS, p)
        return out
    }

    private fun fallbackCard(ctx: Context): Bitmap {
        fallback?.let { return it }
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, H.toFloat(), 0xFF3A3A3C.toInt(), 0xFF1C1C1E.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(0f, 0f, W.toFloat(), H.toFloat()), RADIUS, RADIUS, p)
        ContextCompat.getDrawable(ctx, R.drawable.ic_stat_aurex)?.mutate()?.let { d ->
            val s = (H * 0.42f).toInt()
            val l = (W - s) / 2
            val t = (H - s) / 2
            d.setBounds(l, t, l + s, t + s)
            d.alpha = 235
            d.draw(c)
        }
        fallback = out
        return out
    }

    private fun bakeSquare(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height).coerceAtLeast(1)
        val sx = (src.width - side) / 2
        val sy = (src.height - side) / 2
        val out = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(src, Rect(sx, sy, sx + side, sy + side), RectF(0f, 0f, 512f, 512f), p)
        return out
    }

    private fun fallbackSquareCard(ctx: Context): Bitmap {
        fallbackSquare?.let { return it }
        val out = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 512f, 512f, 0xFF14223D.toInt(), 0xFF0A0D18.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 512f, 512f, p)
        ContextCompat.getDrawable(ctx, R.drawable.ic_stat_aurex)?.mutate()?.let { d ->
            val s = 216
            val l = (512 - s) / 2
            d.setBounds(l, l, l + s, l + s)
            d.alpha = 240
            d.draw(c)
        }
        fallbackSquare = out
        return out
    }

    /** "https://www.example.com/x" -> "example.com" (for the grey line under the title). */
    fun hostOf(url: String?): String =
        runCatching { Uri.parse(url.orEmpty()).host }.getOrNull().orEmpty().removePrefix("www.")
}
