package com.aurex.browser

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

/**
 * The two custom notification bodies (see res/layout/notif_card_*.xml): a collapsed card and an
 * expanded card, drawn in the iOS style - rounded thumbnail, semibold title, one grey line,
 * a thin rounded progress bar, a blue round / capsule button.
 */
object NotifViews {
    class Btn(val label: String, val pi: PendingIntent)

    /**
     * Xiaomi / Redmi / POCO (HyperOS) draw a column for the app icon at the left of every notification and push the
     * app's own layout to the right of it (16dp card padding + 24dp icon + 12dp gap = 52dp), which left an empty band
     * under the icon. The cards pull themselves back by [PULL_LEFT_DP] so they start where the icon starts (16dp, the
     * same margin the right edge has). Other phones keep the plain layout. To tune: change [PULL_LEFT_DP]; 0 = off.
     */
    private const val PULL_LEFT_DP = 36f
    private val pullLeft: Boolean by lazy {
        Build.VERSION.SDK_INT >= 31 && PULL_LEFT_DP > 0f &&
            listOf(Build.MANUFACTURER, Build.BRAND).any {
                it.equals("xiaomi", true) || it.equals("redmi", true) || it.equals("poco", true)
            }
    }

    private fun flush(v: RemoteViews): RemoteViews {
        if (pullLeft && Build.VERSION.SDK_INT >= 31) {
            v.setViewLayoutMargin(R.id.root, RemoteViews.MARGIN_START, -PULL_LEFT_DP, TypedValue.COMPLEX_UNIT_DIP)
        }
        return v
    }

    /** Collapsed card. [progress] < 0 hides the bar; [round] is the small blue circular action (icon + intent). */
    fun small(
        ctx: Context,
        card: Bitmap,
        title: String,
        sub: String,
        progress: Int,
        indeterminate: Boolean,
        roundIcon: Int,
        round: PendingIntent?
    ): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.notif_card_small)
        v.setImageViewBitmap(R.id.thumb, card)
        v.setTextViewText(R.id.title, title)
        v.setTextViewText(R.id.sub, sub)
        bar(v, progress, indeterminate)
        if (round != null) {
            v.setViewVisibility(R.id.btn, View.VISIBLE)
            v.setImageViewResource(R.id.btn, roundIcon)
            v.setOnClickPendingIntent(R.id.btn, round)
        } else {
            v.setViewVisibility(R.id.btn, View.GONE)
        }
        return flush(v)
    }

    /** Expanded card: adds a detail line and up to two capsule buttons. */
    fun big(
        ctx: Context,
        card: Bitmap,
        title: String,
        sub: String,
        detail: String,
        progress: Int,
        indeterminate: Boolean,
        a: Btn?,
        b: Btn?
    ): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.notif_card_big)
        v.setImageViewBitmap(R.id.thumb, card)
        v.setTextViewText(R.id.title, title)
        v.setTextViewText(R.id.sub, sub)
        if (detail.isNotBlank()) {
            v.setViewVisibility(R.id.detail, View.VISIBLE)
            v.setTextViewText(R.id.detail, detail)
        } else {
            v.setViewVisibility(R.id.detail, View.GONE)
        }
        bar(v, progress, indeterminate)
        pill(v, R.id.btn_a, a)
        pill(v, R.id.btn_b, b)
        return flush(v)
    }

    private fun bar(v: RemoteViews, progress: Int, indeterminate: Boolean) {
        if (progress < 0 && !indeterminate) {
            v.setViewVisibility(R.id.progress, View.GONE)
        } else {
            v.setViewVisibility(R.id.progress, View.VISIBLE)
            v.setProgressBar(R.id.progress, 100, progress.coerceIn(0, 100), indeterminate)
        }
    }

    private fun pill(v: RemoteViews, id: Int, b: Btn?) {
        if (b == null) {
            v.setViewVisibility(id, View.GONE)
        } else {
            v.setViewVisibility(id, View.VISIBLE)
            v.setTextViewText(id, b.label)
            v.setOnClickPendingIntent(id, b.pi)
        }
    }

    // ── Media player card (background playback) ─────────────────────────────

    /** Everything one redraw of the player card needs. [accent] is the app's accent colour (opaque). */
    class MediaUi(
        val title: String,
        val sub: String,
        val bar: Bitmap,
        val left: String,
        val right: String,
        val playing: Boolean,
        val hasNext: Boolean,
        val accent: Int,
        val night: Boolean
    )

    class MediaActs(val toggle: PendingIntent, val prev: PendingIntent, val next: PendingIntent)

    fun mediaSmall(ctx: Context, card: Bitmap, ui: MediaUi, acts: MediaActs): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.notif_media_small)
        v.setImageViewBitmap(R.id.thumb, card)
        v.setTextViewText(R.id.title, ui.title)
        v.setTextViewText(R.id.sub, ui.sub)
        v.setImageViewBitmap(R.id.bar, ui.bar)
        transport(v, ui, acts)
        return flush(v)
    }

    fun mediaBig(ctx: Context, card: Bitmap, ui: MediaUi, acts: MediaActs): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.notif_media_big)
        v.setImageViewBitmap(R.id.thumb, card)
        v.setTextViewText(R.id.title, ui.title)
        v.setTextViewText(R.id.sub, ui.sub)
        v.setImageViewBitmap(R.id.bar, ui.bar)
        v.setTextViewText(R.id.t_left, ui.left)
        v.setTextViewText(R.id.t_right, ui.right)
        transport(v, ui, acts)
        return flush(v)
    }

    /** prev / next: plain glyphs in the label colour; play / pause: white glyph on an accent disc (the app's primary button). */
    private fun transport(v: RemoteViews, ui: MediaUi, acts: MediaActs) {
        val ink = if (ui.night) 0xFFFFFFFF.toInt() else 0xFF1C1C1E.toInt()
        v.setInt(R.id.btn_prev, "setColorFilter", ink)
        v.setInt(R.id.btn_next, "setColorFilter", ink)
        v.setInt(R.id.btn_next, "setImageAlpha", if (ui.hasNext) 255 else 90)
        v.setInt(R.id.btn_play_bg, "setColorFilter", ui.accent)
        v.setInt(R.id.btn_play_icon, "setColorFilter", 0xFFFFFFFF.toInt())
        v.setImageViewResource(R.id.btn_play_icon, if (ui.playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play)
        v.setContentDescription(R.id.btn_play, if (ui.playing) "Pause" else "Play")
        v.setContentDescription(R.id.btn_prev, "Previous")
        v.setContentDescription(R.id.btn_next, "Next")
        v.setOnClickPendingIntent(R.id.btn_play, acts.toggle)
        v.setOnClickPendingIntent(R.id.btn_prev, acts.prev)
        v.setOnClickPendingIntent(R.id.btn_next, acts.next)
    }
}
