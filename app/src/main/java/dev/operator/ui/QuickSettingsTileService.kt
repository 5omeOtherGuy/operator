package dev.operator.ui

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.operator.R

/**
 * The quick settings tile (FOUNDATION §11; ADR-0016).
 *
 * The tile opens the command screen. It never starts a task and never approves anything on its own:
 * the owner channel is operator's activities only (§9.3 item 1, S-10). S11 wires it to the command
 * sheet; F0 only declares it and its state.
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

    /** §11: opens the command screen; no task starts from here. */
    override fun onClick() {
        super.onClick()
    }
}
