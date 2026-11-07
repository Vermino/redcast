package com.oak404.redcast.ui

import android.content.Context
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/**
 * Optional fingerprint or screen-lock gate on the app.
 *
 * While it's on, the window is also marked secure, which blanks the app in the recent-apps
 * view and blocks screenshots. Casting isn't affected.
 */
class AppLock(private val activity: AppCompatActivity) {

    private val prefs = activity.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
    private var leftAtMs = 0L

    val available: Boolean =
        BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    var enabled: Boolean
        get() = enabledState
        set(value) {
            enabledState = value
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            applySecureFlag()
            if (!value) locked = false
        }
    private var enabledState by mutableStateOf(prefs.getBoolean(KEY_ENABLED, false))

    var locked by mutableStateOf(prefs.getBoolean(KEY_ENABLED, false))
        private set

    init {
        applySecureFlag()
    }

    fun onLeftApp() {
        leftAtMs = System.currentTimeMillis()
    }

    fun onReturned() {
        if (enabled && System.currentTimeMillis() - leftAtMs > GRACE_MS) locked = true
        if (locked) prompt()
    }

    fun prompt() {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                locked = false
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Redcast")
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
    }

    private fun applySecureFlag() {
        if (enabled) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val GRACE_MS = 30_000L
        const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }
}
