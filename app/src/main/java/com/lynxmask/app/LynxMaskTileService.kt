package com.lynxmask.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

private const val TAG = "LynxMask_Tile"

class LynxMaskTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "LynxMask"
            contentDescription = "Sprawdź schowek przed wysłaniem do AI"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ClipboardCheckActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
        Log.i(TAG, "Kafelek kliknięty — startuję ClipboardCheckActivity")
    }
}
