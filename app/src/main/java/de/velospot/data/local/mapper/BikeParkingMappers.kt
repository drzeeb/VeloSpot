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
        sourceLayer = sourceLayer,
        access = access,
        fee = fee,
        lit = lit,
        surveillance = surveillance,
        supervised = supervised,
        cargoBike = cargoBike,
        cargoBikeCapacity = cargoBikeCapacity,
        disabledCapacity = disabledCapacity,
        chargingCapacity = chargingCapacity,
        indoor = indoor,
        maxstay = maxstay,
        openingHours = openingHours,
        website = website,
        network = network,
        brand = brand,
        ref = ref,
        checkDate = checkDate,
        parkingSubtype = parkingSubtype
    )
}

/**
 * Convert a BikeParkingSpace (domain model) to a BikeParkingSpaceEntity (database).
 */
fun BikeParkingSpace.toEntity(): BikeParkingSpaceEntity {
    return BikeParkingSpaceEntity(
        id = id,
        name = name,
        latitude = latitude,
        longitude = longitude,
        address = address,
        capacity = capacity,
        isCovered = isCovered,
        imageUrl = imageUrl,
        operator = operator,
        type = type.name,
        sourceLayer = sourceLayer,
        access = access,
        fee = fee,
        lit = lit,
        surveillance = surveillance,
        supervised = supervised,
        cargoBike = cargoBike,
        cargoBikeCapacity = cargoBikeCapacity,
        disabledCapacity = disabledCapacity,
        chargingCapacity = chargingCapacity,
        indoor = indoor,
        maxstay = maxstay,
        openingHours = openingHours,
        website = website,
        network = network,
        brand = brand,
        ref = ref,
        checkDate = checkDate,
        parkingSubtype = parkingSubtype
    )
}

/**
 * Convert a list of entities to domain models.
 */
fun List<BikeParkingSpaceEntity>.toDomainModels(): List<BikeParkingSpace> {
    return map { it.toDomainModel() }
}


