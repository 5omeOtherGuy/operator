package dev.operator.gate

import android.app.Activity
import android.os.Bundle

/**
 * The R3 approval screen (FOUNDATION §9.2 item 4, §9.6; ADR-0011).
 *
 * Transparent, excluded from recents and `noHistory`, with a `BiometricPrompt` of
 * `BIOMETRIC_STRONG` and no device-credential fallback. It is reached only after the volume-down hold
 * of §9.2 item 3, and every outcome (approve, reject, void) is audited. S8 implements it.
 */
class GateActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
