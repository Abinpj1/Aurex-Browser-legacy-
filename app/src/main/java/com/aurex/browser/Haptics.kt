package com.aurex.browser

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import java.lang.ref.WeakReference

/** What happened - every haptic in the app is one of these, so the feel is the same everywhere. */
enum class Hx {
    /** Finger touches something that can be pressed (very light). */
    PRESS,
    /** A button / row / chip was tapped. */
    TAP,
    /** One step of a slider, a lens slot, a list reorder slot. */
    TICK,
    TOGGLE_ON,
    TOGGLE_OFF,
    /** Press-and-hold recognised (select mode, copy link ...). */
    HOLD,
    /** A drag picked something up / put it down. */
    DRAG_START,
    DRAG_END,
    /** A swipe or drag crossed the "will commit" line, or a slider reached its end. */
    THRESHOLD,
    /** Download finished, link copied ... */
    SUCCESS,
    ERROR,
    /** Destructive action (delete). */
    WARN
}

/**
 * One place for all vibration.
 *
 *  - Honours the Settings -> "Haptic feedback" switch everywhere ([enabled]) and the
 *    "Haptic strength" choice ([level]: 0 light, 1 medium, 2 strong).
 *  - Three tiers, best first, picked once per phone:
 *      1. composition primitives (Android 11+ with a real haptic motor): crisp, scaled click / tick / thud.
 *         Every primitive is checked on its own and a missing one is replaced by the nearest one the motor
 *         does have (before, one missing primitive disabled the whole tier);
 *      2. predefined effects (Android 10+): the motor's own tuned tick / click / heavy click / double click;
 *      3. the stock "performHapticFeedback" constant that best matches the event on this Android version.
 *  - Two haptics within a few ms are merged into one (no double buzz when a tap also shows a toast), and
 *    ticks are spaced a little wider so a fast slider drag feels like a ratchet instead of a buzz.
 *  - Events fired without a view (downloads finishing) only buzz while the app window has focus.
 */
object Haptics {
    @Volatile var enabled: Boolean = true

    /** 0 = light, 1 = medium (default), 2 = strong. Read from the settings by [init], changed live by the settings page. */
    @Volatile var level: Int = 1

    private var vibrator: Vibrator? = null
    private var anchor: WeakReference<View>? = null
    private var lastAt = 0L
    private var lastTickAt = 0L
    private var inited = false

    // what this phone's motor can do
    private var hasMotor = false
    private var hasClick = false
    private var hasTick = false
    private var hasLow = false
    private var hasThud = false
    private var predefined = false

    /** Output multiplier for the primitives (clamped to 1 afterwards, so "strong" lifts the soft ones the most). */
    private fun gain(): Float = when (level) {
        0 -> 0.7f
        2 -> 1.35f
        else -> 1f
    }

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        runCatching {
            val app = ctx.applicationContext
            level = app.getSharedPreferences("liquid", Context.MODE_PRIVATE).getInt("hapticLevel", 1).coerceIn(0, 2)
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator = v
            hasMotor = v != null && v.hasVibrator()
            if (hasMotor && v != null) {
                if (Build.VERSION.SDK_INT >= 30) {
                    hasClick = primitive(v, VibrationEffect.Composition.PRIMITIVE_CLICK)
                    hasTick = primitive(v, VibrationEffect.Composition.PRIMITIVE_TICK)
                    hasLow = primitive(v, VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
                    hasThud = primitive(v, VibrationEffect.Composition.PRIMITIVE_THUD)
                }
                predefined = Build.VERSION.SDK_INT >= 29
            }
        }
    }

    private fun primitive(v: Vibrator, id: Int): Boolean =
        runCatching { Build.VERSION.SDK_INT >= 30 && v.areAllPrimitivesSupported(id) }.getOrDefault(false)

    /** Called by the theme of every window (browser, player) so events without a view know where to buzz. */
    fun attach(view: View) {
        if (anchor?.get() !== view) anchor = WeakReference(view)
    }

