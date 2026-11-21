package com.aurex.browser

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import java.lang.ref.WeakReference

/** Every haptic event in Aurex maps to a crisp mechanical "TAK" or watch-dial "tik" (zero mushy buzz). */
enum class Hx {
    PRESS,
    TAP,
    TICK,
    TOGGLE_ON,
    TOGGLE_OFF,
    WINDOW_OPEN,
    WINDOW_CLOSE,
    HOLD,
    DRAG_START,
    DRAG_END,
    THRESHOLD,
    SUCCESS,
    ERROR,
    WARN
}

/**
 * Pure mechanical "TAK-TAK" haptic engine:
 *  - Controlled by the single Settings -> "Haptic feedback" on/off switch ([enabled]).
 *  - Routes every vibration through `VibrationAttributes.USAGE_TOUCH` (Android 13+) so modern
 *    X-axis linear motors fire on the ultra-low-latency hardware touch channel at full crispness.
 *  - Pre-builds every `VibrationEffect` once in [init] (0 allocations per tap).
 *  - Single razor-sharp `PRIMITIVE_CLICK` (1.0f, zero ring-out) for all taps, switches, buttons,
 *    window open & window collapse; crisp `PRIMITIVE_TICK` for slider/scrub ratchets; and cleanly
 *    separated `TAK · (60ms silence) · TAK` for hold, confirm, warn, and error.
 */
object Haptics {
    @Volatile var enabled: Boolean = true

    private var vibrator: Vibrator? = null
    private var anchor: WeakReference<View>? = null
    private var lastAt = 0L
    private var lastTickAt = 0L
    private var inited = false
    private var hasMotor = false

