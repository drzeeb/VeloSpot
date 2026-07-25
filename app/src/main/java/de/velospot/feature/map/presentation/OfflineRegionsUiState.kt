package de.velospot.feature.map.presentation

import de.velospot.core.offline.OfflineRegionPack
import de.velospot.data.brouter.BRouterProfile

/**
 * Drives the unified **offline usage** feature: the list of downloaded regions
 * (map tiles + routing combined), any in-progress download and the active routing
 * profile. Replaces the previous split `OfflineRoutingUiState` / `OfflineMapUiState`
 * so map + routing are always downloaded and managed together, per region.
 *
 * @param regions        the downloaded regions (empty = offline usage not set up).
 * @param totalSizeBytes combined on-disk size of all offline data (tiles + segments).
 * @param downloading    non-null while a region is being downloaded.
 * @param profile        the active BRouter routing profile applied to offline routes.
 */
data class OfflineRegionsUiState(
    val regions: List<OfflineRegionPack> = emptyList(),
    val totalSizeBytes: Long = 0L,
    val downloading: Downloading? = null,
    val profile: BRouterProfile = BRouterProfile.TREKKING,
) {
    /** True once at least one region is available offline. */
    val isEnabled: Boolean get() = regions.isNotEmpty()

    /**
     * Progress of the combined download. A region is fetched in two [Phase]s —
     * first its map tiles, then its routing segment — so the UI can label which
     * part is running.
     *
     * @param phase           which part of the region is currently downloading.
     * @param fraction        0f–1f progress of the current phase (-1f = unknown yet).
     * @param downloadedBytes bytes fetched in the current phase so far.
     */
    data class Downloading(
        val phase: Phase,
        val fraction: Float = -1f,
        val downloadedBytes: Long = 0L,
    )

    enum class Phase { MAP, ROUTING }
}

