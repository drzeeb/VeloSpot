package de.velospot.feature.map.presentation.sheets

import de.velospot.feature.map.presentation.*
import de.velospot.core.format.formatRideDistance
import de.velospot.core.format.formatRideDuration
import de.velospot.core.format.formatRideElevation
import de.velospot.core.format.formatRideSpeed

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint
import de.velospot.feature.map.presentation.ride.RideShareDialog
import kotlinx.coroutines.launch

/**
 * Detail view for one recorded ride: the full statistics grid plus a speed
 * "timeline" chart drawn from the captured track. The ride's polyline is shown
 * on the map underneath (drawn when the ride is selected).
 *
 * Unlike a [androidx.compose.material3.ModalBottomSheet], this sheet is **not**
 * modal: it has no scrim and only the sheet surface itself consumes touches, so
 * the user can still pan, pinch and zoom the map above it to inspect the drawn
 * ride track. The sheet starts collapsed (a small "peek") and can be dragged –
 * or tapped on its handle – up to reveal the full statistics.
 */
@Composable
internal fun RideDetailSheet(
    ride: RecordedRide,
    onDismiss: () -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String?) -> Unit,
    onSetArchived: (String, Boolean) -> Unit
) {
    var showShareDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    if (showShareDialog) {
        RideShareDialog(
            ride = ride,
            onDismiss = { showShareDialog = false }
        )
    }

    if (showRenameDialog) {
        RideRenameDialog(
            currentName = ride.name,
            onConfirm = { newName ->
                onRename(ride.id, newName)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false }
        )
    }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    // Collapsed "peek" shows the header + drag hint; expanded reveals the full
    // statistics. Expanded is capped so the map stays generously visible.
    val peekHeight = 156.dp
    val expandedHeight = configuration.screenHeightDp.dp * 0.62f
    val peekPx = with(density) { peekHeight.toPx() }
    val expandedPx = with(density) { expandedHeight.toPx() }

    val scope = rememberCoroutineScope()
    // Start fully expanded so the rider sees all stats immediately; they can drag
    // it down to the peek to free up the map.
    val heightAnim = remember { Animatable(expandedPx) }

    // Keep the live height within bounds across configuration/size changes.
    LaunchedEffect(expandedPx) {
        if (heightAnim.value > expandedPx) heightAnim.snapTo(expandedPx)
    }

    // Back gesture closes the sheet (there is no scrim to tap).
    BackHandler(onBack = onDismiss)

    val dragState = rememberDraggableState { delta ->
        // Dragging up yields a negative delta → the sheet grows.
        scope.launch {
            heightAnim.snapTo((heightAnim.value - delta).coerceIn(peekPx, expandedPx))
        }
    }
    val snapToNearest: () -> Unit = {
        val mid = (peekPx + expandedPx) / 2f
        val target = if (heightAnim.value >= mid) expandedPx else peekPx
        scope.launch { heightAnim.animateTo(target) }
    }

    // Full-screen, but only the bottom-aligned surface is a touch target, so the
    // empty area passes all gestures straight through to the map underneath.
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(with(density) { heightAnim.value.toDp() }),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 3.dp,
            shadowElevation = 10.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ── Drag handle row (drag to resize, tap handle to toggle) ────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = dragState,
                            onDragStopped = { snapToNearest() }
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            .size(width = 38.dp, height = 4.dp)
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 4.dp, top = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.ride_detail_close)
                        )
                    }
                }

                // ── Scrollable content ────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 12.dp)
                ) {
                    // ── Ride name (tap the pencil to rename) ─────────────────
                    val rideName = ride.name?.takeIf { it.isNotBlank() }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = rideName ?: stringResource(R.string.ride_unnamed),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (rideName != null) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { showRenameDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.ride_rename)
                            )
                        }
                    }

                    // ── Mock-recording indicator ─────────────────────────────
                    if (ride.isMock) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Science,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.ride_mock_indicator),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }

                    SheetHeader(
                        title = formatRideDistance(ride.distanceMeters),
                        subtitle = stringResource(
                            R.string.ride_detail_subtitle,
                            formatRideDuration(ride.elapsedSeconds),
                            formatRideSpeed(ride.avgSpeedMps)
                        )
                    )

                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.ride_detail_drag_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(16.dp))

                    // ── Stats grid ───────────────────────────────────────────
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatBox(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.ride_stat_moving),
                            value = formatRideDuration(ride.movingSeconds)
                        )
                        StatBox(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.ride_stat_max_speed),
                            value = formatRideSpeed(ride.maxSpeedMps)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatBox(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.ride_stat_elevation_gain),
                            value = "↑ " + formatRideElevation(ride.elevationGainMeters)
                        )
                        StatBox(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.ride_stat_elevation_loss),
                            value = "↓ " + formatRideElevation(ride.elevationLossMeters)
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    // ── Speed timeline chart ─────────────────────────────────
                    Text(
                        text = stringResource(R.string.ride_speed_chart_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    SpeedChart(
                        points = ride.points,
                        maxSpeedMps = ride.maxSpeedMps
                    )

                    Spacer(Modifier.height(16.dp))

                    // ── Share as a "VeloSpot Wrapped" card ───────────────────
                    Button(
                        onClick = { showShareDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(text = stringResource(R.string.ride_share_action))
                    }

                    Spacer(Modifier.height(8.dp))

                    // ── Archive / restore ────────────────────────────────────
                    TextButton(
                        onClick = { onSetArchived(ride.id, !ride.isArchived) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = if (ride.isArchived) Icons.Default.Unarchive
                                          else Icons.Default.Archive,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (ride.isArchived) stringResource(R.string.ride_unarchive)
                                   else stringResource(R.string.ride_archive)
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // ── Delete ───────────────────────────────────────────────
                    TextButton(
                        onClick = { onDelete(ride.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.ride_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/**
 * Simple dialog to name (or clear the name of) a recorded ride. Pre-fills the
 * current name; an empty field clears it (the ride falls back to its date label).
 */
@Composable
private fun RideRenameDialog(
    currentName: String?,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(currentName.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ride_rename_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.ride_rename_hint)) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim().ifBlank { null }) }) {
                Text(stringResource(R.string.ride_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ride_rename_cancel))
            }
        }
    )
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Minimal line/area chart of speed (km/h) over the ride's track samples — the
 * "timeline" the user asked for. Drawn with a plain [Canvas] so no charting
 * dependency is needed.
 */
@Composable
private fun SpeedChart(points: List<TrackPoint>, maxSpeedMps: Double) {
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    val speeds = points.map { (it.speedMps ?: 0f).toDouble() }
    val maxSpeed = maxOf(maxSpeedMps, speeds.maxOrNull() ?: 0.0, 1.0)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Box(modifier = Modifier.padding(12.dp)) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            ) {
                val w = size.width
                val h = size.height
                // Baseline grid.
                drawLine(
                    color = gridColor,
                    start = Offset(0f, h),
                    end = Offset(w, h),
                    strokeWidth = 1.5f
                )
                if (speeds.size < 2) return@Canvas

                val stepX = w / (speeds.size - 1)
                fun y(v: Double) = (h - (v / maxSpeed * h)).toFloat()

                val linePath = Path()
                val fillPath = Path()
                speeds.forEachIndexed { index, v ->
                    val x = index * stepX
                    val py = y(v)
                    if (index == 0) {
                        linePath.moveTo(x, py)
                        fillPath.moveTo(x, h)
                        fillPath.lineTo(x, py)
                    } else {
                        linePath.lineTo(x, py)
                        fillPath.lineTo(x, py)
                    }
                }
                fillPath.lineTo(w, h)
                fillPath.close()

                drawPath(path = fillPath, color = fillColor)
                drawPath(
                    path = linePath,
                    color = lineColor,
                    style = Stroke(width = 3f)
                )
            }
        }
    }
}


