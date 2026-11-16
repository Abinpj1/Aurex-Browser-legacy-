package com.aurex.browser

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * "Media visibility" for downloads.
 *
 *  - ON (default): finished videos, music and images are visible to the phone's Gallery, Music and Files apps.
 *  - OFF: they are hidden there. They stay in Aurex's own Downloads list.
 *
 * Why a copy: Aurex keeps its downloads in its app-specific folder (Android/data/<package>/files/Download).
 * From Android 11 on, other apps - the Gallery included - cannot see that folder, and the media index never lists
 * it, whatever MediaScannerConnection or a missing ".nomedia" marker says. The only supported way into the Gallery
 * is the MediaStore, so on Android 10+ a finished file is also written to Movies / Music / Pictures / AurexBrowser.
 * Deleting a download in Aurex deletes its Gallery copy, and switching visibility OFF removes all Gallery copies.
 * On Android 9 and older the old behaviour is kept (".nomedia" marker + media scan of the app folder).
 *
 * The choice is kept in the same preferences file as the rest of the browser settings, so the app-wide download
 * manager (which has no BrowserState) can read it too.
 */
object MediaVisibility {
    private const val KEY = "mediaVisible"
    private const val FOLDER = "AurexBrowser"
    private const val COPY_BUFFER = 256 * 1024
    private const val FREE_SPACE_MARGIN = 256L * 1024 * 1024
    private const val CATCH_UP_WINDOW_MS = 2L * 24 * 60 * 60 * 1000

