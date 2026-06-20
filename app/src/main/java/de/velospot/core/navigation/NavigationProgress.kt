package de.velospot.core.navigation

/**
 * Live progress snapshot emitted on every GPS fix while navigating. Pure data,
 * so it can be surfaced straight into the UI (remaining distance / ETA) and unit
 * tested without Android.
 *
 * @property remainingMeters Distance from the matched on-route position to the
 *  destination along the route.
 * @property remainingSeconds Estimated time to arrival (ETA), derived from the
 *  remaining distance and the route's average speed.
 * @property distanceFromRouteMeters Perpendicular distance of the raw GPS fix to
 *  the route — the basis for off-route detection.
 * @property isOffRoute `true` while the rider is currently beyond the off-route
 *  threshold (a separate, debounced [de.velospot.feature.map.presentation.NavigationManager]
 *  callback fires the actual reroute trigger).
 * @property currentSpeedMps Current ground speed in metres per second, taken from
 *  the raw GPS fix (`null` when the fix carries no speed).
 */
data class NavigationProgress(
    val remainingMeters: Double,
    val remainingSeconds: Double,
    val distanceFromRouteMeters: Double,
    val isOffRoute: Boolean,
    val currentSpeedMps: Float? = null
)

