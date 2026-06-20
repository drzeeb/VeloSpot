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
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import de.velospot.MainActivity
import de.velospot.R
import de.velospot.feature.map.presentation.RideTrackingUiState
import de.velospot.feature.map.presentation.formatRideDistance
import de.velospot.feature.map.presentation.formatRideDuration
import de.velospot.feature.map.presentation.formatRideSpeed
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
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
    }
}

