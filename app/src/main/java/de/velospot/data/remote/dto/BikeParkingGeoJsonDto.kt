package de.velospot.data.remote.dto

import com.squareup.moshi.Json

data class GeoJsonFeatureCollectionDto(
    val type: String?,
    val features: List<GeoJsonFeatureDto> = emptyList()
)

data class GeoJsonFeatureDto(
    val id: String?,
    val geometry: GeoJsonGeometryDto?,
    val properties: BikeParkingPropertiesDto?
)

data class GeoJsonGeometryDto(
    val type: String?,
    val coordinates: List<Double>?
)

data class BikeParkingPropertiesDto(
    @Json(name = "id")
    val id: String? = null,
    @Json(name = "fid")
    val featureId: String? = null,
    @Json(name = "name")
    val name: String? = null,
    @Json(name = "bezeichnung")
    val designation: String? = null,
    @Json(name = "adresse")
    val address: String? = null,
    @Json(name = "strasse")
    val street: String? = null,
    @Json(name = "hausnummer")
    val houseNumber: String? = null,
    @Json(name = "typ")
    val type: String? = null,
    @Json(name = "art")
    val kind: String? = null,
    @Json(name = "kapazitaet")
    val capacity: Int? = null,
    @Json(name = "anzahl")
    val amount: Int? = null,
    @Json(name = "stellplaetze")
    val parkingSlots: Int? = null,
    @Json(name = "ueberdacht")
    val covered: String? = null,
    @Json(name = "betreiber")
    val operator: String? = null,
    @Json(name = "layer")
    val layer: String? = null
)

