package de.velospot.core.format

import kotlin.math.roundToInt

/**
 * Locale-neutral formatting helpers shared by the ride-tracking overlay, the
 * "My rides" sheets, the foreground-service notification and the home-screen
 * widget. Units (km, km/h, m) are universal and durations use a digital clock
 * layout, so no per-language string resources are needed.
 *
 * Located in `core.format` (not the presentation layer) so the background
 * recording stack — service, widget, tile — can reuse it without depending
 * "upwards" on the UI.
 */

/** Formats a duration in seconds as `H:MM:SS` (or `M:SS` under an hour). */
internal fun formatRideDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** Formats a metre distance as `350 m` (< 1 km) or `12.34 km`. */
internal fun formatRideDistance(meters: Double): String =
    if (meters < 1_000) "${meters.roundToInt()} m"
    else "%.2f km".format(meters / 1_000.0)

/** Formats a speed in m/s as `km/h` with one decimal, e.g. `18.4 km/h`. */
internal fun formatRideSpeed(metersPerSecond: Double): String =
    "%.1f km/h".format(metersPerSecond * 3.6)

internal fun formatRideSpeed(metersPerSecond: Float): String =
    formatRideSpeed(metersPerSecond.toDouble())

/** Formats an elevation amount in metres, e.g. `↑ 124 m`. */
internal fun formatRideElevation(meters: Double): String = "${meters.roundToInt()} m"

