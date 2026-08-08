package de.velospot.feature.wrapped.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velospot.R
import de.velospot.feature.wrapped.domain.WrappedInterval
import de.velospot.feature.wrapped.domain.WrappedSchedule
import de.velospot.feature.wrapped.engine.WrappedScheduleFormat
import de.velospot.feature.wrapped.engine.WrappedScheduleMath
import java.text.DateFormatSymbols
import java.util.Calendar

/**
 * The "VeloSpot Wrapped" **automatic schedule** settings sheet — the surface that
 * finally lets a real user arm the background scheduler.
 *
 * An enable switch (requesting `POST_NOTIFICATIONS` on Android 13+ when turned on),
 * an interval selector and the matching day picker (weekday / day-of-month), a time
 * picker and a live "next Wrapped" preview. Each change is persisted and re-arms the
 * scheduler through [WrappedScheduleViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WrappedScheduleSettingsSheet(
    onDismiss: () -> Unit,
    viewModel: WrappedScheduleViewModel = hiltViewModel()
) {
    val schedule by viewModel.schedule.collectAsStateWithLifecycle()
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
                text = stringResource(R.string.wrapped_schedule_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            WrappedScheduleContent(
                schedule = schedule,
                onSetEnabled = viewModel::setEnabled,
                onSetInterval = viewModel::setInterval,
                onSetDayOfWeek = viewModel::setDayOfWeek,
                onSetDayOfMonth = viewModel::setDayOfMonth,
                onSetTime = viewModel::setTime
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun WrappedScheduleContent(
    schedule: WrappedSchedule,
    onSetEnabled: (Boolean) -> Unit,
    onSetInterval: (WrappedInterval) -> Unit,
    onSetDayOfWeek: (Int) -> Unit,
    onSetDayOfMonth: (Int) -> Unit,
    onSetTime: (Int, Int) -> Unit
) {
    val context = LocalContext.current

    // Whether the last enable attempt left notifications ungranted (Android 13+).
    var notificationsDenied by remember { mutableStateOf(false) }

    // Requests POST_NOTIFICATIONS on turning the schedule on. Enabling proceeds
    // regardless of the result — reports are generated and stored either way; only
    // the notification is skipped when denied.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> notificationsDenied = !granted }

    Column(modifier = Modifier.fillMaxWidth()) {
        // ── Enable switch ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.wrapped_schedule_enable),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(R.string.wrapped_schedule_enable_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = schedule.enabled,
                onCheckedChange = { enabled ->
                    onSetEnabled(enabled)
                    if (enabled && needsNotificationPermission(context)) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    if (!enabled) notificationsDenied = false
                }
            )
        }

        if (schedule.enabled) {
            if (notificationsDenied) {
                Spacer(Modifier.height(8.dp))
                NotificationsOffHint()
            }

            Spacer(Modifier.height(16.dp))

            // ── Interval selector ─────────────────────────────────────────────
            SectionLabel(stringResource(R.string.wrapped_schedule_interval_label))
            IntervalSelector(selected = schedule.interval, onSelect = onSetInterval)

            // ── Day picker (weekly / monthly) ─────────────────────────────────
            when (schedule.interval) {
                WrappedInterval.DAILY -> Unit
                WrappedInterval.WEEKLY -> {
                    Spacer(Modifier.height(16.dp))
                    SectionLabel(stringResource(R.string.wrapped_schedule_day_of_week_label))
                    DayOfWeekPicker(selected = schedule.dayOfWeek, onSelect = onSetDayOfWeek)
                }
                WrappedInterval.MONTHLY -> {
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

            // ── Time picker ───────────────────────────────────────────────────
            Spacer(Modifier.height(16.dp))
            SectionLabel(stringResource(R.string.wrapped_schedule_time_label))
            TimeRow(hour = schedule.hour, minute = schedule.minute, onSetTime = onSetTime)

            // ── Next-fire preview ─────────────────────────────────────────────
            val nextFireLabel = remember(schedule) {
                WrappedScheduleFormat.formatNextFire(
                    WrappedScheduleMath.nextFireTime(schedule, System.currentTimeMillis())
                )
            }
            if (nextFireLabel != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.wrapped_schedule_next_fire, nextFireLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.wrapped_schedule_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NotificationsOffHint() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.NotificationsOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.wrapped_schedule_notifications_off_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntervalSelector(
    selected: WrappedInterval,
    onSelect: (WrappedInterval) -> Unit
) {
    val options = remember {
        listOf(
            WrappedInterval.DAILY to R.string.wrapped_schedule_interval_daily,
            WrappedInterval.WEEKLY to R.string.wrapped_schedule_interval_weekly,
            WrappedInterval.MONTHLY to R.string.wrapped_schedule_interval_monthly
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
    // Monday-first order to match the app's Monday-based weeks.
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
                    Text(stringResource(R.string.wrapped_range_cancel))
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

/** True when the Android 13+ runtime notification permission is not yet granted. */
private fun needsNotificationPermission(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

