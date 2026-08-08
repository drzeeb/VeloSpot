package de.velospot.feature.wrapped.presentation

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.velospot.R
import de.velospot.core.format.formatCo2Saved
import de.velospot.core.format.formatRideDistance
import de.velospot.core.format.formatRideDuration
import de.velospot.core.format.formatRideElevation
import de.velospot.core.format.formatRideSpeed
import de.velospot.core.share.ImageSharer
import de.velospot.feature.map.presentation.ride.RideShareThemes
import de.velospot.feature.map.presentation.ride.StatsShareBadge
import de.velospot.feature.map.presentation.ride.StatsShareCell
import de.velospot.feature.map.presentation.ride.StatsShareLabels
import de.velospot.feature.map.presentation.ride.ThemePicker
import de.velospot.feature.map.presentation.ride.everestRatio
import de.velospot.feature.map.presentation.ride.qualifiesEverestBadge
import de.velospot.feature.map.presentation.ride.qualifiesStreakBadge
import de.velospot.feature.map.presentation.ride.qualifiesWorldBadge
import de.velospot.feature.map.presentation.ride.renderStatsShareCard
import de.velospot.feature.wrapped.domain.WrappedPeriod
import de.velospot.feature.wrapped.domain.WrappedPeriodType
import de.velospot.feature.wrapped.domain.WrappedReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** How long each auto-advancing Story slide fills its progress bar (~5 s). */
private const val PAGE_DURATION_MS = 5_000f

/**
 * The full-screen, Instagram/Spotify-Wrapped-style Story for a single
 * [WrappedReport].
 *
 * Segmented progress bars run across the top (one per slide), the active one
 * filling over [PAGE_DURATION_MS]. Auto-advances on completion; **tap right** =
 * next, **tap left** = previous, **press-and-hold** = pause, **swipe down / back**
 * = dismiss. The closing slide is the reusable share card (theme picker +
 * off-thread `renderStatsShareCard` + system share sheet).
 */
@Composable
internal fun WrappedStoryScreen(
    report: WrappedReport,
    onDismiss: () -> Unit
) {
    val pages = remember(report.id) { buildWrappedStoryPages(report) }
    val pageCount = pages.size

    // A deterministic theme per report so re-opening the same story looks stable,
    // but the outro's picker can override it (also re-tints the background live).
    var selectedTheme by remember(report.id) {
        mutableStateOf(RideShareThemes.all[abs(report.id.hashCode()) % RideShareThemes.all.size])
    }

    var currentIndex by remember(report.id) { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var progress by remember(currentIndex) { mutableFloatStateOf(0f) }

    val isOutro = pages[currentIndex].kind == WrappedStoryPageKind.OUTRO

    fun next() { if (currentIndex < pageCount - 1) currentIndex++ else onDismiss() }
    fun previous() { if (currentIndex > 0) currentIndex-- }

    // Auto-advance timer (frame-driven so pausing simply cancels + resumes from the
    // retained progress). Suspended on the outro so the share card stays put.
    LaunchedEffectTimer(
        currentIndex = currentIndex,
        paused = paused || isOutro,
        onTick = { delta -> progress = (progress + delta).coerceAtMost(1f) },
        isDone = { progress >= 1f },
        onComplete = { next() }
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BackHandler(enabled = true) { onDismiss() }

        val bg = remember(selectedTheme) {
            Brush.verticalGradient(
                listOf(
                    Color(selectedTheme.gradientTop),
                    Color(selectedTheme.gradientMid),
                    Color(selectedTheme.gradientBottom)
                )
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bg)
                .pointerInput(pageCount) {
                    detectTapGestures(
                        onPress = {
                            paused = true
                            tryAwaitRelease()
                            paused = false
                        },
                        onTap = { offset ->
                            if (offset.x < size.width / 2f) previous() else next()
                        }
                    )
                }
                .pointerInput(Unit) {
                    var dragged = 0f
                    detectVerticalDragGestures(
                        onDragEnd = { dragged = 0f },
                        onVerticalDrag = { _, dy ->
                            dragged += dy
                            if (dragged > 220f) { dragged = 0f; onDismiss() }
                        }
                    )
                }
        ) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                // ── Segmented progress bars ──────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    pages.indices.forEach { i ->
                        val fill = when {
                            i < currentIndex -> 1f
                            i == currentIndex -> progress
                            else -> 0f
                        }
                        SegmentBar(fill = fill, modifier = Modifier.weight(1f))
                    }
                }

                // ── Slide content ────────────────────────────────────────────
                AnimatedContent(
                    targetState = currentIndex,
                    transitionSpec = {
                        (fadeIn(tween(220)) togetherWith fadeOut(tween(160)))
                    },
                    label = "wrapped-slide"
                ) { index ->
                    val page = pages[index]
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when (page.kind) {
                            WrappedStoryPageKind.INTRO ->
                                IntroSlide(report = report, page = page)
                            WrappedStoryPageKind.HIGHLIGHT ->
                                HighlightSlide(page = page)
                            WrappedStoryPageKind.OUTRO ->
                                OutroShareSlide(
                                    report = report,
                                    selectedTheme = selectedTheme,
                                    onSelectTheme = { selectedTheme = it },
                                    onDone = onDismiss
                                )
                        }
                    }
                }
            }
        }
    }
}

