package de.velospot.domain.model

enum class BikeParkingType {
    GARAGE,
    BIKE_RACK,
    UNKNOWN
}

data class BikeParkingSpace(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val type: BikeParkingType,
    val capacity: Int?,
    val name: String?,
    val address: String?,
    val isCovered: Boolean?,
    val imageUrl: String?,
    val operator: String?,
    val sourceLayer: String
)

