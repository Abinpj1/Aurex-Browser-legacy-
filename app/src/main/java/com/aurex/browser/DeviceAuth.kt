package com.aurex.browser

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Asks the person to prove it is them with the PHONE's own lock: fingerprint / face when the phone has one,
 * with the phone PIN, pattern or password as the fallback. Nothing is stored by Aurex, so there is no separate
 * app password to forget, and the system itself rate-limits wrong attempts.
 *
 *  - Android 11+  : system prompt, biometric or screen lock.
 *  - Android 10   : system prompt with "device credential allowed".
 *  - Android 7-9  : the system "confirm your screen lock" screen.
 */
object DeviceAuth {

    private var counter = 0

    /** True when the phone has a PIN / pattern / password (or biometrics behind one). */
    fun isAvailable(ctx: Context): Boolean {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        return km.isDeviceSecure
    }

    /**
     * Shows the system prompt. [onResult] is called once on the main thread: true when the phone lock was
     * passed, false when it was cancelled or failed. When the phone has no lock at all it returns true
     * (there is nothing the app could ask for).
     */
    fun verify(act: Activity, title: String, subtitle: String, onResult: (Boolean) -> Unit) {
        if (!isAvailable(act)) {
            onResult(true)
            return
        }
        var done = false
        fun finish(ok: Boolean) {
            if (done) return
            done = true
            onResult(ok)
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val b = BiometricPrompt.Builder(act).setTitle(title).setSubtitle(subtitle)
                if (Build.VERSION.SDK_INT >= 30) {
                    // BIOMETRIC_WEAK (face unlock counts) or the screen lock.
                    b.setAllowedAuthenticators(0xFF or 0x8000)
                } else {
                    @Suppress("DEPRECATION")
                    b.setDeviceCredentialAllowed(true)
                }
                b.build().authenticate(
                    CancellationSignal(),
                    ContextCompat.getMainExecutor(act),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = finish(true)
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = finish(false)
                    }
                )
            } else {
                val km = act.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                @Suppress("DEPRECATION")
                val intent = km.createConfirmDeviceCredentialIntent(title, subtitle)
                val comp = act as? ComponentActivity
                if (intent == null || comp == null) {
                    finish(false)
                    return
                }
                val key = "aurex_device_auth_" + (counter++)
                var launcher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>? = null
                launcher = comp.activityResultRegistry.register(key, ActivityResultContracts.StartActivityForResult()) { r ->
                    launcher?.unregister()
                    finish(r.resultCode == Activity.RESULT_OK)
                }
                launcher?.launch(intent)
            }
        } catch (_: Throwable) {
            finish(false)
        }
    }
}
