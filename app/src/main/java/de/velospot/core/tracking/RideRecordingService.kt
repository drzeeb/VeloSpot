package de.velospot.core.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import de.velospot.MainActivity
import de.velospot.R
import de.velospot.core.format.formatRideDistance
import de.velospot.core.format.formatRideDuration
import de.velospot.core.format.formatRideSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * Foreground service (`location` type) that keeps an active ride recording running
 * while the app is backgrounded or closed.
 *
 * It owns no recording logic itself — that lives in the singleton
 * [RideRecordingManager], which it shares with the map ViewModel. The service's
 * jobs are purely Android-lifecycle: hold the foreground promotion (so the OS keeps
 * the process + GPS alive), render a live stats notification, and route its
 * Stop/Discard actions back into the manager.
 */
@AndroidEntryPoint
class RideRecordingService : Service() {

    @Inject lateinit var manager: RideRecordingManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    /**
     * Partial wake lock held for the duration of an active recording. A
     * `location`-typed foreground service keeps the *process* alive and location
     * access allowed, but does **not** keep the CPU awake: with the screen off and
     * the device dozing, location callbacks get deferred/batched, so the recorded
     * track would thin out or freeze. Holding a [PowerManager.PARTIAL_WAKE_LOCK]
     * while recording keeps the CPU running so every GPS fix is delivered on time.
     * Acquired when the service goes foreground, released the moment recording ends.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                manager.stop()
                stopRecordingService()
                return START_NOT_STICKY
            }
            ACTION_DISCARD -> {
                manager.discard()
                stopRecordingService()
                return START_NOT_STICKY
            }
            else -> startInForeground()
        }
        return START_STICKY
    }

    private fun startInForeground() {
        ensureChannel()
        val stats = (manager.trackingState.value as? RideTrackingUiState.Recording)?.stats
        try {
            startForegroundCompat(buildNotification(stats))
        } catch (t: Throwable) {
            // e.g. location permission revoked (FGS-location not allowed) or a
            // background-start restriction: give up cleanly rather than crash.
            stopSelf()
            return
        }
        // Keep the CPU awake so GPS fixes keep arriving with the screen off / in Doze.
        acquireWakeLock()
        // Live-update the notification on every stats tick; stop the service when the
        // recording ends from elsewhere (e.g. the in-app FAB).
        observeJob?.cancel()
        observeJob = manager.trackingState
            .onEach { state ->
                when (state) {
                    is RideTrackingUiState.Recording ->
                        notificationManager().notify(NOTIFICATION_ID, buildNotification(state.stats))
                    RideTrackingUiState.Idle -> stopRecordingService()
                }
            }
            .launchIn(serviceScope)
    }

    private fun stopRecordingService() {
        observeJob?.cancel()
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    /** Acquires the partial wake lock (idempotent) for the active recording. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            // Bounded acquire so a missed release can never drain the battery
            // indefinitely; far longer than any realistic ride.
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    /** Releases the wake lock if held. Safe to call when none was acquired. */
    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(stats: de.velospot.domain.model.LiveRideStats?): Notification {
        val contentText = if (stats != null) {
            "${formatRideDuration(stats.elapsedSeconds)}  •  " +
                "${formatRideDistance(stats.distanceMeters)}  •  " +
                formatRideSpeed(stats.currentSpeedMps)
        } else {
            getString(R.string.ride_recording)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingIntentFlags()
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ride_recording)
            .setContentTitle(getString(R.string.ride_recording))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .addAction(
                0,
                getString(R.string.ride_stop_save),
                actionIntent(ACTION_STOP, REQ_STOP)
            )
            .addAction(
                0,
                getString(R.string.ride_discard),
                actionIntent(ACTION_DISCARD, REQ_DISCARD)
            )
            .build()
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, RideRecordingService::class.java).setAction(action)
        return PendingIntent.getService(this, requestCode, intent, pendingIntentFlags())
    }

    private fun pendingIntentFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val existing = notificationManager().getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.ride_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.ride_notification_channel_description)
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun onDestroy() {
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "de.velospot.action.RIDE_RECORDING_START"
        const val ACTION_STOP = "de.velospot.action.RIDE_RECORDING_STOP"
        const val ACTION_DISCARD = "de.velospot.action.RIDE_RECORDING_DISCARD"

        private const val CHANNEL_ID = "ride_recording"
        private const val NOTIFICATION_ID = 4711
        private const val REQ_STOP = 1
        private const val REQ_DISCARD = 2

        /** Wake-lock tag (namespaced, shown in `dumpsys power`). */
        private const val WAKELOCK_TAG = "VeloSpot:ride-recording"

        /**
         * Safety-net timeout for the wake lock (12 h). The lock is released
         * deterministically when recording ends; this only guards against a
         * leaked lock (e.g. process killed without `onDestroy`) draining the
         * battery, and comfortably outlasts any realistic ride.
         */
        private const val WAKELOCK_TIMEOUT_MS = 12L * 60L * 60L * 1_000L
    }
}

