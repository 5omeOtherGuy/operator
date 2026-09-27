package dev.operator.fixture

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * The fixture app's only activity (FOUNDATION §12; ADR-0015). S12 turns this into the localhost page
 * server, the notification poster and the install target the T0–T3 and S-04/S-11 fixtures need.
 */
class FixtureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "operator fixture (S12 builds it)" })
    }
}
