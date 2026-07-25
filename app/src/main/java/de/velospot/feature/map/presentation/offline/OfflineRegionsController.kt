package de.velospot.feature.map.presentation.offline

import android.content.Context
import de.velospot.core.offline.OfflineRegionPack
import de.velospot.core.offline.OfflineRegionsStore
import de.velospot.core.routing.OfflineRoutingPreferences
import de.velospot.core.routing.isInternetAvailable
import de.velospot.core.routing.isWifiConnected
import de.velospot.core.maptiles.OfflineMapPreferences
import de.velospot.core.maptiles.OfflineMapRegions
import de.velospot.data.brouter.BRouterProfile
import de.velospot.data.brouter.BRouterSegmentManager
import de.velospot.data.maptiles.OfflineMapTilesManager
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.MapError
import de.velospot.domain.model.NoInternetConnectionException
import de.velospot.feature.map.presentation.OfflineRegionsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Owns the unified **offline usage** concern: downloading, listing and deleting
 * offline regions (each a combined **map-tiles + routing** pack) plus the active
 * routing profile. Replaces the previous split `OfflineRoutingController` /
 * `OfflineMapController` so a region always gets both halves — no more "offline
 * route on a blank grey map".
 *
 * A region is anchored on a point (the rider's current position when they add it),
 * so extra regions — e.g. a holiday spot far from home — can be added on the go.
 *
 * @param styleUrl        the map style whose tiles/glyphs/sprite are cached.
 * @param currentLocation supplies the rider's position for a new region.
 * @param reverseGeocode  resolves a short place name for the region label.
 * @param onDownloadError routes a failed download to the shared error surface.
 */
class OfflineRegionsController(
    private val scope: CoroutineScope,
    private val context: Context,
    private val segmentManager: BRouterSegmentManager,
    private val tilesManager: OfflineMapTilesManager,
    private val styleUrl: String,
    private val store: OfflineRegionsStore,
    private val currentLocation: () -> GeoCoordinate?,
    private val reverseGeocode: suspend (Double, Double) -> String?,
    private val onDownloadError: (MapError) -> Unit,
    private val onDuplicateRegion: () -> Unit = {},
) {
    private val _uiState = MutableStateFlow(
        OfflineRegionsUiState(
            regions = store.list(),
            profile = OfflineRoutingPreferences.getSelectedProfile(context),
        )
    )
    val uiState: StateFlow<OfflineRegionsUiState> = _uiState.asStateFlow()

    private val _showManagerSheet = MutableStateFlow(false)
    val showManagerSheet: StateFlow<Boolean> = _showManagerSheet.asStateFlow()

    private val _showProfileSheet = MutableStateFlow(false)
    val showProfileSheet: StateFlow<Boolean> = _showProfileSheet.asStateFlow()

    private val _showWifiWarning = MutableStateFlow(false)
    val showWifiWarning: StateFlow<Boolean> = _showWifiWarning.asStateFlow()

    init {
        refreshTotalSize()
    }

    // ── Sheets ────────────────────────────────────────────────────────────────
    fun requestSetup()        { _showManagerSheet.value = true }
    fun dismissManagerSheet() { _showManagerSheet.value = false }
    fun openProfileSheet()    { _showProfileSheet.value = true }
    fun dismissProfileSheet() { _showProfileSheet.value = false }
    fun dismissWifiWarning()  { _showWifiWarning.value = false }

    // ── Download a new region ──────────────────────────────────────────────────

    /** Latitude/longitude of the region whose download is pending a Wi-Fi decision. */
    private var pendingLat: Double? = null
    private var pendingLon: Double? = null

    fun confirmDownloadOnMobileData() {
        _showWifiWarning.value = false
        val lat = pendingLat ?: return
        val lon = pendingLon ?: return
        startDownloadAt(lat, lon)
    }

    /**
     * Adds a region around the rider's current position. Shows a Wi-Fi warning on
     * metered data first, otherwise downloads immediately.
     */
    fun addCurrentRegion() {
        val loc = currentLocation()
        if (loc == null) {
            onDownloadError(MapError.LocationUnavailable)
            return
        }
        addRegionAt(loc.latitude, loc.longitude)
    }

    /**
     * Adds a region around an explicit [lat]/[lon] — e.g. a point the rider picked
     * on the map. Fails fast with a clear error if there is **no connection at all**,
     * otherwise shows a Wi-Fi warning on metered data, otherwise downloads.
     */
    fun addRegionAt(lat: Double, lon: Double) {
        // Don't download the same area twice: if the new point falls within an
        // existing region's coverage radius, it's effectively a duplicate.
        if (store.list().any { isSameArea(it.latitude, it.longitude, lat, lon) }) {
            onDuplicateRegion()
            return
        }
        if (!isInternetAvailable(context)) {
            onDownloadError(MapError.NoInternetConnection)
            return
        }
        pendingLat = lat
        pendingLon = lon
        if (!isWifiConnected(context)) {
            _showWifiWarning.value = true
            return
        }
        startDownloadAt(lat, lon)
    }

    private fun startDownloadAt(lat: Double, lon: Double) {
        val id = UUID.randomUUID().toString()
        _uiState.value = _uiState.value.copy(
            downloading = OfflineRegionsUiState.Downloading(OfflineRegionsUiState.Phase.MAP)
        )
        scope.launch {
            runCatching {
                // Phase 1 — the visible map tiles for a box around the location.
                tilesManager.downloadRegionAroundLocation(
                    lat = lat,
                    lon = lon,
                    styleUrl = styleUrl,
                    regionName = mapRegionName(id),
                ) { fraction, bytes, _, _ ->
                    _uiState.value = _uiState.value.copy(
                        downloading = OfflineRegionsUiState.Downloading(
                            phase = OfflineRegionsUiState.Phase.MAP,
                            fraction = fraction,
                            downloadedBytes = bytes,
                        )
                    )
                }
                // Phase 2 — the BRouter 5°×5° routing tile covering the location.
                _uiState.value = _uiState.value.copy(
                    downloading = OfflineRegionsUiState.Downloading(OfflineRegionsUiState.Phase.ROUTING)
                )
                segmentManager.downloadSegmentsForLocation(
                    lat = lat,
                    lon = lon,
                ) { downloaded, total, _, _, _ ->
                    _uiState.value = _uiState.value.copy(
                        downloading = OfflineRegionsUiState.Downloading(
                            phase = OfflineRegionsUiState.Phase.ROUTING,
                            fraction = if (total > 0L) downloaded / total.toFloat() else -1f,
                            downloadedBytes = downloaded,
                        )
                    )
                }
            }.onSuccess {
                val label = runCatching { reverseGeocode(lat, lon) }.getOrNull()
                    ?: fallbackLabel(lat, lon)
                store.add(
                    OfflineRegionPack(
                        id = id,
                        label = label,
                        latitude = lat,
                        longitude = lon,
                        createdAt = System.currentTimeMillis(),
                    )
                )
                OfflineRoutingPreferences.setOfflineRoutingEnabled(context, true)
                OfflineMapPreferences.setHasOfflineMap(context, true)
                _uiState.value = _uiState.value.copy(regions = store.list(), downloading = null)
                refreshTotalSize()
            }.onFailure { throwable ->
                // Roll back a partial map region so a failed add leaves nothing behind.
                runCatching { tilesManager.deleteRegionByName(mapRegionName(id)) }
                _uiState.value = _uiState.value.copy(downloading = null)
                onDownloadError(
                    when (throwable) {
                        is NoInternetConnectionException -> MapError.NoInternetConnection
                        else                             -> MapError.Unknown(throwable.message)
                    }
                )
            }
        }
    }

    // ── Delete regions ─────────────────────────────────────────────────────────

    /** Deletes one region: its map tiles and — unless shared — its routing tile. */
    fun deleteRegion(id: String) {
        val pack = store.list().firstOrNull { it.id == id } ?: return
        scope.launch {
            runCatching { tilesManager.deleteRegionByName(mapRegionName(id)) }
            // Only delete the routing tile if no other region shares that 5° tile.
            val tile = segmentManager.segmentTileNameForLocation(pack.latitude, pack.longitude)
            val stillNeeded = store.list()
                .filterNot { it.id == id }
                .any { segmentManager.segmentTileNameForLocation(it.latitude, it.longitude) == tile }
            if (!stillNeeded) segmentManager.deleteSegmentTile(tile)
            store.remove(id)
            finishDeletion()
        }
    }

    /** Deletes every offline region and returns to the fully-online state. */
    fun deleteAllRegions() {
        scope.launch {
            runCatching { tilesManager.deleteAllRegions() }
            runCatching { segmentManager.deleteAllSegments() }
            store.clear()
            finishDeletion()
        }
    }

    private fun finishDeletion() {
        val remaining = store.list()
        if (remaining.isEmpty()) {
            OfflineRoutingPreferences.setOfflineRoutingEnabled(context, false)
            OfflineMapPreferences.setHasOfflineMap(context, false)
        }
        _uiState.value = _uiState.value.copy(regions = remaining)
        refreshTotalSize()
    }

    // ── Routing profile ─────────────────────────────────────────────────────────

    /** Persists [profile] as the active offline-routing profile. */
    fun selectProfile(profile: BRouterProfile) {
        OfflineRoutingPreferences.setSelectedProfile(context, profile)
        _uiState.value = _uiState.value.copy(profile = profile)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun refreshTotalSize() {
        scope.launch {
            val size = withContext(Dispatchers.IO) {
                runCatching { tilesManager.totalCacheSizeBytes() }.getOrDefault(0L) +
                    runCatching { segmentManager.totalSegmentsSizeBytes() }.getOrDefault(0L)
            }
            _uiState.value = _uiState.value.copy(totalSizeBytes = size)
        }
    }

    private fun fallbackLabel(lat: Double, lon: Double): String =
        "%.3f, %.3f".format(lat, lon)

    /**
     * Whether [lat2]/[lon2] falls within an existing region anchored at [lat1]/[lon1],
     * i.e. inside its map-coverage radius — used to reject duplicate downloads of the
     * same area.
     */
    private fun isSameArea(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Boolean =
        distanceKm(lat1, lon1, lat2, lon2) <= OfflineMapRegions.DEFAULT_REGION_RADIUS_KM

    /** Great-circle distance in km between two lat/lon points (haversine). */
    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private companion object {
        fun mapRegionName(id: String) = "pack-$id"
    }
}

