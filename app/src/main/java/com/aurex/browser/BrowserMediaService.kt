package com.aurex.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean

// Compiled once; building a Regex on every call is wasted work on hot paths.
private val TAB_COUNT_PREFIX_RE = Regex("^\\(\\d+\\)\\s*")

/**
 * BrowserMediaService – a foreground service that keeps media playing when the
 * screen is off or the app is backgrounded, especially on MIUI / HyperOS devices
 * that aggressively kill background tasks.
 *
 * Started (with [EXTRA_PLAYING] = true) from [BrowserState] the moment a video
 * starts playing; stopped when playback stops or the app exits.
 *
 * Acquires:
 *  - [PowerManager.PARTIAL_WAKE_LOCK] – keeps the CPU running so audio/video
 *    decoding threads are not frozen when the screen turns off.
 *  - [WifiManager.WIFI_MODE_FULL_LOW_LATENCY] (API 29+) or
 *    [WifiManager.WIFI_MODE_FULL_HIGH_PERF] – prevents Wi-Fi from entering power-
 *    save mode, which would otherwise cause the network-stall stuttering seen on
 *    HyperOS during background YouTube streaming.
 */
class BrowserMediaService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var mediaTitle: String = ""
    private var mediaHost: String = ""
    private var mediaUrl: String = ""
    private var accent: Int = 0xFF0A84FF.toInt()
    @Volatile private var isPlaying = false
    private var shown = false          // the notification exists (playing, or paused and still dismissible)
    private var fgUp = false           // currently the foreground notification
    private var session: MediaSession? = null
    private var metaKey = ""
    private val ui = Handler(Looper.getMainLooper())
    // Cards are built and posted off the main thread: a redraw (bitmaps over binder) no longer delays the tap that caused it.
    private val bgThread = HandlerThread("AurexMediaNotif").apply { start() }
    private val bg = Handler(bgThread.looper)
    private val refreshPending = AtomicBoolean(false)
    private val notifLock = Any()
    private var lastArt: Bitmap? = null
    private val settleRun = Runnable { settlePaused() }
    private val idleRun = Runnable {
        MediaNotif.stop()
        teardown()
    }

    companion object {
        /** The running service (null when stopped): [MediaNotif] asks it to redraw. */
        @Volatile var live: BrowserMediaService? = null
        /** Boolean extra: with [EXTRA_PLAYING] = true this means "paused, keep the card" instead of "playing". */
        const val EXTRA_PAUSED    = "aurex_paused"
        /** Int extra: the app's current accent colour (ARGB). */
        const val EXTRA_ACCENT    = "aurex_accent"
        /** Action: the card was swiped away (or the idle timeout hit): stop for good. */
        const val ACTION_DISMISS  = "com.aurex.browser.MEDIA_DISMISS"
        /** Boolean extra: the page's video is gone for a moment (next video loading): keep the card, drop it only if nothing follows. */
        const val EXTRA_HOLD      = "aurex_hold"
        /** Boolean extra: the intent comes from the built-in player (it owns the card while it is open). */
        const val EXTRA_LOCAL     = "aurex_local"
        /** How long a card waits for the next video before it goes. */
        private const val HOLD_MS = 12_000L
        /** A pause shorter than this (buffering, a seek) never drops the foreground state or the locks. */
        private const val PAUSE_SETTLE_MS = 4_000L
        /** A paused card is removed after this long. */
        private const val IDLE_STOP_MS = 600_000L
        /** Boolean extra: true = playback started, false = playback stopped. */
        const val EXTRA_PLAYING   = "aurex_playing"
        /** String extras: what is playing (page / video title) and where (site address). */
        const val EXTRA_TITLE     = "aurex_title"
        const val EXTRA_HOST      = "aurex_host"
        /** Page address: the source of the thumbnail (YouTube id or the page's og:image). */
        const val EXTRA_URL       = "aurex_url"
        private const val NOTIFICATION_ID = 7913
        private const val CHANNEL_ID     = "aurex_media_playback"
        private const val WAKE_TAG        = "Aurex:MediaPlayback"
        private const val WIFI_TAG        = "Aurex:MediaWifi"
        /** Max wake-lock duration: 2 hours.  Auto-released in [onDestroy]. */
        private const val WAKE_LOCK_TIMEOUT_MS = 7_200_000L
    }

    // ── Service lifecycle ────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        live = this
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            MediaNotif.stop()
            teardown()
            return START_NOT_STICKY
        }
        val act = intent?.action
        if (act == MediaNotif.ACTION_TOGGLE || act == MediaNotif.ACTION_PREV || act == MediaNotif.ACTION_NEXT) {
            // Card buttons come straight here (no broadcast queue): the page / player reacts at once.
            MediaNotif.perform(act)
            if (!shown) stopSelf()          // woken by a stale button with no card: do not linger
            return START_NOT_STICKY
        }
        val playing = intent?.getBooleanExtra(EXTRA_PLAYING, false) == true
        val paused = intent?.getBooleanExtra(EXTRA_PAUSED, false) == true
        val hold = intent?.getBooleanExtra(EXTRA_HOLD, false) == true
        val fromLocal = intent?.getBooleanExtra(EXTRA_LOCAL, false) == true
        // While the built-in player has a video open, the page's own start / stop messages must not touch its card.
        if (!fromLocal && MediaNotif.local != null) return START_NOT_STICKY
        if (!playing) {
            teardown()
            return START_NOT_STICKY
        }
        if (hold) {
            if (!shown) { teardown(); return START_NOT_STICKY }
            ui.removeCallbacks(settleRun)
            ui.removeCallbacks(idleRun)
            ui.postDelayed(idleRun, HOLD_MS)
            return START_NOT_STICKY
        }
        intent?.getStringExtra(EXTRA_TITLE)?.let { mediaTitle = cleanTitle(it) }
        intent?.getStringExtra(EXTRA_HOST)?.let { mediaHost = it.removePrefix("www.") }
        intent?.getStringExtra(EXTRA_URL)?.let { mediaUrl = it }
        intent?.getIntExtra(EXTRA_ACCENT, 0)?.takeIf { it != 0 }?.let { accent = it or 0xFF000000.toInt() }

        if (paused) {
            if (!shown) {               // nothing was ever shown: no card for a video that is not playing
                teardown()
                return START_NOT_STICKY
            }
            isPlaying = false
            ui.removeCallbacks(settleRun)
            ui.postDelayed(settleRun, PAUSE_SETTLE_MS)
            ui.removeCallbacks(idleRun)
            ui.postDelayed(idleRun, IDLE_STOP_MS)
            refresh()
        } else {
            isPlaying = true
            ui.removeCallbacks(settleRun)
            ui.removeCallbacks(idleRun)
            promoteToForeground()
            acquireLocks()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (live === this) live = null
        ui.removeCallbacksAndMessages(null)
        releaseLocks()
        releaseSession()
        bgThread.quitSafely()
        super.onDestroy()
    }

    /** The built-in player closed: take the card down now. */
    fun dismissNow() {
        ui.post { teardown() }
    }

    /** Remove the card, free everything, stop. */
    private fun teardown() {
        MediaNotif.stop()
        ui.removeCallbacksAndMessages(null)
        isPlaying = false
        synchronized(notifLock) {
            shown = false
            fgUp = false
            stopForegroundCompat()
            runCatching {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
            }
        }
        releaseLocks()
        releaseSession()
        stopSelf()
    }

    /** Paused for real (not a buffering dip): free the CPU / Wi-Fi locks and let the user swipe the card away. */
    private fun settlePaused() {
        if (isPlaying) return
        releaseLocks()
        if (fgUp) {
            fgUp = false
            runCatching { stopForeground(STOP_FOREGROUND_DETACH) }     // minSdk 24 = N: always available
        }
        refresh()
    }

    /** Redraw the card from the latest [MediaNotif.snap]. Safe from any thread. */
    fun refresh() {
        if (!shown) return
        if (!refreshPending.compareAndSet(false, true)) return     // a redraw is already queued: it reads the newest state
        runCatching {
            bg.post {
                refreshPending.set(false)
                synchronized(notifLock) {
                    if (!shown) return@post
                    runCatching {
                        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                            .notify(NOTIFICATION_ID, buildNotification())
                    }
                }
            }
        }.onFailure { refreshPending.set(false) }
    }

    fun syncPositionOnly() {
        if (!shown) return
        val ss = session ?: return
        val s = MediaNotif.snap ?: return
        val playing = s.playing
        val pos = if (!s.live) s.posMs else PlaybackState.PLAYBACK_POSITION_UNKNOWN
        runCatching {
            ss.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(SESSION_ACTIONS)
                    .setState(
                        if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        pos,
                        if (playing) 1f else 0f
                    )
                    .build()
            )
        }
    }

    // ── Foreground promotion ─────────────────────────────────────────────────

    private fun promoteToForeground() {
        val notification = buildNotification()
        synchronized(notifLock) { shown = true }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            fgUp = true
        }.onFailure {
            // Resuming from the background can be refused by the system: still show the card.
            runCatching {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    // ── Locks ────────────────────────────────────────────────────────────────

    private fun acquireLocks() {
        // CPU wake lock – keeps decoding and audio threads alive when screen is off.
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).also {
                it.setReferenceCounted(false)
                it.acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }

        // Wi-Fi lock – prevents the radio from entering power-save mode and stalling
        // the HLS / DASH adaptive-bitrate stream mid-playback.
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(lockMode, WIFI_TAG).also { it.acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                AppLang.translate("Media Playback"),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = AppLang.translate("Shown while Aurex Browser is playing media in the background")
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    /** "Song name - YouTube" -> "Song name"; YouTube's "(3) " unread counter goes too. */
    private fun cleanTitle(raw: String): String {
        var t = raw.trim()
        for (suffix in listOf(" - YouTube Music", " - YouTube", " | YouTube")) {
            if (t.endsWith(suffix, ignoreCase = true)) t = t.dropLast(suffix.length).trim()
        }
        return t.replace(TAB_COUNT_PREFIX_RE, "")
    }

    /** The thumbnail arrived from the network: redraw the notification. */
    private fun thumbReady() {
        refresh()
    }

    // ── Media session (Dynamic Island, Fluid Cloud, HyperIsland, Atomic Island, Lockscreen, BT) ──

    private val SESSION_ACTIONS =
        PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_STOP

    private fun ensureSession(): MediaSession? {
        session?.let { return it }
        return runCatching {
            MediaSession(this, "AurexMedia").also { s ->
                @Suppress("DEPRECATION")
                s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
                s.setCallback(object : MediaSession.Callback() {
                    override fun onPlay() { if (MediaNotif.snap?.playing != true) MediaNotif.perform(MediaNotif.ACTION_TOGGLE) }
                    override fun onPause() { if (MediaNotif.snap?.playing != false) MediaNotif.perform(MediaNotif.ACTION_TOGGLE) }
                    override fun onSkipToNext() = MediaNotif.perform(MediaNotif.ACTION_NEXT)
                    override fun onSkipToPrevious() = MediaNotif.perform(MediaNotif.ACTION_PREV)
                    override fun onSeekTo(pos: Long) = MediaNotif.seekTo(pos)
                    override fun onStop() = dismissNow()
                })
                s.isActive = true
                session = s
            }
        }.getOrNull()
    }

    private fun releaseSession() {
        runCatching { session?.isActive = false }
        runCatching { session?.release() }
        session = null
        metaKey = ""
    }

    private fun syncSession(art: Bitmap, heading: String, artist: String, s: MediaNotif.Snap?, playing: Boolean): MediaSession.Token? {
        val ss = ensureSession() ?: return null
        runCatching {
            val dur = if (s != null && !s.live && s.durMs > 0) s.durMs else -1L
            val key = "$heading|$artist|$dur|${System.identityHashCode(art)}"
            if (key != metaKey) {
                metaKey = key
                ss.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, heading)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, "Aurex Browser")
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, dur)
                        .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                        .putBitmap(MediaMetadata.METADATA_KEY_ART, art)
                        .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, art)
                        .build()
                )
            }
            val pos = if (s != null && !s.live) s.posMs else PlaybackState.PLAYBACK_POSITION_UNKNOWN
            ss.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(SESSION_ACTIONS)
                    .setState(
                        if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        pos,
                        if (playing) 1f else 0f
                    )
                    .build()
            )
        }
        return ss.sessionToken
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = if (MediaNotif.local != null) {
            PendingIntent.getActivity(
                this, 205,
                Intent(this, PlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flags
            )
        } else {
            PendingIntent.getActivity(
                this, 200,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(MainActivity.EXTRA_OPEN, "media"),
                flags
            )
        }
        fun svc(code: Int, action: String) =
            PendingIntent.getService(this, code, Intent(this, BrowserMediaService::class.java).setAction(action), flags)

        val prevPi = svc(203, MediaNotif.ACTION_PREV)
        val togglePi = svc(201, MediaNotif.ACTION_TOGGLE)
        val nextPi = svc(204, MediaNotif.ACTION_NEXT)
        val dismissPi = svc(202, ACTION_DISMISS)

        val s = MediaNotif.snap
        val playing = s?.playing ?: isPlaying
        val heading = mediaTitle.ifBlank { "Aurex Browser" }
        val site = mediaHost.ifBlank { "Aurex Browser" }
        val statusSuffix = if (s?.live == true) AppLang.translate("Live").uppercase()
            else AppLang.translate(if (playing) "Playing" else "Paused")
        val sub = "$site · $statusSuffix"

        val fresh = NotifArt.mediaArt(this, mediaUrl) { thumbReady() }
        val art = if (NotifArt.has(mediaUrl)) { lastArt = fresh; fresh } else (lastArt ?: fresh)
        val token = syncSession(art, heading, site, s, playing)

        val prevAct = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_media_prev),
            AppLang.translate("Previous"),
            prevPi
        ).build()
        val playPauseAct = Notification.Action.Builder(
            Icon.createWithResource(this, if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play),
            AppLang.translate(if (playing) "Pause" else "Play"),
            togglePi
        ).build()
        val nextAct = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_pip_next),
            AppLang.translate("Next"),
            nextPi
        ).build()
        val closeAct = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notif_close),
            AppLang.translate("Close"),
            dismissPi
        ).build()

        val mediaStyle = Notification.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)
        if (token != null) mediaStyle.setMediaSession(token)

        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }
        b.setSmallIcon(R.drawable.ic_stat_aurex)
            .setLargeIcon(art)
            .setColor(accent)
            .setContentTitle(heading)
            .setContentText(sub)
            .setSubText(if (s?.live == true) AppLang.translate("Live").uppercase() else null)
            .setContentIntent(open)
            .setDeleteIntent(dismissPi)
            .addAction(prevAct)
            .addAction(playPauseAct)
            .addAction(nextAct)
            .addAction(closeAct)
            .setStyle(mediaStyle)
            .setOngoing(fgUp)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            b.setColorized(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }
}
