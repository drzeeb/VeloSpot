package de.velospot.domain.repository

import de.velospot.domain.model.BikeParkingSpace

fun interface BikeParkingRepository {
    suspend fun getBikeParkingSpaces(): List<BikeParkingSpace>
}

