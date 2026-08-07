package de.velospot.core.health

import de.velospot.core.tracking.estimateRideCalories
import de.velospot.domain.model.RecordedRide

/**
 * One speed sample destined for a Health Connect `SpeedRecord`.
 *
 * @property timeMillis Wall-clock time of the sample (epoch millis).
 * @property metersPerSecond Ground speed at that instant, in m/s (always ≥ 0).
 */
data class HealthConnectSpeedSample(
    val timeMillis: Long,
    val metersPerSecond: Double
)

/**
 * The **pure, framework-free** blueprint of everything VeloSpot writes into Health
 * Connect for one finished ride. It carries the already-derived numbers (times,
 * distance, energy, elevation, speed samples) plus the stable client-record ids used
 * for idempotent re-export.
 *
 * Deliberately holds **no** `androidx.health.connect` types so it can be built and
 * asserted in plain JVM unit tests. The thin
 * [de.velospot.core.health.HealthConnectExporter] maps it onto the actual
 * `ExerciseSessionRecord` / `DistanceRecord` / … objects and performs the I/O — the
 * same split as `SensorParsers` vs `BleSensorController`.
 *
 * Each `…ClientRecordId` is derived from the ride's own id, so re-exporting the same
 * ride **replaces** (rather than duplicates) the previously written records.
 */
data class HealthConnectRideData(
    val rideId: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val title: String?,
    val distanceMeters: Double,
    val energyKilocalories: Int,
    val elevationGainMeters: Double,
    val speedSamples: List<HealthConnectSpeedSample>
) {
    /** Whether a `DistanceRecord` should be written (positive distance only). */
    val hasDistance: Boolean get() = distanceMeters > 0.0

    /** Whether a `TotalCaloriesBurnedRecord` should be written (positive energy only). */
    val hasEnergy: Boolean get() = energyKilocalories > 0

    /** Whether an `ElevationGainedRecord` should be written (positive gain only). */
    val hasElevationGain: Boolean get() = elevationGainMeters > 0.0

    /** Whether a `SpeedRecord` should be written (at least one usable sample). */
    val hasSpeedSamples: Boolean get() = speedSamples.isNotEmpty()

    val exerciseClientRecordId: String get() = "$CLIENT_ID_PREFIX-exercise-$rideId"
    val distanceClientRecordId: String get() = "$CLIENT_ID_PREFIX-distance-$rideId"
    val energyClientRecordId: String get() = "$CLIENT_ID_PREFIX-energy-$rideId"
    val elevationClientRecordId: String get() = "$CLIENT_ID_PREFIX-elevation-$rideId"
    val speedClientRecordId: String get() = "$CLIENT_ID_PREFIX-speed-$rideId"

    companion object {
        /** Namespacing prefix so VeloSpot's client-record ids never collide with other apps. */
        const val CLIENT_ID_PREFIX = "velospot"
    }
}

/**
 * Pure builder that turns a persisted [RecordedRide] into a
 * [HealthConnectRideData]. Kept separate from the Health Connect I/O so it is unit
 * testable without any Android dependency.
 *
 * Calories reuse the shared physics estimator [estimateRideCalories] (the same one
 * the ride detail sheet shows). Speed samples are taken from the track's captured
 * `speedMps` values, **skipping points with no speed** (there is no recorded
 * heart-rate/power/cadence series, so none is exported).
 */
object HealthConnectRideMapper {

    fun buildRideData(ride: RecordedRide): HealthConnectRideData {
        val speedSamples = ride.points
            .mapNotNull { point ->
                point.speedMps
                    ?.takeIf { it >= 0f }
                    ?.let { HealthConnectSpeedSample(point.timestamp, it.toDouble()) }
            }
        return HealthConnectRideData(
            rideId = ride.id,
            startTimeMillis = ride.startedAt,
            endTimeMillis = ride.endedAt,
            title = ride.name?.trim()?.takeIf { it.isNotBlank() },
            distanceMeters = ride.distanceMeters,
            energyKilocalories = estimateRideCalories(ride),
            elevationGainMeters = ride.elevationGainMeters,
            speedSamples = speedSamples
        )
    }
}

