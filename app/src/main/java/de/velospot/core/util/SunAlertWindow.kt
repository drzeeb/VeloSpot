package de.velospot.core.util

import java.time.Duration
import java.time.Instant

/** Which imminent solar event a [SunAlertState] refers to. */
enum class SunEventKind { SUNRISE, SUNSET }

/**
 * An active "golden hour" alert: the [kind] of the imminent event and the exact
 * [eventTime] it happens, so the UI can render a live countdown.
 */
data class SunAlertState(val kind: SunEventKind, val eventTime: Instant)

/**
 * Pure, injectable-`now` visibility rule for the golden-hour alert FAB.
 *
 * The FAB is visible **only** inside the pre-event window `[event - leadTime,
 * event]` — i.e. from `leadTime` (default 30 min) before the event up to the event
 * moment, then it hides (nothing is shown *after* the event).
 *
 * Both sunrise and sunset are evaluated:
 *  - if [now] falls inside the sunrise pre-window → [SunEventKind.SUNRISE],
 *  - if inside the sunset pre-window → [SunEventKind.SUNSET],
 *  - otherwise `null` (hide the FAB).
 *
 * `null` events (polar day / night) simply never match. If both windows somehow
 * overlap [now], the **nearer** event wins.
 *
 * @param now the current instant (injected for testability).
 * @param events the day's computed [SunTimes.SunEvents].
 * @param leadTime how long before the event the FAB starts showing (default 30 min).
 * @return the active [SunAlertState], or `null` when nothing should be shown.
 */
fun activeSunAlert(
    now: Instant,
    events: SunTimes.SunEvents,
    leadTime: Duration = Duration.ofMinutes(30)
): SunAlertState? {
    val sunrise = events.sunrise?.takeIf { inWindow(now, it, leadTime) }
        ?.let { SunAlertState(SunEventKind.SUNRISE, it) }
    val sunset = events.sunset?.takeIf { inWindow(now, it, leadTime) }
        ?.let { SunAlertState(SunEventKind.SUNSET, it) }

    return when {
        sunrise != null && sunset != null ->
            // Both match: prefer the nearer event.
            if (Duration.between(now, sunrise.eventTime).abs() <=
                Duration.between(now, sunset.eventTime).abs()
            ) sunrise else sunset
        else -> sunrise ?: sunset
    }
}

/** `true` when [now] is within `[event - leadTime, event]` (inclusive). */
private fun inWindow(now: Instant, event: Instant, leadTime: Duration): Boolean {
    val windowStart = event.minus(leadTime)
    return !now.isBefore(windowStart) && !now.isAfter(event)
}

