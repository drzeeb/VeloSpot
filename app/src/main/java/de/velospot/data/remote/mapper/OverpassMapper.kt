package de.velospot.data.remote.mapper

import de.velospot.data.remote.dto.OverpassElementDto
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BikeParkingType

/**
 * Maps an [OverpassElementDto] to the domain model [BikeParkingSpace].
 * Returns `null` if the element has no usable coordinates.
 */
fun OverpassElementDto.toDomain(): BikeParkingSpace? {
    val lat = lat ?: center?.lat ?: return null
    val lon = lon ?: center?.lon ?: return null
    val tags = tags ?: emptyMap()

    return BikeParkingSpace(
        id = "osm-$type-$id",
        latitude = lat,
        longitude = lon,
        type = tags["bicycle_parking"].toBikeParkingType(),
        capacity = tags["capacity"]?.toIntOrNull(),
        name = tags["name"],
        address = tags.resolveAddress(),
        isCovered = tags["covered"]?.toCoveredOrNull(),
        imageUrl = null,
        operator = tags["operator"],
        sourceLayer = "osm"
    )
}

// ─── Private helpers ─────────────────────────────────────────────────────────

private fun String?.toBikeParkingType(): BikeParkingType = when (this) {
    "shed", "garage_boxes", "building", "lockers" -> BikeParkingType.GARAGE
    "stands", "wall_loops", "rack", "anchors", "bollard", "two-tier" -> BikeParkingType.BIKE_RACK
    else -> BikeParkingType.UNKNOWN
}

private fun String.toCoveredOrNull(): Boolean? = when (lowercase()) {
    "yes" -> true
    "no" -> false
    else -> null
}

private fun Map<String, String>.resolveAddress(): String? {
    val street = this["addr:street"]
    val houseNo = this["addr:housenumber"]
    val city = this["addr:city"]
    val streetPart = listOfNotNull(street, houseNo)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { null }
    return listOfNotNull(streetPart, city)
        .joinToString(", ")
        .ifBlank { null }
}

