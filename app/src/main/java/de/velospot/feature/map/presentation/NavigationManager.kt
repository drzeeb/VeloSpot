package de.velospot.feature.map.presentation

import android.content.Context
import android.view.Choreographer
import de.velospot.core.navigation.GeoMath
import de.velospot.core.navigation.NavigationCamera
import de.velospot.core.navigation.NavigationProgress
import de.velospot.core.navigation.RouteMatcher
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.RoutePoint
import de.velospot.feature.map.presentation.markers.IMG_LOCATION_NAV
import de.velospot.feature.map.presentation.markers.PROP_BEARING
import de.velospot.feature.map.presentation.markers.PROP_ICON
import de.velospot.feature.map.presentation.markers.SOURCE_LOCATION
import de.velospot.feature.map.presentation.markers.SOURCE_ROUTE
import de.velospot.feature.map.presentation.markers.SOURCE_ROUTE_TRAVELED
import de.velospot.feature.map.presentation.markers.createNavigationArrowIcon
import de.velospot.feature.map.presentation.markers.drawableToBitmap
import de.velospot.feature.map.presentation.markers.ensureBuildingExtrusionLayer
import de.velospot.feature.map.presentation.markers.ensureLocationLayer
import de.velospot.feature.map.presentation.markers.ensureRouteLayer
import de.velospot.feature.map.presentation.markers.ensureTraveledRouteLayer
import de.velospot.feature.map.presentation.markers.setBuildingExtrusionVisible
import de.velospot.feature.map.presentation.markers.upsertSource
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Drives the live, mit­laufende 3D navigation experience on top of MapLibre.
 *
 * Responsibilities (single, self-contained controller — the "NavigationManager"):
 *  1. **Map matching** — snaps each raw GPS fix onto the active BRouter polyline
 *     ([RouteMatcher]) so the heading arrow rides the road instead of jittering
 *     beside it.
 *  2. **Smooth interpolation** — raw GPS arrives at ~3 s intervals; a
 *     [Choreographer] frame loop eases the puck position, the camera bearing,
 *     the zoom and the tilt towards their targets every frame (60–120 Hz) using
 *     frame-rate-independent exponential smoothing. All work happens on the main
 *     thread but is tiny per frame, so the animation stays ruckelfrei.
 *  3. **3D camera** — fixed 60° pitch, speed/turn-dependent zoom and a bearing
 *     that follows the direction of travel.
 *  4. **Heading arrow** — owns the [SOURCE_LOCATION] puck during navigation and
 *     rotates the [IMG_LOCATION_NAV] arrow by the live bearing.
 *  5. **3D buildings** — toggles the `fill-extrusion` building layer on/off.
 *  6. **Route progress** — on every fix, reports the remaining distance + ETA via
 *     [onProgress] and greys out the already-travelled part of the polyline.
 *  7. **Off-route detection** — fires [onOffRoute] (debounced) once the rider
 *     strays beyond [OFF_ROUTE_THRESHOLD_M], so the caller can trigger a reroute.
 *
 * Lifecycle: [attach] once the [MapLibreMap] + [Style] are ready, [start] when
 * navigation becomes active, feed fixes via [onLocationUpdate], and [stop] when
 * navigation ends. Idempotent and null-safe so it survives style reloads
 * (dark-mode toggles) — just call [attach] again after the new style loads.
 */
class NavigationManager(private val context: Context) {

