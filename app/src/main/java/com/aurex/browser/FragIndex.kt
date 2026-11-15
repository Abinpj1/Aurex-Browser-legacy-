package com.aurex.browser

import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import java.io.File
import java.io.RandomAccessFile

/**
 * Downloaded HLS videos are fragmented MP4 files (init segment + moof/mdat fragments) WITHOUT a seek index (sidx).
 * ExoPlayer treats such a file as "unseekable": every seek restarts at 0:00 and the duration is unknown. VLC / MX & co
 * scan the fragment headers themselves, which is why they seek fine.
 *
 * This does the same: it reads ONLY the box headers + the small moof boxes (never the video data), builds a
 * time -> byte-position table in memory, and hands it to ExoPlayer as the seek map. The file is not touched, nothing is
 * re-encoded or rewritten, so it works for every codec (AV1, HEVC, HDR ...) and the video starts at once.
 */
object FragIndex {
    class Index(val timesUs: LongArray, val positions: LongArray, val durationUs: Long)

    private class Box(val type: String, val payload: Int, val end: Int)
    private class Trk(val id: Int) {
        var timescale = 0L
        var handler = ""
        var defDur = 0L
        var defFlags = 0L
    }
    private class Traf(val track: Int, val tfdt: Long, val dur: Long, val sap: Boolean)
    private class Moof(val pos: Long, val trafs: List<Traf>)

