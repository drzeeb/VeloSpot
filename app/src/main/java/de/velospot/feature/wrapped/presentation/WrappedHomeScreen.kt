package de.velospot.feature.wrapped.presentation

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velospot.R
import de.velospot.core.format.formatRideDistance
import de.velospot.feature.wrapped.domain.WrappedReport
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * The "VeloSpot Wrapped" history sheet: every stored report as a tappable tile
 * (period label + headline distance + generated date), plus a "create for a date
 * range" affordance. Tapping a tile opens its Story; the trash icon deletes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WrappedHomeScreen(
    reports: List<WrappedReport>,
    onOpenReport: (WrappedReport) -> Unit,
    onDeleteReport: (String) -> Unit,
    onGenerateRange: (fromMillis: Long, toMillisExclusive: Long) -> Unit,
    onOpenSchedule: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    var showRangePicker by remember { mutableStateOf(false) }

    if (showRangePicker) {
        WrappedRangePickerDialog(
            onConfirm = { from, toExclusive ->
                showRangePicker = false
                onGenerateRange(from, toExclusive)
            },
            onDismiss = { showRangePicker = false }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.wrapped_home_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onOpenSchedule) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.wrapped_schedule_settings)
                    )
                }
            }
            Text(
                text = stringResource(R.string.wrapped_home_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { showRangePicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(imageVector = Icons.Default.DateRange, contentDescription = null)
                Text(
                    text = stringResource(R.string.wrapped_create_range),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            if (reports.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = stringResource(R.string.wrapped_empty_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.wrapped_empty_text),
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
                    items(reports, key = { it.id }) { report ->
                        WrappedReportTile(
                            report = report,
                            generatedLabel = dateFormat.format(Date(report.generatedAt)),
                            onClick = { onOpenReport(report) },
                            onDelete = { onDeleteReport(report.id) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WrappedReportTile(
    report: WrappedReport,
    generatedLabel: String,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = wrappedPeriodLabel(report.period),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${formatRideDistance(report.stats.totalDistanceMeters)} • $generatedLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.wrapped_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** A Material3 date-range picker dialog; confirms with a half-open local range. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WrappedRangePickerDialog(
    onConfirm: (fromMillis: Long, toMillisExclusive: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberDateRangePickerState()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                DateRangePicker(
                    state = state,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.wrapped_range_cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    val from = state.selectedStartDateMillis
                    val to = state.selectedEndDateMillis
                    OutlinedButton(
                        enabled = from != null && to != null,
                        onClick = {
                            val f = from ?: return@OutlinedButton
                            val t = to ?: return@OutlinedButton
                            onConfirm(
                                pickerDateToLocalStartOfDay(f),
                                pickerDateToLocalEndOfDayExclusive(t)
                            )
                        }
                    ) {
                        Text(stringResource(R.string.wrapped_range_confirm))
                    }
                }
            }
        }
    }
}

/**
 * Host mounting the Wrapped history sheet, the open Story overlay and the one-shot
 * message, all driven by the shared [WrappedViewModel]. Always composed on the map
 * screen so a notification deep link (collected in the ViewModel) can open a Story
 * even from a cold start, before the user has opened the history.
 */
@Composable
internal fun WrappedHost(
    viewModel: WrappedViewModel,
    onMessage: (String) -> Unit
) {
    val homeVisible by viewModel.homeVisible.collectAsStateWithLifecycle()
    val openStory by viewModel.openStory.collectAsStateWithLifecycle()
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val messageRes by viewModel.messageRes.collectAsStateWithLifecycle()

    // Local visibility of the schedule-settings sheet, opened from the home top bar.
    // Kept here (not in the ViewModel) so the whole surface stays self-contained.
    var showSchedule by remember { mutableStateOf(false) }

    // Resolve the one-shot message at composition scope (stringResource can't run
    // inside a LaunchedEffect), mirroring the map screen's user-message pattern.
    val messageText = messageRes?.let { stringResource(it) }
    androidx.compose.runtime.LaunchedEffect(messageText) {
        messageText?.let {
            onMessage(it)
            viewModel.consumeMessage()
        }
    }

    if (homeVisible) {
        WrappedHomeScreen(
            reports = reports,
            onOpenReport = viewModel::openStory,
            onDeleteReport = viewModel::deleteReport,
            onGenerateRange = viewModel::generateForRange,
            onOpenSchedule = { showSchedule = true },
            onDismiss = viewModel::closeHome
        )
    }

    if (showSchedule) {
        WrappedScheduleSettingsSheet(onDismiss = { showSchedule = false })
    }

    openStory?.let { report ->
        WrappedStoryScreen(report = report, onDismiss = viewModel::closeStory)
    }
}

// ── Date-range conversion (picker UTC-midnight → local half-open range) ────────

/** Converts a [DateRangePicker] UTC-midnight millis to that calendar day's local 00:00. */
private fun pickerDateToLocalStartOfDay(utcMillis: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
    }.timeInMillis
}

/** The exclusive local end bound: local 00:00 of the **day after** the picked end date. */
private fun pickerDateToLocalEndOfDayExclusive(utcMillis: Long): Long {
    val start = pickerDateToLocalStartOfDay(utcMillis)
    return Calendar.getInstance().apply {
        timeInMillis = start
        add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis
}





