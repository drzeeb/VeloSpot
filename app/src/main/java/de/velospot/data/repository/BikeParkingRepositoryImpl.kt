package de.velospot.data.repository

import de.velospot.data.geocoding.NominatimGeocoder
import de.velospot.data.local.BikeParkingLocalDataSource
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BoundingBox
import de.velospot.domain.repository.BikeParkingRepository
import javax.inject.Inject

/**
 * Implementation of [BikeParkingRepository] backed by a pre-populated local Room database.
 *
 * The database is seeded from an OpenStreetMap PBF extract covering all of Germany
 * and is shipped as an Android asset (bike_parking_germany.db).
 * No network calls are made for data loading; addresses are lazily resolved via
 * Nominatim reverse geocoding the first time a user selects a space without one.
 */
class BikeParkingRepositoryImpl @Inject constructor(
    private val localDataSource: BikeParkingLocalDataSource,
    private val nominatimGeocoder: NominatimGeocoder
) : BikeParkingRepository {

    override suspend fun getSpacesInBoundingBox(bbox: BoundingBox): List<BikeParkingSpace> {
        return localDataSource.readSpacesInBoundingBox(bbox)
    }

    override suspend fun getSpacesByIds(ids: List<String>): List<BikeParkingSpace> {
        return localDataSource.readSpacesByIds(ids)
    }

    /**
     * If [space] already has an address, it is returned as-is immediately.
     * Otherwise Nominatim is queried, the result is cached to the local DB,
     * and a copy of [space] with the resolved address is returned.
     */
    override suspend fun resolveAddress(space: BikeParkingSpace): BikeParkingSpace {
        if (space.address != null) return space

        val resolved = nominatimGeocoder.reverseGeocode(space.latitude, space.longitude)
            ?: return space

        localDataSource.updateAddress(space.id, resolved)
        return space.copy(address = resolved)
    }
}
