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
import kotlin.math.roundToInt

/**
 * Compact elevation profile of a route — distance on the x-axis, terrain height
 * on the y-axis — drawn with a plain [Canvas] (no charting dependency). Only
 * shown for routes that carry per-node elevation (BRouter offline; the online
 * OSRM fallback has none), so the composable renders nothing when there are
 * fewer than two elevation samples.
 */
@Composable
internal fun RouteElevationProfile(
    points: List<RoutePoint>,
    modifier: Modifier = Modifier
) {
    val profile = remember(points) { buildElevationProfile(points) } ?: return

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

/** Builds the elevation profile, or `null` when too few elevation samples exist. */
private fun buildElevationProfile(points: List<RoutePoint>): ElevationProfileData? {
    val withElev = points.filter { it.elevationMeters != null }
    if (withElev.size < 2) return null

    val distances = ArrayList<Double>(withElev.size)
    val elevations = ArrayList<Double>(withElev.size)
    var cumulative = 0.0
    var ascent = 0.0
    var descent = 0.0
    var prev: RoutePoint? = null
    for (p in withElev) {
        val e = p.elevationMeters!!
        if (prev != null) {
            cumulative += GeoMath.distanceMeters(
                prev.latitude, prev.longitude, p.latitude, p.longitude
            )
            val delta = e - prev.elevationMeters!!
            if (delta > 0) ascent += delta else descent += -delta
        }
        distances.add(cumulative)
        elevations.add(e)
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

