package de.velospot.domain.model

import kotlin.math.roundToInt

data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    /**
     * Terrain elevation in metres at this point, when the routing source provides
     * it (BRouter reads it from the SRTM data baked into its `.rd5` segment files).
     * `null` for sources without elevation data (e.g. the OSRM online fallback).
     */
    val elevationMeters: Double? = null
)

data class BikeRoute(
    val points: List<RoutePoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val source: RoutingSource = RoutingSource.OSRM_ONLINE,
    /**
     * Mechanical work (Joules) BRouter's kinematic model computed for the whole
     * route — it accounts for the rider+bike mass, power, air drag, rolling
     * resistance and the elevation profile. `null` for sources that don't model
     * it (the OSRM online fallback). See [estimatedKcal].
     */
    val energyJoules: Double? = null
) {
    /**
     * Rough calorie estimate (kcal) for riding this route. BRouter's
     * [energyJoules] is the *mechanical* work; dividing by a cyclist's ~24 %
     * metabolic efficiency and converting J→kcal (÷4184) collapses to
     * ≈ `energyJoules / 1000` — the well-known "1 kJ of work ≈ 1 kcal burned"
     * rule of thumb. `null` when the source provides no energy figure.
     */
    val estimatedKcal: Int? get() = energyJoules?.let { (it / 1000.0).roundToInt() }
}


