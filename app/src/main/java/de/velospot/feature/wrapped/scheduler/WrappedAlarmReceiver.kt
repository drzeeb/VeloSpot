package de.velospot.feature.wrapped.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

/**
 * Fired by [android.app.AlarmManager] at the exact scheduled "VeloSpot Wrapped"
 * time (see [WrappedScheduler]). Because `setAndAllowWhileIdle` wakes the device
 * even in Doze, this replaces WorkManager's deferrable initial-delay scheduling and
 * makes each occurrence fire on time.
 *
 * It is intentionally **not** Hilt-injected: it only hands off to WorkManager, which
 * enqueues the Hilt-aware [WrappedWorker] to do the actual (DB/notification) work.
 * The worker re-arms the next alarm via [WrappedScheduler.reschedule] in its finally
 * block, so the chain keeps ticking.
 */
internal class WrappedAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Kick off the report generation immediately (no initial delay). Expedited so
        // it runs promptly; falls back to a normal request when quota is exhausted.
        val request = OneTimeWorkRequestBuilder<WrappedWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(WrappedScheduler.WORK_TAG)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                WrappedScheduler.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
    }
}

