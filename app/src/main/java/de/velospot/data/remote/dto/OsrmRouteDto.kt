package de.velospot.data.remote.dto

import com.squareup.moshi.Json

data class OsrmRouteResponseDto(
    val code: String,
    val routes: List<OsrmRouteDto>
)

data class OsrmRouteDto(
    val distance: Double,
    val duration: Double,
    val geometry: OsrmGeometryDto
)

data class OsrmGeometryDto(
    @Json(name = "coordinates")
    val coordinates: List<List<Double>>
)

