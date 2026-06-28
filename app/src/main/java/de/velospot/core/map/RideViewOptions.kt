package de.velospot.core.map

import android.content.Context
import androidx.core.content.edit

/**
 * The per-ride **inspection overlays** the rider can toggle while looking at a
 * past ride: the on-map "max speed" bubble and colouring the drawn track by the
 * speed ridden along it. These are view options (not data), so they are global and
 * persisted across sessions — the last-used choices apply to every ride opened
 * afterwards.
 *
 * @property showMaxSpeedBubble Whether the speech bubble marking the ride's top
 *  speed is drawn on the map. Defaults to `true`.
 * @property colorTrackBySpeed Whether the ride's track line is coloured by the
 *  speed ridden (green → red, red at the peak) instead of a single flat colour.
 *  Defaults to `false`.
 */
data class RideViewOptions(
    val showMaxSpeedBubble: Boolean = true,
    val colorTrackBySpeed: Boolean = false
)

/**
 * Persists [RideViewOptions] in shared preferences so the rider's last-used
 * inspection overlays are restored on the next launch and applied to every ride.
 */
object RideViewPreferences {

    private const val PREFS_NAME = "velospot_ride_view"
    private const val KEY_MAX_SPEED_BUBBLE = "show_max_speed_bubble"
    private const val KEY_COLOR_BY_SPEED = "color_track_by_speed"

    fun get(context: Context): RideViewOptions {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return RideViewOptions(
            showMaxSpeedBubble = prefs.getBoolean(KEY_MAX_SPEED_BUBBLE, true),
            colorTrackBySpeed = prefs.getBoolean(KEY_COLOR_BY_SPEED, false)
        )
    }

    fun setShowMaxSpeedBubble(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_MAX_SPEED_BUBBLE, enabled) }
    }

    fun setColorTrackBySpeed(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_COLOR_BY_SPEED, enabled) }
    }
}

