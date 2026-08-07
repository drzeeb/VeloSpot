package de.velospot.core.tracking

import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import de.velospot.MainActivity
import de.velospot.R
import javax.inject.Inject

/**
 * Quick Settings tile controlling a ride recording with a single tap, without
 * opening the app. It shares the singleton [RideRecordingManager] with the map UI,
 * the foreground service and the widget, so the recording state is always
 * consistent across all entry points.
 *
 * As a one-tap control it is optimised for the **commute** flow: a tap **starts**
 * a recording when idle, **pauses** it while recording (e.g. boarding a train/
 * ferry) and **resumes** it when paused. Ending a ride is a deliberate action left
 * to the always-present notification action ("Stop & save"), the widget or the app.
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
        if (!manager.isRecording) {
            // Idle → start via MainActivity so the recording (and its location
            // foreground service) begins from a foreground Activity context. A
            // background foreground-service start is blocked on Android 12+ (and
            // aggressively on ColorOS/OPPO) when the app is closed. The Activity also
            // covers the missing-GPS-permission case (it opens so the user can grant
            // it, then starts once granted).
            startRecordingViaApp()
            return
        }
        // Recording → pause; paused → resume. Both act on the already-running FGS,
        // so no foreground context is required.
        manager.togglePause()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val recording = manager.isRecording
        val paused = manager.isPaused
        // Active (highlighted) only while actively recording; a paused ride and an
        // idle tile both read as inactive so the highlight means "capturing now".
        tile.state = if (recording && !paused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(
            when {
                !recording -> R.string.ride_record_start
                paused -> R.string.ride_paused
                else -> R.string.ride_recording
            }
        )
        tile.icon = Icon.createWithResource(this, R.drawable.ic_ride_recording)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // The subtitle previews the next tap's action.
            tile.subtitle = getString(
                when {
                    !recording -> R.string.ride_record_start
                    paused -> R.string.ride_resume
                    else -> R.string.ride_pause
                }
            )
        }
        tile.updateTile()
    }

    private fun startRecordingViaApp() {
        val intent = MainActivity.startRideRecordingIntent(this)
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

