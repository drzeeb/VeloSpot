package de.velospot.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Top-level GeoJSON response from the Photon API (`/api` and `/reverse`).
 *
 * Example JSON:
 * ```json
 * {
 *   "features": [
 *     {
 *       "geometry": { "coordinates": [6.6417, 49.7596] },
 *       "properties": {
 *         "name": "Hauptstraße",
 *         "housenumber": "12",
 *         "postcode": "54290",
 *         "city": "Trier",
 *         "country": "Deutschland",
 *         "countrycode": "DE"
 *       }
 *     }
 *   ]
 * }
 * ```
 */
@JsonClass(generateAdapter = true)
data class PhotonResponseDto(
    val features: List<PhotonFeatureDto>?
)

/** A single GeoJSON feature (geometry + properties). */
@JsonClass(generateAdapter = true)
data class PhotonFeatureDto(
    val geometry: PhotonGeometryDto?,
    val properties: PhotonPropertiesDto?
)

/** Point geometry; [coordinates] order is `[lon, lat]`. */
@JsonClass(generateAdapter = true)
data class PhotonGeometryDto(
    val coordinates: List<Double>?
)

/**
 * Address/place details for a Photon feature.
 *
 * Settlement fields are listed from most specific to least specific;
 * [resolvedCity] uses the first non-null one.
 */
@JsonClass(generateAdapter = true)
data class PhotonPropertiesDto(
    val name: String?,
    val street: String?,
    val housenumber: String?,
    val postcode: String?,
    val city: String?,
    val district: String?,
    val county: String?,
    val state: String?,
    val country: String?,
    @Json(name = "countrycode") val countrycode: String?,
    @Json(name = "osm_key") val osmKey: String?,
    @Json(name = "osm_value") val osmValue: String?,
    val type: String?
) {
    /** Returns the most specific settlement name available. */
    val resolvedCity: String?
        get() = city ?: district ?: county
}

