package com.aurex.browser

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

/** Cross-OS download notification cards (Android 7-15 fallback for COS, OOS, HOS, XOS, FOS, One UI & AOSP). */
object NotifViews {
    class Btn(val label: String, val pi: PendingIntent)

    private const val HYPEROS_PULL_LEFT_DP = 32f
    private val pullLeft: Boolean by lazy {
        Build.VERSION.SDK_INT in 31..35 && NotifIsland.osFamily == NotifIsland.OsFamily.HYPEROS
    }

    private fun alignForOs(v: RemoteViews): RemoteViews {
        if (pullLeft && Build.VERSION.SDK_INT >= 31) {
            v.setViewLayoutMargin(R.id.root, RemoteViews.MARGIN_START, -HYPEROS_PULL_LEFT_DP, TypedValue.COMPLEX_UNIT_DIP)
        }
        return v
    }

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
            v.setInt(R.id.btn, "setColorFilter", 0xFFFFFFFF.toInt())
            v.setOnClickPendingIntent(R.id.btn, round)
        } else {
            v.setViewVisibility(R.id.btn, View.GONE)
        }
        return alignForOs(v)
    }

    fun big(
        ctx: Context,
        card: Bitmap,
        title: String,
        sub: String,
        detail: String,
        progress: Int,
        indeterminate: Boolean,
        primary: Btn?,
        secondary: Btn?
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
        pill(v, R.id.btn_a, primary)
        pill(v, R.id.btn_b, secondary)
        return alignForOs(v)
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
}