    private companion object {
        /** Duration of the smooth camera transition when the perspective changes. */
        const val CAMERA_RESET_DURATION_MS = 700

        /**
         * Tilt applied to the idle (non-navigating) map when 3D is enabled. A bit
         * gentler than the active-navigation [NavigationCamera.PITCH_DEGREES] so
         * browsing the map stays comfortable while still clearly 3D.
         */
        const val IDLE_PITCH_3D = 45.0

        /** Perpendicular distance from the route (m) above which the rider counts as off-route. */
        const val OFF_ROUTE_THRESHOLD_M = 30.0

        /** Consecutive off-route fixes required before [onOffRoute] fires (debounce GPS noise). */
        const val OFF_ROUTE_CONSECUTIVE = 3

        /** Fallback average bike speed (m/s ≈ 16 km/h) used for the ETA when the route carries none. */
        const val DEFAULT_BIKE_SPEED_MPS = 4.5

        /**
         * Minimum distance (m) the snapped point must travel within the same route
         * segment before the travelled/remaining polyline split is re-rendered.
         * Below this the (potentially O(route)) rebuild is skipped, since moving the
         * single split boundary a few metres along the line is visually imperceptible
         * — the smoothly-eased puck (redrawn every frame) carries the motion instead.
         */
        const val RENDER_MIN_SNAP_MOVE_M = 12.0
    }

    private var map: MapLibreMap? = null

    private var route: List<RoutePoint> = emptyList()
    private var lastSegment = 0

    /** Colour of the "remaining" route line; supplied by [start] (theme-dependent). */
    private var routeColor: Int = 0xFF1976D2.toInt()

    /** Total route distance / duration from BRouter, used to derive the ETA. */
    private var totalDistanceMeters = 0.0
    private var totalDurationSeconds = 0.0

    /** Snapped point of the most recent match — basis for the travelled/remaining split. */
    private var lastSnapLat = 0.0
    private var lastSnapLon = 0.0

    /**
     * The segment index and snapped position the route split was last *rendered* at.
     * Used by [renderRouteSplit] to skip redundant rebuilds while the rider creeps
     * along the same segment (see [RENDER_MIN_SNAP_MOVE_M]). `-1` forces the next
     * render (set on [start]).
     */
    private var renderedSegment = -1
    private var renderedSnapLat = 0.0
    private var renderedSnapLon = 0.0

    /** Consecutive off-route fixes seen so far, and whether [onOffRoute] already fired. */
    private var consecutiveOffRoute = 0
    private var offRouteFired = false

    /** True between [start] and [stop]. */
    private var active = false

    /**
     * Whether the **idle** (non-navigating) map uses the tilted 3D perspective
     * (45° pitch + extruded buildings) or a flat 2D view. Active navigation is
     * **always** 3D regardless of this flag — it only governs the resting map and
     * what we return to once navigation ends.
     */
    private var is3D = true

    /** Whether the eased ("current") state has been seeded from the first fix after [start]. */
    private var initialized = false

    // ── Eased ("current") camera/puck state ───────────────────────────────────
    private var curLat = 0.0
    private var curLon = 0.0
    private var curBearing = 0.0
    private var curZoom = NavigationCamera.ZOOM_SLOW
    private var curPitch = 0.0

    // ── Targets the eased state chases ────────────────────────────────────────
    private var tgtLat = 0.0
    private var tgtLon = 0.0
    private var tgtBearing = 0.0
    private var tgtZoom = NavigationCamera.ZOOM_SLOW

    /** Reports live route progress (remaining distance + ETA) on every GPS fix. */
    var onProgress: ((NavigationProgress) -> Unit)? = null

    /**
     * Fires once (debounced) when the rider has strayed off-route, signalling the
     * caller to recompute a fresh BRouter route. Re-arms automatically once the
     * rider is back on the route or a new route is set via [start].
     */
    var onOffRoute: (() -> Unit)? = null

