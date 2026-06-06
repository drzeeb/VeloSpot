package de.velospot.domain.repository

import de.velospot.domain.model.BikeParkingSpace

interface BikeParkingRepository {
    suspend fun getBikeParkingSpaces(): List<BikeParkingSpace>
}

