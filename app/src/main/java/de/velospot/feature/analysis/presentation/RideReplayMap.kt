package de.velospot.feature.analysis.presentation

import android.annotation.SuppressLint
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import de.velospot.R
import de.velospot.core.analysis.GeoPoint
import de.velospot.core.analysis.RideMapData
import de.velospot.core.analysis.RideMarker
import de.velospot.core.analysis.RideMarkerType
import de.velospot.core.map.RideSpeedSegments
import de.velospot.core.format.formatRideSpeed
import de.velospot.core.navigation.GeoMath
import de.velospot.domain.model.RecordedRide
import de.velospot.feature.map.presentation.mapStyleUrl
import de.velospot.feature.map.presentation.markers.createLocationMarkerIcon
import de.velospot.feature.map.presentation.markers.createSpeedBubbleIcon
import de.velospot.feature.map.presentation.markers.drawableToBitmap
import de.velospot.feature.map.presentation.markers.updateMaxSpeedMarker
import de.velospot.feature.map.presentation.markers.updateTrackLayer
import de.velospot.feature.map.presentation.markers.updateTrackSpeedLayer
import de.velospot.feature.map.presentation.rememberMapViewWithLifecycle
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.roundToInt

// Self-contained source / layer / property ids for the analysis map (its own
// MapView + Style instance, independent of the main map's layers).
private const val SRC_MARKERS = "vs-analysis-markers-source"
private const val LYR_MARKERS = "vs-analysis-markers-layer"
private const val SRC_REPLAY = "vs-analysis-replay-source"
private const val LYR_REPLAY = "vs-analysis-replay-layer"
private const val PROP_COLOR = "markerColor"
private const val PROP_RADIUS = "markerRadius"
private const val PROP_FRAME = "frameImage"
private const val PROP_BEARING = "bearing"

/** Wall-clock duration of one full replay pass, in milliseconds. */
private const val REPLAY_DURATION_MS = 14_000f

/** Number of pedalling frames rendered for the riding cyclist avatar. */
private const val PEDAL_FRAME_COUNT = 8
/** Ground distance (m) over which the legs complete one full pedal cycle. */
private const val PEDAL_CYCLE_METERS = 6.0
/** Below this per-frame movement (m) the rider is treated as standing still. */
private const val MOVING_EPSILON_METERS = 0.3

private fun cyclistFrameImageId(i: Int) = "vs-replay-cyclist-$i"
private fun cyclistIdleImageId() = "vs-replay-cyclist-idle"


