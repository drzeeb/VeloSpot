package de.velospot.feature.analysis.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velospot.R
import de.velospot.core.analysis.Climb
import de.velospot.core.analysis.ClimbCategory
import de.velospot.core.analysis.GradientBin
import de.velospot.core.analysis.KmSplit
import de.velospot.core.analysis.RideAnalysis
import de.velospot.core.analysis.RideMapData
import de.velospot.core.analysis.SpeedBin
import de.velospot.core.format.formatRideDistance
import de.velospot.core.format.formatRideDuration
import de.velospot.core.format.formatRideElevation
import de.velospot.core.format.formatRideSpeed
import de.velospot.domain.model.RecordedRide
import de.velospot.feature.map.presentation.RideElevationProfile
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * Full-screen, in-depth analysis of a single recorded ride (Phase-0 foundation):
 * headline figures, the elevation profile (reused from navigation), per-kilometre
 * splits and a speed distribution. Opened from the ride detail sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideAnalysisScreen(
    onBack: () -> Unit,
    isDarkTheme: Boolean = false,
    viewModel: RideAnalysisViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val title = (state as? RideAnalysisUiState.Ready)?.ride?.titleText()
        ?: stringResource(R.string.ride_analysis_title)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.ride_analysis_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        when (val s = state) {
            RideAnalysisUiState.Loading,
            RideAnalysisUiState.NotFound ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    if (s == RideAnalysisUiState.Loading) CircularProgressIndicator()
                    else Text(
                        text = stringResource(R.string.ride_unnamed),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

            is RideAnalysisUiState.Ready -> RideAnalysisContent(
                ride = s.ride,
                analysis = s.analysis,
                mapData = s.mapData,
                isDarkTheme = isDarkTheme,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RideAnalysisContent(
    ride: RecordedRide,
    analysis: RideAnalysis,
    mapData: RideMapData,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // ── Headline: big distance + duration ────────────────────────────────
        Text(
            text = formatRideDistance(analysis.distanceMeters),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = formatRideDuration(analysis.elapsedSeconds),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ── Map with speed-coloured track, markers & animated replay ─────────
        if (mapData.track.size >= 2) {
            SectionTitle(stringResource(R.string.ride_analysis_map))
            RideReplayMap(
                ride = ride,
                mapData = mapData,
                maxSpeedMps = analysis.maxSpeedMps,
                isDarkTheme = isDarkTheme,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(16.dp))

        // ── Stat tiles ───────────────────────────────────────────────────────
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatTile(stringResource(R.string.ride_stat_speed), formatRideSpeed(analysis.avgMovingSpeedMps))
            StatTile(stringResource(R.string.ride_stat_max_speed), formatRideSpeed(analysis.maxSpeedMps), highlight = true)
            StatTile(stringResource(R.string.ride_stat_moving), formatRideDuration(analysis.movingSeconds))
            StatTile(stringResource(R.string.ride_analysis_stopped), formatRideDuration(analysis.stoppedSeconds))
            StatTile(stringResource(R.string.ride_stat_elevation_gain), "↑ " + formatRideElevation(analysis.elevationGainMeters))
            StatTile(stringResource(R.string.ride_stat_elevation_loss), "↓ " + formatRideElevation(analysis.elevationLossMeters))
            StatTile(stringResource(R.string.ride_stats_calories), "≈ %,d kcal".format(analysis.caloriesKcal))
            if (analysis.avgPowerWatts > 0) {
                StatTile(stringResource(R.string.ride_analysis_power), "≈ ${analysis.avgPowerWatts} W")
            }
            if (analysis.stopCount > 0) {
                StatTile(stringResource(R.string.ride_analysis_stops), analysis.stopCount.toString())
                StatTile(stringResource(R.string.ride_analysis_longest_stop), formatRideDuration(analysis.longestStopSeconds))
            }
        }

        // ── Elevation profile (reused) ───────────────────────────────────────
        if (ride.points.count { it.altitudeMeters != null } >= 2) {
            SectionTitle(stringResource(R.string.ride_elevation_chart_title))
            RideElevationProfile(
                points = ride.points,
                ascentMeters = analysis.elevationGainMeters,
                descentMeters = analysis.elevationLossMeters
            )
        }

        // ── Kilometre splits ─────────────────────────────────────────────────
        if (analysis.splits.isNotEmpty()) {
            SectionTitle(stringResource(R.string.ride_analysis_splits))
            SplitBars(
                splits = analysis.splits,
                fastestIndex = analysis.fastestSplitIndex,
                slowestIndex = analysis.slowestSplitIndex
            )
        }

        // ── Speed distribution ───────────────────────────────────────────────
        if (analysis.speedHistogram.any { it.seconds > 0 }) {
            SectionTitle(stringResource(R.string.ride_analysis_speed_distribution))
            SpeedHistogram(bins = analysis.speedHistogram)
        }

        // ── Climbs ───────────────────────────────────────────────────────────
        if (analysis.climbs.isNotEmpty()) {
            SectionTitle(stringResource(R.string.ride_analysis_climbs))
            ClimbList(climbs = analysis.climbs)
        }

        // ── Gradient distribution ────────────────────────────────────────────
        if (analysis.gradientHistogram.any { it.meters > 0 }) {
            SectionTitle(stringResource(R.string.ride_analysis_gradient_distribution))
            GradientDistribution(bins = analysis.gradientHistogram)
        }

        // ── Moving vs. stopped donut ─────────────────────────────────────────
        if (analysis.movingSeconds > 0 && analysis.elapsedSeconds > 0) {
            SectionTitle(stringResource(R.string.ride_analysis_moving_share))
            MovingDonut(
                movingSeconds = analysis.movingSeconds,
                stoppedSeconds = analysis.stoppedSeconds
            )
        }

        // ── Track quality ────────────────────────────────────────────────────
        analysis.trackQuality.avgAccuracyMeters?.let { acc ->
            SectionTitle(stringResource(R.string.ride_analysis_track_quality))
            Text(
                text = stringResource(
                    R.string.ride_analysis_track_quality_summary,
                    analysis.trackQuality.pointCount,
                    acc.roundToInt(),
                    (analysis.trackQuality.poorFixFraction * 100).roundToInt()
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(22.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun StatTile(label: String, value: String, highlight: Boolean = false) {
    val container = if (highlight) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest
    val onContainer = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer
                      else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = onContainer)
        Text(label, style = MaterialTheme.typography.labelSmall, color = onContainer.copy(alpha = 0.7f))
    }
}

/** Per-kilometre bars sized by average speed; fastest/slowest km are tinted. */
@Composable
private fun SplitBars(splits: List<KmSplit>, fastestIndex: Int, slowestIndex: Int) {
    val maxAvg = splits.maxOf { it.avgSpeedMps }.coerceAtLeast(0.1)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    var cumulative = 0.0
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        splits.forEach { split ->
            cumulative += split.distanceMeters
            val fraction = (split.avgSpeedMps / maxAvg).toFloat().coerceIn(0.04f, 1f)
            val barColor = when (split.index) {
                fastestIndex -> MaterialTheme.colorScheme.primary
                slowestIndex -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.secondary
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatRideDistance(cumulative),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(56.dp)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(track)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(barColor)
                    )
                }
                // Speed label sits outside the bar so it stays readable
                // regardless of the bar's fill colour.
                Text(
                    text = formatRideSpeed(split.avgSpeedMps),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .width(72.dp)
                        .padding(start = 8.dp)
                )
            }
        }
    }
}

/** Vertical bars: time spent in each 5 km/h speed band. */
@Composable
private fun SpeedHistogram(bins: List<SpeedBin>) {
    val barColor = MaterialTheme.colorScheme.primary
    val maxSeconds = bins.maxOf { it.seconds }.coerceAtLeast(1L)

    Column(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) {
            val n = bins.size
            val gap = 6.dp.toPx()
            val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
            bins.forEachIndexed { i, bin ->
                val bh = (bin.seconds.toFloat() / maxSeconds) * size.height
                val x = i * (barW + gap)
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, size.height - bh),
                    size = Size(barW, bh),
                    cornerRadius = CornerRadius(4f, 4f)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            bins.forEach { bin ->
                Text(
                    text = if (bin.toKmh == Int.MAX_VALUE) "${bin.fromKmh}+" else "${bin.fromKmh}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Text(
            text = "km/h",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End
        )
    }
}

/** Categorised climbs: one card per climb with a difficulty badge. */
@Composable
private fun ClimbList(climbs: List<Climb>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        climbs.forEach { climb ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ClimbBadge(climb.category)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "%s • ↑ %s".format(
                            formatRideDistance(climb.lengthMeters),
                            formatRideElevation(climb.ascentMeters)
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Ø %.1f%% · max %.1f%% · %d VAM".format(
                            climb.avgGradientPercent,
                            climb.maxGradientPercent,
                            climb.vamMetersPerHour.roundToInt()
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ClimbBadge(category: ClimbCategory) {
    val label = when (category) {
        ClimbCategory.HC -> "HC"
        ClimbCategory.CAT1 -> "1"
        ClimbCategory.CAT2 -> "2"
        ClimbCategory.CAT3 -> "3"
        ClimbCategory.CAT4 -> "4"
        ClimbCategory.UNCATEGORIZED -> "•"
    }
    // Harder climbs get the bolder colour.
    val color = when (category) {
        ClimbCategory.HC, ClimbCategory.CAT1 -> MaterialTheme.colorScheme.error
        ClimbCategory.CAT2, ClimbCategory.CAT3 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

/** Diverging horizontal bars: distance ridden in each gradient band. */
@Composable
private fun GradientDistribution(bins: List<GradientBin>) {
    val maxMeters = bins.maxOf { it.meters }.coerceAtLeast(1.0)
    val downhill = MaterialTheme.colorScheme.tertiary
    val flat = MaterialTheme.colorScheme.secondary
    val uphill = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        bins.forEach { bin ->
            if (bin.meters <= 0.0) return@forEach
            val fraction = (bin.meters / maxMeters).toFloat().coerceIn(0.02f, 1f)
            val barColor = when {
                bin.toPercent <= -2 -> downhill
                bin.fromPercent >= 2 -> uphill
                else -> flat
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = gradientBandLabel(bin),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(72.dp)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(20.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(track)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(barColor)
                    )
                }
                Text(
                    text = formatRideDistance(bin.meters),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .width(72.dp)
                        .padding(start = 8.dp)
                )
            }
        }
    }
}

private fun gradientBandLabel(bin: GradientBin): String = when {
    bin.fromPercent == Int.MIN_VALUE -> "< ${bin.toPercent}%"
    bin.toPercent == Int.MAX_VALUE -> "> ${bin.fromPercent}%"
    else -> "${bin.fromPercent}…${bin.toPercent}%"
}

/** Donut showing moving time vs. stopped time. */
@Composable
private fun MovingDonut(movingSeconds: Long, stoppedSeconds: Long) {
    val total = (movingSeconds + stoppedSeconds).coerceAtLeast(1)
    val movingFraction = movingSeconds.toFloat() / total
    val movingColor = MaterialTheme.colorScheme.primary
    val stoppedColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(
            modifier = Modifier
                .width(120.dp)
                .height(120.dp)
        ) {
            val stroke = 22.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = stoppedColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Butt)
            )
            drawArc(
                color = movingColor,
                startAngle = -90f,
                sweepAngle = 360f * movingFraction,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Spacer(Modifier.width(20.dp))
        Column {
            LegendRow(movingColor, stringResource(R.string.ride_stat_moving), formatRideDuration(movingSeconds))
            Spacer(Modifier.height(6.dp))
            LegendRow(stoppedColor, stringResource(R.string.ride_analysis_stopped), formatRideDuration(stoppedSeconds))
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${(movingFraction * 100).roundToInt()}%",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(12.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$label  ·  $value",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private val ANALYSIS_DATE_FORMAT: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)

private fun RecordedRide.titleText(): String =
    name?.takeIf { it.isNotBlank() } ?: ANALYSIS_DATE_FORMAT.format(Date(startedAt))