    /** One copy at a time: big videos must not compete for disk and the order stays predictable. */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "aurex-media-export").apply { isDaemon = true } }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("liquid", Context.MODE_PRIVATE)

    fun isOn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY, true)

    private fun dir(ctx: Context): File? =
        ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.apply { mkdirs() }

    /** Saves the choice and applies it to the files that are already downloaded. */
    fun set(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY, on).apply()
        val app = ctx.applicationContext
        worker.execute {
            runCatching { sync(app, rescan = true) }
            runCatching { applyToFinished(app) }
        }
    }

    /** Makes the Download folder match the choice: no marker when visible, a ".nomedia" marker when hidden. */
    fun sync(ctx: Context, rescan: Boolean = false) {
        val d = dir(ctx) ?: return
        val on = isOn(ctx)
        runCatching {
            val marker = File(d, ".nomedia")
            if (on) {
                if (marker.exists()) marker.delete()
            } else if (!marker.exists()) {
                marker.createNewFile()
            }
        }
        if (rescan && Build.VERSION.SDK_INT < 29) {
            // Android 9 and older: ask the media index to look again, so files appear (or disappear) without a reboot.
            val paths = (d.listFiles()?.filter { it.isFile && !isPartial(it) }?.map { it.path }.orEmpty() + d.path)
                .toTypedArray()
            runCatching { MediaScannerConnection.scanFile(ctx.applicationContext, paths, null, null) }
        }
    }

    /** Called when a download has finished: when visible, hand the file to the Gallery straight away. */
    fun onFinished(ctx: Context, file: File) {
        runCatching {
            val app = ctx.applicationContext
            if (isOn(app)) {
                worker.execute { runCatching { publish(app, file) } }
            } else {
                // Hidden: make sure the marker is there (the folder may have been created after start-up).
                sync(app)
            }
        }
    }

    /** Called after a finished download was deleted in Aurex: its Gallery copy goes too. */
    fun onDeleted(ctx: Context, file: File) {
        if (Build.VERSION.SDK_INT < 29) return
        val app = ctx.applicationContext
        worker.execute { runCatching { unpublish(app, file) } }
    }

    /**
     * Start-up safety net: a copy that was cut short (the phone killed the app mid-copy) is finished here.
     * Only files from the last two days are looked at, so an update never starts copying a whole old library.
     */
    fun catchUpAsync(ctx: Context) {
        if (Build.VERSION.SDK_INT < 29) return
        val app = ctx.applicationContext
        if (!isOn(app)) return
        worker.execute {
            val since = System.currentTimeMillis() - CATCH_UP_WINDOW_MS
            finishedFiles().filter { it.lastModified() >= since }.forEach { runCatching { publish(app, it) } }
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun finishedFiles(): List<File> =
        DownloadManager.current?.items?.toList().orEmpty()
            .filter { it.status == DlStatus.DONE }
            .map { it.file }
            .filter { it.isFile && !isPartial(it) }

    /** Switch flipped: show every finished download in the Gallery, or take every Gallery copy away. */
    private fun applyToFinished(ctx: Context) {
        if (Build.VERSION.SDK_INT < 29) return
        if (isOn(ctx)) finishedFiles().forEach { runCatching { publish(ctx, it) } } else unpublishAll(ctx)
    }

    private class Target(val collection: Uri, val relativePath: String, val mime: String)

    private fun mimeOf(file: File): String? {
        val ext = file.extension.lowercase()
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg", "oga", "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/x-wav"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> null
        }
    }

    private fun targetFor(file: File): Target? {
        val mime = mimeOf(file) ?: return null
        return when {
            mime.startsWith("video/") -> Target(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MOVIES + "/" + FOLDER + "/", mime
            )
            mime.startsWith("audio/") -> Target(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MUSIC + "/" + FOLDER + "/", mime
            )
            mime.startsWith("image/") -> Target(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES + "/" + FOLDER + "/", mime
            )
            else -> null
        }
    }

    /** The Gallery copy of [name] (its row and size) when Aurex already put one there. Rows of other apps are never seen. */
    @RequiresApi(29)
    private fun findCopy(ctx: Context, t: Target, name: String): Pair<Uri, Long>? {
        ctx.contentResolver.query(
            t.collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(name, t.relativePath),
            null
        )?.use { c ->
            if (c.moveToFirst()) return Pair(ContentUris.withAppendedId(t.collection, c.getLong(0)), c.getLong(1))
        }
        return null
    }

    /** Puts a finished file into the Gallery. Android 9 and older: asks the media scan instead. */
    private fun publish(ctx: Context, file: File) {
        if (!file.isFile || file.length() <= 0L || isPartial(file)) return
        if (Build.VERSION.SDK_INT < 29) {
            MediaScannerConnection.scanFile(ctx, arrayOf(file.path), null, null)
            return
        }
        val t = targetFor(file) ?: return
        val resolver = ctx.contentResolver
        val size = file.length()
        findCopy(ctx, t, file.name)?.let { (uri, copySize) ->
            if (copySize == size) return          // already there
            runCatching { resolver.delete(uri, null, null) }   // an older or cut-short copy: replace it
        }
        val free = runCatching { StatFs((ctx.getExternalFilesDir(null) ?: file).path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (free < size + FREE_SPACE_MARGIN) {
            AppLog.warn("MediaVisibility", "not enough free space to show ${file.name} in the Gallery")
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, t.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, t.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)      // hidden from the Gallery until the copy is complete
        }
        val uri = resolver.insert(t.collection, values) ?: return
        try {
            val out = resolver.openOutputStream(uri, "w") ?: throw IOException("no output stream")
            out.use { o -> file.inputStream().use { it.copyTo(o, COPY_BUFFER) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            AppLog.warn("MediaVisibility", "Gallery copy of ${file.name} failed: ${e.message}")
        }
    }

    @RequiresApi(29)
    private fun unpublish(ctx: Context, file: File) {
        val t = targetFor(file) ?: return
        findCopy(ctx, t, file.name)?.let { (uri, _) -> runCatching { ctx.contentResolver.delete(uri, null, null) } }
    }

    /** Removes every copy Aurex made (only Aurex's own rows can be touched, other apps' files are never affected). */
    @RequiresApi(29)
    private fun unpublishAll(ctx: Context) {
        val folders = listOf(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MUSIC,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
        )
        for ((collection, top) in folders) {
            runCatching {
                ctx.contentResolver.delete(
                    collection, "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf("$top/$FOLDER/")
                )
            }
        }
    }

    private fun isPartial(f: File): Boolean {
        val n = f.name
        return n.startsWith(".") || n.contains("~ax") || n.endsWith(".part", true) ||
            n.endsWith(".ytdl", true) || n.endsWith(".tmp", true)
    }
}
