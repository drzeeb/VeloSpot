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
        // Derive the label/subtitle/highlight from the single source of truth (the
        // shared manager) via the pure, unit-tested mapping so a re-render — however
        // it was triggered (onStartListening, onClick, or the manager's
        // requestListeningState re-invocation on every state change) — always paints
        // the current recording/paused/idle state and never goes stale.
        val render = tileRenderState(recording = manager.isRecording, paused = manager.isPaused)
        tile.state = if (render.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(render.labelRes)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_ride_recording)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // The subtitle previews the next tap's action.
            tile.subtitle = getString(render.subtitleRes)
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

/**
 * How the Quick Settings tile should render for a given recording state — kept
 * Android-free (string resource ids + a plain [active] flag) so the
 * recording→label/state mapping is unit-testable without a live `TileService`.
 */
internal data class RideTileRenderState(
    /** `true` → [Tile.STATE_ACTIVE] (highlighted); `false` → [Tile.STATE_INACTIVE]. */
    val active: Boolean,
    /** Tile label string resource. */
    val labelRes: Int,
    /** Subtitle string resource (previews the next tap's action; API 29+ only). */
    val subtitleRes: Int,
)

/**
 * Pure mapping from the recording state to the tile's rendered label/subtitle and
 * active/inactive highlight. Extracted from [RideRecordingTileService.updateTile]
 * so it can be exercised by a plain JVM unit test (the `TileService` glue itself is
 * not JVM-testable).
 *
 * The tile is **active (highlighted) only while actively recording**; a paused ride
 * and an idle tile both read as inactive, so the highlight always means
 * "capturing now". The subtitle previews the next tap: start when idle, pause while
 * recording, resume while paused.
 */
internal fun tileRenderState(recording: Boolean, paused: Boolean): RideTileRenderState =
    when {
        !recording -> RideTileRenderState(
            active = false,
            labelRes = R.string.ride_record_start,
            subtitleRes = R.string.ride_record_start,
        )
        paused -> RideTileRenderState(
            active = false,
            labelRes = R.string.ride_paused,
            subtitleRes = R.string.ride_resume,
        )
        else -> RideTileRenderState(
            active = true,
            labelRes = R.string.ride_recording,
            subtitleRes = R.string.ride_pause,
        )
    }

