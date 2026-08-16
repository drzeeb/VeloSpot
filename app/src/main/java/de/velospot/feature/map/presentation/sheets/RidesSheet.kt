package de.velospot.feature.map.presentation.sheets

import de.velospot.feature.map.presentation.*
import de.velospot.core.format.formatRideDistance
import de.velospot.core.format.formatRideDuration
import de.velospot.core.format.formatRideElevation
import de.velospot.core.format.formatRideSpeed

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.domain.model.RecordedRideSummary
import de.velospot.core.stats.computeRideStatistics
import de.velospot.feature.map.presentation.ride.RideStatisticsSection
import java.text.DateFormat
import java.util.Date

/**
 * The "My rides" timeline: a scrollable list of past recorded rides with their
 * date, distance and duration. Tapping a ride opens its [RideDetailSheet].
 *
 * The list is driven by track-free [RecordedRideSummary]s — no GPS track is loaded
 * to render the timeline; the full track is fetched only when a ride is opened or
 * exported.
 *
 * The header carries **Import** and **Export** actions. Tapping *Export* turns the
 * list into a **multi-select**: each ride gets a checkbox and a Cancel/Export bar
 * appears at the bottom. Confirming with several rides selected asks whether to
 * combine them into one GPX file or write one file per ride; a single selection
 * exports straight away (file named after the ride).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RidesSheet(
    rides: List<RecordedRideSummary>,
    onDismiss: () -> Unit,
    onSelectRide: (RecordedRideSummary) -> Unit,
    onExportRides: (rides: List<RecordedRideSummary>, combine: Boolean, save: Boolean) -> Unit,
    onMergeRides: (ids: List<String>, name: String) -> Unit,
    onImport: () -> Unit,
    onOpenWrapped: () -> Unit
) {
    // A partially-expanded (peek) state lets riders keep an eye on the map behind
    // the sheet, while a swipe-up reveals the full timeline. Selection/merge mode
    // still works in either snap state.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val statistics = remember(rides) { computeRideStatistics(rides) }

    // Active rides drive the timeline; archived rides are tucked into a collapsible
    // section so they stay out of the way but remain restorable.
    val activeRides = remember(rides) { rides.filterNot { it.isArchived } }
    val archivedRides = remember(rides) { rides.filter { it.isArchived } }
    var showArchived by remember { mutableStateOf(false) }

    // Two mutually-exclusive multi-select purposes reuse the same checkbox list:
    // exporting to GPX and merging into one ride.
    var selectionMode by remember { mutableStateOf(false) }
    var mergeMode by remember { mutableStateOf(false) }
    val inSelection = selectionMode || mergeMode
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var showDestinationDialog by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }

    val selectedRides = remember(rides, selectedIds) { rides.filter { it.id in selectedIds } }

    fun exitSelection() {
        selectionMode = false
        mergeMode = false
        selectedIds = emptySet()
    }

    // Back exits the selection first (rather than closing the whole sheet).
    BackHandler(enabled = inSelection) { exitSelection() }

    if (showDestinationDialog) {
        ExportDestinationDialog(
            onShare = {
                showDestinationDialog = false
                // Always one file per ride: a combined GPX would be read as a single
                // activity by Strava/Komoot/Garmin (their upload = one file → one
                // activity), so multiple rides in one file are merged into one.
                onExportRides(selectedRides, false, false)
                exitSelection()
            },
            onSave = {
                showDestinationDialog = false
                onExportRides(selectedRides, false, true)
                exitSelection()
            },
            onDismiss = { showDestinationDialog = false }
        )
    }

    if (showMergeDialog && selectedRides.size >= 2) {
        MergeRidesDialog(
            rides = selectedRides,
            onConfirm = { name ->
                showMergeDialog = false
                onMergeRides(selectedRides.sortedBy { it.startedAt }.map { it.id }, name)
                exitSelection()
            },
            onDismiss = { showMergeDialog = false }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 8.dp)
        ) {
            // Top bar: title + overflow menu (Import / Export / Merge) in browse
            // mode, or the selection title + close action while picking rides.
            RidesTopBar(
                title = when {
                    mergeMode -> stringResource(R.string.ride_merge_select_hint)
                    selectionMode -> stringResource(R.string.ride_export_select_hint)
                    else -> stringResource(R.string.rides_title)
                },
                subtitle = when {
                    mergeMode && selectedIds.size < 2 ->
                        stringResource(R.string.ride_merge_min_selection)
                    mergeMode ->
                        stringResource(R.string.ride_merge_selected_count, selectedIds.size)
                    selectionMode ->
                        stringResource(R.string.ride_export_selected_count, selectedIds.size)
                    rides.isEmpty() -> null
                    // The stats card owns the authoritative "all rides" total, so the
                    // header only counts the rides shown in the timeline to avoid the
                    // two numbers appearing to contradict.
                    else -> stringResource(R.string.rides_active_count, activeRides.size)
                },
                inSelection = inSelection,
                canExport = rides.isNotEmpty(),
                canMerge = rides.size >= 2,
                onImport = onImport,
                onStartExport = { selectionMode = true },
                onStartMerge = { mergeMode = true },
                onCloseSelection = { exitSelection() }
            )

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                // "VeloSpot Wrapped" recap — an elegant accent banner rather than a
                // full-width button. Hidden while picking rides.
                if (!inSelection && rides.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    WrappedBanner(onClick = onOpenWrapped)
                }

                Spacer(Modifier.height(12.dp))

                if (rides.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = stringResource(R.string.rides_empty_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.rides_empty_text),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 460.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (!inSelection) {
                            item(key = "ride-statistics") {
                                StatisticsHeader(stats = statistics)
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        // Active timeline (in selection mode every ride is selectable).
                        val listRides = if (inSelection) rides else activeRides
                        items(listRides, key = { it.id }) { ride ->
                            RideListItem(
                                ride = ride,
                                dateLabel = dateFormat.format(Date(ride.startedAt)),
                                selectable = inSelection,
                                selected = ride.id in selectedIds,
                                onClick = {
                                    if (inSelection) {
                                        selectedIds =
                                            if (ride.id in selectedIds) selectedIds - ride.id
                                            else selectedIds + ride.id
                                    } else {
                                        onSelectRide(ride)
                                    }
                                }
                            )
                        }
                        // Collapsible "Archived" section (hidden while selecting).
                        if (!inSelection && archivedRides.isNotEmpty()) {
                            item(key = "archived-toggle") {
                                TextButton(
                                    onClick = { showArchived = !showArchived },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = if (showArchived)
                                            stringResource(R.string.rides_archived_hide)
                                        else
                                            stringResource(R.string.rides_archived_show, archivedRides.size)
                                    )
                                }
                            }
                            if (showArchived) {
                                items(archivedRides, key = { "archived-" + it.id }) { ride ->
                                    RideListItem(
                                        ride = ride,
                                        dateLabel = dateFormat.format(Date(ride.startedAt)),
                                        selectable = false,
                                        selected = false,
                                        onClick = { onSelectRide(ride) }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    // Selection action bar.
                    if (inSelection) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { exitSelection() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    stringResource(
                                        if (mergeMode) R.string.ride_merge_cancel
                                        else R.string.ride_export_cancel
                                    )
                                )
                            }
                            if (mergeMode) {
                                Button(
                                    onClick = {
                                        if (selectedRides.size >= 2) showMergeDialog = true
                                    },
                                    enabled = selectedIds.size >= 2,
                                    modifier = Modifier.weight(1f)
                                ) { Text(stringResource(R.string.ride_merge_confirm)) }
                            } else {
                                Button(
                                    onClick = {
                                        if (selectedRides.isNotEmpty()) showDestinationDialog = true
                                    },
                                    enabled = selectedIds.isNotEmpty(),
                                    modifier = Modifier.weight(1f)
                                ) { Text(stringResource(R.string.ride_export_confirm)) }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

/**
 * A `TopAppBar`-style header for the "My rides" sheet.
 *
 * In browse mode it shows the sheet title/subtitle and an overflow (three-dots)
 * [IconButton] whose [DropdownMenu] carries the Import / Export / Merge actions —
 * moving them out of the scrolling content reclaims vertical space. Export needs
 * at least one ride ([canExport]) and Merge at least two ([canMerge]). While
 * picking rides ([inSelection]) it swaps to the selection title plus a close
 * action, mirroring the previous behaviour.
 */
