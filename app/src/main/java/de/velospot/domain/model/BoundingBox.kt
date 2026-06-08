package de.velospot.domain.model

/**
 * Axis-aligned geographic bounding box used for spatial queries (e.g. Overpass API).
 *
 * All values are in decimal degrees (WGS-84).
 */
data class BoundingBox(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double
) {
    /**
     * Returns `true` if this bounding box fully contains [other].
     * Used by the repository to skip re-fetching already-covered areas.
     */
    fun covers(other: BoundingBox): Boolean =
        other.minLat >= minLat && other.maxLat <= maxLat &&
                other.minLon >= minLon && other.maxLon <= maxLon

    /**
     * Returns a new bounding box expanded by [factor] in each direction.
     * E.g. `expand(0.2)` adds a 20 % margin around the current box.
     * Useful for pre-fetching data slightly beyond the visible viewport.
     */
    fun expand(factor: Double = 0.2): BoundingBox {
        val latMargin = (maxLat - minLat) * factor
        val lonMargin = (maxLon - minLon) * factor
        return BoundingBox(
            minLat = minLat - latMargin,
            minLon = minLon - lonMargin,
            maxLat = maxLat + latMargin,
            maxLon = maxLon + lonMargin
        )
    }

    companion object {
        /** Roughly 15 km around the default map start position (Trier city centre). */
        val DEFAULT = BoundingBox(
            minLat = 49.67,
            minLon = 6.54,
            maxLat = 49.85,
            maxLon = 6.75
        )
    }
}

