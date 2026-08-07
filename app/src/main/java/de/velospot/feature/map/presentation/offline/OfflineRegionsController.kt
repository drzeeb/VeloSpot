package de.velospot.feature.map.presentation.offline

import android.content.Context
import de.velospot.core.offline.OfflineRegionPack
import de.velospot.core.offline.OfflineRegionsStore
import de.velospot.core.routing.OfflineRoutingPreferences
import de.velospot.core.routing.isInternetAvailable
import de.velospot.core.routing.isWifiConnected
import de.velospot.core.maptiles.OfflineMapPreferences
import de.velospot.core.maptiles.OfflineMapRegions
import de.velospot.core.maptiles.RouteCorridor
import de.velospot.data.brouter.BRouterProfile
import de.velospot.data.brouter.BRouterSegmentManager
import de.velospot.data.maptiles.OfflineMapTilesManager
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.MapError
import de.velospot.domain.model.NoInternetConnectionException
import de.velospot.domain.model.PlannedRoute
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

    /** The download awaiting a Wi-Fi / mobile-data decision (a region or a route). */
    private var pendingDownload: (() -> Unit)? = null

    fun confirmDownloadOnMobileData() {
        _showWifiWarning.value = false
        val action = pendingDownload ?: return
        pendingDownload = null
        action()
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
        gateDownload { startDownloadAt(lat, lon) }
    }

    /**
     * Downloads the **whole corridor** of a planned [route] for offline use — the
     * map tiles along the route (not just a box around the start) *and* every BRouter
     * routing tile the route passes through — so a long tour into a dead zone works
     * end-to-end offline.
     */
    fun downloadRouteCorridor(route: PlannedRoute) {
        // Don't download the same corridor twice: a corridor pack is stored with its
        // route's name as the pack label (see startRouteDownload), so a matching label
        // means this exact route is already saved offline. Signal it through the same
        // callback the point path uses so the UX stays consistent.
        if (route.name.isNotBlank() && store.list().any { it.label == route.name }) {
            onDuplicateRegion()
            return
        }
        val points = route.geometry
            .map { it.latitude to it.longitude }
            .ifEmpty { route.waypoints.map { it.latitude to it.longitude } }
        if (points.isEmpty()) return
        gateDownload { startRouteDownload(route, points) }
    }

    /** Applies the connectivity / Wi-Fi gate, then runs [download] (or defers it). */
    private fun gateDownload(download: () -> Unit) {
        if (!isInternetAvailable(context)) {
            onDownloadError(MapError.NoInternetConnection)
            return
        }
        if (!isWifiConnected(context)) {
            pendingDownload = download
            _showWifiWarning.value = true
            return
        }
        download()
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
                        routingTiles = listOf(segmentManager.segmentTileNameForLocation(lat, lon)),
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

    /**
     * Downloads a whole route corridor: the map-tile boxes tiling the route, then
     * every BRouter routing tile the route passes through. Registered as one pack
     * (named `pack-<id>`, like a point region) so it lists and deletes as a unit.
     */
    private fun startRouteDownload(route: PlannedRoute, points: List<Pair<Double, Double>>) {
        val id = UUID.randomUUID().toString()
        _uiState.value = _uiState.value.copy(
            downloading = OfflineRegionsUiState.Downloading(OfflineRegionsUiState.Phase.MAP)
        )
        scope.launch {
            runCatching {
                // Phase 1 — the map tiles for every box tiling the route corridor.
                val boxes = RouteCorridor.corridorBoxes(points)
                tilesManager.downloadRouteCorridor(
                    boxes = boxes,
                    styleUrl = styleUrl,
                    regionName = mapRegionName(id),
                ) { fraction, bytes, regionIndex, totalRegions ->
                    // Fold per-box progress into one overall 0f–1f fraction.
                    val overall = if (fraction >= 0f && totalRegions > 0) {
                        ((regionIndex - 1) + fraction) / totalRegions
                    } else -1f
                    _uiState.value = _uiState.value.copy(
                        downloading = OfflineRegionsUiState.Downloading(
                            phase = OfflineRegionsUiState.Phase.MAP,
                            fraction = overall,
                            downloadedBytes = bytes,
                        )
                    )
                }
                // Phase 2 — every BRouter 5°×5° routing tile the route crosses.
                _uiState.value = _uiState.value.copy(
                    downloading = OfflineRegionsUiState.Downloading(OfflineRegionsUiState.Phase.ROUTING)
                )
                segmentManager.downloadSegmentsForRoute(points) { downloaded, total, fileIndex, totalFiles, _ ->
                    val perFile = if (total > 0L) downloaded / total.toFloat() else 0f
                    val overall = if (totalFiles > 0) ((fileIndex - 1) + perFile) / totalFiles else -1f
                    _uiState.value = _uiState.value.copy(
                        downloading = OfflineRegionsUiState.Downloading(
                            phase = OfflineRegionsUiState.Phase.ROUTING,
                            fraction = overall,
                            downloadedBytes = downloaded,
                        )
                    )
                }
            }.onSuccess {
                store.add(
                    OfflineRegionPack(
                        id = id,
                        label = route.name,
                        latitude = points[points.size / 2].first,
                        longitude = points[points.size / 2].second,
                        createdAt = System.currentTimeMillis(),
                        routingTiles = segmentManager.requiredSegmentNamesForPoints(points),
                    )
                )
                OfflineRoutingPreferences.setOfflineRoutingEnabled(context, true)
                OfflineMapPreferences.setHasOfflineMap(context, true)
                _uiState.value = _uiState.value.copy(regions = store.list(), downloading = null)
                refreshTotalSize()
            }.onFailure { throwable ->
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

    /** Deletes one region: its map tiles and — unless shared — its routing tile(s). */
    fun deleteRegion(id: String) {
        val pack = store.list().firstOrNull { it.id == id } ?: return
        scope.launch {
            runCatching { tilesManager.deleteRegionByName(mapRegionName(id)) }
            // The tiles this pack needs (legacy entries fall back to the anchor tile).
            val myTiles = pack.routingTiles.ifEmpty {
                listOf(segmentManager.segmentTileNameForLocation(pack.latitude, pack.longitude))
            }
            // The tiles every *other* remaining pack still needs.
            val stillNeeded = store.list()
                .filterNot { it.id == id }
                .flatMap { other ->
                    other.routingTiles.ifEmpty {
                        listOf(segmentManager.segmentTileNameForLocation(other.latitude, other.longitude))
                    }
                }
                .toSet()
            // Only remove routing tiles no other region depends on.
            myTiles.filterNot { it in stillNeeded }.forEach { segmentManager.deleteSegmentTile(it) }
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