@Composable
private fun RidesTopBar(
    title: String,
    subtitle: String?,
    inSelection: Boolean,
    canExport: Boolean,
    canMerge: Boolean,
    onImport: () -> Unit,
    onStartExport: () -> Unit,
    onStartMerge: () -> Unit,
    onCloseSelection: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SheetHeader(
            modifier = Modifier.weight(1f),
            title = title,
            subtitle = subtitle
        )
        if (inSelection) {
            IconButton(onClick = onCloseSelection) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.common_close)
                )
            }
        } else {
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.rides_more_actions)
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ride_import)) },
                        leadingIcon = {
                            Icon(Icons.Default.FileDownload, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onImport()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ride_export)) },
                        leadingIcon = {
                            Icon(Icons.Default.Upload, contentDescription = null)
                        },
                        enabled = canExport,
                        onClick = {
                            menuExpanded = false
                            onStartExport()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ride_merge)) },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Filled.CallMerge, contentDescription = null)
                        },
                        enabled = canMerge,
                        onClick = {
                            menuExpanded = false
                            onStartMerge()
                        }
                    )
                }
            }
        }
    }
}

/**
 * A compact, tappable accent banner for the "VeloSpot Wrapped" recap. Uses the
 * primary-container colour pair, a sparkle icon, a short title and a trailing
 * chevron — deliberately not a full-width button so it stays elegant.
 */
@Composable
private fun WrappedBanner(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null)
            Text(
                text = stringResource(R.string.wrapped_open_action),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null
            )
        }
    }
}

/**
 * The statistics summary rendered as a compact elevated card. Delegates the
 * expandable stats body (count • total km, share, expand/collapse) to
 * [RideStatisticsSection].
 */
