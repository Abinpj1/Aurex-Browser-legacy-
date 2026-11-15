package com.aurex.browser

import android.content.Context

/**
 * Process-wide master switch for the video downloader ("Video download" in Settings -> Downloads).
 *
 * While it is OFF nothing of the video engine runs in the background: it is not unpacked or updated
 * at start-up, pages are not scanned for videos, YouTube pages are not looked up in advance and
 * no download buttons are shown. Plain file downloads (APK, PDF, ZIP, ...) are not affected.
 *
 * Kept outside BrowserState because [DownloadManager] and [PlayerActivity] live longer than a
 * single browser screen and need the same answer.
 */
object VideoDl {
    private const val PREFS = "liquid"
    private const val KEY = "videoDl"

    @Volatile
    var enabled: Boolean = true

    /** Reads the saved choice. Called once from [AurexApp.onCreate], before anything else starts. */
    fun load(ctx: Context) {
        enabled = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    }

    const val OFF_HINT = "Video download is off · turn it on in Settings → Downloads"
}
