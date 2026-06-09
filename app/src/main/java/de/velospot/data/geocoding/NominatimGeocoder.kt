package de.velospot.data.geocoding

import android.util.Log
import de.velospot.BuildConfig
import de.velospot.data.remote.api.NominatimApi
import de.velospot.data.remote.dto.NominatimAddressDto
import de.velospot.domain.model.AddressSearchResult
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
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Nominatim returned HTTP ${response.code()} for ($latitude, $longitude)")
                }
                return@runCatching null
            }
            response.body()?.address?.toAddressString()
        }.onFailure { e ->
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Reverse geocoding failed for ($latitude, $longitude): ${e.message}")
            }
        }.getOrNull()

    /**
     * Forward geocoding: returns up to 5 address suggestions for [query], restricted to Germany.
     * Returns an empty list on network error or if Nominatim returns no results.
     */
    suspend fun searchAddress(query: String): List<AddressSearchResult> =
        runCatching {
            val response = api.search(query = query)
            if (!response.isSuccessful) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Nominatim search returned HTTP ${response.code()} for '$query'")
                return@runCatching emptyList()
            }
            response.body().orEmpty().map { dto ->
                AddressSearchResult(
                    displayName = dto.displayName,
                    latitude    = dto.lat.toDouble(),
                    longitude   = dto.lon.toDouble()
                )
            }
        }.onFailure { e ->
            if (BuildConfig.DEBUG) Log.w(TAG, "Address search failed for '$query': ${e.message}")
        }.getOrElse { emptyList() }

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
