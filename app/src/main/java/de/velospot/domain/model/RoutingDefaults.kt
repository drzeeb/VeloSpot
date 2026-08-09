package de.velospot.domain.model
/**
 * Shared routing/navigation defaults so the app models cycling pace from a single
 * source of truth instead of scattered magic numbers.
 */
object RoutingDefaults {
    /**
     * Default average cycling speed (m/s, ~16 km/h). Used as the fallback pace
     * whenever a route carries no per-node timing model - the live-navigation ETA
     * (see `NavigationManager.DEFAULT_BIKE_SPEED_MPS`) and the OSRM online fallback,
     * whose own bicycle duration is calibrated for road speeds rather than real
     * cycling pace, both derive their time estimate from this constant.
     */
    const val DEFAULT_CYCLING_SPEED_MPS = 4.5
}
