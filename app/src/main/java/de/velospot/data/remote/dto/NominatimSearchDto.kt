package de.velospot.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Single result item from the Nominatim /search endpoint.
 */
@JsonClass(generateAdapter = true)
data class NominatimSearchResultDto(
    @Json(name = "place_id")    val placeId: Long,
    @Json(name = "display_name") val displayName: String,
    @Json(name = "lat")          val lat: String,
    @Json(name = "lon")          val lon: String,
    @Json(name = "type")         val type: String?,
    @Json(name = "importance")   val importance: Double?
)

