package de.velospot.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing location permissions and current user position.
 */
interface LocationRepository {

    /**
     * Check if location permission is granted.
     *
     * @return True if location permission is granted, false otherwise
     */
    suspend fun isLocationPermissionGranted(): Boolean

    /**
     * Get current user location as a Flow.
     * Emits GeoPoint data when location updates are available.
     *
     * @return Flow of location coordinates (latitude, longitude)
     */
    fun getCurrentLocationFlow(): Flow<Pair<Double, Double>?>

    /**
     * Request location permission from the user.
     * Should be called from a composable with proper context.
     *
     * @return True if permission was granted, false otherwise
     */
    suspend fun requestLocationPermission(): Boolean

    /**
     * Stop listening to location updates.
     */
    fun stopLocationUpdates()
}

