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
    @param:Json(name = "id")
    val id: String? = null,
    @param:Json(name = "fid")
    val featureId: String? = null,
    @param:Json(name = "name")
    val name: String? = null,
    @param:Json(name = "bezeichnung")
    val designation: String? = null,
    @param:Json(name = "adresse")
    val address: String? = null,
    @param:Json(name = "strasse")
    val street: String? = null,
    @param:Json(name = "hausnummer")
    val houseNumber: String? = null,
    @param:Json(name = "typ")
    val type: String? = null,
    @param:Json(name = "art")
    val kind: String? = null,
    @param:Json(name = "kapazitaet")
    val capacity: Int? = null,
    @param:Json(name = "anzahl")
    val amount: Int? = null,
    @param:Json(name = "stellplaetze")
    val parkingSlots: Int? = null,
    @param:Json(name = "ueberdacht")
    val covered: String? = null,
    @param:Json(name = "betreiber")
    val operator: String? = null,
    @param:Json(name = "layer")
    val layer: String? = null
)

