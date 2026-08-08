package de.velospot.feature.map.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.VisibleRegion
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Projection
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class MapCameraAnimatorTest {

    @Test
    fun `hasZoomChange returns false for tiny delta`() {
        assertFalse(hasZoomChange(startZoom = 15.0, targetZoom = 15.005))
    }

    @Test
    fun `hasZoomChange returns true for visible delta`() {
        assertTrue(hasZoomChange(startZoom = 15.0, targetZoom = 15.05))
    }

    @Test
    fun `calculateAdjustedCenter uses current span when zoom stays equal`() {
        val adjusted = calculateAdjustedCenter(
            startZoom = 15.0,
            targetZoom = 15.0,
            baseCenter = LatLng(49.75, 6.64),
            verticalOffsetFraction = 1.0 / 6.0,
            currentLatitudeSpan = 0.12,
            startLatitudeSpan = 0.8
        )

        // 49.75 - (0.12 * 1/6) = 49.73
        assertEquals(49.73, adjusted.latitude, 1e-9)
        assertEquals(6.64, adjusted.longitude, 1e-9)
    }

    @Test
    fun `calculateAdjustedCenter uses projected target span when zoom changes`() {
        val adjusted = calculateAdjustedCenter(
            startZoom = 14.0,
            targetZoom = 16.0,
            baseCenter = LatLng(49.75, 6.64),
            verticalOffsetFraction = 0.25,
            currentLatitudeSpan = 0.9,
            startLatitudeSpan = 0.8
        )

        // targetSpan = 0.8 / 2^(16-14) = 0.2; offset = 0.05
        assertEquals(49.70, adjusted.latitude, 1e-9)
        assertEquals(6.64, adjusted.longitude, 1e-9)
    }

    @Test
    fun `calculateAdjustedCenter returns base center when offset is zero`() {
        val base = LatLng(49.75, 6.64)
        val adjusted = calculateAdjustedCenter(
            startZoom = 14.0,
            targetZoom = 18.0,
            baseCenter = base,
            verticalOffsetFraction = 0.0,
            currentLatitudeSpan = 0.9,
            startLatitudeSpan = 0.8
        )

        assertEquals(base.latitude, adjusted.latitude, 1e-9)
        assertEquals(base.longitude, adjusted.longitude, 1e-9)
    }

    private fun mapWith(zoom: Double, span: Double): MapLibreMap {
        // Real geometry objects (LatLngBounds.latitudeSpan is a final getter that
        // Mockito can't stub), so only the map + projection are mocked.
        val north = 49.75 + span / 2.0
        val south = 49.75 - span / 2.0
        val bounds = LatLngBounds.from(north, 6.70, south, 6.60)
        val region = VisibleRegion(
            LatLng(north, 6.60), LatLng(north, 6.70),
            LatLng(south, 6.60), LatLng(south, 6.70),
            bounds
        )
        val projection = mock<Projection> { on { visibleRegion } doReturn region }
        val position = CameraPosition.Builder()
            .target(LatLng(49.75, 6.64))
            .zoom(zoom)
            .tilt(30.0)
            .bearing(90.0)
            .build()
        return mock {
            on { cameraPosition } doReturn position
            on { getProjection() } doReturn projection
        }
    }

    @Test
    fun `animateMapCameraToTarget animates with the longer duration on a zoom change`() {
        val map = mapWith(zoom = 14.0, span = 0.4)
        animateMapCameraToTarget(
            map,
            MapCameraTarget(latitude = 49.80, longitude = 6.70, zoom = 16.0, verticalOffsetFraction = 1.0 / 6.0)
        )
        // 600 ms easing when the zoom level changes.
        verify(map).animateCamera(any(), org.mockito.kotlin.eq(600))
    }

    @Test
    fun `animateMapCameraToTarget uses the pan-only duration when the zoom is unchanged`() {
        val map = mapWith(zoom = 16.0, span = 0.2)
        animateMapCameraToTarget(
            map,
            MapCameraTarget(latitude = 49.80, longitude = 6.70, zoom = 16.0)
        )
        // 350 ms for a pure pan.
        verify(map).animateCamera(any(), org.mockito.kotlin.eq(350))
    }
}
