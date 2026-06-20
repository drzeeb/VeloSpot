package de.velospot.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class OsrmRouteResponseDto(
    val code: String,
    val routes: List<OsrmRouteDto>
)

@JsonClass(generateAdapter = true)
data class OsrmRouteDto(
    val distance: Double,
    val duration: Double,
    val geometry: OsrmGeometryDto
)

@JsonClass(generateAdapter = true)
data class OsrmGeometryDto(
    @Json(name = "coordinates")
    val coordinates: List<List<Double>>
)

