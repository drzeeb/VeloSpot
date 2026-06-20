package de.velospot.feature.map.presentation.offline

import android.content.Context
import de.velospot.core.routing.OfflineRoutingPreferences
import de.velospot.core.routing.isWifiConnected
import de.velospot.data.brouter.BRouterProfile
import de.velospot.data.brouter.BRouterSegmentManager
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.MapError
import de.velospot.domain.model.NoInternetConnectionException
import de.velospot.feature.map.presentation.OfflineRoutingUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the offline-routing concern: the segment download lifecycle, the setup /
 * profile / Wi-Fi-warning sheets and the active routing profile. Extracted from
 * `MapViewModel` so the (stateful, multi-step) download flow lives behind a small,
 * focused API.
 *
 * Cross-feature effects are surfaced through callbacks rather than reaching into
 * other state: [onDownloadError] lets the host route a failed download to the
 * shared error surface without this controller knowing about navigation.
 */
class OfflineRoutingController(
    private val scope: CoroutineScope,
    private val context: Context,
    private val segmentManager: BRouterSegmentManager,
    private val currentLocation: () -> GeoCoordinate?,
    private val onDownloadError: (MapError) -> Unit,
) {
    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<OfflineRoutingUiState> = _uiState.asStateFlow()

    private val _showSetupSheet = MutableStateFlow(false)
    val showSetupSheet: StateFlow<Boolean> = _showSetupSheet.asStateFlow()

    private val _showProfileSheet = MutableStateFlow(false)
    val showProfileSheet: StateFlow<Boolean> = _showProfileSheet.asStateFlow()

    private val _showWifiWarning = MutableStateFlow(false)
    val showWifiWarning: StateFlow<Boolean> = _showWifiWarning.asStateFlow()

    fun requestSetup()        { _showSetupSheet.value = true }
    fun dismissSetupSheet()   { _showSetupSheet.value = false }
    fun openProfileSheet()    { _showProfileSheet.value = true }
    fun dismissProfileSheet() { _showProfileSheet.value = false }
    fun dismissWifiWarning()  { _showWifiWarning.value = false }

    fun confirmDownloadOnMobileData() {
        _showWifiWarning.value = false
        startDownload()
    }

    /**
     * Confirms the setup sheet. Shows a Wi-Fi warning when the device is on
     * metered data, otherwise starts the download immediately.
     */
    fun confirmSetup() {
        _showSetupSheet.value = false
        if (!isWifiConnected(context)) {
            _showWifiWarning.value = true
            return
        }
        startDownload()
    }

    private fun startDownload() {
        val loc = currentLocation() ?: GeoCoordinate(DEFAULT_LAT, DEFAULT_LON)
        _uiState.value = OfflineRoutingUiState.Downloading()
        scope.launch {
            runCatching {
                segmentManager.downloadSegmentsForLocation(
                    lat = loc.latitude,
                    lon = loc.longitude,
                    onProgress = { downloaded, total, fileIndex, totalFiles, fileName ->
                        val progress = if (total > 0L) downloaded / total.toFloat() else -1f
                        _uiState.value = OfflineRoutingUiState.Downloading(
                            fileProgress      = progress,
                            currentFileIndex  = fileIndex,
                            totalFiles        = totalFiles,
                            downloadedBytes   = downloaded,
                            totalBytes        = total,
                            currentFile       = fileName
                        )
                    }
                )
            }.onSuccess {
                val profile = OfflineRoutingPreferences.getSelectedProfile(context)
                OfflineRoutingPreferences.setOfflineRoutingEnabled(context, true)
                // Show success state briefly, then switch to Enabled.
                _uiState.value = OfflineRoutingUiState.DownloadComplete(profile)
                delay(DOWNLOAD_COMPLETE_DISPLAY_MS)
                _uiState.value = OfflineRoutingUiState.Enabled(profile)
            }.onFailure { throwable ->
                _uiState.value = OfflineRoutingUiState.Disabled
                onDownloadError(
                    when (throwable) {
                        is NoInternetConnectionException -> MapError.NoInternetConnection
                        else                             -> MapError.Unknown(throwable.message)
                    }
                )
            }
        }
    }

    /** Persists [profile] as the active one and reflects it in the UI state. */
    fun selectProfile(profile: BRouterProfile) {
        OfflineRoutingPreferences.setSelectedProfile(context, profile)
        _uiState.value = OfflineRoutingUiState.Enabled(profile)
    }

    /** Disables offline routing and wipes all downloaded segment files. */
    fun disable() {
        OfflineRoutingPreferences.setOfflineRoutingEnabled(context, false)
        _uiState.value = OfflineRoutingUiState.Disabled
        scope.launch { segmentManager.deleteAllSegments() }
    }

    private fun initialState(): OfflineRoutingUiState =
        if (OfflineRoutingPreferences.isOfflineRoutingEnabled(context)) {
            OfflineRoutingUiState.Enabled(OfflineRoutingPreferences.getSelectedProfile(context))
        } else {
            OfflineRoutingUiState.Disabled
        }

    companion object {
        /** How long the "download complete" state lingers before switching to Enabled. */
        private const val DOWNLOAD_COMPLETE_DISPLAY_MS = 2_500L

        // Default download centre used when no GPS fix is available yet.
        private const val DEFAULT_LAT = 49.7596
        private const val DEFAULT_LON = 6.6441
    }
}

