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

    const val SHOW_ACTIONS = true

    @Volatile private var pushedMuted: Boolean? = null
    @Volatile private var pushedHasNext: Boolean? = null

    fun mutedOr(fallback: Boolean): Boolean = pushedMuted ?: fallback
    fun hasNextOr(fallback: Boolean): Boolean = pushedHasNext ?: fallback

    /** Window shape that follows the video. Android only accepts roughly 1:2.39 .. 2.39:1. */
    fun aspect(width: Int, height: Int): Rational {
        if (width <= 0 || height <= 0) return Rational(16, 9)
        val r = (width.toFloat() / height.toFloat()).coerceIn(0.42f, 2.38f)
        return Rational(Math.round(r * 100f).coerceAtLeast(1), 100)
    }

    /** Adds API 33+ PiP title & subtitle cleanly (no-op on older Android). */
    fun applyMeta(builder: PictureInPictureParams.Builder, title: String?, subtitle: String? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            title?.takeIf { it.isNotBlank() }?.let { builder.setTitle(it.take(64)) }
            subtitle?.takeIf { it.isNotBlank() }?.let { builder.setSubtitle(it.take(48)) }
        }
    }

    fun action(ctx: Context, icon: Icon, title: String, requestCode: Int, action: String): RemoteAction =
        RemoteAction(
            icon, title, title,
            PendingIntent.getBroadcast(
                ctx, requestCode,
                Intent(action).setPackage(ctx.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )

    // Responsive 180ms tap guard (prevents OS bounce duplicates while allowing fast +10s / play-pause taps!)
    private const val TAP_GUARD_MS = 180L
    private val lastTapAt = HashMap<String, Long>()

    @Synchronized
    fun acceptTap(action: String): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val prev = lastTapAt[action] ?: 0L
        if (now - prev < TAP_GUARD_MS) return false
        lastTapAt[action] = now
        return true
    }

    private val resIcons = HashMap<Int, Icon>()
    private fun icon(ctx: Context, res: Int): Icon =
        resIcons.getOrPut(res) { Icon.createWithResource(ctx.applicationContext, res) }

    private var cachedKey: String? = null
    private var cachedActions: List<RemoteAction> = emptyList()

    /**
     * Three tactile PiP controls (bottom centre, left to right):
     *   0 LEFT   Audio only
     *   1 CENTER Play / Pause
     *   2 RIGHT  Next video (enabled whenever a next video / playlist item is available)
     */
    fun buildThree(
        ctx: Context,
        isPlaying: Boolean,
        hasNext: Boolean,
        audioAction: String,
        toggleAction: String,
        nextAction: String,
        codeBase: Int = 100
    ): List<RemoteAction> {
        val key = "$codeBase|$isPlaying|$hasNext|$audioAction|$toggleAction|$nextAction"
        if (key == cachedKey) return cachedActions
        val audio = action(ctx, icon(ctx, R.drawable.ic_pip_audio), AppLang.translate("Audio only"), codeBase + 1, audioAction)
        val playPause = action(
            ctx,
            icon(ctx, if (isPlaying) R.drawable.ic_pip_pause else R.drawable.ic_pip_play),
            AppLang.translate(if (isPlaying) "Pause" else "Play"),
            codeBase + 2,
            toggleAction
        )
        val right = action(ctx, icon(ctx, R.drawable.ic_pip_next), AppLang.translate("Next video"), codeBase + 3, nextAction)
        right.isEnabled = hasNext
        return listOf(audio, playPause, right).also { cachedKey = key; cachedActions = it }
    }

    fun buildActions(ctx: Context, isPlaying: Boolean, isMuted: Boolean, hasNext: Boolean): List<RemoteAction> {
        if (!SHOW_ACTIONS) return emptyList()
        return buildThree(
            ctx, isPlaying, pushedHasNext ?: hasNext,
            MainActivity.ACTION_PIP_AUDIO, MainActivity.ACTION_PIP_PLAY_PAUSE, MainActivity.ACTION_PIP_NEXT, 100
        )
    }

    private var sentKey: String? = null

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

    fun resetSent() { sentKey = null }
}