    // Pre-built per-Hx hardware effects (API 26+) and touch attributes (API 33+)
    private var effects: Array<VibrationEffect?>? = null
    private var touchAttrs: Any? = null

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        runCatching {
            val app = ctx.applicationContext
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator = v
            hasMotor = v != null && v.hasVibrator()
            if (hasMotor && v != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    touchAttrs = VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_TOUCH)
                        .build()
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    val hasClick = primitive(v, VibrationEffect.Composition.PRIMITIVE_CLICK)
                    val hasTick = primitive(v, VibrationEffect.Composition.PRIMITIVE_TICK)
                    val arr = arrayOfNulls<VibrationEffect>(Hx.values().size)
                    for (hx in Hx.values()) {
                        arr[hx.ordinal] = buildEffect(hx, hasClick, hasTick)
                    }
                    effects = arr
                }
            }
        }
    }

    private fun primitive(v: Vibrator, id: Int): Boolean =
        runCatching { Build.VERSION.SDK_INT >= 30 && v.areAllPrimitivesSupported(id) }.getOrDefault(false)

    private fun buildEffect(kind: Hx, hasClick: Boolean, hasTick: Boolean): VibrationEffect? {
        // Tier 1 (Android 11+ with LRA primitives): pure single-cycle PRIMITIVE_CLICK ("TAK") & PRIMITIVE_TICK ("tik")
        if (Build.VERSION.SDK_INT >= 30 && (hasClick || hasTick)) {
            val clickId = if (hasClick) VibrationEffect.Composition.PRIMITIVE_CLICK else VibrationEffect.Composition.PRIMITIVE_TICK
            val tickId = if (hasTick) VibrationEffect.Composition.PRIMITIVE_TICK else VibrationEffect.Composition.PRIMITIVE_CLICK
            return runCatching {
                val c = VibrationEffect.startComposition()
                when (kind) {
                    // Crisp watch-dial tik for slider steps & drag ticks
                    Hx.PRESS -> c.addPrimitive(tickId, 0.85f)
                    Hx.TICK -> c.addPrimitive(tickId, 0.95f)
                    // Pure punchy single mechanical TAK everywhere (zero overlapping buzz!)
                    Hx.TAP -> c.addPrimitive(clickId, 1.00f)
                    Hx.TOGGLE_ON -> c.addPrimitive(clickId, 1.00f)
                    Hx.TOGGLE_OFF -> c.addPrimitive(clickId, 0.86f)
                    Hx.WINDOW_OPEN -> c.addPrimitive(clickId, 0.96f)
                    Hx.WINDOW_CLOSE -> c.addPrimitive(clickId, 0.84f)
                    Hx.DRAG_START -> c.addPrimitive(clickId, 0.96f)
                    Hx.DRAG_END -> c.addPrimitive(clickId, 0.86f)
                    Hx.THRESHOLD -> c.addPrimitive(clickId, 1.00f)
                    // Cleanly separated TAK ... TAK (58-68ms silence gap -> zero blur/buzz!)
                    Hx.HOLD -> {
                        c.addPrimitive(clickId, 1.00f)
                        c.addPrimitive(clickId, 0.92f, 58)
                    }
                    Hx.WARN -> {
                        c.addPrimitive(clickId, 1.00f)
                        c.addPrimitive(clickId, 0.85f, 55)
                    }
                    Hx.SUCCESS -> {
                        c.addPrimitive(clickId, 0.85f)
                        c.addPrimitive(clickId, 1.00f, 62)
                    }
                    Hx.ERROR -> {
                        c.addPrimitive(clickId, 1.00f)
                        c.addPrimitive(clickId, 1.00f, 68)
                    }
                }
                c.compose()
            }.getOrNull()
        }

        // Tier 2 (Android 10+): hardware-tuned predefined clicks (never mushy!)
        if (Build.VERSION.SDK_INT >= 29) {
            val id = when (kind) {
                Hx.PRESS, Hx.TICK -> VibrationEffect.EFFECT_TICK
                Hx.TAP, Hx.TOGGLE_ON, Hx.TOGGLE_OFF, Hx.WINDOW_OPEN, Hx.WINDOW_CLOSE, Hx.DRAG_START, Hx.DRAG_END -> VibrationEffect.EFFECT_CLICK
                Hx.THRESHOLD, Hx.HOLD, Hx.WARN -> VibrationEffect.EFFECT_HEAVY_CLICK
                Hx.SUCCESS, Hx.ERROR -> VibrationEffect.EFFECT_DOUBLE_CLICK
            }
            return runCatching { VibrationEffect.createPredefined(id) }.getOrNull()
        }

        // Tier 3 (Android 8-9): ultra-short sharp impulse at max amplitude
        if (Build.VERSION.SDK_INT >= 26) {
            return runCatching {
                when (kind) {
                    Hx.PRESS, Hx.TICK -> VibrationEffect.createOneShot(7L, 210)
                    Hx.HOLD, Hx.SUCCESS, Hx.ERROR, Hx.WARN ->
                        VibrationEffect.createWaveform(longArrayOf(0L, 11L, 58L, 11L), intArrayOf(0, 255, 0, 240), -1)
                    else -> VibrationEffect.createOneShot(11L, 255)
                }
            }.getOrNull()
        }
        return null
    }

    /** Called by the theme of every window (browser, player) so background events know where to fire. */
    fun attach(view: View) {
        if (anchor?.get() !== view) anchor = WeakReference(view)
    }

    fun fire(view: View?, kind: Hx) {
        if (!enabled) return
        if (view != null) {
            if (anchor?.get() !== view) anchor = WeakReference(view)
        } else {
            val a = anchor?.get() ?: return
            if (!a.hasWindowFocus()) return
        }
        if (!gate(kind)) return
        if (vibrateNow(kind)) return
        val target = view ?: anchor?.get() ?: return
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) viewHaptic(target, kind)
        else target.post { viewHaptic(target, kind) }
    }

    /** For notification buttons where the app window is not focused. */
    fun fireDetached(kind: Hx) {
        if (!enabled) return
        if (!gate(kind)) return
        if (vibrateNow(kind)) return
        val v = vibrator ?: return
        runCatching {
            @Suppress("DEPRECATION")
            v.vibrate(10L)
        }
    }

    /**
     * Prevents two back-to-back haptics (e.g. Button TAP + Window OPEN on the next 8ms frame)
     * from colliding on the LRA motor and turning a crisp "TAK" into a buzz.
     */
    private fun gate(kind: Hx): Boolean {
        val now = SystemClock.uptimeMillis()
        if (kind == Hx.TICK || kind == Hx.PRESS) {
            if (now - lastTickAt < 22L || now - lastAt < 22L) return false
            lastTickAt = now
            lastAt = now
            return true
        }
        if (now - lastAt < 36L) return false
        lastAt = now
        return true
    }

    private fun vibrateNow(kind: Hx): Boolean {
        if (!hasMotor) return false
        val v = vibrator ?: return false
        if (Build.VERSION.SDK_INT >= 26) {
            val eff = effects?.getOrNull(kind.ordinal) ?: return false
            return runCatching {
                if (Build.VERSION.SDK_INT >= 33 && touchAttrs is VibrationAttributes) {
                    v.vibrate(eff, touchAttrs as VibrationAttributes)
                } else {
                    v.vibrate(eff)
                }
                true
            }.getOrDefault(false)
        }
        return false
    }

    private fun viewHaptic(view: View, kind: Hx) {
        val sdk = Build.VERSION.SDK_INT
        val c = when (kind) {
            Hx.PRESS, Hx.TICK -> if (sdk >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
            Hx.HOLD -> HapticFeedbackConstants.LONG_PRESS
            Hx.ERROR, Hx.WARN -> if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            else -> if (sdk >= 23) HapticFeedbackConstants.CONTEXT_CLICK else HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(c)
    }
}
