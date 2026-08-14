package de.velospot.feature.map.presentation.sheets

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velospot.R
import de.velospot.feature.backup.domain.BackupInterval
import de.velospot.feature.backup.domain.BackupSchedule
import de.velospot.feature.backup.engine.BackupScheduleEdits
import de.velospot.feature.backup.engine.BackupScheduleMath
import de.velospot.feature.wrapped.engine.WrappedScheduleFormat
import de.velospot.feature.backup.presentation.BackupScheduleViewModel
import java.text.DateFormatSymbols
import java.util.Calendar

/**
 * The **automatic backup** schedule-settings sheet, modelled on the "VeloSpot
 * Wrapped" schedule sheet.
 *
 * Offers an enable switch (gated on a folder + passphrase being set), an interval
 * selector with the matching day picker, a time picker, a destination-folder row
 * (SAF `OpenDocumentTree`, persisting the permission), a passphrase field and a
 * live next-run preview. Each change persists and re-arms the scheduler through
 * [BackupScheduleViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackupScheduleSettingsSheet(
    onDismiss: () -> Unit,
    viewModel: BackupScheduleViewModel = hiltViewModel()
) {
    val schedule by viewModel.schedule.collectAsStateWithLifecycle()
    val destinationTreeUri by viewModel.destinationTreeUri.collectAsStateWithLifecycle()
    val hasPassphrase by viewModel.hasPassphrase.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp

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
                text = stringResource(R.string.backup_schedule_action),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            BackupScheduleContent(
                schedule = schedule,
                destinationTreeUri = destinationTreeUri,
                hasPassphrase = hasPassphrase,
                onSetEnabled = viewModel::setEnabled,
                onSetInterval = viewModel::setInterval,
                onSetDayOfWeek = viewModel::setDayOfWeek,
                onSetDayOfMonth = viewModel::setDayOfMonth,
                onSetTime = viewModel::setTime,
                onSetDestination = viewModel::setDestination,
                onSetPassphrase = viewModel::setPassphrase
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun BackupScheduleContent(
    schedule: BackupSchedule,
    destinationTreeUri: String?,
    hasPassphrase: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onSetInterval: (BackupInterval) -> Unit,
    onSetDayOfWeek: (Int) -> Unit,
    onSetDayOfMonth: (Int) -> Unit,
    onSetTime: (Int, Int) -> Unit,
    onSetDestination: (String?) -> Unit,
    onSetPassphrase: (String) -> Unit
) {
    val context = LocalContext.current
    val canEnable = BackupScheduleEdits.canEnable(
        hasDestination = destinationTreeUri != null,
        hasPassphrase = hasPassphrase
    )

    // Folder picker (SAF tree) — persists the read/write permission grant so the
    // unattended worker can keep writing to the chosen folder across reboots.
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            onSetDestination(uri.toString())
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // ── Enable switch ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.backup_schedule_enable),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(R.string.backup_schedule_overwrite_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = schedule.enabled,
                enabled = canEnable || schedule.enabled,
                onCheckedChange = onSetEnabled
            )
        }

        if (!canEnable) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.backup_schedule_needs_folder_passphrase),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // ── Destination folder ────────────────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        SectionLabel(stringResource(R.string.backup_schedule_folder))
        FolderRow(
            treeUri = destinationTreeUri,
            onPick = { folderLauncher.launch(null) }
        )

        // ── Passphrase ────────────────────────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        SectionLabel(stringResource(R.string.backup_schedule_passphrase))
        PassphraseField(hasPassphrase = hasPassphrase, onSet = onSetPassphrase)

        // ── Interval selector ─────────────────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        SectionLabel(stringResource(R.string.backup_schedule_interval_label))
        IntervalSelector(selected = schedule.interval, onSelect = onSetInterval)

        // ── Day picker (weekly / monthly) ─────────────────────────────────────
        when (schedule.interval) {
            BackupInterval.DAILY -> Unit
            BackupInterval.WEEKLY -> {
                Spacer(Modifier.height(16.dp))
                SectionLabel(stringResource(R.string.wrapped_schedule_day_of_week_label))
                DayOfWeekPicker(selected = schedule.dayOfWeek, onSelect = onSetDayOfWeek)
            }
            BackupInterval.MONTHLY -> {
                Spacer(Modifier.height(16.dp))
                SectionLabel(stringResource(R.string.wrapped_schedule_day_of_month_label))
                DayOfMonthPicker(selected = schedule.dayOfMonth, onSelect = onSetDayOfMonth)
                if (schedule.dayOfMonth >= 29) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.wrapped_schedule_day_of_month_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ── Time picker ───────────────────────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        SectionLabel(stringResource(R.string.backup_schedule_time))
        TimeRow(hour = schedule.hour, minute = schedule.minute, onSetTime = onSetTime)

        // ── Next-run preview ──────────────────────────────────────────────────
        val nextFireLabel = remember(schedule) {
            WrappedScheduleFormat.formatNextFire(
                BackupScheduleMath.nextFireTime(schedule, System.currentTimeMillis())
            )
        }
        if (schedule.enabled && nextFireLabel != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.backup_schedule_next_run, nextFireLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun FolderRow(treeUri: String?, onPick: () -> Unit) {
    val context = LocalContext.current
    val label = remember(treeUri) {
        treeUri?.let { uri -> resolveTreeDisplayName(context, uri) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label ?: stringResource(R.string.backup_schedule_folder_none),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun PassphraseField(hasPassphrase: Boolean, onSet: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                value = it
                onSet(it)
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            label = { Text(stringResource(R.string.backup_passphrase_field)) },
            modifier = Modifier.fillMaxWidth()
        )
        if (hasPassphrase && value.isBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.backup_schedule_passphrase_set),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntervalSelector(
    selected: BackupInterval,
    onSelect: (BackupInterval) -> Unit
) {
    val options = remember {
        listOf(
            BackupInterval.DAILY to R.string.backup_schedule_interval_daily,
            BackupInterval.WEEKLY to R.string.backup_schedule_interval_weekly,
            BackupInterval.MONTHLY to R.string.backup_schedule_interval_monthly
        )
    }
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (interval, labelRes) ->
            SegmentedButton(
                selected = selected == interval,
                onClick = { onSelect(interval) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
            ) {
                Text(stringResource(labelRes))
            }
        }
    }
}

/** Monday-first weekday chips, labelled with the platform's localized short names. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayOfWeekPicker(
    selected: Int,
    onSelect: (Int) -> Unit
) {
    val locale = LocalLocale.current.platformLocale
    val shortWeekdays = remember(locale) { DateFormatSymbols.getInstance(locale).shortWeekdays }
    val days = remember {
        listOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        days.forEach { day ->
            FilterChip(
                selected = selected == day,
                onClick = { onSelect(day) },
                label = { Text(shortWeekdays.getOrNull(day).orEmpty()) }
            )
        }
    }
}

/** Horizontally scrollable 1–31 chips; a tap commits (and reschedules) the value. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayOfMonthPicker(
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        (1..31).forEach { day ->
            FilterChip(
                selected = selected == day,
                onClick = { onSelect(day) },
                label = { Text(day.toString()) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeRow(
    hour: Int,
    minute: Int,
    onSetTime: (Int, Int) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Schedule,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "%02d:%02d".format(hour, minute),
            style = MaterialTheme.typography.bodyLarge
        )
    }

    if (showDialog) {
        val timeState = rememberTimePickerState(initialHour = hour, initialMinute = minute)
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    onSetTime(timeState.hour, timeState.minute)
                    showDialog = false
                }) { Text(stringResource(R.string.wrapped_schedule_time_set)) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            text = { TimePicker(state = timeState) }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
}

/**
 * The human-readable display name of a SAF tree [treeUriString], via
 * [DocumentsContract] (avoids pulling in the `documentfile` support library).
 * Returns `null` when it can't be resolved.
 */
private fun resolveTreeDisplayName(
    context: android.content.Context,
    treeUriString: String
): String? = runCatching {
    val treeUri = Uri.parse(treeUriString)
    val docUri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri)
    )
    context.contentResolver.query(
        docUri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null, null, null
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull()
