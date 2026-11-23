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
 * "Files visibility" for downloads.
 *
 *  - ON (default): every finished download (PDF, APK, ZIP, videos, music, images, ...) is also saved in the phone's
 *    public Downloads folder (Downloads / AurexBrowser), so File Manager, Gallery and music apps can see it.
 *  - OFF: those copies are removed. The files stay private, in Aurex's own Downloads list only.
 *
 * Why a copy: Aurex keeps its downloads in its app-specific folder (Android/data/<package>/files/Download).
 * From Android 11 on, other apps - the File Manager included - cannot open that folder, and the media index never
 * lists it. The only supported way into the public Downloads folder is the MediaStore, so on Android 10+ a finished
 * file is also written to Downloads / AurexBrowser. Deleting a download in Aurex deletes its public copy, and
 * switching visibility OFF removes all public copies.
 * (Older versions put media in Movies / Music / Pictures / AurexBrowser: those copies are cleaned up too.)
 * On Android 9 and older the old behaviour is kept (".nomedia" marker + media scan of the app folder).
 *
 * The choice is kept in the same preferences file as the rest of the browser settings, so the app-wide download
 * manager (which has no BrowserState) can read it too.
 */
object MediaVisibility {
    private const val KEY = "mediaVisible"
    private const val FOLDER = "AurexBrowser"
    private const val FULL_EXPORT_KEY = "filesExportAllDone"
    private val DOWNLOADS_PATH = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/"
    private const val COPY_BUFFER = 256 * 1024
    private const val FREE_SPACE_MARGIN = 256L * 1024 * 1024
    private const val CATCH_UP_WINDOW_MS = 2L * 24 * 60 * 60 * 1000