    private val LATIN1 = Charsets.ISO_8859_1

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)

    /** The boxes lying directly in b[from, to). */
    private fun boxes(b: ByteArray, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = u32(b, p)
            val type = String(b, p + 4, 4, LATIN1)
            var h = 8
            if (size == 1L) {
                if (p + 16 > to) break
                size = u64(b, p + 8); h = 16
            } else if (size == 0L) size = (to - p).toLong()
            if (size < h) break
            val end = minOf(p + size, to.toLong()).toInt()
            out.add(Box(type, p + h, end))
            p = end
        }
        return out
    }

    private fun parseMoov(b: ByteArray): Map<Int, Trk> {
        val m = LinkedHashMap<Int, Trk>()
        val top = boxes(b, 0, b.size)
        for (bx in top) if (bx.type == "trak") {
            var id = -1
            var ts = 0L
            var hd = ""
            for (c in boxes(b, bx.payload, bx.end)) {
                if (c.type == "tkhd") {
                    id = u32(b, c.payload + (if (b[c.payload].toInt() == 1) 20 else 12)).toInt()
                } else if (c.type == "mdia") {
                    for (d in boxes(b, c.payload, c.end)) {
                        if (d.type == "mdhd") ts = u32(b, d.payload + (if (b[d.payload].toInt() == 1) 20 else 12))
                        else if (d.type == "hdlr") hd = String(b, d.payload + 8, 4, LATIN1)
                    }
                }
            }
            if (id >= 0) m[id] = Trk(id).also { it.timescale = ts; it.handler = hd }
        }
        for (bx in top) if (bx.type == "mvex") {
            for (c in boxes(b, bx.payload, bx.end)) if (c.type == "trex") {
                val t = m[u32(b, c.payload + 4).toInt()]
                if (t != null) { t.defDur = u32(b, c.payload + 12); t.defFlags = u32(b, c.payload + 20) }
            }
        }
        return m
    }

    private fun parseMoof(b: ByteArray, trks: Map<Int, Trk>): List<Traf> {
        val out = ArrayList<Traf>()
        for (bx in boxes(b, 0, b.size)) if (bx.type == "traf") {
            var id = -1
            var defDur = 0L
            var defFlags = 0L
            var tfdt = -1L
            var dur = 0L
            var sap = true
            var seenRun = false
            for (c in boxes(b, bx.payload, bx.end)) {
                when (c.type) {
                    "tfhd" -> {
                        val fl = u32(b, c.payload) and 0xFFFFFFL
                        id = u32(b, c.payload + 4).toInt()
                        val t = trks[id]
                        defDur = t?.defDur ?: 0L
                        defFlags = t?.defFlags ?: 0L
                        var o = c.payload + 8
                        if ((fl and 0x01L) != 0L) o += 8
                        if ((fl and 0x02L) != 0L) o += 4
                        if ((fl and 0x08L) != 0L) { defDur = u32(b, o); o += 4 }
                        if ((fl and 0x10L) != 0L) o += 4
                        if ((fl and 0x20L) != 0L) defFlags = u32(b, o)
                    }
                    "tfdt" -> tfdt = if (b[c.payload].toInt() == 1) u64(b, c.payload + 4) else u32(b, c.payload + 4)
                    "trun" -> {
                        val fl = u32(b, c.payload) and 0xFFFFFFL
                        val cnt = u32(b, c.payload + 4).toInt()
                        var o = c.payload + 8
                        if ((fl and 0x001L) != 0L) o += 4
                        var first = -1L
                        if ((fl and 0x004L) != 0L) { first = u32(b, o); o += 4 }
                        val hasDur = (fl and 0x100L) != 0L
                        val hasSize = (fl and 0x200L) != 0L
                        val hasFlags = (fl and 0x400L) != 0L
                        val hasCto = (fl and 0x800L) != 0L
                        val per = 4 * ((if (hasDur) 1 else 0) + (if (hasSize) 1 else 0) +
                            (if (hasFlags) 1 else 0) + (if (hasCto) 1 else 0))
                        if (hasDur) {
                            var s = 0L
                            var p = o
                            for (i in 0 until cnt) { s += u32(b, p); p += per }
                            dur += s
                        } else dur += cnt.toLong() * defDur
                        if (!seenRun) {
                            seenRun = true
                            val f0 = when {
                                first >= 0L -> first
                                hasFlags -> u32(b, o + (if (hasDur) 4 else 0) + (if (hasSize) 4 else 0))
                                else -> defFlags
                            }
                            sap = (f0 and 0x10000L) == 0L     // "sample is not a sync sample" bit is clear
                        }
                    }
                    else -> Unit
                }
            }
            out.add(Traf(id, tfdt, dur, sap))
        }
        return out
    }

    /**
     * Builds the seek table of [f]. Reads box headers and the (small) moov / moof boxes only, so it takes a few ms even
     * for a multi-GB file. Returns null when the file is not a fragmented MP4 it understands: then nothing changes
     * (the video still plays, as before).
     */
    fun build(f: File): Index? = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            val hdr = ByteArray(16)
            var trks: Map<Int, Trk> = emptyMap()
            val moofs = ArrayList<Moof>()
            var pos = 0L
            var n = 0
            while (pos + 8 <= len && n < 500_000) {
                raf.seek(pos)
                raf.readFully(hdr, 0, 8)
                var size = u32(hdr, 0)
                val type = String(hdr, 4, 4, LATIN1)
                var h = 8
                if (size == 1L) {
                    if (pos + 16 > len) break
                    raf.readFully(hdr, 8, 8)
                    size = u64(hdr, 8); h = 16
                } else if (size == 0L) size = len - pos
                if (size < h) break
                val bodyLen = size - h
                if (type == "moov" && bodyLen in 1..(32L shl 20)) {
                    val body = ByteArray(bodyLen.toInt())
                    raf.seek(pos + h); raf.readFully(body)
                    trks = parseMoov(body)
                } else if (type == "moof" && bodyLen in 1..(4L shl 20) && pos + size <= len) {
                    val body = ByteArray(bodyLen.toInt())
                    raf.seek(pos + h); raf.readFully(body)
                    moofs.add(Moof(pos, parseMoof(body, trks)))
                }
                pos += size
                n++
            }
            if (trks.isEmpty() || moofs.size < 2) return@use null

            // the picture decides the seek points (audio-only file: the audio)
            val trk = trks.values.firstOrNull { it.handler == "vide" }
                ?: trks.values.firstOrNull { it.handler == "soun" } ?: trks.values.first()
            val scale = trk.timescale
            if (scale <= 0L) return@use null

            val times = ArrayList<Long>()
            val poss = ArrayList<Long>()
            var cur = 0L
            var prevDelta = 0L
            var groupStart = -1L
            var nextStartsGroup = true
            for (m in moofs) {
                // A seek must also catch the audio fragments that sit between the previous picture fragment and this one,
                // so a seek point starts at the first fragment AFTER the previous picture fragment.
                if (nextStartsGroup) { groupStart = m.pos; nextStartsGroup = false }
                val t = m.trafs.firstOrNull { it.track == trk.id } ?: continue
                val start = if (t.tfdt >= 0L) t.tfdt else cur
                val d = if (t.dur > 0L) t.dur else prevDelta
                val us = start * 1_000_000L / scale
                // only fragments that begin on a key frame are valid places to start decoding
                if (t.sap && (times.isEmpty() || us >= times[times.size - 1])) {
                    times.add(us); poss.add(groupStart)
                }
                cur = start + d
                prevDelta = d
                nextStartsGroup = true
            }
            if (times.size < 2) return@use null
            Index(times.toLongArray(), poss.toLongArray(), cur * 1_000_000L / scale)
        }
    }.getOrNull()

    /** ExoPlayer seek map over [ix]: the newest key-frame fragment at or before the asked time. */
    private class IndexSeekMap(private val ix: Index) : SeekMap {
        override fun isSeekable() = true
        override fun getDurationUs() = ix.durationUs
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val t = ix.timesUs
            var lo = 0
            var hi = t.size - 1
            var i = 0
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (t[mid] <= timeUs) { i = mid; lo = mid + 1 } else hi = mid - 1
            }
            val a = androidx.media3.extractor.SeekPoint(t[i], ix.positions[i])
            return if (t[i] >= timeUs || i + 1 >= t.size) SeekMap.SeekPoints(a)
            else SeekMap.SeekPoints(a, androidx.media3.extractor.SeekPoint(t[i + 1], ix.positions[i + 1]))
        }
    }

    /**
     * ExoPlayer's own fragmented-MP4 extractor, with the missing seek map supplied from [build]. Everything else (sample
     * parsing, codecs, audio / video sync) is the stock extractor, so playback is unchanged; only seeking and the
     * duration now work. If no index could be built the stock behaviour stays.
     */
    class IndexedExtractor(private val file: File) : Extractor {
        private val inner: Extractor = DefaultExtractorsFactory().createExtractors()
            .filterIsInstance<FragmentedMp4Extractor>().first()
        private var custom: SeekMap? = null

        override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)

        override fun init(output: ExtractorOutput) {
            custom = build(file)?.let { IndexSeekMap(it) }
            inner.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput = output.track(id, type)
                override fun endTracks() = output.endTracks()
                // the stock extractor says "unseekable" (or knows only the first fragment): ours knows the whole file
                override fun seekMap(seekMap: SeekMap) = output.seekMap(custom ?: seekMap)
            })
        }

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = inner.read(input, seekPosition)
        override fun seek(position: Long, timeUs: Long) = inner.seek(position, timeUs)
        override fun release() = inner.release()
    }
}
