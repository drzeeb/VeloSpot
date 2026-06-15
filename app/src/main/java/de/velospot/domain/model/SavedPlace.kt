package de.velospot.domain.model

/**
 * A user-created place saved as a named favourite.
 *
 * Unlike [BikeParkingSpace] favourites (which reference rows in the bundled OSM
 * database), saved places are arbitrary coordinates the user dropped a pin on,
 * so they are persisted in their own dedicated store.
 */
data class SavedPlace(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String?,
    val addedAt: Long
)

