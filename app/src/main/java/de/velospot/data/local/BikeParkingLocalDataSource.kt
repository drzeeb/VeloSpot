package de.velospot.data.local

import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BoundingBox

/**
 * Abstraction for local bike parking persistence.
 *
 * Keeping the repository dependent on this contract instead of a concrete Room-backed
 * implementation makes the data layer easier to test with fakes.
 */
interface BikeParkingLocalDataSource {
    suspend fun readSpaces(): List<BikeParkingSpace>
    suspend fun writeSpaces(spaces: List<BikeParkingSpace>)
    suspend fun lastSyncEpochMs(): Long

    /**
     * Retrieve all parking spaces within the given geographic bounding box.
     * Use this for viewport-based loading to avoid reading the entire dataset.
     */
    suspend fun readSpacesInBoundingBox(bbox: BoundingBox): List<BikeParkingSpace>

    /**
     * Retrieve specific parking spaces by their IDs.
     * Use this to load favorites that may be outside the current viewport.
     */
    suspend fun readSpacesByIds(ids: List<String>): List<BikeParkingSpace>

    /**
     * Persist a reverse-geocoded address string for a single parking space.
     * Updates the local database so the address is available on the next read
     * without triggering another network request.
     */
    suspend fun updateAddress(id: String, address: String)
}
