package de.velospot.feature.wrapped.scheduler

/**
 * The action a scheduled "VeloSpot Wrapped" run should take, decided purely from
 * three booleans so the branching is JVM-unit-testable without WorkManager, Room or
 * Android. See [WrappedWorkDecision.decide].
 */
internal enum class WrappedWorkOutcome {
    /** The schedule is off — do nothing but let the scheduler settle (cancel). */
    DISABLED,

    /** A report for this bucket already exists — skip (no duplicate, no notification). */
    SKIP_ALREADY_EXISTS,

    /** The period had no (non-mock) rides — skip: no report, no notification. */
    SKIP_EMPTY,

    /** A fresh report was built — persist it and post the notification. */
    SAVE_AND_NOTIFY
}

/** Pure decision table for [WrappedWorker], kept side-effect-free for unit tests. */
internal object WrappedWorkDecision {

    /**
     * @param enabled whether the stored schedule is currently enabled.
     * @param alreadyExists whether a report for the due period is already stored.
     * @param hasReport whether the engine produced a (non-null) report for the period
     *   (i.e. the period was non-empty). Only meaningful when [enabled] and not
     *   [alreadyExists].
     */
    fun decide(
        enabled: Boolean,
        alreadyExists: Boolean,
        hasReport: Boolean
    ): WrappedWorkOutcome = when {
        !enabled -> WrappedWorkOutcome.DISABLED
        alreadyExists -> WrappedWorkOutcome.SKIP_ALREADY_EXISTS
        !hasReport -> WrappedWorkOutcome.SKIP_EMPTY
        else -> WrappedWorkOutcome.SAVE_AND_NOTIFY
    }
}

