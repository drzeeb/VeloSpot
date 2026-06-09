package de.velospot.domain.model

/**
 * Indicates which routing engine produced a [BikeRoute].
 */
enum class RoutingSource {
    /** Route calculated on-device via the embedded BRouter engine (offline). */
    BROUTER_OFFLINE,

    /** Route calculated via the OSRM online API (fallback when segments are missing). */
    OSRM_ONLINE
}

