package de.velospot.core.map

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


