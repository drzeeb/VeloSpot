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
     * Start listening to location updates using the given power [profile].
     *
     * Replaces the earlier `highAccuracy: Boolean` with an explicit
     * [LocationPowerProfile] so the recorder can additionally drop the GNSS engine
     * to a power-saving cadence while the rider is standing still ([LocationPowerProfile.IDLE_RECORDING]),
     * without affecting navigation or moving-recording fidelity.
     *
     * @param profile the requested GPS-radio power profile; defaults to the
     *  battery-friendly [LocationPowerProfile.BROWSE] used while just viewing the map.
     */
    fun startLocationUpdates(profile: LocationPowerProfile = LocationPowerProfile.BROWSE)

    /**
     * Stop listening to location updates.
     */
    fun stopLocationUpdates()
}

