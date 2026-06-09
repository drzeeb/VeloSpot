package de.velospot.domain.model

/**
 * A single address search result returned by Nominatim /search.
 *
 * @param displayName Human-readable label shown in the result list.
 * @param latitude    Latitude of the result center.
 * @param longitude   Longitude of the result center.
 */
data class AddressSearchResult(
    val displayName: String,
    val latitude: Double,
    val longitude: Double
)

