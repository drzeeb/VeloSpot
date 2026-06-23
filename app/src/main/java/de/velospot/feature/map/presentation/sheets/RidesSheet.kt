package de.velospot.feature.map.presentation.sheets

import de.velospot.feature.map.presentation.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.domain.model.RecordedRide
import de.velospot.feature.map.presentation.ride.RideStatisticsSection
import de.velospot.feature.map.presentation.ride.computeRideStatistics
import java.text.DateFormat
import java.util.Date

/**
 * The "My rides" timeline: a scrollable list of past recorded rides with their
 * date, distance and duration. Tapping a ride opens its [RideDetailSheet].
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
    rides: List<RecordedRide>,
    onDismiss: () -> Unit,
    onSelectRide: (RecordedRide) -> Unit,
    onExportRides: (rides: List<RecordedRide>, combine: Boolean, save: Boolean) -> Unit,
    onImport: () -> Unit
) {
    // Always open fully expanded (no half-height peek) so the whole list shows.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val statistics = remember(rides) { computeRideStatistics(rides) }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var showModeDialog by remember { mutableStateOf(false) }
    var showDestinationDialog by remember { mutableStateOf(false) }
    // Chosen file layout (combine vs. separate) carried into the destination dialog.
    var pendingCombine by remember { mutableStateOf(true) }

    val selectedRides = remember(rides, selectedIds) { rides.filter { it.id in selectedIds } }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    // Back exits the selection first (rather than closing the whole sheet).
    BackHandler(enabled = selectionMode) { exitSelection() }

    if (showModeDialog) {
        ExportModeDialog(
            onCombined = {
                showModeDialog = false
                pendingCombine = true
                showDestinationDialog = true
            },
            onSeparate = {
                showModeDialog = false
                pendingCombine = false
                showDestinationDialog = true
            },
            onDismiss = { showModeDialog = false }
        )
    }

    if (showDestinationDialog) {
        ExportDestinationDialog(
            onShare = {
                showDestinationDialog = false
                onExportRides(selectedRides, pendingCombine, false)
                exitSelection()
            },
            onSave = {
                showDestinationDialog = false
                onExportRides(selectedRides, pendingCombine, true)
                exitSelection()
            },
            onDismiss = { showDestinationDialog = false }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            SheetHeader(
                title = if (selectionMode) stringResource(R.string.ride_export_select_hint)
                        else stringResource(R.string.rides_title),
                subtitle = when {
                    selectionMode -> stringResource(R.string.ride_export_selected_count, selectedIds.size)
                    rides.isEmpty() -> null
                    else -> stringResource(R.string.rides_count, rides.size)
                }
            )

            // Import / Export actions (hidden while picking rides to export).
            if (!selectionMode) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onImport,
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.ride_import)) }
                    OutlinedButton(
                        onClick = { selectionMode = true },
                        enabled = rides.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.ride_export)) }
                }
            }

            Spacer(Modifier.height(16.dp))

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
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!selectionMode) {
                        item(key = "ride-statistics") {
                            RideStatisticsSection(stats = statistics)
                        }
                    }
                    items(rides, key = { it.id }) { ride ->
                        RideListItem(
                            ride = ride,
                            dateLabel = dateFormat.format(Date(ride.startedAt)),
                            selectable = selectionMode,
                            selected = ride.id in selectedIds,
                            onClick = {
                                if (selectionMode) {
                                    selectedIds =
                                        if (ride.id in selectedIds) selectedIds - ride.id
                                        else selectedIds + ride.id
                                } else {
                                    onSelectRide(ride)
                                }
                            }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                // Selection action bar.
                if (selectionMode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { exitSelection() },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.ride_export_cancel)) }
                        Button(
                            onClick = {
                                when (selectedRides.size) {
                                    0 -> Unit
                                    1 -> {
                                        pendingCombine = true
                                        showDestinationDialog = true
                                    }
                                    else -> showModeDialog = true
                                }
                            },
                            enabled = selectedIds.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.ride_export_confirm)) }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

/** Dialog asking whether multiple exported rides go into one file or separate files. */
@Composable
private fun ExportModeDialog(
    onCombined: () -> Unit,
    onSeparate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ride_export_mode_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.ride_export_mode_message))
                Spacer(Modifier.height(2.dp))
                Button(onClick = onCombined, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ride_export_mode_combined))
                }
                OutlinedButton(onClick = onSeparate, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ride_export_mode_separate))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RideListItem(
    ride: RecordedRide,
    dateLabel: String,
    selectable: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                             else MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectable) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            } else {
                Icon(
                    imageVector = Icons.Default.Timeline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                val rideName = ride.name?.takeIf { it.isNotBlank() }
                Text(
                    text = rideName ?: formatRideDistance(ride.distanceMeters),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (rideName != null)
                        "$dateLabel • ${formatRideDistance(ride.distanceMeters)}"
                    else dateLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatRideDuration(ride.elapsedSeconds),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatRideSpeed(ride.avgSpeedMps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}



