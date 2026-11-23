package com.aurex.browser

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper

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
        PerfTier.init(this)
        NotifIsland.init(this)
        AppLog.init(this)
        AppLang.init(this)   // app language + website language (Lang.kt)
        AppLang.onReady = {
            // notifications are not Compose: redraw them once the language / its translations are ready
            runCatching { DownloadService.update(this, DownloadManager.current?.items?.toList().orEmpty()) }
            runCatching { BrowserMediaService.live?.refresh() }
        }
        VideoDl.load(this)
        downloads = DownloadManager(this)
        // Offload cold-start disk house-keeping (stale cookie cleanup + .nomedia sync) off the UI thread.
        Thread({
            runCatching { cacheDir.listFiles { f -> f.name.startsWith("ytdlp_cookies_") }?.forEach { it.delete() } }
            runCatching { MediaVisibility.sync(this) }
        }, "aurex-boot-io").apply { isDaemon = true; start() }
        // Tier-aware cold-start warmup:
        //  - LOW-end phones: skip eager warmup completely (yt-dlp unpacks on-demand when a video is downloaded).
        //  - BALANCED: defer 4s so startup & first page render stay silky smooth.
        //  - HIGH: defer 800ms to avoid competing with Frame 1 UI draw.
        if (VideoDl.enabled && !PerfTier.low) {
            val delayMs = if (PerfTier.balanced) 4_000L else 800L
            Handler(Looper.getMainLooper()).postDelayed({
                if (VideoDl.enabled && !PerfTier.low) YtDlpEngine.warmUpAsync(this)
            }, delayMs)
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val aggressive = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_MODERATE ||
            level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || aggressive) {
            GlassBackdrop.trimAll()
            Favicons.trim(aggressive)
            AurexHttp.trim(); OkHttpStreamDataSource.trim()
            DownloadManager.trimSockets()
            BrowserState.onMemoryPressure(aggressive)
        }
    }

    @Suppress("DEPRECATION")
    override fun onLowMemory() {
        super.onLowMemory()
        GlassBackdrop.trimAll()
        Favicons.trim(aggressive = true)
        AurexHttp.trim(); OkHttpStreamDataSource.trim()
        DownloadManager.trimSockets()
        BrowserState.onMemoryPressure(aggressive = true)
    }
}
