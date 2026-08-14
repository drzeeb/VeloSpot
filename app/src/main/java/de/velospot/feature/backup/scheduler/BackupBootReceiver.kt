package de.velospot.feature.backup.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Re-arms the automatic-backup scheduler after events that clear WorkManager's pending
 * one-shot work: a device reboot (`ACTION_BOOT_COMPLETED`) and an app update
 * (`MY_PACKAGE_REPLACED`). Mirrors the "VeloSpot Wrapped" boot receiver.
 *
 * Field-injected via Hilt ([AndroidEntryPoint]). [BackupScheduler.reschedule] reads the
 * stored schedule and cancels itself when the feature is disabled, so this is a safe
 * no-op when automatic backup is off.
 */
@AndroidEntryPoint
class BackupBootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: BackupScheduler

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        scheduler.reschedule()
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}

