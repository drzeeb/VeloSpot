package de.velospot.domain.repository

import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BoundingBox

/**
 * Repository interface for bike parking spaces.
 *
 * Data is pre-populated from an OpenStreetMap PBF extract and stored locally.
 * All queries run against the local Room database – no network calls required.
 */
interface BikeParkingRepository {

    /**
     * Return all parking spaces within the given geographic bounding box.
     * This is the primary method used for map-viewport-based loading.
     */
    suspend fun getSpacesInBoundingBox(bbox: BoundingBox): List<BikeParkingSpace>

    /**
     * Return specific parking spaces by their IDs.
     * Used to resolve favorites that may lie outside the current map viewport.
     */
    suspend fun getSpacesByIds(ids: List<String>): List<BikeParkingSpace>

    /**
     * Reverse-geocode the address of [space] via Nominatim if it has none yet,
     * persist the result in the local database, and return a copy with the address filled in.
     *
     * Returns the original [space] unchanged if it already has an address,
     * or if the geocoding request failed (e.g., no connectivity).
     *
     * This method is idempotent: calling it multiple times for the same space
     * only triggers a network request on the first call (subsequent calls find a
     * cached address in the database).
     */
    suspend fun resolveAddress(space: BikeParkingSpace): BikeParkingSpace
}
