package de.velospot.data.local.mapper

import de.velospot.data.local.entity.BikeParkingSpaceEntity
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BikeParkingType

/**
 * Extension functions to convert between domain model and database entity.
 */

/**
 * Convert a BikeParkingSpaceEntity (database) to BikeParkingSpace (domain model).
 */
fun BikeParkingSpaceEntity.toDomainModel(): BikeParkingSpace {
    return BikeParkingSpace(
        id = id,
        name = name,
        latitude = latitude,
        longitude = longitude,
        address = address,
        capacity = capacity,
        isCovered = isCovered,
        imageUrl = imageUrl,
        operator = operator,
        type = BikeParkingType.valueOf(type),
        sourceLayer = sourceLayer
    )
}

/**
 * Convert a list of entities to domain models.
 */
fun List<BikeParkingSpaceEntity>.toDomainModels(): List<BikeParkingSpace> {
    return map { it.toDomainModel() }
}


