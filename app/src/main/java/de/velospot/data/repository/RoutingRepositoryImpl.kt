package de.velospot.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.velospot.core.routing.OfflineRoutingPreferences
import de.velospot.data.brouter.BRouterEngine
import de.velospot.data.brouter.BRouterSegmentManager
import de.velospot.data.remote.api.OsrmApi
import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.EmptyRouteGeometryException
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.NoRouteFoundException
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.model.RoutingFailedException
import de.velospot.domain.model.RoutingSource
import de.velospot.domain.repository.RoutingRepository
import javax.inject.Inject

/**
 * Routes bicycle trips.
 *
 * - **Offline routing enabled + segments present** → BRouter on-device.
 * - **Offline routing enabled + segments missing + on-demand on** → download the
 *   tile(s) for this route, then BRouter. OSRM is used only if the download fails.
 * - **Offline routing enabled + segments missing + on-demand off** → OSRM online.
 * - **Offline routing disabled** → OSRM online API.
 */
class RoutingRepositoryImpl @Inject constructor(
    private val brouterEngine: BRouterEngine,
    private val segmentManager: BRouterSegmentManager,
    private val osrmApi: OsrmApi,
    @ApplicationContext private val context: Context,
) : RoutingRepository {

    override suspend fun getBikeRoute(from: GeoCoordinate, to: GeoCoordinate): BikeRoute {
        val offlineEnabled = OfflineRoutingPreferences.isOfflineRoutingEnabled(context)
        val profile = OfflineRoutingPreferences.getSelectedProfile(context)

        if (!offlineEnabled) {
            return osrmFallbackRoute(osrmApi, from, to)
        }

        val segmentsReady = segmentManager.hasAllSegments(
            fromLat = from.latitude, fromLon = from.longitude,
            toLat   = to.latitude,   toLon   = to.longitude
        )
        if (segmentsReady) {
            return brouterEngine.calculateRoute(from, to, profile)
        }

        // Segments for this route are missing. With on-demand enabled, fetch the
        // tile(s) covering this route and then route offline. If the download
        // can't happen (e.g. no connectivity), degrade gracefully to OSRM rather
        // than blocking the user.
        val onDemand = OfflineRoutingPreferences.isOnDemandDownloadEnabled(context)
        if (onDemand) {
            val downloaded = runCatching {
                segmentManager.ensureSegments(
                    fromLat = from.latitude, fromLon = from.longitude,
                    toLat   = to.latitude,   toLon   = to.longitude
                )
            }.isSuccess
            if (downloaded && segmentManager.hasAllSegments(
                    fromLat = from.latitude, fromLon = from.longitude,
                    toLat   = to.latitude,   toLon   = to.longitude
                )
            ) {
                return brouterEngine.calculateRoute(from, to, profile)
            }
        }

        // On-demand disabled, or the download failed – fall back gracefully.
        return osrmFallbackRoute(osrmApi, from, to)
    }
}

// ── OSRM online fallback ──────────────────────────────────────────────────────

/** Realistic average cycling speed used to recalculate OSRM duration (15 km/h). */
private const val OSRM_CYCLING_SPEED_MS = 15.0 / 3.6

/**
 * Relative OSRM bicycle-routing path. Resolved by Retrofit against the OSRM base
 * URL configured once in `NetworkModule`, so the host is defined in a single place
 * (no constant drift).
 */
private const val OSRM_BICYCLE_PATH = "route/v1/bicycle/"

internal suspend fun osrmFallbackRoute(
    osrmApi: OsrmApi,
    from: GeoCoordinate,
    to: GeoCoordinate
): BikeRoute {
    val url = buildString {
        append(OSRM_BICYCLE_PATH)
        append(from.longitude); append(','); append(from.latitude)
        append(';')
        append(to.longitude); append(','); append(to.latitude)
        append("?overview=full&geometries=geojson&alternatives=false&steps=false")
    }
    val response = osrmApi.getBikeRoute(url)
    if (!response.isSuccessful) throw RoutingFailedException(response.code().toString())
    val body = response.body() ?: throw NoRouteFoundException()
    if (body.code != "Ok") throw RoutingFailedException(body.code)
    val bestRoute = body.routes.firstOrNull() ?: throw NoRouteFoundException()
    val points = bestRoute.geometry.coordinates.mapNotNull { coordinate ->
        if (coordinate.size < 2) null
        else RoutePoint(latitude = coordinate[1], longitude = coordinate[0])
    }
    if (points.isEmpty()) throw EmptyRouteGeometryException()
    // OSRM's bicycle duration can be calibrated for road speeds rather than
    // real cycling pace. Recalculate from distance at 15 km/h average.
    return BikeRoute(
        points          = points,
        distanceMeters  = bestRoute.distance,
        durationSeconds = bestRoute.distance / OSRM_CYCLING_SPEED_MS,
        source          = RoutingSource.OSRM_ONLINE
    )
}
