package de.velospot.feature.backup.scheduler

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import de.velospot.feature.backup.data.BackupScheduleDataStore
import de.velospot.feature.backup.domain.BackupSchedule
import de.velospot.feature.backup.engine.BackupScheduleMath
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager-based scheduler for the automatic backup, mirroring the "VeloSpot
 * Wrapped" scheduler.
 *
 * It reads the stored [BackupSchedule], computes the next fire time with the pure
 * [BackupScheduleMath] and enqueues a **single one-shot** [BackupWorker] with the
 * corresponding initial delay under a stable unique work name. The worker itself
 * calls [reschedule] again on completion, so the chain **self-perpetuates** — which
 * lets each occurrence honour DST, unequal months and a mid-cycle schedule change.
 *
 * When the schedule is disabled, [reschedule] cancels any pending work.
 */
@Singleton
class BackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduleDataStore: BackupScheduleDataStore
) {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /**
     * (Re)computes and enqueues the next run from the currently-stored schedule.
     * Replaces any previously-scheduled occurrence, and cancels everything when the
     * schedule is disabled. Safe to call repeatedly (idempotent).
     */
    suspend fun reschedule(now: Long = System.currentTimeMillis()) {
        val schedule = scheduleDataStore.schedule.first()
        val delayMillis = initialDelayMillis(schedule, now)
        if (delayMillis == null) {
            cancel()
            return
        }
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
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

    companion object {
        /** Stable unique work name so re-enqueues replace the prior scheduling. */
        const val UNIQUE_WORK_NAME = "velospot_auto_backup"
        const val WORK_TAG = "velospot_backup"

        /**
         * The initial delay (ms) until the next fire for [schedule] relative to [now],
         * or `null` when the schedule is disabled (⇒ no work). Never negative: a fire
         * time already at/just past [now] collapses to `0` (fire immediately).
         *
         * Pure wrapper around [BackupScheduleMath.nextFireTime] — JVM-unit-testable.
         */
        fun initialDelayMillis(schedule: BackupSchedule, now: Long): Long? {
            val fireTime = BackupScheduleMath.nextFireTime(schedule, now) ?: return null
            return (fireTime - now).coerceAtLeast(0L)
        }
    }
}

