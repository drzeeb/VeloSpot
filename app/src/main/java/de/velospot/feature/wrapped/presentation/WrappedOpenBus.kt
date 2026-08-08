package de.velospot.feature.wrapped.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-level hand-off for a "VeloSpot Wrapped" report the user asked to open
 * from **outside** the composed UI — currently the scheduled-report notification.
 *
 * Modelled on [de.velospot.core.gpx.GpxOpenBus]: [de.velospot.MainActivity]
 * receives the notification's `PendingIntent` — possibly on a cold start, before
 * the Wrapped UI's `WrappedViewModel` even exists — and [post]s the report id
 * here. The ViewModel collects [pending] once it is created, opens that report's
 * Story and [consume]s the value so a configuration change (rotation) does not
 * re-open it. A retained [StateFlow] (not a one-shot event) is used deliberately
 * so an id posted before any collector exists is delivered as soon as the
 * ViewModel starts observing.
 */
@Singleton
class WrappedOpenBus @Inject constructor() {

    private val _pending = MutableStateFlow<String?>(null)

    /** The report id awaiting the Story, or `null` when there is none. */
    val pending: StateFlow<String?> = _pending.asStateFlow()

    /** Publishes a report [id] the UI should open once it is ready. */
    fun post(id: String) { _pending.value = id }

    /** Clears the pending id once the Story has been opened. */
    fun consume() { _pending.value = null }

    companion object {
        /**
         * Intent extra carrying the id of the Wrapped report to open. Set by the
         * scheduled-report notification's `PendingIntent` and read by
         * [de.velospot.MainActivity], which forwards it to [post].
         */
        const val EXTRA_OPEN_WRAPPED_REPORT_ID = "de.velospot.extra.OPEN_WRAPPED_REPORT_ID"
    }
}


