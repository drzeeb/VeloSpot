package de.velospot.feature.map.presentation.ride

import android.content.Context
import de.velospot.domain.model.RecordedRide
import de.velospot.feature.map.presentation.MAP_STYLE_URL_LIGHT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import kotlin.coroutines.resume

/**
 * Renders a static 2D map snapshot covering a ride's bounding box, to be drawn
 * behind the route line on the share card. Uses MapLibre's [MapSnapshotter], which
 * renders off-screen on its own GL context — no on-screen [org.maplibre.android.maps.MapView] needed.
 *
 * The track points are projected with the snapshot's own [org.maplibre.android.snapshotter.MapSnapshot.pixelForLatLng]
 * so the polyline lines up exactly with the rendered map.
 *
 * Returns `null` when the snapshot can't be produced (offline, error or timeout);
 * the card then falls back to its plain gradient panel.
 */
internal suspend fun snapshotRouteMap(
    context: Context,
    ride: RecordedRide
): RideMapLayer? {
    val points = ride.points
    if (points.size < 2) return null

    val lats = points.map { it.latitude }
    val lons = points.map { it.longitude }
    // A small epsilon avoids a degenerate (zero-area) bounds for very short rides,
    // which LatLngBounds.from rejects.
    val eps = 1e-4
    val bounds = LatLngBounds.from(
        lats.max() + eps,
        lons.max() + eps,
        lats.min() - eps,
        lons.min() - eps
    )

    return withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                MapLibre.getInstance(context)
                val options = MapSnapshotter.Options(
                    RIDE_SHARE_PANEL_WIDTH,
                    RIDE_SHARE_PANEL_HEIGHT
                )
                    .withStyleBuilder(Style.Builder().fromUri(MAP_STYLE_URL_LIGHT))
                    .withRegion(bounds)
                    .withPadding(EDGE_PADDING, EDGE_PADDING, EDGE_PADDING, EDGE_PADDING)
                    .withPixelRatio(1f)
                    .withLogo(false)
                    .withAttribution(false)

                val snapshotter = MapSnapshotter(context, options)
                cont.invokeOnCancellation { runCatching { snapshotter.cancel() } }

                snapshotter.start(
                    { snapshot ->
                        if (!cont.isActive) return@start
                        val bitmap = snapshot.bitmap
                        val projected = points.map {
                            snapshot.pixelForLatLng(LatLng(it.latitude, it.longitude))
                        }
                        cont.resume(RideMapLayer(bitmap, projected))
                    },
                    { _ -> if (cont.isActive) cont.resume(null) }
                )
            }
        }
    }
}

private const val SNAPSHOT_TIMEOUT_MS = 8_000L
private const val EDGE_PADDING = 56



