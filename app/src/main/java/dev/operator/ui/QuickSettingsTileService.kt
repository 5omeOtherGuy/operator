package dev.operator.ui

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.operator.R

/**
 * The quick settings tile (FOUNDATION §11; ADR-0016).
 *
 * §11: the tile opens the command sheet, never a task, and never approves anything on its own — the
 * owner channel is operator's activities only (§9.3 item 1, S-10). F0 declares the tile and its
 * state; S11 wires [onClick] to the command sheet.
 */
class QuickSettingsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.app_name)
            icon = Icon.createWithResource(this@QuickSettingsTileService, android.R.drawable.ic_menu_compass)
            updateTile()
        }
    }

    /** F0 placeholder: S11 opens the command sheet here; no task ever starts from this tile (§11). */
    override fun onClick() {
        super.onClick()
    }
}
