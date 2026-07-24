package de.velospot.data.maptiles

import android.content.Context
import de.velospot.core.maptiles.GeoBounds
import de.velospot.core.maptiles.OfflineMapRegions
import de.velospot.domain.model.NoInternetConnectionException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Downloads and stores the **visible vector map** (OpenFreeMap tiles + glyphs +
 * sprite) for a region so the map still renders when offline — the missing half of
 * VeloSpot's offline story, alongside the already-offline BRouter routing.
 *
 * Deliberately mirrors [de.velospot.data.brouter.BRouterSegmentManager]: the user
 * downloads either the area **around their location** or the **whole supported area**,
 * on demand — nothing is bundled into the APK. Built entirely on MapLibre's own
 * [OfflineManager], so it adds **no new dependency** and, once tiles are cached,
 * MapLibre serves them automatically when the network is gone.
 *
 * All [OfflineManager] calls happen on the main thread (its callbacks are delivered
 * there), so the suspend functions bridge its callbacks via
 * [suspendCancellableCoroutine] rather than moving off-thread.
 */
class OfflineMapTilesManager(private val context: Context) {

    private val offlineManager: OfflineManager
        get() {
            // Idempotent; guarantees MapLibre is up before the offline store is used
            // (same defensive pattern as RideRouteMapSnapshotter).
            MapLibre.getInstance(context)
            return OfflineManager.getInstance(context).apply {
                // MapLibre aborts a region download at ~6000 tiles by default; raise
                // the ceiling so a real region / country download can complete.
                setOfflineMapboxTileCountLimit(MAX_TILE_COUNT)
            }
        }

    /** Progress for a running download. */
    fun interface ProgressListener {
        /**
         * @param fraction        0f–1f resource fraction of the current region (-1f = unknown yet).
         * @param downloadedBytes bytes fetched for the current region so far.
         * @param regionIndex     1-based index of the region currently downloading.
         * @param totalRegions    number of regions in this download (1 for "my region").
         */
        fun onProgress(fraction: Float, downloadedBytes: Long, regionIndex: Int, totalRegions: Int)
    }

    // ── Public API (mirrors BRouterSegmentManager) ────────────────────────────

    /** Downloads the map around [lat]/[lon] at street-level zoom (the small option). */
    suspend fun downloadRegionAroundLocation(
        lat: Double,
        lon: Double,
        styleUrl: String,
        onProgress: ProgressListener = ProgressListener { _, _, _, _ -> },
    ) {
        val bounds = OfflineMapRegions.boundsAround(lat, lon)
        downloadRegion(
            bounds = bounds,
            styleUrl = styleUrl,
            maxZoom = OfflineMapRegions.REGION_MAX_ZOOM,
            regionName = "my-region",
            regionIndex = 1,
            totalRegions = 1,
            onProgress = onProgress,
        )
    }

    /**
     * Downloads the **whole supported area** (DE/FR/LU) in a few resumable chunks
     * ([OfflineMapRegions.COUNTRY_BOUNDS]) at a lower max zoom to keep the (large)
     * download from ballooning. Only missing tiles are actually fetched, so a
     * re-run resumes where it left off.
     */
    suspend fun downloadCountryRegions(
        styleUrl: String,
        onProgress: ProgressListener = ProgressListener { _, _, _, _ -> },
    ) {
        val boxes = OfflineMapRegions.COUNTRY_BOUNDS
        boxes.forEachIndexed { index, bounds ->
            downloadRegion(
                bounds = bounds,
                styleUrl = styleUrl,
                maxZoom = OfflineMapRegions.COUNTRY_MAX_ZOOM,
                regionName = "country-$index",
                regionIndex = index + 1,
                totalRegions = boxes.size,
                onProgress = onProgress,
            )
        }
    }

    /** True when at least one offline map region has been downloaded. */
    suspend fun hasAnyRegion(): Boolean = listRegions().isNotEmpty()

    /** Sum of the completed download size (bytes) across every offline region. */
    suspend fun totalCacheSizeBytes(): Long =
        listRegions().sumOf { runCatching { regionStatus(it).completedResourceSize }.getOrDefault(0L) }

    /** Deletes every downloaded offline map region, freeing the storage. */
    suspend fun deleteAllRegions() {
        listRegions().forEach { deleteRegion(it) }
    }

    // ── Private MapLibre bridges ──────────────────────────────────────────────

    private suspend fun downloadRegion(
        bounds: GeoBounds,
        styleUrl: String,
        maxZoom: Double,
        regionName: String,
        regionIndex: Int,
        totalRegions: Int,
        onProgress: ProgressListener,
    ) = suspendCancellableCoroutine { cont ->
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            bounds.toLatLngBounds(),
            OfflineMapRegions.MIN_ZOOM,
            maxZoom,
            context.resources.displayMetrics.density,
        )
        val metadata = """{"name":"$regionName"}""".toByteArray(Charsets.UTF_8)

        offlineManager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    var settled = false
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            onProgress.onProgress(
                                OfflineMapRegions.progressFraction(
                                    status.completedResourceCount,
                                    status.requiredResourceCount,
                                ),
                                status.completedResourceSize,
                                regionIndex,
                                totalRegions,
                            )
                            if (status.isComplete && !settled) {
                                settled = true
                                offlineRegion.setObserver(null)
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                cont.resume(Unit)
                            }
                        }

                        override fun onError(error: OfflineRegionError) {
                            if (settled) return
                            settled = true
                            offlineRegion.setObserver(null)
                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            // A download error mid-flight is almost always connectivity;
                            // surface it as the shared "no internet" error like segments.
                            cont.resumeWithException(NoInternetConnectionException(RuntimeException(error.reason)))
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            if (settled) return
                            settled = true
                            offlineRegion.setObserver(null)
                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            cont.resumeWithException(IllegalStateException("Offline map tile limit reached ($limit)"))
                        }
                    })
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    cont.invokeOnCancellation {
                        offlineRegion.setObserver(null)
                        offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    }
                }

                override fun onError(error: String) {
                    cont.resumeWithException(NoInternetConnectionException(RuntimeException(error)))
                }
            },
        )
    }

    private suspend fun listRegions(): List<OfflineRegion> =
        suspendCancellableCoroutine { cont ->
            offlineManager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    cont.resume(offlineRegions?.toList().orEmpty())
                }

                override fun onError(error: String) {
                    cont.resume(emptyList())
                }
            })
        }

    private suspend fun regionStatus(region: OfflineRegion): OfflineRegionStatus =
        suspendCancellableCoroutine { cont ->
            region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                override fun onStatus(status: OfflineRegionStatus?) {
                    if (status != null) cont.resume(status)
                    else cont.resumeWithException(IllegalStateException("null offline region status"))
                }

                override fun onError(error: String?) =
                    cont.resumeWithException(IllegalStateException(error ?: "offline region status error"))
            })
        }

    private suspend fun deleteRegion(region: OfflineRegion): Unit =
        suspendCancellableCoroutine { cont ->
            region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() = cont.resume(Unit)
                override fun onError(error: String) = cont.resume(Unit)
            })
        }

    private fun GeoBounds.toLatLngBounds(): LatLngBounds =
        LatLngBounds.from(north, east, south, west)

    companion object {
        /** Generous tile ceiling so a real region/country download is never truncated. */
        private const val MAX_TILE_COUNT = 1_000_000L
    }
}


