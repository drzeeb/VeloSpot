package de.velospot.core.offline

/**
 * A single **offline region** the rider has downloaded for offline use — one
 * combined pack of the visible **map tiles** (a ~40 km box) *and* the **BRouter
 * routing** 5°×5° tile that covers the same spot. Anchored on a point (the rider's
 * position when they added it), so a Frankfurt-based rider on holiday in Sydney can
 * simply add a second region while there.
 *
 * A plain, Android-free value type so the region bookkeeping stays a pure,
 * JVM-unit-testable concern (`OfflineRegionsStore` handles persistence).
 *
 * @param id        stable unique id (also used to name the MapLibre offline region).
 * @param label     human-readable name (reverse-geocoded place, e.g. "Sydney").
 * @param latitude  anchor latitude of the region.
 * @param longitude anchor longitude of the region.
 * @param createdAt epoch millis the region was added (for stable ordering).
 */
data class OfflineRegionPack(
    val id: String,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val createdAt: Long,
)

