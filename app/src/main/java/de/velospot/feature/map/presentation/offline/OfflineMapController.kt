package de.velospot.feature.map.presentation.offline

import android.content.Context
import de.velospot.core.maptiles.OfflineMapPreferences
import de.velospot.core.routing.isWifiConnected
import de.velospot.data.maptiles.OfflineMapTilesManager
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.MapError
import de.velospot.domain.model.NoInternetConnectionException
import de.velospot.feature.map.presentation.OfflineMapUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the **offline map tiles** concern: the download lifecycle, the setup sheet and
 * the optional Wi-Fi warning. A near-twin of
 * [OfflineRoutingController], so downloading the visible map feels exactly like
 * downloading the routing segments.
 *
 * @param styleUrl        the map style whose tiles/glyphs/sprite are cached (the light
 *                        style is fine — dark reuses the very same OpenFreeMap tiles).
 * @param currentLocation supplies the rider's position for the "my region" download.
 * @param onDownloadError routes a failed download to the shared error surface.
 */
class OfflineMapController(
    private val scope: CoroutineScope,
    private val context: Context,
    private val tilesManager: OfflineMapTilesManager,
    private val styleUrl: String,
    private val currentLocation: () -> GeoCoordinate?,
    private val onDownloadError: (MapError) -> Unit,
) {
    private val _uiState = MutableStateFlow<OfflineMapUiState>(
        if (OfflineMapPreferences.hasOfflineMap(context)) OfflineMapUiState.Ready() else OfflineMapUiState.Disabled
    )
    val uiState: StateFlow<OfflineMapUiState> = _uiState.asStateFlow()

    private val _showSetupSheet = MutableStateFlow(false)
    val showSetupSheet: StateFlow<Boolean> = _showSetupSheet.asStateFlow()

    private val _showWifiWarning = MutableStateFlow(false)
    val showWifiWarning: StateFlow<Boolean> = _showWifiWarning.asStateFlow()

    /** Remembered across the (optional) Wi-Fi warning so confirming resumes the right download. */
    private var pendingFullDownload = false

    init {
        // Resolve the (async) cache size for the initial Ready state.
        if (_uiState.value is OfflineMapUiState.Ready) refreshCacheSize()
    }

    fun requestSetup()      { _showSetupSheet.value = true }
    fun dismissSetupSheet() { _showSetupSheet.value = false }
    fun dismissWifiWarning() { _showWifiWarning.value = false }

    fun confirmDownloadOnMobileData() {
        _showWifiWarning.value = false
        startDownload(full = pendingFullDownload)
    }

    /**
     * Confirms the setup sheet. [full] selects between the small "my region" download
     * and the whole DE/FR/LU area. Shows a Wi-Fi warning on metered data (the whole
     * area can be large), otherwise starts immediately.
     */
    fun confirmSetup(full: Boolean) {
        _showSetupSheet.value = false
        pendingFullDownload = full
        if (!isWifiConnected(context)) {
            _showWifiWarning.value = true
            return
        }
        startDownload(full = full)
    }

    private fun startDownload(full: Boolean) {
        // "My region" needs a real position: without a GPS fix we can't know which
        // area to cache, so abort rather than download some default region.
        val loc = currentLocation()
        if (!full && loc == null) {
            _uiState.value = idleState()
            onDownloadError(MapError.LocationUnavailable)
            return
        }
        _uiState.value = OfflineMapUiState.Downloading()
        scope.launch {
            runCatching {
                val listener = OfflineMapTilesManager.ProgressListener { fraction, bytes, index, total ->
                    _uiState.value = OfflineMapUiState.Downloading(
                        fraction        = fraction,
                        downloadedBytes = bytes,
                        regionIndex     = index,
                        totalRegions    = total,
                    )
                }
                if (full) {
                    tilesManager.downloadCountryRegions(styleUrl, listener)
                } else {
                    tilesManager.downloadRegionAroundLocation(loc!!.latitude, loc.longitude, styleUrl, listener)
                }
            }.onSuccess {
                OfflineMapPreferences.setHasOfflineMap(context, true)
                _uiState.value = OfflineMapUiState.Ready()
                refreshCacheSize()
            }.onFailure { throwable ->
                _uiState.value = idleState()
                onDownloadError(
                    when (throwable) {
                        is NoInternetConnectionException -> MapError.NoInternetConnection
                        else                             -> MapError.Unknown(throwable.message)
                    }
                )
            }
        }
    }

    /** Deletes all offline map tiles and returns to the streaming (disabled) state. */
    fun deleteOfflineMap() {
        scope.launch {
            runCatching { tilesManager.deleteAllRegions() }
            OfflineMapPreferences.setHasOfflineMap(context, false)
            _uiState.value = OfflineMapUiState.Disabled
        }
    }

    /** Whichever resting state matches whether an offline map is present. */
    private fun idleState(): OfflineMapUiState =
        if (OfflineMapPreferences.hasOfflineMap(context)) OfflineMapUiState.Ready() else OfflineMapUiState.Disabled

    /** Fetches the offline cache size off-thread and folds it into a [OfflineMapUiState.Ready]. */
    private fun refreshCacheSize() {
        scope.launch {
            val size = runCatching { tilesManager.totalCacheSizeBytes() }.getOrDefault(0L)
            if (_uiState.value is OfflineMapUiState.Ready) {
                _uiState.value = OfflineMapUiState.Ready(cacheSizeBytes = size)
            }
        }
    }
}



