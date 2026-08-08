package de.velospot.feature.wrapped.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velospot.feature.wrapped.data.WrappedScheduleDataStore
import de.velospot.feature.wrapped.data.WrappedScheduleMapping
import de.velospot.feature.wrapped.domain.WrappedInterval
import de.velospot.feature.wrapped.domain.WrappedSchedule
import de.velospot.feature.wrapped.engine.WrappedScheduleEdits
import de.velospot.feature.wrapped.scheduler.WrappedScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the "VeloSpot Wrapped" **schedule settings** surface: it exposes the
 * stored [WrappedSchedule] and one action per editable field. Every action
 * persists the change through [WrappedScheduleDataStore] and then calls
 * [WrappedScheduler.reschedule] so a toggle/edit takes effect immediately (the
 * dormant background scheduler only runs once a real user enables it here).
 *
 * All writes run off the main thread on [Dispatchers.Default]; the persistence and
 * WorkManager plumbing they call switch to their own dispatchers internally.
 * Rescheduling is idempotent, so calling it after every edit is safe.
 */
@HiltViewModel
internal class WrappedScheduleViewModel @Inject constructor(
    private val scheduleDataStore: WrappedScheduleDataStore,
    private val scheduler: WrappedScheduler
) : ViewModel() {

    /** The current schedule, re-emitting on every persisted change. */
    val schedule: StateFlow<WrappedSchedule> = scheduleDataStore.schedule
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = WrappedScheduleMapping.DEFAULT
        )

    /** Enables/disables automatic Wrapped generation. */
    fun setEnabled(enabled: Boolean) = update { WrappedScheduleEdits.withEnabled(it, enabled) }

    /** Switches the cadence (daily / weekly / monthly). */
    fun setInterval(interval: WrappedInterval) = update { WrappedScheduleEdits.withInterval(it, interval) }

    /** Sets the weekly day (a `Calendar.*` day constant). */
    fun setDayOfWeek(dayOfWeek: Int) = update { WrappedScheduleEdits.withDayOfWeek(it, dayOfWeek) }

    /** Sets the monthly day (1–31; clamped to the month length by the maths). */
    fun setDayOfMonth(dayOfMonth: Int) = update { WrappedScheduleEdits.withDayOfMonth(it, dayOfMonth) }

    /** Sets the fire time. */
    fun setTime(hour: Int, minute: Int) = update { WrappedScheduleEdits.withTime(it, hour, minute) }

    /**
     * Reads the current schedule, applies [transform], persists it when it changed
     * and always re-arms the scheduler (a no-op-ish re-enqueue if unchanged). Runs
     * off the main thread.
     */
    private fun update(transform: (WrappedSchedule) -> WrappedSchedule) {
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

