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
     */
    fun startLocationUpdates()

    /**
     * Stop listening to location updates.
     */
    fun stopLocationUpdates()
}

