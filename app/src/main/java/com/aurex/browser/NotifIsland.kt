package com.aurex.browser

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import org.json.JSONObject

/**
 * Cross-OS notification & Dynamic Island adaptation layer for Aurex Browser:
 *  - COS (ColorOS / RealmeUI) -> Fluid Cloud + Android 16 Live Updates + Native MediaStyle
 *  - OOS (OxygenOS)           -> Live Alerts + Android 16 Live Updates + Native MediaStyle
 *  - HOS (HyperOS / MIUI)     -> HyperOS 2/3 Super Island (`miui.focus.param`) + Android 16 Live Updates
 *  - FOS (FuntouchOS/OriginOS)-> Atomic Island + Android 16 Live Updates + Native MediaStyle
 *  - XOS (Infinix / TECNO)    -> Dynamic Bar + Native MediaStyle
 *  - One UI / Stock Android   -> Now Bar + Android 16 Live Updates (`Notification.ProgressStyle`)
 */
object NotifIsland {

    enum class OsFamily { HYPEROS, COLOROS_OOS, FUNTOUCH_ORIGIN, XOS_TRANSSION, ONEUI, AOSP }

    private const val EXTRA_REQUEST_PROMOTED = "android.requestPromotedOngoing"
    private const val EXTRA_SHORT_CRITICAL = "android.shortCriticalText"
    private const val ACTION_PROMOTION_SETTINGS = "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS"

    /** Controlled by Settings -> Appearance -> Dynamic Island. */
    @Volatile var enabled: Boolean = true

    fun init(ctx: Context) {
        runCatching {
            enabled = ctx.applicationContext
                .getSharedPreferences("liquid", Context.MODE_PRIVATE)
                .getBoolean("dynamicIsland", true)
        }
    }

    val osFamily: OsFamily by lazy { detectOs() }

