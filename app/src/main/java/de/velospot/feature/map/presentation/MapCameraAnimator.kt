package de.velospot.feature.map.presentation

import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

internal fun hasZoomChange(startZoom: Double, targetZoom: Double): Boolean {
    return abs(targetZoom - startZoom) > 0.01
}

internal fun calculateAdjustedCenter(
    startZoom: Double,
    targetZoom: Double,
    baseCenter: GeoPoint,
    verticalOffsetFraction: Double,
    currentLatitudeSpan: Double,
    startLatitudeSpan: Double
): GeoPoint {
    if (verticalOffsetFraction <= 0.0) return baseCenter

    val hasZoomDelta = hasZoomChange(startZoom = startZoom, targetZoom = targetZoom)
        val latitudeSpan = if (!hasZoomDelta) {
            currentLatitudeSpan
        } else if (startLatitudeSpan > 0.0) {
            val zoomDelta = targetZoom - startZoom
            startLatitudeSpan / 2.0.pow(zoomDelta)
        } else {
        0.0
    }

    return GeoPoint(
        baseCenter.latitude - (latitudeSpan * verticalOffsetFraction),
        baseCenter.longitude
    )
}

internal suspend fun animateMapCameraToTarget(
    mapView: MapView,
    cameraTarget: MapCameraTarget
) {
    val startZoom = mapView.zoomLevelDouble
    val targetZoom = cameraTarget.zoom
    val baseCenter = GeoPoint(cameraTarget.latitude, cameraTarget.longitude)
    val hasZoomChange = hasZoomChange(startZoom = startZoom, targetZoom = targetZoom)
    val startCenter = mapView.mapCenter
    val startLat = startCenter.latitude
    val startLon = startCenter.longitude
    val startLatitudeSpan = mapView.boundingBox?.latitudeSpan ?: 0.0

    if (!hasZoomChange) {
        val latitudeSpan = mapView.boundingBox?.latitudeSpan ?: 0.0
        val adjustedCenter = calculateAdjustedCenter(
            startZoom = startZoom,
            targetZoom = targetZoom,
            baseCenter = baseCenter,
            verticalOffsetFraction = cameraTarget.verticalOffsetFraction,
            currentLatitudeSpan = latitudeSpan,
            startLatitudeSpan = startLatitudeSpan
        )
        // Fast smooth shift (~120 ms) when zoom already matches target.
        val steps = 8
        repeat(steps) { index ->
            val t = (index + 1).toDouble() / steps
            val eased = t * t * (3 - 2 * t)
            mapView.controller.setCenter(
                GeoPoint(
                    startLat + (adjustedCenter.latitude - startLat) * eased,
                    startLon + (adjustedCenter.longitude - startLon) * eased
                )
            )
            delay(15.milliseconds)
        }
        mapView.controller.setCenter(adjustedCenter)
        return
    }

    val adjustedCenter = calculateAdjustedCenter(
        startZoom = startZoom,
        targetZoom = targetZoom,
        baseCenter = baseCenter,
        verticalOffsetFraction = cameraTarget.verticalOffsetFraction,
        currentLatitudeSpan = mapView.boundingBox?.latitudeSpan ?: 0.0,
        startLatitudeSpan = startLatitudeSpan
    )

    // Single fast smooth animation: zoom + center together (~180 ms).
    val steps = 12
    repeat(steps) { index ->
        val t = (index + 1).toDouble() / steps
        val eased = t * t * (3 - 2 * t)
        mapView.controller.setZoom(startZoom + (targetZoom - startZoom) * eased)
        mapView.controller.setCenter(
            GeoPoint(
                startLat + (adjustedCenter.latitude - startLat) * eased,
                startLon + (adjustedCenter.longitude - startLon) * eased
            )
        )
        delay(15.milliseconds)
    }

    mapView.controller.setZoom(targetZoom)
    mapView.controller.setCenter(adjustedCenter)
}

