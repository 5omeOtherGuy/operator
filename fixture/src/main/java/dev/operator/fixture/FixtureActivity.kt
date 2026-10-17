package dev.operator.fixture

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The fixture UI (FOUNDATION §12; ADR-0015; research 07 §R2).
 *
 * Its default view reports the fixture status; `--es mode <name>` selects one of the S-04 extended
 * fixtures (research 07 §R2): a smart-reply chip, a one-tap reaction, the share sheet, a dialog whose
 * positive button is "OK", and an icon-only send button without a view id. These views exist so the
 * executor's §7.3/§7.4 rules can be exercised against realistic shapes; the fixtures must not depend on
 * tapping data-sensitive or `filterTouchesWhenObscured` views (R7).
 *
 * Everything it shows carries the `op-test` data prefix (OQ-1).
 */
class FixtureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent?.getStringExtra(EXTRA_MODE).orEmpty()
        setContentView(if (mode.isEmpty()) statusView() else fixtureView(mode))
    }

    private fun statusView(): LinearLayout = column().apply {
        addView(headline("operator fixture (dev.operator.fixture)"))
        addView(body("localhost pages on http://127.0.0.1:${Pages.DEFAULT_PORT}"))
        addView(body("op-test fixture data; the page server runs in FixtureService"))
        for (path in listOf(Pages.PATH_RECIPE, Pages.PATH_DATE, Pages.PATH_WEATHER, Pages.PATH_EXFIL, Pages.PATH_S01)) {
            addView(body("op-test page $path"))
        }
    }

    private fun fixtureView(mode: String): LinearLayout = column().apply {
        addView(headline("op-test fixture: $mode"))
        when (mode) {
            MODE_SMART_REPLY -> {
                addView(body("op-test message: are you coming?"))
                addView(Button(this@FixtureActivity).apply { text = "Thanks!" })
            }
            MODE_REACTION -> addView(Button(this@FixtureActivity).apply { text = "\uD83D\uDC4D" })
            MODE_SHARE -> addView(Button(this@FixtureActivity).apply { text = "Share" })
            MODE_ICON_ONLY_SEND -> {
                addView(body("op-test draft"))
                addView(EditText(this@FixtureActivity).apply { hint = "op-test message" })
                addView(ImageButton(this@FixtureActivity).apply { contentDescription = "Send" })
            }
            MODE_DIALOG -> AlertDialog.Builder(this@FixtureActivity)
                .setTitle("op-test")
                .setMessage("op-test confirm this?")
                .setPositiveButton("OK") { _, _ -> }
                .setNegativeButton("Cancel") { _, _ -> }
                .show()
            else -> addView(body("op-test unknown mode: $mode"))
        }
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    private fun headline(text: String) = TextView(this).apply { this.text = text; textSize = 18f }

    private fun body(text: String) = TextView(this).apply { this.text = text }

    companion object {
        const val EXTRA_MODE = "mode"

        const val MODE_SMART_REPLY = "smart_reply"
        const val MODE_REACTION = "reaction"
        const val MODE_SHARE = "share"
        const val MODE_DIALOG = "dialog"
        const val MODE_ICON_ONLY_SEND = "icon_only_send"
    }
}
