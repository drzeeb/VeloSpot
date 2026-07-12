package de.velospot.domain.model

import androidx.compose.runtime.Immutable

/**
 * The kind of bike a [BikeProfile] describes. Drives the display icon and lets the
 * stats be grouped by discipline. [OTHER] is the safe fallback for anything the
 * fixed list doesn't cover.
 */
enum class BikeType {
    ROAD,
    MOUNTAIN,
    GRAVEL,
    TREKKING,
    CITY,
    EBIKE,
    CARGO,
    FOLDING,
    BMX,
    OTHER
}

/**
 * A single bike in the rider's garage.
 *
 * Cyclists — especially the "pros" this feature was asked for — often own several
 * bikes and want their ride history split per bike. A profile carries the
 * identifying details a rider actually cares about (a nickname, brand/model, the
 * discipline, tyre size, weight, colour, model year) plus a free-form note.
 *
 * Exactly one profile can be the [isDefault] bike: recordings are tagged with the
 * currently *active* bike, which falls back to the default when the rider hasn't
 * explicitly switched before a ride.
 *
 * @property id Stable UUID, also stored on each [RecordedRide.bikeProfileId].
 * @property name Rider-facing nickname / display name (e.g. "Sunday Racer").
 * @property brand Manufacturer / Marke (e.g. "Canyon"), or `null` when unset.
 * @property model Model / Modell (e.g. "Ultimate CF SL"), or `null` when unset.
 * @property type The discipline / [BikeType].
 * @property tireSize Tyre size / Reifengröße as free text (e.g. "700x28c", "29\"").
 * @property weightKg Bike weight in kilograms, or `null` when unset.
 * @property color Frame colour / Farbe, or `null` when unset.
 * @property modelYear Model / purchase year, or `null` when unset.
 * @property notes Free-form notes (components, service history, …).
 * @property isDefault Whether this is the rider's default bike (at most one).
 * @property createdAt Wall-clock time the profile was created (stable ordering).
 */
@Immutable
data class BikeProfile(
    val id: String,
    val name: String,
    val brand: String? = null,
    val model: String? = null,
    val type: BikeType = BikeType.OTHER,
    val tireSize: String? = null,
    val weightKg: Double? = null,
    val color: String? = null,
    val modelYear: Int? = null,
    val notes: String? = null,
    val isDefault: Boolean = false,
    val createdAt: Long
) {
    /** "Brand Model" when both are present, otherwise whichever is set (or `null`). */
    val brandModelLabel: String?
        get() = listOfNotNull(brand?.takeIf { it.isNotBlank() }, model?.takeIf { it.isNotBlank() })
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
}