    private fun sysProp(key: String): String = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java, String::class.java).invoke(null, key, "") as? String ?: ""
    }.getOrDefault("")

    private fun detectOs(): OsFamily {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        return when {
            m in listOf("xiaomi", "redmi", "poco") || b in listOf("xiaomi", "redmi", "poco") ||
                sysProp("ro.mi.os.version.name").isNotBlank() || sysProp("ro.miui.ui.version.name").isNotBlank() ->
                OsFamily.HYPEROS
            m in listOf("oppo", "oneplus", "realme") || b in listOf("oppo", "oneplus", "realme") ||
                sysProp("ro.build.version.oplusrom").isNotBlank() ->
                OsFamily.COLOROS_OOS
            m in listOf("vivo", "iqoo") || b in listOf("vivo", "iqoo") ||
                sysProp("ro.vivo.os.version").isNotBlank() ->
                OsFamily.FUNTOUCH_ORIGIN
            m in listOf("infinix", "tecno", "itel") || b in listOf("infinix", "tecno", "itel") ->
                OsFamily.XOS_TRANSSION
            m == "samsung" || b == "samsung" ->
                OsFamily.ONEUI
            else -> OsFamily.AOSP
        }
    }

    val supportsAndroid16LiveUpdates: Boolean
        get() = Build.VERSION.SDK_INT >= 36

    fun canPostLiveUpdates(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        return runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
            NotificationManager::class.java.getMethod("canPostPromotedNotifications").invoke(nm) as? Boolean ?: false
        }.getOrDefault(false)
    }

    fun openLiveUpdatesSettings(ctx: Context) {
        val pkg = ctx.packageName
        if (Build.VERSION.SDK_INT >= 36) {
            val ok = runCatching {
                val i = Intent(ACTION_PROMOTION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            }.isSuccess
            if (ok) return
        }
        runCatching {
            val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }

    fun buildAndroid16LiveDownload(
        ctx: Context,
        channelId: String,
        openIntent: PendingIntent,
        title: String,
        line: String,
        subText: String?,
        chipText: String,
        thumb: Bitmap?,
        progress: Int,
        indeterminate: Boolean,
        ongoing: Boolean,
        accent: Int,
        primaryLabel: String,
        primaryIcon: Int,
        primaryPi: PendingIntent,
        secondaryLabel: String?,
        secondaryIcon: Int,
        secondaryPi: PendingIntent?
    ): Notification? {
        if (!enabled || Build.VERSION.SDK_INT < 36) return null
        return runCatching {
            val psClass = Class.forName("android.app.Notification\$ProgressStyle")
            val segClass = Class.forName("android.app.Notification\$ProgressStyle\$Segment")
            val style = psClass.getDeclaredConstructor().newInstance() as Notification.Style

            val seg = segClass.getConstructor(Int::class.javaPrimitiveType).newInstance(100)
            segClass.getMethod("setColor", Int::class.javaPrimitiveType).invoke(seg, accent)
            psClass.getMethod("addProgressSegment", segClass).invoke(style, seg)
            runCatching {
                psClass.getMethod("setStyledByProgress", Boolean::class.javaPrimitiveType).invoke(style, true)
            }
            if (indeterminate || progress < 0) {
                psClass.getMethod("setProgressIndeterminate", Boolean::class.javaPrimitiveType).invoke(style, true)
            } else {
                psClass.getMethod("setProgress", Int::class.javaPrimitiveType).invoke(style, progress.coerceIn(0, 100))
            }

            val b = Notification.Builder(ctx, channelId)
                .setSmallIcon(R.drawable.ic_notif_download)
                .setColor(accent)
                .setContentTitle(title)
                .setContentText(line)
                .setContentIntent(openIntent)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(if (ongoing) Notification.CATEGORY_PROGRESS else Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setStyle(style)

            if (!subText.isNullOrBlank()) b.setSubText(subText)
            if (thumb != null) b.setLargeIcon(thumb)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }

            b.addAction(
                Notification.Action.Builder(Icon.createWithResource(ctx, primaryIcon), primaryLabel, primaryPi).build()
            )
            if (secondaryLabel != null && secondaryPi != null) {
                b.addAction(
                    Notification.Action.Builder(Icon.createWithResource(ctx, secondaryIcon), secondaryLabel, secondaryPi).build()
                )
            }

            val shortText = chipText.take(7)
            runCatching {
                Notification.Builder::class.java
                    .getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                    .invoke(b, ongoing)
            }
            runCatching {
                Notification.Builder::class.java
                    .getMethod("setShortCriticalText", CharSequence::class.java)
                    .invoke(b, shortText)
            }

            val extras = Bundle().apply {
                putBoolean(EXTRA_REQUEST_PROMOTED, ongoing)
                putString(EXTRA_SHORT_CRITICAL, shortText)
            }
            attachHyperOsFocusExtras(ctx, extras, title, line, shortText, progress, indeterminate, ongoing, accent)
            b.addExtras(extras)
            b.build()
        }.getOrNull()
    }

    fun applyIslandExtras(
        ctx: Context,
        extras: Bundle,
        title: String,
        subtitle: String,
        chipText: String,
        progress: Int,
        indeterminate: Boolean,
        ongoing: Boolean,
        accent: Int
    ) {
        if (!enabled) return
        val shortText = chipText.take(7)
        extras.putBoolean(EXTRA_REQUEST_PROMOTED, ongoing)
        extras.putString(EXTRA_SHORT_CRITICAL, shortText)
        attachHyperOsFocusExtras(ctx, extras, title, subtitle, shortText, progress, indeterminate, ongoing, accent)
    }

    private fun attachHyperOsFocusExtras(
        ctx: Context,
        extras: Bundle,
        title: String,
        subtitle: String,
        chipText: String,
        progress: Int,
        indeterminate: Boolean,
        ongoing: Boolean,
        accent: Int
    ) {
        if (osFamily != OsFamily.HYPEROS || Build.VERSION.SDK_INT < 33) return
        runCatching {
            val pct = progress.coerceIn(0, 100)
            val hex = String.format("#%06X", accent and 0xFFFFFF)
            val island = JSONObject().apply {
                put("islandProperty", 1)
                put("smallIslandArea", JSONObject().apply {
                    put("combinePicInfo", JSONObject().apply {
                        put("picInfo", JSONObject().apply {
                            put("type", 1)
                            put("pic", "miui.focus.pic_app")
                        })
                        put("progressInfo", JSONObject().apply {
                            put("progress", if (indeterminate) 50 else pct)
                            put("colorReach", hex)
                            put("colorUnReach", "#44FFFFFF")
                        })
                    })
                })
                put("bigIslandArea", JSONObject().apply {
                    put("imageTextInfoLeft", JSONObject().apply {
                        put("type", 1)
                        put("picInfo", JSONObject().apply {
                            put("type", 1)
                            put("pic", "miui.focus.pic_app")
                        })
                        put("textInfo", JSONObject().apply {
                            put("title", title.take(18))
                            put("narrowFont", true)
                        })
                    })
                    put("textInfoRight", JSONObject().apply {
                        put("title", chipText)
                        put("colorTitle", hex)
                        put("narrowFont", true)
                    })
                })
            }
            val paramV2 = JSONObject().apply {
                put("protocol", 1)
                put("business", "aurex_download")
                put("ticker", chipText)
                put("tickerPic", "miui.focus.pic_app")
                put("enableFloat", false)
                put("updatable", true)
                put("filterWhenNoPermission", false)
                put("cancel", !ongoing)
                put("baseInfo", JSONObject().apply {
                    put("type", 2)
                    put("title", title.take(40))
                    put("content", subtitle.take(60))
                })
                if (!indeterminate && progress >= 0) {
                    put("progressInfo", JSONObject().apply {
                        put("progress", pct)
                        put("colorProgress", hex)
                    })
                }
                put("param_island", island)
            }
            extras.putString("miui.focus.param", JSONObject().apply { put("param_v2", paramV2) }.toString())
            extras.putBundle("miui.focus.pics", Bundle().apply {
                putParcelable("miui.focus.pic_app", Icon.createWithResource(ctx, R.drawable.ic_notif_download))
            })
        }
    }
}
