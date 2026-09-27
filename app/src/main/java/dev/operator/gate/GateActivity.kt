package dev.operator.gate

import android.os.Bundle
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * The R3 approval screen (FOUNDATION §9.2 item 4, §9.6; ADR-0011).
 *
 * Transparent, excluded from recents and `noHistory`, with a `BiometricPrompt` of
 * `BIOMETRIC_STRONG` and no device-credential fallback. It is reached only after the volume-down hold
 * of §9.2 item 3, and every outcome (approve, reject, void) is audited. S8 implements it.
 */
class GateActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setHideOverlayWindows(true)
        window.decorView.setFilterTouchesWhenObscured(true)
        val callback = BiometricGate.pending ?: run { finish(); return }
        BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    BiometricGate.pending = null
                    callback(true)
                    finish()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    BiometricGate.pending = null
                    callback(false)
                    finish()
                }
            }).authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("Confirm operator action")
            .setNegativeButtonText("Reject")
            .setAllowedAuthenticators(BiometricPrompt.Authenticators.BIOMETRIC_STRONG)
            .build())
    }
}
