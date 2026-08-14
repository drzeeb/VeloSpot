package de.velospot.feature.wrapped.scheduler

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.velospot.domain.repository.RecordedRidesRepository
import de.velospot.feature.wrapped.data.WrappedScheduleDataStore
import de.velospot.feature.wrapped.domain.WrappedRepository
import de.velospot.feature.wrapped.engine.WrappedEngine
import de.velospot.feature.wrapped.engine.WrappedScheduleMath
import kotlinx.coroutines.flow.first

/**
 * The self-rescheduling background worker that generates one scheduled "VeloSpot
 * Wrapped" report.
 *
 * On each run it: resolves the due [de.velospot.feature.wrapped.domain.WrappedPeriod]
 * for the current schedule, pulls the ride aggregates, runs the pure [WrappedEngine]
 * and — on a non-null report — persists it and posts a notification. An **empty
 * period is skipped** (no report, no notification), as is a bucket already stored
 * (dedupe). Whatever the outcome, it always asks [WrappedScheduler] to enqueue the
 * next occurrence, so the schedule keeps ticking.
 *
 * Injected via [HiltWorker] + the app's `HiltWorkerFactory` (see BaseApplication).
 * The branch decision is factored into the pure [WrappedWorkDecision] for testing.
 */
@HiltWorker
internal class WrappedWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val ridesRepository: RecordedRidesRepository,
    private val wrappedRepository: WrappedRepository,
    private val scheduleDataStore: WrappedScheduleDataStore,
    private val scheduler: WrappedScheduler,
    private val notifier: WrappedNotifier
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            val now = System.currentTimeMillis()
            val schedule = scheduleDataStore.schedule.first()

            if (!schedule.enabled) {
                // Nothing to generate; the reschedule below cancels pending work.
                return Result.success()
            }

            val period = WrappedScheduleMath.periodForFire(schedule, now)
            val alreadyExists = wrappedRepository.getReportForPeriod(period) != null

            if (!alreadyExists) {
                val rides = ridesRepository.getRideSummariesFlow().first()
                val report = WrappedEngine.build(rides = rides, period = period, now = now)
                when (
                    WrappedWorkDecision.decide(
                        enabled = true,
                        alreadyExists = false,
                        hasReport = report != null
                    )
                ) {
                    // A fresh report was built ⇒ always save it; notify only when the
                    // user left the "notify when created" toggle on.
                    WrappedWorkOutcome.SAVE_AND_NOTIFY -> {
                        wrappedRepository.saveReport(report!!)
                        if (schedule.notifyOnGenerate) {
                            notifier.notifyNewReport(report)
                        }
                    }
                    // Empty period ⇒ skip: no report, no notification.
                    else -> Unit
                }
            }
            return Result.success()
        } finally {
            // Always enqueue the next occurrence, even if this run failed/threw, so a
            // single hiccup never silently stops the schedule forever.
            scheduler.reschedule()
        }
    }
}

