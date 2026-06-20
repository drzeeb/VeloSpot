package de.velospot.domain.model

/**
 * The single location where the user has parked their bike, persisted until they
 * pick it up again ("Fahrrad abgeholt"). Unlike [SavedPlace] (an arbitrary number
 * of named favourites) there is only ever **one** parked bike at a time, so it is
 * stored as a single record in its own lightweight preference store.
 *
 * @property parkedAt epoch millis when the bike was parked — drives the "parked X
 *  minutes ago" readout in the detail sheet.
 * @property note     optional free-text reminder (e.g. "Level 2, near the lift").
 * @property address  reverse-geocoded street address, resolved when available.
 */
data class ParkedBike(
    val latitude: Double,
    val longitude: Double,
    val parkedAt: Long,
    val note: String? = null,
    val address: String? = null
)

