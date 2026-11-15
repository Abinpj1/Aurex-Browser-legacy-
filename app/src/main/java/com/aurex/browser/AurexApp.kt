package com.aurex.browser

import android.app.Application

/** App-scoped services shared by the browser UI, PlayerActivity and DownloadService. */
class AurexApp : Application() {
    lateinit var downloads: DownloadManager
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        // A killed download can leave a plaintext cookie file behind: never keep one.
        runCatching { cacheDir.listFiles { f -> f.name.startsWith("ytdlp_cookies_") }?.forEach { it.delete() } }
        PerfTier.init(this)
        VideoDl.load(this)
        downloads = DownloadManager(this)
        // Keep the Download folder in line with the "Media visibility" setting (default: visible).
        MediaVisibility.sync(this)
        // Unpacks the universal downloader engine and keeps it current, off the main thread.
        // Skipped completely while "Video download" is off, so it costs no memory or data.
        if (VideoDl.enabled) YtDlpEngine.warmUpAsync(this)
    }
}
