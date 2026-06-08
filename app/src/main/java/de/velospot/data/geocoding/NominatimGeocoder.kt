package de.velospot.data.geocoding

import android.util.Log
import de.velospot.data.remote.api.NominatimApi
import de.velospot.data.remote.dto.NominatimAddressDto
import javax.inject.Inject

private const val TAG = "NominatimGeocoder"

/**
 * Coroutine-friendly reverse geocoder backed by the Nominatim REST API
 * (https://nominatim.openstreetmap.org/reverse).
 *
 * The call is already executed on a background thread by Retrofit's coroutine adapter,
 * so it is safe to call from any coroutine context.
 *
 * Nominatim Usage Policy:
 *  - User-Agent header is set in [NominatimApi]                 ✓
 *  - At most 1 request per second                               ✓ (user-triggered only)
 *  - No automated bulk requests                                  ✓
 */
class NominatimGeocoder @Inject constructor(
    private val api: NominatimApi
) {

    /**
     * Reverse geocodes [latitude]/[longitude] and returns a human-readable address string,
     * or `null` if no result was found or the network call failed.
     *
     * Result format: "Straßenname Hausnummer, PLZ Stadt"
     * Example:       "Hauptstraße 12, 54290 Trier"
     */
    suspend fun reverseGeocode(latitude: Double, longitude: Double): String? =
        runCatching {
            val response = api.reverseGeocode(lat = latitude, lon = longitude)
            if (!response.isSuccessful) {
                Log.w(TAG, "Nominatim returned HTTP ${response.code()} for ($latitude, $longitude)")
                return@runCatching null
            }
            response.body()?.address?.toAddressString()
        }.onFailure { e ->
            Log.w(TAG, "Reverse geocoding failed for ($latitude, $longitude): ${e.message}")
        }.getOrNull()

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    /**
     * Builds "Straßenname Hausnummer, PLZ Stadt" from a [NominatimAddressDto].
     * Falls back gracefully if individual components are missing.
     */
    private fun NominatimAddressDto.toAddressString(): String? {
        val streetPart = when {
            road != null && houseNumber != null -> "$road $houseNumber"
            road != null -> road
            else -> null
        }
        val cityPart = when {
            postcode != null && resolvedCity != null -> "$postcode $resolvedCity"
            resolvedCity != null -> resolvedCity
            postcode != null -> postcode
            else -> null
        }
        return listOfNotNull(streetPart, cityPart)
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
    }
}
