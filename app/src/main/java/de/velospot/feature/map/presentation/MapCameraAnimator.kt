package de.velospot.feature.map.presentation

import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.abs
import kotlin.math.pow

private const val CAMERA_ANIMATION_DURATION_MS = 600
private const val CAMERA_PAN_ONLY_DURATION_MS  = 350

internal fun hasZoomChange(startZoom: Double, targetZoom: Double): Boolean =
    abs(targetZoom - startZoom) > 0.01

/**
 * Calculates a vertically offset camera center so the selected marker is
 * not hidden behind a bottom sheet.
 *
 * Kept as a standalone testable function (mirrors the previous osmdroid helper).
 */
internal fun calculateAdjustedCenter(
    startZoom: Double,
    targetZoom: Double,
    baseCenter: LatLng,
    verticalOffsetFraction: Double,
    currentLatitudeSpan: Double,
    startLatitudeSpan: Double
): LatLng {
    if (verticalOffsetFraction <= 0.0) return baseCenter

    val latitudeSpan = if (!hasZoomChange(startZoom, targetZoom)) {
        currentLatitudeSpan
    } else if (startLatitudeSpan > 0.0) {
        val zoomDelta = targetZoom - startZoom
        startLatitudeSpan / 2.0.pow(zoomDelta)
    } else {
        0.0
    }

    return LatLng(
        baseCenter.latitude - latitudeSpan * verticalOffsetFraction,
        baseCenter.longitude
    )
}

/**
 * Animates the MapLibre camera to [cameraTarget].
 *
 * MapLibre's [MapLibreMap.animateCamera] handles easing and zoom+pan
 * simultaneously – no manual coroutine stepping needed.
 */
internal fun animateMapCameraToTarget(
    map: MapLibreMap,
    cameraTarget: MapCameraTarget
) {
    val currentZoom       = map.cameraPosition.zoom
    val startLatitudeSpan = map.projection.visibleRegion.latLngBounds.latitudeSpan
    val baseLatLng        = LatLng(cameraTarget.latitude, cameraTarget.longitude)

    val adjustedLatLng = calculateAdjustedCenter(
        startZoom              = currentZoom,
        targetZoom             = cameraTarget.zoom,
        baseCenter             = baseLatLng,
        verticalOffsetFraction = cameraTarget.verticalOffsetFraction,
        currentLatitudeSpan    = startLatitudeSpan,
        startLatitudeSpan      = startLatitudeSpan
    )

    val cameraPosition = CameraPosition.Builder()
        .target(adjustedLatLng)
        .zoom(cameraTarget.zoom)
        // Preserve the current tilt/bearing so one-shot moves (selecting a spot,
        // centering, search results) keep the active 2D/3D perspective instead of
        // snapping the map back to flat north-up.
        .tilt(map.cameraPosition.tilt)
        .bearing(map.cameraPosition.bearing)
        .build()

    val durationMs = if (hasZoomChange(currentZoom, cameraTarget.zoom))
        CAMERA_ANIMATION_DURATION_MS
    else
        CAMERA_PAN_ONLY_DURATION_MS

    map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), durationMs)
}

