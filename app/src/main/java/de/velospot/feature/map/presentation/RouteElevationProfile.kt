package de.velospot.feature.map.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.core.navigation.GeoMath
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.model.TrackPoint
import kotlin.math.roundToInt

/**
 * Compact elevation profile of a **planned route** — distance on the x-axis,
 * terrain height on the y-axis — drawn with a plain [Canvas] (no charting
 * dependency). Only shown for routes that carry per-node elevation (BRouter
 * offline; the online OSRM fallback has none), so the composable renders nothing
 * when there are fewer than two elevation samples.
 */
@Composable
internal fun RouteElevationProfile(
    points: List<RoutePoint>,
    modifier: Modifier = Modifier
) {
    val profile = remember(points) {
        buildElevationProfile(points.map { ElevSample(it.latitude, it.longitude, it.elevationMeters) })
    } ?: return
    ElevationProfileChart(profile, modifier)
}

/**
 * Elevation profile of a **recorded ride**, built from the captured GPS track.
 *
 * GPS-only altitude is noisy, so the plotted line is low-pass filtered (matching
 * the recorder's own altitude smoothing). The ascent/descent figures shown are the
 * ride's **stored** (already-smoothed) [ascentMeters]/[descentMeters] so they stay
 * consistent with the elevation stat boxes, rather than re-derived from the raw
 * per-fix deltas. Renders nothing when the ride carries fewer than two altitude
 * samples (e.g. some mock rides).
 */
@Composable
internal fun RideElevationProfile(
    points: List<TrackPoint>,
    ascentMeters: Double,
    descentMeters: Double,
    modifier: Modifier = Modifier
) {
    val profile = remember(points, ascentMeters, descentMeters) {
        buildElevationProfile(
            samples = points.map { ElevSample(it.latitude, it.longitude, it.altitudeMeters) },
            smooth = true
        )?.copy(ascentMeters = ascentMeters, descentMeters = descentMeters)
    } ?: return
    ElevationProfileChart(profile, modifier)
}

/** Shared Canvas rendering for [RouteElevationProfile] and [RideElevationProfile]. */
@Composable
private fun ElevationProfileChart(
    profile: ElevationProfileData,
    modifier: Modifier = Modifier
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.elevation_profile_ascent, profile.ascentMeters.roundToInt()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.elevation_profile_descent, profile.descentMeters.roundToInt()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${profile.minElev.roundToInt()}–${profile.maxElev.roundToInt()} m",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(top = 2.dp)
        ) {
            val w = size.width
            val h = size.height
            drawLine(
                color = gridColor,
                start = Offset(0f, h),
                end = Offset(w, h),
                strokeWidth = 1.5f
            )
            val totalDist = profile.distances.last().coerceAtLeast(1.0)
            val range = (profile.maxElev - profile.minElev).coerceAtLeast(1.0)
            fun x(d: Double) = (d / totalDist * w).toFloat()
            fun y(e: Double) = (h - ((e - profile.minElev) / range) * h * 0.9f - h * 0.05f).toFloat()

            val linePath = Path()
            val fillPath = Path()
            profile.distances.forEachIndexed { i, d ->
                val px = x(d)
                val py = y(profile.elevations[i])
                if (i == 0) {
                    linePath.moveTo(px, py)
                    fillPath.moveTo(px, h)
                    fillPath.lineTo(px, py)
                } else {
                    linePath.lineTo(px, py)
                    fillPath.lineTo(px, py)
                }
            }
            fillPath.lineTo(w, h)
            fillPath.close()

            drawPath(path = fillPath, color = fillColor)
            drawPath(path = linePath, color = lineColor, style = Stroke(width = 3f))
        }
    }
}


private data class ElevationProfileData(
    val distances: List<Double>,
    val elevations: List<Double>,
    val minElev: Double,
    val maxElev: Double,
    val ascentMeters: Double,
    val descentMeters: Double
)

/** One position sample with an optional elevation (terrain or GPS altitude). */
private data class ElevSample(val latitude: Double, val longitude: Double, val elevation: Double?)

/** Exponential smoothing factor for noisy GPS altitude (matches the recorder). */
private const val ALT_SMOOTHING_ALPHA = 0.3

/**
 * Builds the elevation profile from [samples], or `null` when fewer than two carry
 * an elevation. When [smooth] is set, the elevation series is low-pass filtered
 * (for noisy GPS altitude); distances always come from the raw coordinates.
 */
private fun buildElevationProfile(
    samples: List<ElevSample>,
    smooth: Boolean = false
): ElevationProfileData? {
    val withElev = samples.filter { it.elevation != null }
    if (withElev.size < 2) return null

    // Elevation series (optionally EMA-smoothed to tame GPS jitter).
    val elevations = ArrayList<Double>(withElev.size)
    var ema = withElev.first().elevation!!
    for ((i, p) in withElev.withIndex()) {
        ema = if (!smooth || i == 0) p.elevation!! else ema + ALT_SMOOTHING_ALPHA * (p.elevation!! - ema)
        elevations.add(ema)
    }

    val distances = ArrayList<Double>(withElev.size)
    var cumulative = 0.0
    var ascent = 0.0
    var descent = 0.0
    var prev: ElevSample? = null
    for ((i, p) in withElev.withIndex()) {
        if (prev != null) {
            cumulative += GeoMath.distanceMeters(prev.latitude, prev.longitude, p.latitude, p.longitude)
            val delta = elevations[i] - elevations[i - 1]
            if (delta > 0) ascent += delta else descent += -delta
        }
        distances.add(cumulative)
        prev = p
    }
    return ElevationProfileData(
        distances = distances,
        elevations = elevations,
        minElev = elevations.min(),
        maxElev = elevations.max(),
        ascentMeters = ascent,
        descentMeters = descent
    )
}

