package de.velospot.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing location permissions and current user position.
 */
interface LocationRepository {

    /**
     * Get current user location as a Flow.
     * Emits GeoPoint data when location updates are available.
     *
     * @return Flow of location coordinates (latitude, longitude)
     */
    fun getCurrentLocationFlow(): Flow<Pair<Double, Double>?>


    /**
     * Start listening to location updates.
     */
    fun startLocationUpdates()

    /**
     * Stop listening to location updates.
     */
    fun stopLocationUpdates()
}

