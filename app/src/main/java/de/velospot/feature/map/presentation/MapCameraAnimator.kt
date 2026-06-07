package de.velospot.feature.map.presentation

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

internal suspend fun animateMapCameraToTarget(
    mapView: MapView,
    cameraTarget: MapCameraTarget
) {
    val startZoom = mapView.zoomLevelDouble
    val targetZoom = cameraTarget.zoom
    val baseCenter = GeoPoint(cameraTarget.latitude, cameraTarget.longitude)
    val hasZoomChange = kotlin.math.abs(targetZoom - startZoom) > 0.01
    val startCenter = mapView.mapCenter
    val startLat = startCenter.latitude
    val startLon = startCenter.longitude
    val startLatitudeSpan = mapView.boundingBox?.latitudeSpan ?: 0.0

    if (!hasZoomChange) {
        val latitudeSpan = mapView.boundingBox?.latitudeSpan ?: 0.0
        val adjustedCenter = if (cameraTarget.verticalOffsetFraction > 0.0) {
            GeoPoint(
                baseCenter.latitude - (latitudeSpan * cameraTarget.verticalOffsetFraction),
                baseCenter.longitude
            )
        } else {
            baseCenter
        }
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

    // Estimate final latitude span for target zoom to compute the final vertical offset first.
    val zoomDelta = targetZoom - startZoom
    val targetLatitudeSpan = if (startLatitudeSpan > 0.0) {
        startLatitudeSpan / Math.pow(2.0, zoomDelta)
    } else {
        0.0
    }
    val adjustedCenter = if (cameraTarget.verticalOffsetFraction > 0.0) {
        GeoPoint(
            baseCenter.latitude - (targetLatitudeSpan * cameraTarget.verticalOffsetFraction),
            baseCenter.longitude
        )
    } else {
        baseCenter
    }

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

