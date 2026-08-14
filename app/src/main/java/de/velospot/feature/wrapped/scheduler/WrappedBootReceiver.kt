package de.velospot.feature.wrapped.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Re-arms the "VeloSpot Wrapped" scheduler after events that clear the pending
 * exact alarm: a device reboot (`ACTION_BOOT_COMPLETED`) and an app update
 * (`MY_PACKAGE_REPLACED`). Without this the self-rescheduling chain would be broken
 * by a reboot until the app is next opened.
 *
 * Field-injected via Hilt ([AndroidEntryPoint]). [WrappedScheduler.reschedule] reads
 * the stored schedule and cancels itself when the feature is disabled, so this is a
 * safe no-op when Wrapped is off.
 */
@AndroidEntryPoint
internal class WrappedBootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: WrappedScheduler

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Reschedule off the main thread; keep the receiver alive until done.
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