    fun fire(view: View?, kind: Hx) {
        if (!enabled) return
        if (view != null) {
            if (anchor?.get() !== view) anchor = WeakReference(view)
        } else {
            // event from the background (a download finished): only while our window is in front
            val a = anchor?.get() ?: return
            if (!a.hasWindowFocus()) return
        }
        if (!gate(kind)) return
        if (play(kind)) return
        val target = view ?: anchor?.get() ?: return
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) viewHaptic(target, kind)
        else target.post { viewHaptic(target, kind) }      // downloads finish on worker threads
    }

    /**
     * For taps on the notification's buttons: the app window is not in front there, so the focus gate of [fire] would
     * swallow the haptic. Same motor tiers, same Settings switch, no window needed.
     */
    fun fireDetached(kind: Hx) {
        if (!enabled) return
        if (!gate(kind)) return
        if (play(kind)) return
        val v = vibrator ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                val ms = (if (kind == Hx.ERROR) 30L else 16L) * (if (level == 2) 3 else 2) / 2
                val amp = when (level) { 0 -> 90; 2 -> 200; else -> 140 }
                v.vibrate(VibrationEffect.createOneShot(ms, amp))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(16L)
            }
        }
    }

    /** Merge near-simultaneous events; space ticks wider than everything else. */
    private fun gate(kind: Hx): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastAt < 14L) return false
        if ((kind == Hx.TICK || kind == Hx.PRESS) && now - lastTickAt < 26L) return false
        lastAt = now
        if (kind == Hx.TICK || kind == Hx.PRESS) lastTickAt = now
        return true
    }

    private fun play(kind: Hx): Boolean {
        if (!hasMotor) return false
        if (hasClick && hasTick && playPrimitives(kind)) return true
        return playPredefined(kind)
    }

    // ── Tier 1: composition primitives ───────────────────────────────────────

    private enum class P { CLICK, TICK, LOW, THUD }

    /** Id of [p] on this motor, or the closest one it has (as a pair with the scale to apply), or null. */
    private fun resolve(p: P): Pair<Int, Float>? {
        if (Build.VERSION.SDK_INT < 30) return null
        val c = VibrationEffect.Composition.PRIMITIVE_CLICK
        val t = VibrationEffect.Composition.PRIMITIVE_TICK
        val l = VibrationEffect.Composition.PRIMITIVE_LOW_TICK
        val th = VibrationEffect.Composition.PRIMITIVE_THUD
        return when (p) {
            P.CLICK -> if (hasClick) c to 1f else if (hasThud) th to 0.85f else if (hasTick) t to 1f else null
            P.TICK -> if (hasTick) t to 1f else if (hasLow) l to 1f else if (hasClick) c to 0.6f else null
            P.LOW -> if (hasLow) l to 1f else if (hasTick) t to 0.7f else if (hasClick) c to 0.4f else null
            P.THUD -> if (hasThud) th to 1f else if (hasClick) c to 1f else if (hasTick) t to 1f else null
        }
    }

    private fun playPrimitives(kind: Hx): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val v = vibrator ?: return false
        val g = gain()
        return runCatching {
            val comp = VibrationEffect.startComposition()
            var count = 0
            fun add(p: P, scale: Float, delayMs: Int = 0) {
                val (id, k) = resolve(p) ?: return
                comp.addPrimitive(id, (scale * k * g).coerceIn(0.05f, 1f), delayMs)
                count++
            }
            when (kind) {
                Hx.PRESS -> add(P.LOW, 0.45f)
                Hx.TAP -> add(P.CLICK, 0.55f)
                Hx.TICK -> add(P.TICK, 0.42f)
                // rising pair = "switch clicked on", falling pair = "switch let go"
                Hx.TOGGLE_ON -> { add(P.TICK, 0.5f); add(P.CLICK, 0.8f, 26) }
                Hx.TOGGLE_OFF -> { add(P.CLICK, 0.6f); add(P.LOW, 0.4f, 26) }
                Hx.HOLD -> { add(P.THUD, 0.7f); add(P.LOW, 0.3f, 45) }
                // picked up: light touch then a firm click; put down: click that settles
                Hx.DRAG_START -> { add(P.TICK, 0.5f); add(P.CLICK, 0.85f, 22) }
                Hx.DRAG_END -> { add(P.CLICK, 0.6f); add(P.LOW, 0.3f, 24) }
                Hx.THRESHOLD -> { add(P.CLICK, 0.9f); add(P.TICK, 0.5f, 18) }
                Hx.SUCCESS -> { add(P.LOW, 0.3f); add(P.TICK, 0.5f, 40); add(P.CLICK, 0.9f, 48) }
                Hx.ERROR -> { add(P.CLICK, 0.9f); add(P.CLICK, 0.9f, 70); add(P.THUD, 0.7f, 90) }
                Hx.WARN -> { add(P.THUD, 0.85f); add(P.LOW, 0.4f, 60) }
            }
            if (count == 0) return@runCatching false
            v.vibrate(comp.compose())
            true
        }.getOrDefault(false)
    }

    // ── Tier 2: the motor's own predefined effects (Android 10+) ─────────────

    private fun playPredefined(kind: Hx): Boolean {
        if (!predefined || Build.VERSION.SDK_INT < 29) return false
        val v = vibrator ?: return false
        val tick = VibrationEffect.EFFECT_TICK
        val click = VibrationEffect.EFFECT_CLICK
        val heavy = VibrationEffect.EFFECT_HEAVY_CLICK
        val dbl = VibrationEffect.EFFECT_DOUBLE_CLICK
        val lvl = level
        val id = when (kind) {
            Hx.PRESS, Hx.TICK -> tick
            Hx.TAP -> if (lvl == 0) tick else click
            Hx.TOGGLE_ON, Hx.DRAG_START, Hx.THRESHOLD -> if (lvl == 2) heavy else click
            Hx.TOGGLE_OFF, Hx.DRAG_END -> if (lvl == 2) click else tick
            Hx.HOLD -> if (lvl == 0) click else heavy
            Hx.SUCCESS -> dbl
            Hx.ERROR -> dbl
            Hx.WARN -> heavy
        }
        return runCatching {
            v.vibrate(VibrationEffect.createPredefined(id))
            true
        }.getOrDefault(false)
    }

    // ── Tier 3: stock view haptics ───────────────────────────────────────────

    private fun viewHaptic(view: View, kind: Hx) {
        val sdk = Build.VERSION.SDK_INT
        val c = when (kind) {
            Hx.PRESS -> HapticFeedbackConstants.CLOCK_TICK
            Hx.TAP -> HapticFeedbackConstants.KEYBOARD_TAP
            Hx.TICK -> if (sdk >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
            Hx.TOGGLE_ON -> when {
                sdk >= 34 -> HapticFeedbackConstants.TOGGLE_ON
                sdk >= 30 -> HapticFeedbackConstants.CONFIRM
                else -> HapticFeedbackConstants.KEYBOARD_TAP
            }
            Hx.TOGGLE_OFF -> if (sdk >= 34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CLOCK_TICK
            Hx.HOLD -> HapticFeedbackConstants.LONG_PRESS
            Hx.DRAG_START -> when {
                sdk >= 34 -> HapticFeedbackConstants.DRAG_START
                sdk >= 30 -> HapticFeedbackConstants.GESTURE_START
                else -> HapticFeedbackConstants.LONG_PRESS
            }
            Hx.DRAG_END -> if (sdk >= 30) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.CLOCK_TICK
            Hx.THRESHOLD -> when {
                sdk >= 34 -> HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                else -> HapticFeedbackConstants.CONTEXT_CLICK
            }
            Hx.SUCCESS -> if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP
            Hx.ERROR -> if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            Hx.WARN -> HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(c, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
    }
}
