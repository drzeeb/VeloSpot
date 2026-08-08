package de.velospot.feature.wrapped.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velospot.R
import de.velospot.domain.repository.RecordedRidesRepository
import de.velospot.feature.wrapped.domain.WrappedPeriod
import de.velospot.feature.wrapped.domain.WrappedReport
import de.velospot.feature.wrapped.domain.WrappedRepository
import de.velospot.feature.wrapped.engine.WrappedEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Drives the whole "VeloSpot Wrapped" Phase-4 UI: the history list, the ad-hoc
 * date-range generator, the currently-open Story and the notification deep link.
 *
 * All coroutine work runs off the main thread. Follows the app's existing one-shot
 * message pattern ([messageRes] consumed by the UI as a snackbar/toast). Shares a
 * single instance across the map screen's composables (history entry, Story host)
 * because they resolve `hiltViewModel()` against the same back-stack entry.
 */
@HiltViewModel
internal class WrappedViewModel @Inject constructor(
    private val repository: WrappedRepository,
    private val ridesRepository: RecordedRidesRepository,
    private val openBus: WrappedOpenBus
) : ViewModel() {

    /** All stored reports, newest generated first (reactive). */
    val reports: StateFlow<List<WrappedReport>> = repository.observeReports()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _homeVisible = MutableStateFlow(false)

    /** Whether the Wrapped "home" (history) sheet is showing. */
    val homeVisible: StateFlow<Boolean> = _homeVisible.asStateFlow()

    private val _openStory = MutableStateFlow<WrappedReport?>(null)

    /** The report whose full-screen Story is currently open, or `null`. */
    val openStory: StateFlow<WrappedReport?> = _openStory.asStateFlow()

    private val _messageRes = MutableStateFlow<Int?>(null)

    /** One-shot user message (e.g. "no rides in that range"); `null` when none. */
    val messageRes: StateFlow<Int?> = _messageRes.asStateFlow()

    init {
        // Consume a notification deep link once the ViewModel exists. The retained
        // StateFlow delivers an id posted before this collector started (cold start).
        viewModelScope.launch {
            openBus.pending.collect { id ->
                if (id != null) {
                    openReport(id)
                    openBus.consume()
                }
            }
        }
    }

    /** Opens the Wrapped history sheet. */
    fun openHome() { _homeVisible.value = true }

    /** Closes the Wrapped history sheet. */
    fun closeHome() { _homeVisible.value = false }

    /** Loads and opens the Story for the stored report with [id] (if it exists). */
    fun openReport(id: String) {
        viewModelScope.launch {
            repository.getReport(id)?.let { _openStory.value = it }
        }
    }

    /** Opens the Story for an already-loaded [report] (e.g. tapped in the history). */
    fun openStory(report: WrappedReport) { _openStory.value = report }

    /** Dismisses the open Story. */
    fun closeStory() { _openStory.value = null }

    /**
     * Builds a Wrapped for the custom range `[fromMillis, toMillisExclusive)` from
     * the current rides. Saves and opens it when non-empty; otherwise surfaces a
     * friendly "no rides" message and saves nothing.
     */
    fun generateForRange(fromMillis: Long, toMillisExclusive: Long) {
        viewModelScope.launch {
            val rides = ridesRepository.getRideSummariesFlow().first()
            val period = WrappedPeriod.custom(fromMillis, toMillisExclusive)
            val report = withContext(Dispatchers.Default) {
                WrappedEngine.build(rides = rides, period = period)
            }
            if (report == null) {
                _messageRes.value = R.string.wrapped_range_empty
            } else {
                repository.saveReport(report)
                _openStory.value = report
            }
        }
    }

    /** Deletes the stored report with [id]; closes its Story if it was open. */
    fun deleteReport(id: String) {
        viewModelScope.launch {
            repository.deleteReport(id)
            if (_openStory.value?.id == id) _openStory.value = null
        }
    }

    /** Clears the one-shot message after it has been shown. */
    fun consumeMessage() { _messageRes.value = null }
}




