package com.aurex.browser

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File

/**
 * "Media visibility" for downloads.
 *
 *  - ON (default): finished videos, music and images are visible to the phone's Gallery, Music and Files apps.
 *  - OFF: a ".nomedia" marker in Aurex's Download folder hides them there. They stay in Aurex's own Downloads list.
 *
 * The choice is kept in the same preferences file as the rest of the browser settings, so the app-wide download
 * manager (which has no BrowserState) can read it too.
 */
object MediaVisibility {
    private const val KEY = "mediaVisible"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("liquid", Context.MODE_PRIVATE)

    fun isOn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY, true)

    private fun dir(ctx: Context): File? =
        ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.apply { mkdirs() }

    /** Saves the choice and applies it to the files that are already downloaded. */
    fun set(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY, on).apply()
        val app = ctx.applicationContext
        Thread { sync(app, rescan = true) }.start()
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
        if (rescan) {
            // Ask the media index to look again, so files appear (or disappear) without waiting for a reboot.
            val paths = (d.listFiles()?.filter { it.isFile && !isPartial(it) }?.map { it.path }.orEmpty() + d.path)
                .toTypedArray()
            runCatching { MediaScannerConnection.scanFile(ctx.applicationContext, paths, null, null) }
        }
    }

    /** Called when a download has finished: when visible, hand the file to the media index straight away. */
    fun onFinished(ctx: Context, file: File) {
        runCatching {
            if (isOn(ctx)) {
                MediaScannerConnection.scanFile(ctx.applicationContext, arrayOf(file.path), null, null)
            } else {
                // Hidden: make sure the marker is there (the folder may have been created after start-up).
                sync(ctx)
            }
        }
    }

    private fun isPartial(f: File): Boolean {
        val n = f.name
        return n.startsWith(".") || n.contains("~ax") || n.endsWith(".part", true) ||
            n.endsWith(".ytdl", true) || n.endsWith(".tmp", true)
    }
}
