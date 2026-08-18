package de.velospot.feature.bikeprofiles.presentation

import de.velospot.domain.model.RecordedRideSummary

/**
 * Pure, JVM-testable aggregation of a bike's ride statistics from the track-free
 * ride summaries already in memory.
 *
 * Kept out of the [BikeProfilesViewModel] (and free of any Android type) so the
 * per-bike numbers that feed both the garage list and the shareable "Sharepic" —
 * totals, longest single ride, top speed, first/last ridden date — can be verified
 * with plain unit tests. Callers pass **already-filtered** rides (real rides only,
 * i.e. mock and — where relevant — archived rides excluded).
 */
object BikeStatsCalculator {

    /** Aggregates [rides] (assumed pre-filtered to this bike's real rides). */
    fun aggregate(rides: List<RecordedRideSummary>): BikeProfileStats {
        if (rides.isEmpty()) return BikeProfileStats()
        return BikeProfileStats(
            rideCount = rides.size,
            totalDistanceMeters = rides.sumOf { it.distanceMeters },
            totalMovingSeconds = rides.sumOf { it.movingSeconds },
            totalElevationGainMeters = rides.sumOf { it.elevationGainMeters },
            longestRideMeters = rides.maxOf { it.distanceMeters },
            topSpeedMps = rides.maxOf { it.maxSpeedMps },
            firstRideAt = rides.minOf { it.startedAt },
            lastRideAt = rides.maxOf { it.startedAt }
        )
    }
}

