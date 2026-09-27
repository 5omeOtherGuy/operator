package dev.operator.ui

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import dev.operator.R

/**
 * Operator's settings (FOUNDATION §11; ADR-0016): models and decide backend, keep-warm, denylist and
 * limits, audit viewer with the chain fingerprint, Disarm/Re-arm, and from M2 "Release device owner"
 * behind BiometricPrompt (§9.4).
 *
 * Not exported: owner input is accepted only inside our own activities (§9.3 item 1). S11 builds it.
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = getString(R.string.settings_screen_stub) })
    }
}
