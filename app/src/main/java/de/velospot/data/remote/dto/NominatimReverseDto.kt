package de.velospot.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Top-level response from the Nominatim reverse geocoding endpoint.
 *
 * Example JSON:
 * ```json
 * {
 *   "display_name": "Fahrradständer, Hauptstraße 12, 54290 Trier, Rheinland-Pfalz, Deutschland",
 *   "address": {
 *     "road": "Hauptstraße",
 *     "house_number": "12",
 *     "city": "Trier",
 *     "postcode": "54290"
 *   }
 * }
 * ```
 */
@JsonClass(generateAdapter = true)
data class NominatimReverseDto(
    @Json(name = "display_name") val displayName: String?,
    val address: NominatimAddressDto?
)

/**
 * Address detail block returned by the Nominatim API.
 *
 * Settlement fields are listed from most specific to least specific;
 * [NominatimReverseDto.resolvedCity] uses the first non-null one.
 */
@JsonClass(generateAdapter = true)
data class NominatimAddressDto(
    val road: String?,
    @Json(name = "house_number") val houseNumber: String?,
    val suburb: String?,
    val city: String?,
    val town: String?,
    val village: String?,
    val hamlet: String?,
    val postcode: String?
) {
    /** Returns the most specific settlement name available. */
    val resolvedCity: String?
        get() = city ?: town ?: village ?: hamlet
}