    private var lastFrameNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!active) return
            stepFrame(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Binds the manager to the (freshly loaded) [map] + [style]. Registers the
     * rotating arrow image, the location layer and the 3D building layer. Safe to
     * call repeatedly (e.g. after a dark-mode style reload).
     */
    fun attach(map: MapLibreMap, style: Style) {
        this.map = map
        if (style.getImage(IMG_LOCATION_NAV) == null) {
            style.addImage(IMG_LOCATION_NAV, drawableToBitmap(createNavigationArrowIcon(context)))
        }
        ensureLocationLayer(style)
        ensureBuildingExtrusionLayer(style)
        if (active) {
            // Navigation is always 3D: re-assert buildings after a style reload.
            setBuildingExtrusionVisible(style, true)
            // The reloaded style has empty sources — re-render unconditionally.
            renderRouteSplit(force = true)
            if (initialized) writePuck()
        } else {
            // Reflect the saved 2D/3D preference on the idle map (also after a
            // dark-mode style reload, which wipes the extrusion layer).
            applyIdleView()
        }
    }

    /**
     * Sets the perspective preference for the **idle** map (2D flat vs 3D tilted)
     * and persists the choice via the caller. Active navigation is unaffected and
     * stays 3D; the change only takes visible effect on the resting map and on the
     * view we return to when navigation ends.
     */
    fun setMode(is3D: Boolean) {
        this.is3D = is3D
        // Only the idle map reacts; during navigation we keep the 3D view intact.
        if (!active) applyIdleView()
    }

    /**
     * Begins navigation along [routePoints]. Pass the BRouter route's
     * [totalDistanceMeters] / [totalDurationSeconds] (for the ETA) and the themed
     * [routeColor] for the remaining-route line. Camera tilts in on the first fix.
     */
    fun start(
        routePoints: List<RoutePoint>,
        totalDistanceMeters: Double,
        totalDurationSeconds: Double,
        routeColor: Int
    ) {
        route = routePoints
        this.totalDistanceMeters = totalDistanceMeters
        this.totalDurationSeconds = totalDurationSeconds
        this.routeColor = routeColor
        lastSegment = 0
        consecutiveOffRoute = 0
        offRouteFired = false
        active = true
        initialized = false
        // Force the first split render for this route (invalidate the diff tracker).
        renderedSegment = -1
        // Seed the split geometry at the route start (nothing travelled yet).
        lastSnapLat = routePoints.firstOrNull()?.latitude ?: 0.0
        lastSnapLon = routePoints.firstOrNull()?.longitude ?: 0.0
        // Navigation is always 3D.
        map?.style?.let { setBuildingExtrusionVisible(it, true) }
        renderRouteSplit(force = true)
        lastFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /** Feeds a raw GPS fix; snaps to the route and updates the camera/puck targets. */
    fun onLocationUpdate(location: GeoCoordinate) {
        if (!active || route.size < 2) return

        val match = RouteMatcher.match(route, location.latitude, location.longitude, lastSegment)
            ?: return
        lastSegment = match.segmentIndex
        lastSnapLat = match.latitude
        lastSnapLon = match.longitude

        val offRoute = match.distanceFromRouteMeters > OFF_ROUTE_THRESHOLD_M

        // While on-route, follow the snapped point (rides the road). While clearly
        // off-route, follow the *raw* GPS so the puck shows the true position until
        // a new route arrives.
        if (offRoute) {
            tgtLat = location.latitude
            tgtLon = location.longitude
            tgtBearing = location.bearing?.toDouble() ?: tgtBearing
            tgtZoom = NavigationCamera.targetZoom(location.speedMetersPerSecond, 0.0)
        } else {
            tgtLat = match.latitude
            tgtLon = match.longitude
            // Prefer the GPS heading while actually moving (more accurate around the
            // bike's true facing); fall back to the route segment heading at low
            // speed or when the fix carries no bearing.
            val moving = (location.speedMetersPerSecond ?: 0f) > 1.5f
            tgtBearing = if (moving && location.bearing != null) {
                location.bearing.toDouble()
            } else {
                match.bearing
            }
            tgtZoom = NavigationCamera.targetZoom(location.speedMetersPerSecond, match.turnSharpnessDegrees)
        }

        if (!initialized) seedCurrentState()

        // ── Route progress + ETA ──────────────────────────────────────────────
        renderRouteSplit()
        onProgress?.invoke(
            NavigationProgress(
                remainingMeters = match.remainingMeters,
                remainingSeconds = estimateRemainingSeconds(match.remainingMeters),
                distanceFromRouteMeters = match.distanceFromRouteMeters,
                isOffRoute = offRoute
            )
        )

        // ── Off-route detection (debounced, self re-arming) ───────────────────
        if (offRoute) {
            consecutiveOffRoute++
            if (consecutiveOffRoute >= OFF_ROUTE_CONSECUTIVE && !offRouteFired) {
                offRouteFired = true
                onOffRoute?.invoke()
            }
        } else {
            consecutiveOffRoute = 0
            offRouteFired = false
        }
    }

    /** ETA in seconds = remaining distance ÷ the route's average speed (BRouter-consistent). */
    private fun estimateRemainingSeconds(remainingMeters: Double): Double {
        val avgSpeed = if (totalDurationSeconds > 0.0 && totalDistanceMeters > 0.0) {
            totalDistanceMeters / totalDurationSeconds
        } else {
            DEFAULT_BIKE_SPEED_MPS
        }
        return remainingMeters / avgSpeed.coerceAtLeast(0.5)
    }

    /** Ends navigation: stops the frame loop, clears the puck and returns the
     *  idle map to the saved 2D/3D perspective. */
    fun stop() {
        active = false
        initialized = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        map?.style?.let { style ->
            // Clear the puck source so the normal location dot (owned by the
            // marker renderer) can take over again.
            (style.getSource(SOURCE_LOCATION) as? GeoJsonSource)
                ?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            // Clear the travelled-route overlay (the marker renderer will redraw
            // SOURCE_ROUTE — empty, since navigation ended).
            (style.getSource(SOURCE_ROUTE_TRAVELED) as? GeoJsonSource)
                ?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        }
        // Return to the preferred idle view: stay tilted in 3D, flatten in 2D.
        applyIdleView()
    }

    /**
     * Splits the route at the current snapped position into a greyed-out
     * "travelled" line ([SOURCE_ROUTE_TRAVELED]) and the coloured "remaining" line
     * ([SOURCE_ROUTE]), so the part behind the rider visually falls back. Cheap —
     * only runs on new GPS fixes, not every frame.
     *
     * Skips the rebuild entirely while the rider is still on the **same segment**
     * and the snapped point has moved less than [RENDER_MIN_SNAP_MOVE_M]: in that
     * case only the single split-boundary point would shift a few metres, which is
     * imperceptible, so the (potentially O(route)) polyline reconstruction and
     * GeoJSON re-upload are avoided. Pass [force] = `true` to render unconditionally
     * (route start, style reload).
     */
    private fun renderRouteSplit(force: Boolean = false) {
        val style = map?.style ?: return
        if (route.size < 2) return

        val seg = lastSegment.coerceIn(0, route.size - 2)

        // Diff: nothing structural changed (same segment) and the boundary barely
        // moved → skip the rebuild. The eased puck still carries the live motion.
        if (!force &&
            seg == renderedSegment &&
            GeoMath.distanceMeters(lastSnapLat, lastSnapLon, renderedSnapLat, renderedSnapLon) < RENDER_MIN_SNAP_MOVE_M
        ) {
            return
        }

        val snap = Point.fromLngLat(lastSnapLon, lastSnapLat)

        val traveled = ArrayList<Point>(seg + 2)
        for (i in 0..seg) traveled.add(Point.fromLngLat(route[i].longitude, route[i].latitude))
        traveled.add(snap)

        val remaining = ArrayList<Point>(route.size - seg + 1)
        remaining.add(snap)
        for (i in seg + 1 until route.size) remaining.add(Point.fromLngLat(route[i].longitude, route[i].latitude))

        upsertSource(style, SOURCE_ROUTE_TRAVELED, lineFeatureCollection(traveled))
        upsertSource(style, SOURCE_ROUTE, lineFeatureCollection(remaining))
        // Coloured line first (so it can be the anchor), then the grey line below it.
        ensureRouteLayer(style, routeColor)
        ensureTraveledRouteLayer(style)

        // Remember what we just rendered so the next fix can be diffed against it.
        renderedSegment = seg
        renderedSnapLat = lastSnapLat
        renderedSnapLon = lastSnapLon
    }

    /** Builds a single-LineString [FeatureCollection], or an empty one for < 2 points. */
    private fun lineFeatureCollection(points: List<Point>): FeatureCollection =
        if (points.size > 1) {
            FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points)))
        } else {
            FeatureCollection.fromFeatures(emptyList())
        }

    /**
     * Applies the saved 2D/3D preference to the idle (non-navigating) map: toggles
     * the 3D buildings and animates the camera to the matching tilt (north-up).
     * No-op while navigating (the frame loop owns the camera then).
     */
    private fun applyIdleView() {
        if (active) return
        val map = map ?: return
        map.style?.let { setBuildingExtrusionVisible(it, is3D) }
        val pos = map.cameraPosition
        val targetTilt = if (is3D) IDLE_PITCH_3D else 0.0
        // Skip the animation when nothing visibly changes (avoids fighting other
        // one-shot camera moves at startup).
        if (kotlin.math.abs(pos.tilt - targetTilt) < 0.5 && kotlin.math.abs(pos.bearing) < 0.5) return
        val reset = CameraPosition.Builder()
            .target(pos.target)
            .zoom(pos.zoom)
            .tilt(targetTilt)
            .bearing(0.0)
            .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(reset), CAMERA_RESET_DURATION_MS)
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /** Seeds the eased state so the very first frame tilts in from the current view. */
    private fun seedCurrentState() {
        val cam = map?.cameraPosition
        curLat = tgtLat
        curLon = tgtLon
        curBearing = tgtBearing
        // Start from the live zoom/tilt so the transition into 3D is smooth, not a jump.
        curZoom = cam?.zoom ?: tgtZoom
        curPitch = cam?.tilt ?: 0.0
        initialized = true
    }

    private fun stepFrame(frameTimeNanos: Long) {
        if (!initialized) {
            lastFrameNanos = frameTimeNanos
            return
        }
        val dt = if (lastFrameNanos == 0L) 0.016
                 else (frameTimeNanos - lastFrameNanos) / 1_000_000_000.0
        lastFrameNanos = frameTimeNanos

        val aPos = NavigationCamera.smoothingAlpha(dt, NavigationCamera.TAU_POSITION_S)
        val aBear = NavigationCamera.smoothingAlpha(dt, NavigationCamera.TAU_BEARING_S)
        val aZoom = NavigationCamera.smoothingAlpha(dt, NavigationCamera.TAU_ZOOM_S)
        val aPitch = NavigationCamera.smoothingAlpha(dt, NavigationCamera.TAU_PITCH_S)

        curLat = GeoMath.lerp(curLat, tgtLat, aPos)
        curLon = GeoMath.lerp(curLon, tgtLon, aPos)
        curBearing = GeoMath.lerpAngle(curBearing, tgtBearing, aBear)
        curZoom = GeoMath.lerp(curZoom, tgtZoom, aZoom)
        // Active navigation is always 3D — fixed full pitch regardless of the
        // idle 2D/3D preference.
        curPitch = GeoMath.lerp(curPitch, NavigationCamera.PITCH_DEGREES, aPitch)

        applyCamera()
        writePuck()
    }

    private fun applyCamera() {
        val map = map ?: return
        val position = CameraPosition.Builder()
            .target(LatLng(curLat, curLon))
            .zoom(curZoom)
            .bearing(curBearing)
            .tilt(curPitch)
            .build()
        // moveCamera (not animateCamera): we run our own per-frame easing, so the
        // map must jump exactly to the interpolated value without double-easing.
        map.moveCamera(CameraUpdateFactory.newCameraPosition(position))
    }

    private fun writePuck() {
        val style = map?.style ?: return
        val source = style.getSource(SOURCE_LOCATION) as? GeoJsonSource ?: return
        val feature = Feature.fromGeometry(Point.fromLngLat(curLon, curLat)).apply {
            addStringProperty(PROP_ICON, IMG_LOCATION_NAV)
            addNumberProperty(PROP_BEARING, curBearing)
        }
        source.setGeoJson(FeatureCollection.fromFeature(feature))
    }
}

