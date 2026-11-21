package com.aurex.browser

import android.media.MediaCodecList
import android.os.Build

/**
 * What this phone can really play smoothly. Qualities above 1080p (2K / 4K / 8K) are offered ONLY when a
 * hardware video decoder accepts that picture size - the picker, the in-app player and the download sheet
 * all use this one answer, so an unsupported quality never shows up anywhere.
 */
object DeviceCaps {
    private val memo = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    private val decodeMemo = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun tierShort(s: Int): Int = when {
        s >= 4300 -> 4320
        s >= 2100 -> 2160
        s >= 1400 -> 1440
        s >= 1000 -> 1080
        s >= 700 -> 720
        s >= 460 -> 480
        s >= 340 -> 360
        s >= 230 -> 240
        else -> 144
    }

    private fun tierLong(l: Int): Int = when {
        l >= 7600 -> 4320
        l >= 3800 -> 2160
        l >= 2500 -> 1440
        l >= 1900 -> 1080
        l >= 1250 -> 720
        l >= 830 -> 480
        l >= 600 -> 360
        l >= 420 -> 240
        else -> 144
    }

    /**
     * The "p" class of a picture (144 ... 2160 ... 4320). 0 = unknown. Orientation and aspect ratio independent, so
     * ultra-wide 4K (3840x1606) and portrait 4K (2160x3840) count as the 4K they are.
     */
    fun tierOf(width: Int, height: Int): Int {
        val h = MediaTools.plainHeight(height)
        if (h <= 0) return 0
        val s = if (width > 0) minOf(width, h) else h
        val l = if (width > 0) maxOf(width, h) else h * 16 / 9
        return maxOf(tierShort(s), tierLong(l))
    }

    fun tier(c: YtDlpEngine.StreamChoice): Int = tierOf(c.width, c.height)

    private val FPS_RE = Regex("p(\\d{2,3})$")

    private fun fpsOf(c: YtDlpEngine.StreamChoice): Int =
        if (c.fps > 0) c.fps else FPS_RE.find(c.label)?.groupValues?.get(1)?.toIntOrNull() ?: 30

    private fun mimeOf(vcodec: String): String? = when {
        vcodec.startsWith("avc1") || vcodec.startsWith("h264") -> "video/avc"
        vcodec.startsWith("vp09") || vcodec.startsWith("vp9") -> "video/x-vnd.on2.vp9"
        vcodec.startsWith("av01") -> "video/av01"
        vcodec.startsWith("hev1") || vcodec.startsWith("hvc1") || vcodec.startsWith("hevc") || vcodec.startsWith("h265") -> "video/hevc"
        else -> null
    }

    /**
     * True when a decoder accepts this codec at this real picture size AND frame rate (4K60 is often missing on chips that
     * do 4K30). [hardwareOnly] = a hardware decoder is required (smooth, cool); false = any decoder, software included.
     */
    fun decodesRaw(vcodec: String, width: Int, height: Int, fps: Int, hardwareOnly: Boolean): Boolean {
        val mime = mimeOf(vcodec) ?: return tierOf(width, height) <= 1080
        val h = MediaTools.plainHeight(height)
        if (h <= 0) return true
        val w = if (width > 0) width else h * 16 / 9
        val f = if (fps > 0) fps else 30
        return decodeMemo.getOrPut("$mime:$w:$h:$f:$hardwareOnly") {
            runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                    if (info.isEncoder) return@any false
                    val type = info.supportedTypes.firstOrNull { it.equals(mime, true) } ?: return@any false
                    if (hardwareOnly && Build.VERSION.SDK_INT >= 29 && !info.isHardwareAccelerated) return@any false
                    val v = info.getCapabilitiesForType(type).videoCapabilities ?: return@any false
                    fun ok(a: Int, b: Int): Boolean =
                        v.isSizeSupported(a, b) && (f <= 30 || v.areSizeAndRateSupported(a, b, f.toDouble()))
                    ok(w, h) || ok(h, w)
                }
            }.getOrDefault(false)
        }
    }

    /** [decodesRaw] for a stream row. */
    fun decodes(c: YtDlpEngine.StreamChoice, hardwareOnly: Boolean = true): Boolean =
        decodesRaw(c.vcodec, c.width, c.height, fpsOf(c), hardwareOnly)

    /** Below this height every phone plays it (H.264 hardware decoders cover 1080p everywhere). */
    private const val ALWAYS_OK = 1080

    /** True when a stream of this height (HDR marker allowed) can be offered on this phone. */
    fun supportsHeight(height: Int): Boolean {
        val h = MediaTools.plainHeight(height)
        if (h <= ALWAYS_OK) return true
        return memo.getOrPut(h) { check(h) }
    }

    private fun check(h: Int): Boolean {
        val w = h * 16 / 9
        val mimes = listOf("video/avc", "video/x-vnd.on2.vp9", "video/av01", "video/hevc")
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                if (info.isEncoder) return@any false
                if (Build.VERSION.SDK_INT >= 29 && !info.isHardwareAccelerated) return@any false
                info.supportedTypes.any { t ->
                    mimes.any { it.equals(t, true) } &&
                        info.getCapabilitiesForType(t).videoCapabilities
                            ?.let { it.isSizeSupported(w, h) || it.isSizeSupported(h, w) } == true
                }
            }
        }.getOrDefault(false)
    }
}
