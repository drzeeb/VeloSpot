package de.velospot.domain.model

enum class BikeParkingType {
    GARAGE,
    BIKE_RACK,
    UNKNOWN
}

data class BikeParkingSpace(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val type: BikeParkingType,
    val capacity: Int?,
    val name: String?,
    val address: String?,
    val isCovered: Boolean?,
    val imageUrl: String?,
    val operator: String?,
    val sourceLayer: String,
    // --- Enriched OSM attributes (all nullable: >95% of OSM nodes are sparsely
    // tagged, so "unknown" must degrade gracefully and never be treated as "bad").
    /** Raw OSM `access` value (yes/private/customers/permissive/designated/no). */
    val access: String? = null,
    /** OSM `fee`: yes → true, no → false, else null. */
    val fee: Boolean? = null,
    /** OSM `lit`: yes → true, no → false, else null. */
    val lit: Boolean? = null,
    /** OSM `surveillance`: present & not "no" → true, "no" → false, absent → null. */
    val surveillance: Boolean? = null,
    /** OSM `supervised`: yes → true, no → false, else null. */
    val supervised: Boolean? = null,
    /** OSM `cargo_bike` availability (or presence of `capacity:cargo_bike` > 0). */
    val cargoBike: Boolean? = null,
    /** OSM `capacity:cargo_bike` parsed as int. */
    val cargoBikeCapacity: Int? = null,
    /** OSM `capacity:disabled` parsed as int. */
    val disabledCapacity: Int? = null,
    /** OSM `capacity:charging` parsed as int. */
    val chargingCapacity: Int? = null,
    /** OSM `indoor` (implied true for building/multi-storey/garage). */
    val indoor: Boolean? = null,
    /** Raw OSM `maxstay`. */
    val maxstay: String? = null,
    /** Raw OSM `opening_hours`. */
    val openingHours: String? = null,
    /** OSM `website` (preferred) or `contact:website`. */
    val website: String? = null,
    /** Raw OSM `network`. */
    val network: String? = null,
    /** Raw OSM `brand`. */
    val brand: String? = null,
    /** Raw OSM `ref`. */
    val ref: String? = null,
    /** OSM `check_date` (preferred) or `survey:date` — data-freshness signal. */
    val checkDate: String? = null,
    /** Raw OSM `bicycle_parking` subtype (e.g. lockers, stands, shed, two-tier). */
    val parkingSubtype: String? = null
)