@Composable
private fun StatisticsHeader(stats: de.velospot.core.stats.RideStatistics) {
    RideStatisticsSection(stats = stats)
}


/** Dialog choosing the export destination: share via another app or save to a file. */
@Composable
private fun ExportDestinationDialog(
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ride_export_destination_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ride_export_save))
                }
                OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ride_export_share))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ride_export_cancel))
            }
        }
    )
}

/**
 * Confirmation dialog for merging the selected [rides] into one. Lets the rider
 * name the merged ride (pre-filled with the earliest ride's name), previews the
 * combined totals (distance, duration, elevation, time span, segment count) and —
 * when the sources were recorded with different bikes — warns that the merged ride
 * keeps no bike profile. Preview figures are summed from the track-free summaries;
 * the persisted ride's elevation is recomputed by `RideMerger` on confirm.
 */
@Composable
private fun MergeRidesDialog(
    rides: List<RecordedRideSummary>,
    onConfirm: (name: String) -> Unit,
    onDismiss: () -> Unit
) {
    val sorted = remember(rides) { rides.sortedBy { it.startedAt } }
    var name by remember(rides) {
        mutableStateOf(sorted.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: "")
    }

    val distanceMeters = remember(rides) { rides.sumOf { it.distanceMeters } }
    val durationSeconds = remember(rides) { rides.sumOf { it.elapsedSeconds } }
    val elevationGain = remember(rides) { rides.sumOf { it.elevationGainMeters } }
    val timeSpanSeconds = remember(rides) {
        ((rides.maxOf { it.endedAt } - rides.minOf { it.startedAt }).coerceAtLeast(0L)) / 1000L
    }
    // "Shares one profile" also holds when every source is untagged (all null).
    val sharesSingleProfile = remember(rides) {
        rides.map { it.bikeProfileId }.distinct().size == 1
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ride_merge_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.ride_merge_dialog_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ride_merge_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.ride_merge_preview_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                MergePreviewRow(
                    label = stringResource(R.string.ride_merge_preview_distance),
                    value = formatRideDistance(distanceMeters)
                )
                MergePreviewRow(
                    label = stringResource(R.string.ride_merge_preview_duration),
                    value = formatRideDuration(durationSeconds)
                )
                MergePreviewRow(
                    label = stringResource(R.string.ride_merge_preview_elevation),
                    value = formatRideElevation(elevationGain)
                )
                MergePreviewRow(
                    label = stringResource(R.string.ride_merge_preview_timespan),
                    value = formatRideDuration(timeSpanSeconds)
                )
                MergePreviewRow(
                    label = stringResource(R.string.ride_merge_preview_segments),
                    value = rides.size.toString()
                )
                if (!sharesSingleProfile) {
                    RideBadge(
                        text = stringResource(R.string.ride_merge_different_profiles),
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.trim()) }) {
                Text(stringResource(R.string.ride_merge_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ride_merge_cancel))
            }
        }
    )
}

/** A single "label … value" row inside the merge preview. */
@Composable
private fun MergePreviewRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * A single ride row. A slim Material3 [ListItem] replaces the old per-ride card:
 * the ride name/place is the headline ([titleMedium]-ish), the date + distance the
 * supporting line ([bodySmall]) and the duration/speed sit right-aligned in the
 * trailing slot ([labelLarge]). A route/sparkline icon leads in browse mode, a
 * [Checkbox] in selection mode. Tapping the row opens the ride or toggles its
 * selection; selected rows tint with the secondary-container colour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RideListItem(
    ride: RecordedRideSummary,
    dateLabel: String,
    selectable: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    val rideName = ride.name?.takeIf { it.isNotBlank() }
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                             else Color.Transparent
        ),
        leadingContent = {
            if (selectable) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            } else {
                Icon(
                    imageVector = Icons.Default.Timeline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        headlineContent = {
            Text(
                text = rideName ?: formatRideDistance(ride.distanceMeters),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        supportingContent = {
            Column {
                // Always show "date • distance" so named and unnamed rides read the
                // same way; the distance is no longer dropped for unnamed recordings.
                Text(
                    text = "$dateLabel • ${formatRideDistance(ride.distanceMeters)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Indicators: a mock-recording badge and/or an archived badge.
                if (ride.isMock || ride.isArchived) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (ride.isMock) {
                            RideBadge(
                                text = stringResource(R.string.ride_mock_badge),
                                container = MaterialTheme.colorScheme.tertiaryContainer,
                                content = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        if (ride.isArchived) {
                            RideBadge(
                                text = stringResource(R.string.ride_archived_badge),
                                container = MaterialTheme.colorScheme.surfaceContainerHigh,
                                content = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatRideDuration(ride.elapsedSeconds),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatRideSpeed(ride.avgSpeedMps),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

/**
 * Tiny pill-shaped label used to flag a ride in the timeline (e.g. a mock
 * recording or an archived ride).
 */
@Composable
private fun RideBadge(text: String, container: Color, content: Color) {
    androidx.compose.material3.Surface(
        color = container,
        contentColor = content,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}



