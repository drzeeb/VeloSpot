package de.velospot.feature.analysis.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import de.velospot.R
import de.velospot.core.analysis.GeoPoint
import de.velospot.core.analysis.RideMapData
import de.velospot.core.analysis.RideMarker
import de.velospot.core.analysis.RideMarkerType
import de.velospot.core.map.RideSpeedSegments
import de.velospot.domain.model.RecordedRide
import de.velospot.feature.map.presentation.mapStyleUrl
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
private const val LYR_MARKER_LABELS = "vs-analysis-marker-labels-layer"
private const val SRC_REPLAY = "vs-analysis-replay-source"
private const val LYR_REPLAY = "vs-analysis-replay-layer"
private const val PROP_COLOR = "markerColor"
private const val PROP_RADIUS = "markerRadius"
private const val PROP_LABEL = "markerLabel"

/** Wall-clock duration of one full replay pass, in milliseconds. */
private const val REPLAY_DURATION_MS = 14_000f

/**
 * An embedded, interactive map for the ride analysis screen: the ride's track
 * painted on a green → red **speed ramp**, **start / finish / top-speed / stop /
 * kilometre** markers, and an **animated replay** dot that retraces the ride
 * (time-based, so it pauses where you stopped). Controlled by a play/restart
 * button and a scrub slider.
 */
@Composable
fun RideReplayMap(
    ride: RecordedRide,
    mapData: RideMapData,
    maxSpeedMps: Double,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val mapView = rememberMapViewWithLifecycle(enabled = true)
    var progress by remember { mutableFloatStateOf(0f) }
    var isPlaying by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf<Style?>(null) }

    val segments = remember(ride.id) { RideSpeedSegments.build(ride.points) }

    Column(modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(16.dp))
        ) {
            if (mapView != null) {
                AndroidView(
                    factory = { mapView },
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
                val speedColoured = segments.any { it.speedMps > 0.0 } && ride.maxSpeedMps > 0.0
                if (speedColoured) {
                    updateTrackSpeedLayer(loaded, segments, ride.maxSpeedMps, visible = true)
                } else {
                    updateTrackLayer(loaded, ride.points.map { it.latitude to it.longitude }, 0x2962FF)
                }
                addRideMarkers(loaded, mapData.markers)
                addReplayDot(loaded)
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

    // Move the replay dot whenever the progress (or the loaded style) changes.
    LaunchedEffect(progress, style) {
        val s = style ?: return@LaunchedEffect
        val frames = mapData.replayFrames
        if (frames.isEmpty()) return@LaunchedEffect
        val idx = (progress * (frames.size - 1)).roundToInt().coerceIn(0, frames.size - 1)
        val p = frames[idx]
        (s.getSource(SRC_REPLAY) as? GeoJsonSource)?.setGeoJson(
            Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude))
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

/** Adds the marker circles (data-driven colour/size) and km-number labels. */
private fun addRideMarkers(style: Style, markers: List<RideMarker>) {
    val features = markers.map { m ->
        Feature.fromGeometry(Point.fromLngLat(m.point.longitude, m.point.latitude)).apply {
            addStringProperty(PROP_COLOR, markerColor(m.type))
            addNumberProperty(PROP_RADIUS, markerRadius(m.type))
            m.label?.let { addStringProperty(PROP_LABEL, it) }
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
    if (style.getLayer(LYR_MARKER_LABELS) == null) {
        style.addLayer(
            SymbolLayer(LYR_MARKER_LABELS, SRC_MARKERS).withProperties(
                PropertyFactory.textField(Expression.get(PROP_LABEL)),
                PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                PropertyFactory.textSize(11f),
                PropertyFactory.textColor("#FFFFFF"),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_CENTER)
            )
        )
    }
}

/** Registers the (initially empty) animated replay dot on top of everything. */
private fun addReplayDot(style: Style) {
    if (style.getSource(SRC_REPLAY) == null) {
        style.addSource(GeoJsonSource(SRC_REPLAY, FeatureCollection.fromFeatures(emptyList())))
    }
    if (style.getLayer(LYR_REPLAY) == null) {
        style.addLayer(
            CircleLayer(LYR_REPLAY, SRC_REPLAY).withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor("#2962FF"),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(3f)
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

