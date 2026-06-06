package de.velospot.data.remote.dto

import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BikeParkingType

fun GeoJsonFeatureDto.toDomain(defaultLayer: String): BikeParkingSpace? {
    val pointCoordinates = geometry?.coordinates ?: return null
    if (pointCoordinates.size < 2) return null

    val longitude = pointCoordinates[0]
    val latitude = pointCoordinates[1]
    val sourceLayer = properties?.layer?.ifBlank { null } ?: defaultLayer

    return BikeParkingSpace(
        id = resolveId(sourceLayer),
        latitude = latitude,
        longitude = longitude,
        type = resolveType(sourceLayer),
        capacity = resolveCapacity(),
        name = firstNonBlank(properties?.name, properties?.designation),
        address = resolveAddress(),
        isCovered = properties?.covered.toBooleanValue(),
        operator = properties?.operator,
        sourceLayer = sourceLayer
    )
}

private fun GeoJsonFeatureDto.resolveId(sourceLayer: String): String {
    return firstNonBlank(id, properties?.id, properties?.featureId)
        ?: "$sourceLayer-${geometry?.coordinates.orEmpty().joinToString(separator = "_")}" 
}

private fun GeoJsonFeatureDto.resolveType(sourceLayer: String): BikeParkingType {
    val normalized = firstNonBlank(properties?.type, properties?.kind, sourceLayer)
        ?.lowercase()
        .orEmpty()

    return when {
        "garage" in normalized || "garagen" in normalized -> BikeParkingType.GARAGE
        "abstell" in normalized || "buegel" in normalized || "rack" in normalized -> BikeParkingType.BIKE_RACK
        else -> BikeParkingType.UNKNOWN
    }
}

private fun GeoJsonFeatureDto.resolveCapacity(): Int? {
    return properties?.capacity ?: properties?.amount ?: properties?.parkingSlots
}

private fun GeoJsonFeatureDto.resolveAddress(): String? {
    val explicitAddress = properties?.address
    if (!explicitAddress.isNullOrBlank()) return explicitAddress

    val street = properties?.street
    val houseNumber = properties?.houseNumber
    return listOfNotNull(street, houseNumber)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { null }
}

private fun firstNonBlank(vararg values: String?): String? {
    return values.firstOrNull { !it.isNullOrBlank() }
}

private fun String?.toBooleanValue(): Boolean? {
    return when (this?.trim()?.lowercase()) {
        "true", "1", "ja", "yes" -> true
        "false", "0", "nein", "no" -> false
        else -> null
    }
}

