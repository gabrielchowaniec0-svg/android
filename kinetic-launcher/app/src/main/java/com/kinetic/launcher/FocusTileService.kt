package com.kinetic.launcher

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * "Focus" tile for the quick-settings panel: tap to switch your last Focus on or
 * off; long-press (or tap while locked) to open the Focus picker.
 */
class FocusTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        FocusModes.init(this)
        val active = FocusModes.active.value
        if (active != null) FocusModes.turnOff(this) else FocusModes.turnOn(this, FocusModes.last(this).id)
        refresh()
    }

    private fun refresh() {
        FocusModes.init(this)
        val tile = qsTile ?: return
        val active = FocusModes.active.value
        tile.state = if (active != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = active?.name ?: "Focus"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = if (active != null) "On" else FocusModes.last(this).name
        tile.updateTile()
    }

    companion object {
        /** Opens the picker from the tile (used for long-press via the preferences action). */
        fun pickerIntent(service: TileService): PendingIntent = PendingIntent.getActivity(
            service, 0, Intent(service, FocusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
