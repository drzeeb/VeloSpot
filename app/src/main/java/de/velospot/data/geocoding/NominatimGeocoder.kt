package de.velospot.data.geocoding

import android.util.Log
import de.velospot.BuildConfig
import de.velospot.data.remote.api.NominatimApi
import de.velospot.data.remote.dto.NominatimAddressDto
import de.velospot.domain.model.AddressSearchResult
import javax.inject.Inject

private const val TAG = "NominatimGeocoder"

/**
 * Half the side length (in degrees, ≈ 110 km) of the bias `viewbox` placed around the
 * user's position. Large enough to comfortably cover the surrounding region/country so
 * nearby (= same-country) results rank first, while distant matches still appear.
 */
private const val VIEWBOX_HALF_SPAN_DEG = 1.0

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
     * Reverse geocodes [latitude]/[longitude] to a **short place name** (the city /
     * town / village, falling back to the suburb), e.g. `"Trier"`. Used to name
     * recorded rides (round-trip label, manual-recording suggestion). Returns `null`
     * when nothing was found or the network call failed.
     */
    suspend fun reverseGeocodePlace(latitude: Double, longitude: Double): String? =
        runCatching {
            val response = api.reverseGeocode(lat = latitude, lon = longitude)
            if (!response.isSuccessful) return@runCatching null
            val address = response.body()?.address ?: return@runCatching null
            (address.resolvedCity ?: address.suburb)?.takeIf { it.isNotBlank() }
        }.onFailure { e ->
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Reverse place lookup failed for ($latitude, $longitude): ${e.message}")
            }
        }.getOrNull()

    /**
     * Forward geocoding: returns up to 5 address suggestions for [query], restricted to
     * the covered countries (DE, FR, LU).
     *
     * When [nearLatitude]/[nearLongitude] are provided (the user's current position),
     * the search is biased toward the surrounding area via a Nominatim `viewbox`, so
     * results in the country the user is currently in are preferred — without excluding
     * the other countries (`bounded=0`).
     *
     * Returns an empty list on network error or if Nominatim returns no results.
     */
    suspend fun searchAddress(
        query: String,
        nearLatitude: Double? = null,
        nearLongitude: Double? = null
    ): List<AddressSearchResult> =
        runCatching {
            val viewBox = buildViewBox(nearLatitude, nearLongitude)
            val response = api.search(query = query, viewBox = viewBox)
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
     * Builds a Nominatim `viewbox` string (`lonMin,latMin,lonMax,latMax`) of roughly
     * [VIEWBOX_HALF_SPAN_DEG]° around the user's position, or `null` if no position is
     * known. Used purely to bias result ranking toward the user's surroundings.
     */
    private fun buildViewBox(latitude: Double?, longitude: Double?): String? {
        if (latitude == null || longitude == null) return null
        val lonMin = longitude - VIEWBOX_HALF_SPAN_DEG
        val latMin = latitude - VIEWBOX_HALF_SPAN_DEG
        val lonMax = longitude + VIEWBOX_HALF_SPAN_DEG
        val latMax = latitude + VIEWBOX_HALF_SPAN_DEG
        return "$lonMin,$latMin,$lonMax,$latMax"
    }

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
