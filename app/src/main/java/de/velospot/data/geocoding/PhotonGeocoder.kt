package de.velospot.data.geocoding

import android.util.Log
import de.velospot.BuildConfig
import de.velospot.data.remote.api.PhotonApi
import de.velospot.data.remote.dto.PhotonPropertiesDto
import de.velospot.domain.model.AddressSearchResult
import javax.inject.Inject

private const val TAG = "PhotonGeocoder"

/** Countries covered by the bundled parking data; search results are restricted to these. */
private val SUPPORTED_COUNTRY_CODES = setOf("DE", "FR", "LU")

/**
 * Coroutine-friendly geocoder backed by Komoot's Photon API
 * (https://photon.komoot.io/).
 *
 * The call is already executed on a background thread by Retrofit's coroutine adapter,
 * so it is safe to call from any coroutine context.
 *
 * Photon usage:
 *  - No mandatory User-Agent header (one is set in [PhotonApi] anyway)  ✓
 *  - Fair use only — calls are user-triggered and debounced             ✓
 */
class PhotonGeocoder @Inject constructor(
    private val api: PhotonApi
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
            val response = api.reverse(lat = latitude, lon = longitude)
            if (!response.isSuccessful) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Photon returned HTTP ${response.code()} for ($latitude, $longitude)")
                }
                return@runCatching null
            }
            response.body()?.features?.firstOrNull()?.properties?.toAddressString()
        }.onFailure { e ->
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Reverse geocoding failed for ($latitude, $longitude): ${e.message}")
            }
        }.getOrNull()

    /**
     * Reverse geocodes [latitude]/[longitude] to a **short place name** (the city /
     * district / county), e.g. `"Trier"`. Used to name recorded rides (round-trip
     * label, manual-recording suggestion). Returns `null` when nothing was found or
     * the network call failed.
     */
    suspend fun reverseGeocodePlace(latitude: Double, longitude: Double): String? =
        runCatching {
            val response = api.reverse(lat = latitude, lon = longitude)
            if (!response.isSuccessful) return@runCatching null
            val properties = response.body()?.features?.firstOrNull()?.properties
                ?: return@runCatching null
            properties.resolvedCity?.takeIf { it.isNotBlank() }
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
     * they are passed straight through as Photon's `lat`/`lon` location-bias params, so
     * results in the country the user is currently in are preferred — without excluding
     * the other countries.
     *
     * Returns an empty list on network error or if Photon returns no results.
     */
    suspend fun searchAddress(
        query: String,
        nearLatitude: Double? = null,
        nearLongitude: Double? = null
    ): List<AddressSearchResult> =
        runCatching {
            val response = api.search(
                query = query,
                lat = nearLatitude,
                lon = nearLongitude
            )
            if (!response.isSuccessful) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Photon search returned HTTP ${response.code()} for '$query'")
                return@runCatching emptyList()
            }
            response.body()?.features.orEmpty().mapNotNull { feature ->
                val properties = feature.properties ?: return@mapNotNull null
                val countryCode = properties.countrycode?.uppercase()
                if (countryCode !in SUPPORTED_COUNTRY_CODES) return@mapNotNull null

                val coordinates = feature.geometry?.coordinates
                if (coordinates == null || coordinates.size < 2) return@mapNotNull null

                AddressSearchResult(
                    displayName = properties.toDisplayName(),
                    latitude    = coordinates[1],
                    longitude   = coordinates[0]
                )
            }
        }.onFailure { e ->
            if (BuildConfig.DEBUG) Log.w(TAG, "Address search failed for '$query': ${e.message}")
        }.getOrElse { emptyList() }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    /**
     * Builds "Straßenname Hausnummer, PLZ Stadt" from a [PhotonPropertiesDto].
     * Falls back to [PhotonPropertiesDto.name] when no street is available, and
     * gracefully drops individual components that are missing.
     */
    private fun PhotonPropertiesDto.toAddressString(): String? {
        val streetPart = when {
            street != null && housenumber != null -> "$street $housenumber"
            street != null -> street
            else -> name
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

    /**
     * Builds a human-readable, multi-line-ish display name from Photon properties.
     * Composes: primary label (name, or street+housenumber); postcode+city; state;
     * country — joining non-blank parts with ", " and de-duplicating so the primary
     * label is never repeated.
     */
    private fun PhotonPropertiesDto.toDisplayName(): String {
        val streetLabel = when {
            street != null && housenumber != null -> "$street $housenumber"
            street != null -> street
            else -> null
        }
        val primary = name ?: streetLabel
        val cityPart = when {
            postcode != null && resolvedCity != null -> "$postcode $resolvedCity"
            resolvedCity != null -> resolvedCity
            postcode != null -> postcode
            else -> null
        }
        return listOfNotNull(primary, cityPart, state, country)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")
    }
}

