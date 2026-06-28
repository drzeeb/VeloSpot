package de.velospot.core.tracking

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import de.velospot.MainActivity
import de.velospot.R
import de.velospot.core.location.hasLocationPermission
import javax.inject.Inject

/**
 * Quick Settings tile that starts/stops a ride recording with a single tap,
 * without opening the app. It shares the singleton [RideRecordingManager] with the
 * map UI, the foreground service and the widget, so the recording state is always
 * consistent across all entry points.
 *
 * When location permission is missing, the tile opens the app instead so the user
 * can grant it (a recording is useless without GPS).
 */
@AndroidEntryPoint
class RideRecordingTileService : TileService() {

    @Inject lateinit var manager: RideRecordingManager

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (!manager.isRecording && !hasLocationPermission(this)) {
            // Can't record without GPS — bounce the user into the app to grant it.
            openApp()
            return
        }
        manager.toggle()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val recording = manager.isRecording
        tile.state = if (recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(
            if (recording) R.string.ride_recording else R.string.ride_record_start
        )
        tile.icon = Icon.createWithResource(this, R.drawable.ic_ride_recording)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                if (recording) R.string.ride_stop else R.string.ride_record_start
            )
        }
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}

