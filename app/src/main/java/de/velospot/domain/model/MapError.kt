package de.velospot.domain.model

// ─── Typed domain errors ──────────────────────────────────────────────────────

/**
 * Represents all possible error states that can occur in the map feature.
 * Used in [de.velospot.feature.map.presentation.MapUiState] and
 * [de.velospot.feature.map.presentation.NavigationUiState] instead of raw strings.
 */
sealed class MapError {
    /** Network or server error while loading parking data. */
    data object NetworkUnavailable : MapError()

    /** GPS / location permission unavailable. */
    data object LocationUnavailable : MapError()

    /** Routing API returned a non-OK status code. */
    data class RoutingFailed(val code: String) : MapError()

    /** Routing API returned no routes for the given coordinates. */
    data object NoRouteFound : MapError()

    /** Route was returned but geometry contains no coordinates. */
    data object EmptyRouteGeometry : MapError()

    /** Catch-all for unexpected exceptions. */
    data class Unknown(val message: String?) : MapError()
}

// ─── Domain exceptions thrown by data-layer implementations ──────────────────

/** Thrown when the routing API returns a non-OK status code. */
class RoutingFailedException(val code: String) : Exception("Routing API returned: $code")

/** Thrown when the routing API returns no routes. */
class NoRouteFoundException : Exception("No route returned by routing API")

/** Thrown when a returned route contains no geometry coordinates. */
class EmptyRouteGeometryException : Exception("Route geometry is empty")