    /** One copy at a time: big videos must not compete for disk and the order stays predictable. */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "aurex-media-export").apply { isDaemon = true } }
    /** Maps private file absolutePath -> exact MediaStore Uri published in Downloads/AurexBrowser. */
    private val publishedUris = java.util.concurrent.ConcurrentHashMap<String, Uri>()
    /** Paths explicitly deleted by the user so an in-flight publish() aborts instead of recreating the file. */
    private val deletedPaths = java.util.concurrent.ConcurrentHashMap<String, Long>()

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

    /** Called when a download has finished: when visible, save it in the public Downloads folder straight away. */
    fun onFinished(ctx: Context, file: File, onPublished: ((String) -> Unit)? = null) {
        runCatching {
            val app = ctx.applicationContext
            deletedPaths.remove(file.absolutePath)
            if (isOn(app)) {
                worker.execute {
                    val uri = runCatching { publish(app, file) }.getOrNull()
                    if (uri != null && !deletedPaths.containsKey(file.absolutePath)) {
                        runCatching { onPublished?.invoke(uri.toString()) }
                    }
                }
            } else {
                sync(app)
            }
        }
    }

    /**
     * Permanently deletes every public/local-storage copy of [file] (exact MediaStore [publicUri],
     * cached [publishedUris], MediaStore Downloads/Files/Video/Audio/Images rows, and direct Downloads/AurexBrowser file).
     */
    fun onDeleted(ctx: Context, file: File, publicUri: String? = null) {
        val app = ctx.applicationContext
        val absPath = file.absolutePath
        val name = file.name
        deletedPaths[absPath] = System.currentTimeMillis()
        worker.execute {
            runCatching {
                if (Build.VERSION.SDK_INT >= 29) {
                    unpublish(app, file, publicUri)
                } else {
                    runCatching {
                        @Suppress("DEPRECATION")
                        val pub = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$FOLDER/$name")
                        if (pub.exists()) pub.delete()
                    }
                    runCatching { MediaScannerConnection.scanFile(app, arrayOf(absPath), null, null) }
                }
            }
        }
    }

    /**
     * Start-up safety net: a copy that was cut short (the phone killed the app mid-copy) is finished here.
     * Normally only files from the last two days are looked at. The first start after this feature was added looks at
     * every finished download once, so PDFs, APKs and other files downloaded earlier show up in Downloads as well.
     */
    fun catchUpAsync(ctx: Context) {
        if (Build.VERSION.SDK_INT < 29) return
        val app = ctx.applicationContext
        if (!isOn(app)) return
        worker.execute {
            val firstFullRun = !prefs(app).getBoolean(FULL_EXPORT_KEY, false)
            val since = if (firstFullRun) 0L else System.currentTimeMillis() - CATCH_UP_WINDOW_MS
            val files = finishedFiles()
            // The download list may not be loaded yet this early: do not burn the one-time run on an empty list.
            if (files.isEmpty() && firstFullRun) return@execute
            files.filter { it.lastModified() >= since }.forEach { runCatching { publish(app, it) } }
            if (firstFullRun) prefs(app).edit().putBoolean(FULL_EXPORT_KEY, true).apply()
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun finishedFiles(): List<File> =
        DownloadManager.current?.items?.toList().orEmpty()
            .filter { it.status == DlStatus.DONE }
            .map { it.file }
            .filter { it.isFile && !isPartial(it) }

    /** Switch flipped: save every finished download in Downloads, or take every public copy away. */
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

    /** Every kind of file goes to the public Downloads folder (Android 10+). */
    @RequiresApi(29)
    private fun targetFor(file: File): Target =
        Target(MediaStore.Downloads.EXTERNAL_CONTENT_URI, DOWNLOADS_PATH, mimeOf(file) ?: "application/octet-stream")

    /** Where older versions of Aurex put media (Movies / Music / Pictures). Only used to clean those copies up. */
    private fun legacyTargetFor(file: File): Target? {
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

    /** Removes the copy an older Aurex version made in Movies / Music / Pictures, so the file is not listed twice. */
    @RequiresApi(29)
    private fun removeLegacyCopy(ctx: Context, file: File) {
        val t = legacyTargetFor(file) ?: return
        findCopy(ctx, t, file.name)?.let { (uri, _) -> runCatching { ctx.contentResolver.delete(uri, null, null) } }
    }

    /** The public copy of [name] (its row and size) when Aurex already put one in [t]. Rows of other apps are never seen. */
    @RequiresApi(29)
    private fun findCopy(ctx: Context, t: Target, name: String): Pair<Uri, Long>? {
        val relNoSlash = t.relativePath.trimEnd('/')
        ctx.contentResolver.query(
            t.collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND (${MediaStore.MediaColumns.RELATIVE_PATH}=? OR ${MediaStore.MediaColumns.RELATIVE_PATH}=?)",
            arrayOf(name, t.relativePath, relNoSlash),
            null
        )?.use { c ->
            if (c.moveToFirst()) return Pair(ContentUris.withAppendedId(t.collection, c.getLong(0)), c.getLong(1))
        }
        return null
    }

    /** Puts a finished file into the public Downloads folder and returns its public MediaStore Uri (API 29+). */
    private fun publish(ctx: Context, file: File): Uri? {
        val absPath = file.absolutePath
        if (deletedPaths.containsKey(absPath) || !file.isFile || file.length() <= 0L || isPartial(file)) return null
        if (Build.VERSION.SDK_INT < 29) {
            MediaScannerConnection.scanFile(ctx, arrayOf(file.path), null, null)
            return null
        }
        val t = targetFor(file)
        val resolver = ctx.contentResolver
        val size = file.length()
        findCopy(ctx, t, file.name)?.let { (uri, copySize) ->
            if (copySize == size) {
                publishedUris[absPath] = uri
                runCatching { removeLegacyCopy(ctx, file) }
                return uri
            }
            runCatching { resolver.delete(uri, null, null) }
        }
        val free = runCatching { StatFs((ctx.getExternalFilesDir(null) ?: file).path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (free < size + FREE_SPACE_MARGIN) {
            AppLog.warn("MediaVisibility", "not enough free space to save ${file.name} in Downloads")
            return null
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, t.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, t.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(t.collection, values) ?: return null
        return try {
            val out = resolver.openOutputStream(uri, "w") ?: throw IOException("no output stream")
            out.use { o ->
                file.inputStream().use { input ->
                    val buf = ByteArray(COPY_BUFFER)
                    while (true) {
                        if (deletedPaths.containsKey(absPath)) throw IOException("deleted while publishing")
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                    }
                }
            }
            if (deletedPaths.containsKey(absPath)) {
                runCatching { resolver.delete(uri, null, null) }
                return null
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            publishedUris[absPath] = uri
            runCatching { removeLegacyCopy(ctx, file) }
            uri
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            if (!deletedPaths.containsKey(absPath)) {
                AppLog.warn("MediaVisibility", "Downloads copy of ${file.name} failed: ${e.message}")
            }
            null
        }
    }

    @RequiresApi(29)
    private fun deleteAllMatching(ctx: Context, collection: Uri, name: String, relPath: String) {
        val resolver = ctx.contentResolver
        val relNoSlash = relPath.trimEnd('/')
        val sel = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND (${MediaStore.MediaColumns.RELATIVE_PATH}=? OR ${MediaStore.MediaColumns.RELATIVE_PATH}=?)"
        val args = arrayOf(name, relPath, relNoSlash)
        val ids = ArrayList<Long>()
        runCatching {
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), sel, args, null)?.use { c ->
                while (c.moveToNext()) ids.add(c.getLong(0))
            }
        }
        for (id in ids) {
            runCatching { resolver.delete(ContentUris.withAppendedId(collection, id), null, null) }
        }
        runCatching { resolver.delete(collection, sel, args) }
    }

    @RequiresApi(29)
    private fun unpublish(ctx: Context, file: File, publicUri: String? = null) {
        val resolver = ctx.contentResolver
        publishedUris.remove(file.absolutePath)?.let { cached ->
            runCatching { resolver.delete(cached, null, null) }
        }
        if (!publicUri.isNullOrBlank()) {
            runCatching { resolver.delete(Uri.parse(publicUri), null, null) }
        }
        val name = file.name
        deleteAllMatching(ctx, MediaStore.Downloads.EXTERNAL_CONTENT_URI, name, DOWNLOADS_PATH)
        deleteAllMatching(ctx, MediaStore.Files.getContentUri("external"), name, DOWNLOADS_PATH)
        runCatching { removeLegacyCopy(ctx, file) }
        val legacy = legacyTargetFor(file)
        if (legacy != null) deleteAllMatching(ctx, legacy.collection, name, legacy.relativePath)
        runCatching {
            @Suppress("DEPRECATION")
            val pub = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$FOLDER/$name")
            if (pub.exists()) {
                pub.delete()
                runCatching { MediaScannerConnection.scanFile(ctx, arrayOf(pub.absolutePath), null, null) }
            }
        }
    }

    /** Removes every copy Aurex made (only Aurex's own rows can be touched, other apps' files are never affected). */
    @RequiresApi(29)
    private fun unpublishAll(ctx: Context) {
        val folders = listOf(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS,
            // Copies made by older versions of Aurex:
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MUSIC,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
        )
        for ((collection, top) in folders) {
            val rel = "$top/$FOLDER/"
            val relNoSlash = "$top/$FOLDER"
            runCatching {
                ctx.contentResolver.delete(
                    collection,
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? OR ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(rel, relNoSlash)
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
