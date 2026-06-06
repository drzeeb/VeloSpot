package de.velospot.data.local

import de.velospot.domain.model.BikeParkingSpace

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
}

