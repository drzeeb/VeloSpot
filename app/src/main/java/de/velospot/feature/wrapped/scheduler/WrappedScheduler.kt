package de.velospot.feature.wrapped.scheduler

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import de.velospot.feature.wrapped.data.WrappedScheduleDataStore
import de.velospot.feature.wrapped.domain.WrappedSchedule
import de.velospot.feature.wrapped.engine.WrappedScheduleMath
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager-based scheduler for the "VeloSpot Wrapped" background report.
 *
 * It reads the stored [WrappedSchedule], computes the next fire time with the pure
 * [WrappedScheduleMath] and enqueues a **single one-shot** [WrappedWorker] with the
 * corresponding initial delay under a stable unique work name. The worker itself
 * calls [reschedule] again on completion, so the chain **self-perpetuates** (one
 * run → enqueue the next) without a periodic worker — which lets each occurrence
 * honour DST, unequal months and a mid-cycle schedule change.
 *
 * When the schedule is disabled, [reschedule] cancels any pending work.
 */
@Singleton
internal class WrappedScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduleDataStore: WrappedScheduleDataStore
) {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /**
     * (Re)computes and enqueues the next run from the currently-stored schedule.
     * Replaces any previously-scheduled occurrence (stable unique name), and cancels
     * everything when the schedule is disabled. Safe to call repeatedly (idempotent).
     */
    suspend fun reschedule(now: Long = System.currentTimeMillis()) {
        val schedule = scheduleDataStore.schedule.first()
        val delayMillis = initialDelayMillis(schedule, now)
        if (delayMillis == null) {
            cancel()
            return
        }
        val request = OneTimeWorkRequestBuilder<WrappedWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** Alias of [reschedule] for call sites that read better as an initial "schedule". */
    suspend fun schedule(now: Long = System.currentTimeMillis()) = reschedule(now)

    /** Cancels any pending scheduled run (e.g. when the feature is turned off). */
    fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    internal companion object {
        /** Stable unique work name so re-enqueues replace the prior scheduling. */
        const val UNIQUE_WORK_NAME = "velospot_wrapped_report"
        const val WORK_TAG = "velospot_wrapped"

        /**
         * The initial delay (ms) until the next fire for [schedule] relative to
         * [now], or `null` when the schedule is disabled (⇒ no work). Never negative:
         * a fire time already at/just past [now] collapses to `0` (fire immediately).
         *
         * Pure wrapper around [WrappedScheduleMath.nextFireTime] — JVM-unit-testable.
         */
        fun initialDelayMillis(schedule: WrappedSchedule, now: Long): Long? {
            val fireTime = WrappedScheduleMath.nextFireTime(schedule, now) ?: return null
            return (fireTime - now).coerceAtLeast(0L)
        }
    }
}

