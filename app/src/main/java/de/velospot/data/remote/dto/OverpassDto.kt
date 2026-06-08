package de.velospot.data.remote.dto

/**
 * Top-level Overpass API JSON response.
 *
 * Example response shape:
 * ```json
 * {
 *   "elements": [
 *     { "type": "node", "id": 123, "lat": 49.75, "lon": 6.64, "tags": { ... } },
 *     { "type": "way",  "id": 456, "center": { "lat": 49.76, "lon": 6.65 }, "tags": { ... } }
 *   ]
 * }
 * ```
 */
data class OverpassResponseDto(
    val elements: List<OverpassElementDto> = emptyList()
)

data class OverpassElementDto(
    /** OSM element type: "node", "way", or "relation". */
    val type: String = "",
    /** OSM element ID. */
    val id: Long = 0L,
    /** Latitude – present for `node` elements. */
    val lat: Double? = null,
    /** Longitude – present for `node` elements. */
    val lon: Double? = null,
    /** Geometric centre – present for `way`/`relation` elements when `out center` is used. */
    val center: OverpassCenterDto? = null,
    /** OSM tags as a key-value map. */
    val tags: Map<String, String>? = null
)

data class OverpassCenterDto(
    val lat: Double,
    val lon: Double
)

