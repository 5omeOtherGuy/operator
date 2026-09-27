package dev.operator.ui

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import dev.operator.R

/**
 * The command screen (FOUNDATION §11; ADR-0016). It is the launcher and the only place a task starts
 * from: the owner's words are the only source of goals, and the QS tile and the FGS notification's
 * *Open* action lead here (§9.3 item 1, S-10).
 *
 * S11 builds the command sheet, the history and the live step log. F0 shows a placeholder.
 */
class CommandActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = getString(R.string.command_screen_stub) })
    }
}