/** A single rounded progress segment; [fill] is 0..1 of its width. */
@Composable
private fun SegmentBar(fill: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.28f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fill.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White)
        )
    }
}

@Composable
private fun IntroSlide(report: WrappedReport, page: WrappedStoryPage) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = wrappedPeriodLabel(report.period).uppercase(Locale.ROOT),
            color = Color.White.copy(alpha = 0.85f),
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.wrapped_intro_headline),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = formatRideDistance(page.valueNumber),
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 64.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun HighlightSlide(page: WrappedStoryPage) {
    val type = page.highlightType ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = wrappedIconFor(type),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(52.dp)
            )
        }
        Spacer(Modifier.height(24.dp))
        if (page.isRecord) {
            Text(
                text = stringResource(R.string.wrapped_record_kicker),
                color = Color.White.copy(alpha = 0.9f),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            text = stringResource(wrappedTitleResFor(type)),
            color = Color.White.copy(alpha = 0.9f),
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = formatWrappedValue(type, page.valueNumber),
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 60.sp,
            textAlign = TextAlign.Center
        )
        formatWrappedDelta(page.deltaPercent)?.let { delta ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.wrapped_vs_previous_caption, delta),
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun OutroShareSlide(
    report: WrappedReport,
    selectedTheme: de.velospot.feature.map.presentation.ride.RideShareTheme,
    onSelectTheme: (de.velospot.feature.map.presentation.ride.RideShareTheme) -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val labels = wrappedShareLabels(report)
    val periodLabel = wrappedPeriodLabel(report.period).uppercase(Locale.ROOT)
    val shareChooserTitle = stringResource(R.string.ride_share_chooser_title)

    val bitmap by produceState<Bitmap?>(initialValue = null, selectedTheme, report.id) {
        value = null
        value = withContext(Dispatchers.Default) {
            renderStatsShareCard(
                stats = report.stats,
                labels = labels,
                periodLabel = periodLabel,
                theme = selectedTheme
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .aspectRatio(1080f / 1350f),
            contentAlignment = Alignment.Center
        ) {
            val bmp = bitmap
            if (bmp == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        ThemePicker(selected = selectedTheme, onSelect = onSelectTheme)
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                bitmap?.let {
                    ImageSharer.shareBitmap(
                        context = context,
                        bitmap = it,
                        chooserTitle = shareChooserTitle
                    )
                }
            },
            enabled = bitmap != null,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color(selectedTheme.gradientMid)
            ),
            modifier = Modifier.fillMaxWidth(0.82f)
        ) {
            Icon(imageVector = Icons.Default.Share, contentDescription = null)
            Text(
                text = stringResource(R.string.wrapped_share_action),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth(0.82f)) {
            Text(stringResource(R.string.wrapped_done), color = Color.White)
        }
    }
}

/** Builds the share-card labels for [report] (reuses the all-time card pipeline). */
@Composable
private fun wrappedShareLabels(report: WrappedReport): StatsShareLabels {
    val stats = report.stats
    val cells = listOf(
        StatsShareCell("⏱", formatRideDuration(stats.totalMovingSeconds), stringResource(R.string.ride_stats_total_moving)),
        StatsShareCell("⛰", "↑ " + formatRideElevation(stats.totalElevationGainMeters), stringResource(R.string.ride_stats_total_gain)),
        StatsShareCell("⚡", formatRideSpeed(stats.topSpeedMps), stringResource(R.string.ride_stats_top_speed)),
        StatsShareCell("🚴", formatRideSpeed(stats.avgMovingSpeedMps), stringResource(R.string.ride_stats_avg_speed)),
        StatsShareCell("🔥", "%,d kcal".format(stats.caloriesBurned), stringResource(R.string.ride_stats_calories)),
        StatsShareCell("🌱", formatCo2Saved(stats.co2SavedGrams), stringResource(R.string.ride_stats_co2))
    )
    val badges = buildList {
        if (qualifiesWorldBadge(stats.earthCircumferencePercent)) {
            add(StatsShareBadge("🌍", stringResource(R.string.stats_share_badge_world, stats.earthCircumferencePercent)))
        }
        if (qualifiesEverestBadge(stats.totalElevationGainMeters)) {
            add(StatsShareBadge("🏔", stringResource(R.string.stats_share_badge_everest, everestRatio(stats.totalElevationGainMeters))))
        }
        if (qualifiesStreakBadge(stats.longestStreakDays)) {
            add(StatsShareBadge("🔥", stringResource(R.string.stats_share_badge_streak, stats.longestStreakDays)))
        }
    }
    return StatsShareLabels(
        headline = stringResource(R.string.stats_share_headline_distance),
        subtitle = stringResource(R.string.stats_share_subtitle, stats.rideCount, stats.activeDays),
        cells = cells,
        badges = badges,
        footer = stringResource(R.string.ride_share_footer)
    )
}

/** A localized label for a [WrappedPeriod], e.g. "June 2024" or "Week of 10 Jun". */
@Composable
internal fun wrappedPeriodLabel(period: WrappedPeriod): String {
    val start = Date(period.startInclusive)
    return when (period.type) {
        WrappedPeriodType.DAY ->
            remember(period) { SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(start) }
        WrappedPeriodType.WEEK ->
            stringResource(
                R.string.wrapped_period_week,
                remember(period) { SimpleDateFormat("d MMM", Locale.getDefault()).format(start) }
            )
        WrappedPeriodType.MONTH ->
            remember(period) { SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(start) }
        WrappedPeriodType.YEAR ->
            remember(period) { SimpleDateFormat("yyyy", Locale.getDefault()).format(start) }
        WrappedPeriodType.CUSTOM ->
            remember(period) {
                val fmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
                // The range is half-open; show the inclusive last day.
                val lastDay = Date(period.endExclusive - 1)
                "${fmt.format(start)} – ${fmt.format(lastDay)}"
            }
    }
}

/**
 * Frame-driven page timer. Separated so the gesture-heavy Story stays readable and
 * the loop's key handling (reset on slide change, cancel-on-pause) is explicit.
 */
@Composable
private fun LaunchedEffectTimer(
    currentIndex: Int,
    paused: Boolean,
    onTick: (deltaFraction: Float) -> Unit,
    isDone: () -> Boolean,
    onComplete: () -> Unit
) {
    androidx.compose.runtime.LaunchedEffect(currentIndex, paused) {
        if (paused) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (!isDone()) {
            val now = withFrameNanos { it }
            onTick((now - last) / 1_000_000f / PAGE_DURATION_MS)
            last = now
        }
        onComplete()
    }
}

