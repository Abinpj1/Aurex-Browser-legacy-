package com.aurex.browser

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * HLS (fMP4) downloads are an init segment with the fragments glued behind it and NO index (sidx).
 * The built-in player cannot seek in such a file: every seek restarts the video at 0:00, while players that scan the
 * whole file (VLC, MX ...) cope. [fix] rewrites the file once as a normal MP4 (no re-encoding, MediaMuxer), which
 * has a proper index and seeks instantly.
 */
object FragFix {
    private val busy = ConcurrentHashMap.newKeySet<String>()

    /** The file behind a local player url (file:// or plain path), or null. */
    fun localFile(url: String): File? {
        val p = when {
            url.startsWith("file:", true) -> android.net.Uri.parse(url).path
            url.startsWith("/") -> url
            else -> null
        }
        return p?.let { File(it) }?.takeIf { it.isFile }
    }

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)

    /**
     * True for a fragmented MP4 (moof boxes) that has no usable index: no sidx before the first fragment, or one sidx per
     * fragment (the player only reads the first, so it could seek inside the first few seconds only).
     * Reads box headers only (a few hundred bytes), so it is instant even for a multi-GB file.
     */
    fun needsFix(f: File): Boolean = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            val h = ByteArray(16)
            var pos = 0L
            var n = 0
            var moofs = 0
            var sidxBefore = false
            var sidxAfter = false
            while (pos + 8 <= len && n < 400 && moofs < 2) {
                raf.seek(pos)
                raf.readFully(h, 0, 8)
                var size = u32(h, 0)
                val type = String(h, 4, 4, Charsets.ISO_8859_1)
                if (size == 1L) {
                    if (pos + 16 > len) break
                    raf.readFully(h, 8, 8)
                    size = u64(h, 8)
                } else if (size == 0L) size = len - pos
                if (size < 8L) break
                if (type == "moof") moofs++
                else if (type == "sidx") { if (moofs == 0) sidxBefore = true else sidxAfter = true }
                pos += size
                n++
            }
            moofs > 0 && (!sidxBefore || sidxAfter)
        }
    }.getOrDefault(false)

    /** HDR keeps its colour data only through the full engine; MediaMuxer would drop it, so such files stay as they are. */
    private fun isHdr(f: File): Boolean = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(f.absolutePath)
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                if ((fmt.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/") && fmt.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                    val t = fmt.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                    if (t == MediaFormat.COLOR_TRANSFER_ST2084 || t == MediaFormat.COLOR_TRANSFER_HLG) return@runCatching true
                }
            }
            false
        } finally {
            runCatching { ex.release() }
        }
    }.getOrDefault(false)

    /**
     * Rewrites [f] in place as a normal, indexed MP4. Blocking: call it off the main thread. Returns false (file left
     * untouched) when it cannot be done, for example a codec this phone's MediaMuxer cannot write.
     */
    fun fix(f: File, onProgress: ((Float) -> Unit)? = null): Boolean {
        if (!busy.add(f.absolutePath)) return false
        val dir = f.parentFile ?: run { busy.remove(f.absolutePath); return false }
        val tmp = File(dir, f.name + ".fixing")
        val bak = File(dir, f.name + ".orig")
        try {
            if (isHdr(f)) return false
            runCatching { tmp.delete() }
            // video + audio of the one file; a video-only file has no audio track, then it goes without
            var ok = runCatching { Remux.mux(f, f, tmp, false, onProgress) }.isSuccess
            if (!ok) ok = runCatching { Remux.mux(f, null, tmp, false, onProgress) }.isSuccess
            if (!ok || !tmp.isFile || tmp.length() < f.length() / 2) {
                runCatching { tmp.delete() }
                return false
            }
            runCatching { bak.delete() }
            if (!f.renameTo(bak)) { runCatching { tmp.delete() }; return false }
            if (!tmp.renameTo(f)) {
                runCatching { bak.renameTo(f) }
                runCatching { tmp.delete() }
                return false
            }
            runCatching { bak.delete() }
            return true
        } finally {
            busy.remove(f.absolutePath)
        }
    }
}
