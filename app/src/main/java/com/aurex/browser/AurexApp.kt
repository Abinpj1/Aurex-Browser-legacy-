package com.aurex.browser

import android.app.Application
import android.content.res.Configuration

/** App-scoped services shared by the browser UI, PlayerActivity and DownloadService. */
class AurexApp : Application() {
    lateinit var downloads: DownloadManager
        private set

    /** The phone's language can change while the app lives: "Auto" (app and website language) follows it. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLang.onSystemLocaleChanged()
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLang.init(this)   // app language + website language (Lang.kt)
        AppLang.onReady = {
            // notifications are not Compose: redraw them once the language / its translations are ready
            runCatching { DownloadService.update(this, DownloadManager.current?.items?.toList().orEmpty()) }
            runCatching { BrowserMediaService.live?.refresh() }
        }
        // A killed download can leave a plaintext cookie file behind: never keep one.
        runCatching { cacheDir.listFiles { f -> f.name.startsWith("ytdlp_cookies_") }?.forEach { it.delete() } }
        PerfTier.init(this)
        VideoDl.load(this)
        downloads = DownloadManager(this)
        // Keep the Download folder in line with the "Media visibility" setting (default: visible).
        MediaVisibility.sync(this)
        MediaVisibility.catchUpAsync(this)   // finishes a Gallery copy that was cut short (Android 10+)
        // Unpacks the universal downloader engine and keeps it current, off the main thread.
        // Skipped completely while "Video download" is off, so it costs no memory or data.
        if (VideoDl.enabled) YtDlpEngine.warmUpAsync(this)
    }
}