/**
 * An embedded, interactive map for the ride analysis screen: the ride's track
 * painted on a green → red **speed ramp**, **start / finish / top-speed / stop /
 * kilometre** markers, and an **animated replay** dot that retraces the ride
 * (time-based, so it pauses where you stopped). Controlled by a play/restart
 * button and a scrub slider.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun RideReplayMap(
    ride: RecordedRide,
    mapData: RideMapData,
    maxSpeedMps: Double,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mapView = rememberMapViewWithLifecycle(enabled = true)
    var progress by remember { mutableFloatStateOf(0f) }
    var isPlaying by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf<Style?>(null) }
    var lastBearing by remember { mutableFloatStateOf(0f) }

    val segments = remember(ride.id) { RideSpeedSegments.build(ride.points) }
    // Cumulative ground distance at each replay frame, so the pedalling cadence
    // tracks distance covered (and freezes when the dot is paused at a stop).
    val frameCumDist = remember(mapData) {
        val f = mapData.replayFrames
        DoubleArray(f.size).also { arr ->
            for (i in 1 until f.size) {
                arr[i] = arr[i - 1] + GeoMath.distanceMeters(
                    f[i - 1].latitude, f[i - 1].longitude, f[i].latitude, f[i].longitude
                )
            }
        }
    }

    Column(modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(16.dp))
        ) {
            if (mapView != null) {
                val mv = mapView
                AndroidView(
                    factory = {
                        // Let the map handle its own pan/zoom even though it lives
                        // inside a vertical scroll: on touch-down ask the Compose
                        // parent to stop intercepting the gesture (otherwise the
                        // page scroll steals every drag and the map feels dead).
                        mv.setOnTouchListener { v, event ->
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN,
                                MotionEvent.ACTION_MOVE ->
                                    v.parent?.requestDisallowInterceptTouchEvent(true)
                                MotionEvent.ACTION_UP,
                                MotionEvent.ACTION_CANCEL ->
                                    v.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                            false // never consume — the map still processes the event
                        }
                        mv
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = {
                if (progress >= 1f) progress = 0f
                isPlaying = !isPlaying
            }) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Refresh else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (isPlaying) R.string.ride_replay_pause else R.string.ride_replay_play
                    )
                )
            }
            Spacer(Modifier.width(8.dp))
            Slider(
                value = progress,
                onValueChange = {
                    isPlaying = false
                    progress = it
                },
                modifier = Modifier.weight(1f)
            )
        }
    }

    // One-off map setup once the style is ready.
    LaunchedEffect(mapView, isDarkTheme) {
        val mv = mapView ?: return@LaunchedEffect
        mv.getMapAsync { map ->
            map.setStyle(mapStyleUrl(isDarkTheme)) { loaded ->
                val speedColoured = segments.any { it.speedMps > 0.0 } && maxSpeedMps > 0.0
                if (speedColoured) {
                    updateTrackSpeedLayer(loaded, segments, maxSpeedMps, visible = true)
                } else {
                    updateTrackLayer(loaded, ride.points.map { it.latitude to it.longitude }, 0x2962FF)
                }
                addRideMarkers(loaded, mapData.markers)
                addTopSpeedBubble(loaded, mapData.markers, maxSpeedMps)
                registerCyclistFrames(loaded, context)
                addReplayCyclist(loaded)
                fitCameraToTrack(map, mapData.track)
                style = loaded
            }
        }
    }

    // Drive the replay progress while playing.
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (isPlaying && progress < 1f) {
            val now = withFrameNanos { it }
            val dtMs = (now - last) / 1_000_000f
            last = now
            progress = (progress + dtMs / REPLAY_DURATION_MS).coerceAtMost(1f)
            if (progress >= 1f) isPlaying = false
        }
    }

    // Move + animate the riding cyclist whenever progress (or the style) changes.
    LaunchedEffect(progress, style) {
        val s = style ?: return@LaunchedEffect
        val frames = mapData.replayFrames
        if (frames.isEmpty()) return@LaunchedEffect
        val idx = (progress * (frames.size - 1)).roundToInt().coerceIn(0, frames.size - 1)
        val cur = frames[idx]
        val prev = frames[(idx - 1).coerceAtLeast(0)]
        val moved = GeoMath.distanceMeters(prev.latitude, prev.longitude, cur.latitude, cur.longitude)

        val image: String
        if (moved > MOVING_EPSILON_METERS) {
            lastBearing = GeoMath.bearingDegrees(
                prev.latitude, prev.longitude, cur.latitude, cur.longitude
            ).toFloat()
            val cycle = (frameCumDist[idx] / PEDAL_CYCLE_METERS) * PEDAL_FRAME_COUNT
            val frameNo = ((cycle.toInt() % PEDAL_FRAME_COUNT) + PEDAL_FRAME_COUNT) % PEDAL_FRAME_COUNT
            image = cyclistFrameImageId(frameNo)
        } else {
            // Standing still (start or a stop) → foot-down idle pose.
            image = cyclistIdleImageId()
        }

        (s.getSource(SRC_REPLAY) as? GeoJsonSource)?.setGeoJson(
            Feature.fromGeometry(Point.fromLngLat(cur.longitude, cur.latitude)).apply {
                addStringProperty(PROP_FRAME, image)
                addNumberProperty(PROP_BEARING, lastBearing.toDouble())
            }
        )
    }
}

private fun markerColor(type: RideMarkerType): String = when (type) {
    RideMarkerType.START -> "#2E7D32"
    RideMarkerType.FINISH -> "#C62828"
    RideMarkerType.TOP_SPEED -> "#F9A825"
    RideMarkerType.STOP -> "#616161"
    RideMarkerType.KILOMETRE -> "#1565C0"
}

private fun markerRadius(type: RideMarkerType): Float = when (type) {
    RideMarkerType.START, RideMarkerType.FINISH -> 9f
    RideMarkerType.TOP_SPEED -> 8f
    RideMarkerType.KILOMETRE -> 7f
    RideMarkerType.STOP -> 6f
}

/** Adds the marker circles (data-driven colour/size) for start, finish & stops. */
private fun addRideMarkers(style: Style, markers: List<RideMarker>) {
    // The top-speed marker is drawn as its own speech bubble (see
    // addTopSpeedBubble); only the plain dots are rendered here.
    val dots = markers.filter { it.type != RideMarkerType.TOP_SPEED }
    val features = dots.map { m ->
        Feature.fromGeometry(Point.fromLngLat(m.point.longitude, m.point.latitude)).apply {
            addStringProperty(PROP_COLOR, markerColor(m.type))
            addNumberProperty(PROP_RADIUS, markerRadius(m.type))
        }
    }
    (style.getSource(SRC_MARKERS) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(features))
        ?: style.addSource(GeoJsonSource(SRC_MARKERS, FeatureCollection.fromFeatures(features)))

    if (style.getLayer(LYR_MARKERS) == null) {
        style.addLayer(
            CircleLayer(LYR_MARKERS, SRC_MARKERS).withProperties(
                PropertyFactory.circleRadius(Expression.get(PROP_RADIUS)),
                PropertyFactory.circleColor(Expression.get(PROP_COLOR)),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2f)
            )
        )
    }
}

