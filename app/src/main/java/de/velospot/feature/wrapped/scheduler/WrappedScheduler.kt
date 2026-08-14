package de.velospot.feature.wrapped.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import de.velospot.feature.wrapped.data.WrappedScheduleDataStore
import de.velospot.feature.wrapped.domain.WrappedSchedule
import de.velospot.feature.wrapped.engine.WrappedScheduleMath
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AlarmManager-based scheduler for the "VeloSpot Wrapped" background report.
 *
 * It reads the stored [WrappedSchedule], computes the next fire time with the pure
 * [WrappedScheduleMath] and arms a **single exact alarm** via
 * `AlarmManager.setAndAllowWhileIdle` targeting [WrappedAlarmReceiver]. Unlike a
 * WorkManager initial delay (deferrable by Doze → unreliable timing), the alarm
 * wakes the device and fires on time — and it needs **no** special exact-alarm
 * permission. When the alarm fires, the receiver enqueues [WrappedWorker], which
 * calls [reschedule] again on completion, so the chain **self-perpetuates** (one
 * run → arm the next) — honouring DST, unequal months and a mid-cycle change.
 *
 * When the schedule is disabled, [reschedule] cancels the pending alarm (and any
 * in-flight unique work).
 */
@Singleton
internal class WrappedScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduleDataStore: WrappedScheduleDataStore
) {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)
    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * (Re)computes and arms the next run from the currently-stored schedule.
     * Replaces any previously-scheduled alarm (stable request code), and cancels
     * everything when the schedule is disabled. Safe to call repeatedly (idempotent).
     */
    suspend fun reschedule(now: Long = System.currentTimeMillis()) {
        val schedule = scheduleDataStore.schedule.first()
        val fireTime = WrappedScheduleMath.nextFireTime(schedule, now)
        if (fireTime == null) {
            cancel()
            return
        }
        // setAndAllowWhileIdle fires even under Doze and needs no exact-alarm
        // permission. Cross-DST/month correctness comes from the fresh fire time
        // computed for each occurrence (the worker re-arms the next one).
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireTime, alarmPendingIntent())
    }

    /** Alias of [reschedule] for call sites that read better as an initial "schedule". */
    suspend fun schedule(now: Long = System.currentTimeMillis()) = reschedule(now)

    /** Cancels the pending alarm and any in-flight run (e.g. when turned off). */
    fun cancel() {
        alarmManager.cancel(alarmPendingIntent())
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    /** The stable broadcast [PendingIntent] delivered to [WrappedAlarmReceiver]. */
    private fun alarmPendingIntent(): PendingIntent {
        val intent = Intent(context, WrappedAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    internal companion object {
        /** Stable unique work name so re-enqueues replace the prior scheduling. */
        const val UNIQUE_WORK_NAME = "velospot_wrapped_report"
        const val WORK_TAG = "velospot_wrapped"

        /** Stable request code so re-arming/cancelling targets the same alarm. */
        private const val ALARM_REQUEST_CODE = 5101

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

