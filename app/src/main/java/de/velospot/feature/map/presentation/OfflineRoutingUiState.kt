package de.velospot.feature.map.presentation

import de.velospot.data.brouter.BRouterProfile

/** Drives the Offline Navigation menu items and setup sheet. */
sealed class OfflineRoutingUiState {
    /** User has not enabled offline routing (default). Routes via OSRM online. */
    data object Disabled : OfflineRoutingUiState()

    /**
     * Segment files are being downloaded.
     *
     * @param fileProgress     Progress within the current file (0f–1f; -1f = unknown size).
     * @param currentFileIndex 1-based index of the file currently being downloaded.
     * @param totalFiles       Total number of segment files to download.
     * @param downloadedBytes  Bytes received for the current file.
     * @param totalBytes       Content-Length of the current file (-1 if unknown).
     * @param currentFile      Display name / file name being downloaded.
     */
    data class Downloading(
        val fileProgress: Float      = 0f,
        val currentFileIndex: Int    = 1,
        val totalFiles: Int          = 1,
        val downloadedBytes: Long    = 0L,
        val totalBytes: Long         = -1L,
        val currentFile: String      = ""
    ) : OfflineRoutingUiState()

    /** All segment files downloaded successfully. Shown briefly before transitioning to [Enabled]. */
    data class DownloadComplete(val profile: BRouterProfile) : OfflineRoutingUiState()

    /** Offline routing is active with the selected [profile]. */
    data class Enabled(val profile: BRouterProfile) : OfflineRoutingUiState()
}