/**
 * Draws the **top-speed** marker as a clean red speech bubble carrying the peak
 * speed — the exact same look as the "max speed" pin on the main map (reusing
 * `createSpeedBubbleIcon` + `updateMaxSpeedMarker`).
 */
private fun addTopSpeedBubble(style: Style, markers: List<RideMarker>, maxSpeedMps: Double) {
    val top = markers.firstOrNull { it.type == RideMarkerType.TOP_SPEED } ?: return
    if (maxSpeedMps <= 0.0) return
    val icon = createSpeedBubbleIcon(formatRideSpeed(maxSpeedMps))
    updateMaxSpeedMarker(style, top.point.latitude to top.point.longitude, icon)
}

/**
 * Renders and registers the riding cyclist avatar frames: [PEDAL_FRAME_COUNT]
 * pedalling poses (cycled by distance to fake the legs turning) plus a foot-down
 * idle pose shown while standing still. Reuses the app's `createLocationMarkerIcon`
 * so the replay rider matches the live map avatar.
 */
private fun registerCyclistFrames(style: Style, context: android.content.Context) {
    for (i in 0 until PEDAL_FRAME_COUNT) {
        val drawable = createLocationMarkerIcon(
            context = context,
            isNavigationActive = false,
            pedalPhase = i.toFloat() / PEDAL_FRAME_COUNT,
            idle = false
        )
        style.addImage(cyclistFrameImageId(i), drawableToBitmap(drawable))
    }
    val idle = createLocationMarkerIcon(context, isNavigationActive = false, idle = true)
    style.addImage(cyclistIdleImageId(), drawableToBitmap(idle))
}

/**
 * Registers the (initially empty) animated replay marker on top of everything:
 * the cyclist avatar, its frame chosen per-feature ([PROP_FRAME]) and rotated to
 * the travel heading ([PROP_BEARING]) so it heads along the track.
 */
private fun addReplayCyclist(style: Style) {
    if (style.getSource(SRC_REPLAY) == null) {
        style.addSource(GeoJsonSource(SRC_REPLAY, FeatureCollection.fromFeatures(emptyList())))
    }
    if (style.getLayer(LYR_REPLAY) == null) {
        style.addLayer(
            SymbolLayer(LYR_REPLAY, SRC_REPLAY).withProperties(
                PropertyFactory.iconImage(Expression.get(PROP_FRAME)),
                PropertyFactory.iconRotate(Expression.get(PROP_BEARING)),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconSize(0.5f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER)
            )
        )
    }
}

/** Frames the camera on the whole track (no-op for a degenerate track). */
private fun fitCameraToTrack(map: MapLibreMap, track: List<GeoPoint>) {
    if (track.size < 2) return
    val lats = track.map { it.latitude }
    val lons = track.map { it.longitude }
    val eps = 1e-4
    val bounds = LatLngBounds.from(
        lats.max() + eps,
        lons.max() + eps,
        lats.min() - eps,
        lons.min() - eps
    )
    runCatching { map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 64)) }
}

