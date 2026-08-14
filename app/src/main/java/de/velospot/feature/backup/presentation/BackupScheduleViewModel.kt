package de.velospot.feature.backup.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velospot.data.backup.BackupSecretStore
import de.velospot.feature.backup.data.BackupScheduleDataStore
import de.velospot.feature.backup.data.BackupScheduleMapping
import de.velospot.feature.backup.domain.BackupInterval
import de.velospot.feature.backup.domain.BackupSchedule
import de.velospot.feature.backup.engine.BackupScheduleEdits
import de.velospot.feature.backup.scheduler.BackupScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the **automatic-backup schedule settings** surface, mirroring
 * [de.velospot.feature.wrapped.presentation.WrappedScheduleViewModel].
 *
 * It exposes the stored [BackupSchedule], the chosen SAF destination tree Uri and
 * whether a passphrase is set, plus one action per editable field. Every field edit
 * persists through [BackupScheduleDataStore] and then calls [BackupScheduler.reschedule]
 * so a toggle/edit takes effect immediately. The unattended worker needs both a
 * destination folder and a passphrase, so [setEnabled] refuses to turn on until both
 * are present (see [BackupScheduleEdits.canEnable]).
 *
 * All writes run off the main thread on [Dispatchers.Default]; the persistence,
 * keystore and WorkManager plumbing they call switch to their own dispatchers.
 */
@HiltViewModel
class BackupScheduleViewModel @Inject constructor(
    private val scheduleDataStore: BackupScheduleDataStore,
    private val scheduler: BackupScheduler,
    private val secretStore: BackupSecretStore
) : ViewModel() {

    /** The current schedule, re-emitting on every persisted change. */
    val schedule: StateFlow<BackupSchedule> = scheduleDataStore.schedule
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BackupScheduleMapping.DEFAULT
        )

    /** The chosen SAF destination tree Uri (as a String), or `null` if unset. */
    val destinationTreeUri: StateFlow<String?> = scheduleDataStore.destinationTreeUri
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    private val _hasPassphrase = MutableStateFlow(false)
    /** Whether an automatic-backup passphrase is currently stored (value never exposed). */
    val hasPassphrase: StateFlow<Boolean> = _hasPassphrase.asStateFlow()

    init {
        viewModelScope.launch { _hasPassphrase.value = secretStore.hasPassphrase() }
    }

    /**
     * Enables/disables automatic backups. Enabling only takes effect when both a
     * destination folder and a passphrase are present; otherwise the request is
     * ignored (the UI keeps the switch off and hints what's missing).
     */
    fun setEnabled(enabled: Boolean) {
        if (enabled && !BackupScheduleEdits.canEnable(
                hasDestination = destinationTreeUri.value != null,
                hasPassphrase = _hasPassphrase.value
            )
        ) {
            return
        }
        update { BackupScheduleEdits.withEnabled(it, enabled) }
    }

    /** Switches the cadence (daily / weekly / monthly). */
    fun setInterval(interval: BackupInterval) = update { BackupScheduleEdits.withInterval(it, interval) }

    /** Sets the weekly day (a `Calendar.*` day constant). */
    fun setDayOfWeek(dayOfWeek: Int) = update { BackupScheduleEdits.withDayOfWeek(it, dayOfWeek) }

    /** Sets the monthly day (1–31; clamped to the month length by the maths). */
    fun setDayOfMonth(dayOfMonth: Int) = update { BackupScheduleEdits.withDayOfMonth(it, dayOfMonth) }

    /** Sets the fire time. */
    fun setTime(hour: Int, minute: Int) = update { BackupScheduleEdits.withTime(it, hour, minute) }

    /** Persists the SAF destination tree Uri (the folder the worker overwrites in). */
    fun setDestination(treeUri: String?) {
        viewModelScope.launch(Dispatchers.Default) {
            scheduleDataStore.setDestinationTreeUri(treeUri)
            // Removing the folder invalidates the enable gate — turn the schedule off.
            if (treeUri == null) disableIfArmed()
            scheduler.reschedule()
        }
    }

    /**
     * Stores (or clears, when blank) the automatic-backup passphrase. Clearing a
     * passphrase disables an armed schedule since the worker can no longer encrypt.
     */
    fun setPassphrase(passphrase: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val trimmed = passphrase.trim()
            if (trimmed.isBlank()) {
                secretStore.clear()
                _hasPassphrase.value = false
                disableIfArmed()
            } else {
                secretStore.setPassphrase(trimmed)
                _hasPassphrase.value = true
            }
            scheduler.reschedule()
        }
    }

    /** Turns an enabled schedule off when its enable pre-conditions no longer hold. */
    private suspend fun disableIfArmed() {
        val current = scheduleDataStore.schedule.first()
        if (current.enabled) {
            scheduleDataStore.setSchedule(BackupScheduleEdits.withEnabled(current, false))
        }
    }

    /**
     * Reads the current schedule, applies [transform], persists it when it changed
     * and always re-arms the scheduler. Runs off the main thread.
     */
    private fun update(transform: (BackupSchedule) -> BackupSchedule) {
        viewModelScope.launch(Dispatchers.Default) {
            val current = scheduleDataStore.schedule.first()
            val updated = transform(current)
            if (updated != current) {
                scheduleDataStore.setSchedule(updated)
            }
            scheduler.reschedule()
        }
    }
}

