package de.velospot.feature.map.presentation.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velospot.R
import de.velospot.core.format.formatRideDistance
import de.velospot.core.format.formatRideElevation
import de.velospot.domain.model.BikeType
import de.velospot.feature.bikeprofiles.presentation.BikeDraft
import de.velospot.feature.bikeprofiles.presentation.BikeProfileRow
import de.velospot.feature.bikeprofiles.presentation.BikeProfilesViewModel
import de.velospot.feature.map.presentation.SheetHeader
import de.velospot.feature.map.presentation.headingSemantics

/**
 * The rider's **bike garage**: one profile per bike, each with its own ride
 * statistics, plus the quick pre-ride switch ("ride this bike next") and the
 * default-bike marker.
 *
 * Riders — the "pros" this was asked for — often own several bikes and want their
 * ride history split per bike. Tapping a bike opens the [BikeEditorSheet]; the
 * overflow menu offers set-active / set-default / edit / delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BikeGarageSheet(
    onDismiss: () -> Unit,
    viewModel: BikeProfilesViewModel = hiltViewModel()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Which bike is being created (null draft) / edited (existing row) in the editor.
    var editorDraft by remember { mutableStateOf<BikeDraft?>(null) }
    var editingRow by remember { mutableStateOf<BikeProfileRow?>(null) }
    var pendingDelete by remember { mutableStateOf<BikeProfileRow?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            SheetHeader(
                title = stringResource(R.string.bike_garage_title),
                subtitle = stringResource(R.string.bike_garage_subtitle)
            )

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    editingRow = null
                    editorDraft = BikeDraft(name = "")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.bike_garage_add))
            }

            Spacer(Modifier.height(16.dp))

            if (uiState.isEmpty) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = stringResource(R.string.bike_garage_empty_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.bike_garage_empty_text),
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
                    items(uiState.bikes, key = { it.profile.id }) { row ->
                        BikeCard(
                            row = row,
                            onEdit = {
                                editingRow = row
                                editorDraft = BikeDraft.from(row.profile)
                            },
                            onSetActive = { viewModel.setActive(row.profile.id) },
                            onSetDefault = { viewModel.setDefault(row.profile.id) },
                            onDelete = { pendingDelete = row }
                        )
                    }
                    if (uiState.unassignedRideCount > 0) {
                        item(key = "unassigned") {
                            Text(
                                text = stringResource(
                                    R.string.bike_garage_unassigned,
                                    uiState.unassignedRideCount
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    // Create / edit editor sheet.
    editorDraft?.let { draft ->
        BikeEditorSheet(
            initial = draft,
            isEditing = editingRow != null,
            onDismiss = { editorDraft = null },
            onSave = { result ->
                val row = editingRow
                if (row != null) {
                    viewModel.updateBike(row.profile.id, row.profile.createdAt, result)
                } else {
                    viewModel.addBike(result)
                }
                editorDraft = null
            }
        )
    }

    // Delete confirmation.
    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.bike_garage_delete)) },
            text = { Text(stringResource(R.string.bike_garage_delete_confirm, row.profile.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteBike(row.profile.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.bike_garage_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.bike_editor_cancel))
                }
            }
        )
    }
}

@Composable
private fun BikeCard(
    row: BikeProfileRow,
    onEdit: () -> Unit,
    onSetActive: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val profile = row.profile
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (row.isActive) MaterialTheme.colorScheme.secondaryContainer
                             else MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.DirectionsBike,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    val secondary = listOfNotNull(
                        profile.brandModelLabel,
                        stringResource(bikeTypeLabelRes(profile.type))
                    ).joinToString(" • ")
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (!row.isActive) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bike_garage_set_active)) },
                                onClick = { menuOpen = false; onSetActive() },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.DirectionsBike, null) }
                            )
                        }
                        if (!profile.isDefault) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bike_garage_set_default)) },
                                onClick = { menuOpen = false; onSetDefault() },
                                leadingIcon = { Icon(Icons.Default.Star, null) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bike_garage_edit)) },
                            onClick = { menuOpen = false; onEdit() },
                            leadingIcon = { Icon(Icons.Default.Edit, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bike_garage_delete)) },
                            onClick = { menuOpen = false; onDelete() },
                            leadingIcon = { Icon(Icons.Default.Delete, null) }
                        )
                    }
                }
            }

            // Badges: default + "ride next" active marker.
            if (profile.isDefault || row.isActive) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (profile.isDefault) {
                        BikeBadge(
                            text = stringResource(R.string.bike_garage_default_badge),
                            container = MaterialTheme.colorScheme.tertiaryContainer,
                            content = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    if (row.isActive) {
                        BikeBadge(
                            text = stringResource(R.string.bike_garage_active_hint),
                            container = MaterialTheme.colorScheme.primaryContainer,
                            content = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            val stats = row.stats
            Text(
                text = if (stats.rideCount == 0) {
                    stringResource(R.string.bike_garage_stats_empty)
                } else {
                    stringResource(
                        R.string.bike_garage_stats_line,
                        stats.rideCount,
                        formatRideDistance(stats.totalDistanceMeters),
                        formatRideElevation(stats.totalElevationGainMeters)
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Service reminder status (only when the rider set an interval).
            val nextService = row.nextServiceAtKm
            val kmUntil = row.kmUntilService
            if (nextService != null && kmUntil != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.bike_garage_service_status, kmUntil, nextService),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Create / edit form for a single bike. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BikeEditorSheet(
    initial: BikeDraft,
    isEditing: Boolean,
    onDismiss: () -> Unit,
    onSave: (BikeDraft) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.92f).dp

    var draft by remember { mutableStateOf(initial) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(
                    if (isEditing) R.string.bike_editor_edit_title
                    else R.string.bike_editor_new_title
                ),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 8.dp).headingSemantics()
            )

            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text(stringResource(R.string.bike_editor_name)) },
                placeholder = { Text(stringResource(R.string.bike_editor_name_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.bike_editor_type),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BikeType.entries.forEach { type ->
                    FilterChip(
                        selected = draft.type == type,
                        onClick = { draft = draft.copy(type = type) },
                        label = { Text(stringResource(bikeTypeLabelRes(type))) },
                        leadingIcon = if (draft.type == type) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft.brand,
                    onValueChange = { draft = draft.copy(brand = it) },
                    label = { Text(stringResource(R.string.bike_editor_brand)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = draft.model,
                    onValueChange = { draft = draft.copy(model = it) },
                    label = { Text(stringResource(R.string.bike_editor_model)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft.tireSize,
                    onValueChange = { draft = draft.copy(tireSize = it) },
                    label = { Text(stringResource(R.string.bike_editor_tire_size)) },
                    placeholder = { Text(stringResource(R.string.bike_editor_tire_size_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = draft.weightKg,
                    onValueChange = { draft = draft.copy(weightKg = it) },
                    label = { Text(stringResource(R.string.bike_editor_weight)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft.color,
                    onValueChange = { draft = draft.copy(color = it) },
                    label = { Text(stringResource(R.string.bike_editor_color)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = draft.modelYear,
                    onValueChange = { draft = draft.copy(modelYear = it) },
                    label = { Text(stringResource(R.string.bike_editor_year)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = draft.notes,
                onValueChange = { draft = draft.copy(notes = it) },
                label = { Text(stringResource(R.string.bike_editor_notes)) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = draft.serviceIntervalKm,
                onValueChange = { value -> draft = draft.copy(serviceIntervalKm = value.filter { it.isDigit() }) },
                label = { Text(stringResource(R.string.bike_editor_service_interval)) },
                placeholder = { Text(stringResource(R.string.bike_editor_service_interval_hint)) },
                supportingText = { Text(stringResource(R.string.bike_editor_service_interval_help)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { draft = draft.copy(isDefault = !draft.isDefault) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.bike_editor_default_toggle),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = draft.isDefault,
                    onCheckedChange = { draft = draft.copy(isDefault = it) }
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.bike_editor_cancel)) }
                Button(
                    onClick = { onSave(draft) },
                    enabled = draft.isValid,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.bike_editor_save)) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun BikeBadge(text: String, container: Color, content: Color) {
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/** Maps a [BikeType] to its localised label string resource. */
internal fun bikeTypeLabelRes(type: BikeType): Int = when (type) {
    BikeType.ROAD -> R.string.bike_type_road
    BikeType.MOUNTAIN -> R.string.bike_type_mountain
    BikeType.GRAVEL -> R.string.bike_type_gravel
    BikeType.TREKKING -> R.string.bike_type_trekking
    BikeType.CITY -> R.string.bike_type_city
    BikeType.EBIKE -> R.string.bike_type_ebike
    BikeType.CARGO -> R.string.bike_type_cargo
    BikeType.FOLDING -> R.string.bike_type_folding
    BikeType.BMX -> R.string.bike_type_bmx
    BikeType.OTHER -> R.string.bike_type_other
}




