package de.velospot.feature.map.presentation

/**
 * Drives the "Offline map" entry in the Navigation & routing sheet and its setup
 * sheet. Mirrors [OfflineRoutingUiState] so the download UX feels identical to the
 * offline-routing one.
 */
sealed class OfflineMapUiState {
    /** No offline map downloaded (default). The map streams tiles live. */
    data object Disabled : OfflineMapUiState()

    /**
     * Map tiles are being downloaded for a region.
     *
     * @param fraction        Resource fraction of the current region (0f–1f; -1f = unknown yet).
     * @param downloadedBytes Bytes fetched for the current region so far.
     * @param regionIndex     1-based index of the region currently downloading.
     * @param totalRegions    Number of regions in this download (1 for "my region").
     */
    data class Downloading(
        val fraction: Float       = -1f,
        val downloadedBytes: Long = 0L,
        val regionIndex: Int      = 1,
        val totalRegions: Int     = 1,
    ) : OfflineMapUiState()

    /** At least one offline map region is present. [cacheSizeBytes] is its total size. */
    data class Ready(val cacheSizeBytes: Long = 0L) : OfflineMapUiState()
}

