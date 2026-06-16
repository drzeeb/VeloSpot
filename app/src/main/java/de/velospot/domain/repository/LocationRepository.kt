package de.velospot.domain.repository

import de.velospot.domain.model.GeoCoordinate
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing location permissions and current user position.
 */
interface LocationRepository {

    /**
     * Get current user location as a Flow.
     * Emits a [GeoCoordinate] whenever a new location fix is available.
     *
     * @return Flow of [GeoCoordinate], or null if location is not yet known.
     */
    fun getCurrentLocationFlow(): Flow<GeoCoordinate?>


    /**
     * Start listening to location updates.
     *
     * @param highAccuracy when `true`, requests frequent, GPS-based high-accuracy
     *  fixes (used during active turn-by-turn navigation). When `false` (the
     *  default), a battery-friendly balanced-power mode with a larger update
     *  interval and minimum displacement is used for idle map browsing.
     */
    fun startLocationUpdates(highAccuracy: Boolean = false)

    /**
     * Stop listening to location updates.
     */
    fun stopLocationUpdates()
}

