package com.aurex.browser

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Looper
import android.util.Rational

/** Shared helpers for the picture-in-picture window (browser and cinema player). */
internal object PipSupport {

    /** false = the PiP window shows only the video (Android adds just its own close/expand on tap). */
    const val SHOW_ACTIONS = true

    /**
     * Latest mute / next-video state pushed through [updatePipState]. It wins over the arguments of
     * later [buildActions] calls, so a full params rebuild (aspect ratio change, entering PiP) can
     * never put the buttons back to a stale value.
     */
    @Volatile private var pushedMuted: Boolean? = null
    @Volatile private var pushedHasNext: Boolean? = null

    /** Last mute state pushed through [updatePipState], or [fallback] before anything was pushed. */
    fun mutedOr(fallback: Boolean): Boolean = pushedMuted ?: fallback

    /** Last next-video state pushed through [updatePipState], or [fallback] before anything was pushed. */
    fun hasNextOr(fallback: Boolean): Boolean = pushedHasNext ?: fallback

    /** Window shape that follows the video. Android only accepts roughly 1:2.39 .. 2.39:1. */
    fun aspect(width: Int, height: Int): Rational {
        if (width <= 0 || height <= 0) return Rational(16, 9)
        // Snapped to 2 decimals: adaptive quality changes 1280x720 -> 854x480 mid-play, and a
        // 1.7778 -> 1.7792 "new" ratio made the window re-layout (visible hitch) for nothing.
        val r = (width.toFloat() / height.toFloat()).coerceIn(0.42f, 2.38f)
        return Rational(Math.round(r * 100f).coerceAtLeast(1), 100)
    }

    /**
     * One PiP button. The Intent carries an explicit package (Android 14 does not deliver an
     * implicit broadcast to a RECEIVER_NOT_EXPORTED receiver) and the PendingIntent is
     * IMMUTABLE | UPDATE_CURRENT with its own requestCode per button (101 audio, 102 play/pause, 103 next).
     */
    fun action(ctx: Context, icon: Icon, title: String, requestCode: Int, action: String): RemoteAction =
        RemoteAction(
            icon, title, title,
            PendingIntent.getBroadcast(
                ctx, requestCode,
                Intent(action).setPackage(ctx.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )

    // ---- Tap guard ---------------------------------------------------------------------------
    // A PiP button can deliver two broadcasts for one physical tap (system re-dispatch while the
    // window animates), and a fast double tap on play/pause used to cancel itself out. One tap per
    // button per TAP_GUARD_MS is accepted; everything else is dropped.
    private const val TAP_GUARD_MS = 280L
    private val lastTapAt = HashMap<String, Long>()

    /** true = handle this tap, false = it is a duplicate / bounce and must be ignored. */
    @Synchronized
    fun acceptTap(action: String): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val prev = lastTapAt[action] ?: 0L
        if (now - prev < TAP_GUARD_MS) return false
        lastTapAt[action] = now
        return true
    }

    // ---- Icon / action cache -----------------------------------------------------------------
    // Icons are created once per resource; the 3-button list is rebuilt only when the visible state
    // (play|pause, next enabled, audio-mode) really changes, so a burst of <video> events allocates nothing.
    private val resIcons = HashMap<Int, Icon>()
    private fun icon(ctx: Context, res: Int): Icon =
        resIcons.getOrPut(res) { Icon.createWithResource(ctx.applicationContext, res) }

    private var cachedKey: String? = null
    private var cachedActions: List<RemoteAction> = emptyList()

    /**
     * The three PiP buttons, laid out by Android at the bottom centre, left to right, in list order:
     *   0 LEFT   audio-only      -> [audioAction]
     *   1 CENTER play / pause    -> [toggleAction]
     *   2 RIGHT  next video      -> [nextAction] (greyed out while [hasNext] is false)
     * Browser and cinema player share this, each with its own broadcast actions.
     */
    fun buildThree(
        ctx: Context, isPlaying: Boolean, hasNext: Boolean,
        audioAction: String, toggleAction: String, nextAction: String, codeBase: Int = 100
    ): List<RemoteAction> {
        val key = "$codeBase|$isPlaying|$hasNext|$audioAction|$toggleAction|$nextAction"
        if (key == cachedKey) return cachedActions
        val audio = action(ctx, icon(ctx, R.drawable.ic_pip_audio), "Audio only", codeBase + 1, audioAction)
        val playPause = action(
            ctx, icon(ctx, if (isPlaying) R.drawable.ic_pip_pause else R.drawable.ic_pip_play),
            if (isPlaying) "Pause" else "Play", codeBase + 2, toggleAction
        )
        val next = action(ctx, icon(ctx, R.drawable.ic_pip_next), "Next video", codeBase + 3, nextAction)
        next.isEnabled = hasNext
        return listOf(audio, playPause, next).also { cachedKey = key; cachedActions = it }
    }

    /** Browser PiP: [audio, play/pause, next]. Mute is no longer part of the window (audio-only replaces it). */
    fun buildActions(ctx: Context, isPlaying: Boolean, isMuted: Boolean, hasNext: Boolean): List<RemoteAction> {
        if (!SHOW_ACTIONS) return emptyList()
        return buildThree(
            ctx, isPlaying, pushedHasNext ?: hasNext,
            MainActivity.ACTION_PIP_AUDIO, MainActivity.ACTION_PIP_PLAY_PAUSE, MainActivity.ACTION_PIP_NEXT, 100
        )
    }

    // Last state that was actually sent to the system: identical pushes are skipped (no flicker, no IPC).
    private var sentKey: String? = null

    /**
     * Pushes the 3 PiP actions to the system, on the main thread. Only the actions are sent; the
     * window's aspect ratio and auto-enter flag are kept as they are. Called from
     * AurexWebView.PipBridge on every <video> event and optimistically after every PiP tap.
     */
    fun updatePipState(activity: Activity, isPlaying: Boolean, isMuted: Boolean, hasNext: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (activity.isFinishing || activity.isDestroyed) return
        pushedMuted = isMuted
        pushedHasNext = hasNext
        val key = "$isPlaying|$hasNext"
        if (key == sentKey) return
        val apply = Runnable {
            if (activity.isFinishing || activity.isDestroyed) return@Runnable
            runCatching {
                activity.setPictureInPictureParams(
                    PictureInPictureParams.Builder()
                        .setActions(buildActions(activity, isPlaying, isMuted, hasNext))
                        .build()
                )
                sentKey = key
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply.run() else activity.runOnUiThread(apply)
    }

    /** Forget what was sent (new PiP session / full params rebuild): the next push always goes through. */
    fun resetSent() { sentKey = null }
}
