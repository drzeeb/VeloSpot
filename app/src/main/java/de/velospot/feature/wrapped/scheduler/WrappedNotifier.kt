package de.velospot.feature.wrapped.scheduler

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import de.velospot.MainActivity
import de.velospot.R
import de.velospot.feature.wrapped.domain.WrappedReport
import de.velospot.feature.wrapped.presentation.WrappedOpenBus
import javax.inject.Inject

/**
 * Posts a **one-shot "your Wrapped is ready" notification** once a scheduled report
 * has been generated and saved.
 *
 * Mirrors [de.velospot.core.tracking.BikeServiceNotifier]: a normal, dismissible
 * alert on its own low-priority channel, best-effort — if the user denied the
 * `POST_NOTIFICATIONS` permission (Android 13+) the notification is silently
 * skipped. The report itself is already persisted by the worker, so a skipped
 * notification never loses data (Phase 3 rule: skip posting, still save the report).
 */
internal class WrappedNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** Posts the "new Wrapped report" notification; no-op without notif permission. */
    fun notifyNewReport(report: WrappedReport) {
        if (!hasPermission()) return
        ensureChannel()

        val contentIntent = PendingIntent.getActivity(
            context,
            // Per-report request code so each report's PendingIntent keeps its own
            // extra (FLAG_UPDATE_CURRENT would otherwise overwrite a shared one).
            report.period.startInclusive.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(WrappedOpenBus.EXTRA_OPEN_WRAPPED_REPORT_ID, report.id),
            pendingIntentFlags()
        )

        val text = context.getString(R.string.wrapped_notification_text)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ride_recording)
            .setContentTitle(context.getString(R.string.wrapped_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent)
            .build()

        // Stable per period so re-posting the same bucket can't stack duplicates.
        val id = NOTIFICATION_ID_BASE + report.period.startInclusive.hashCode()
        // Permission is checked above; still guard the platform call defensively as
        // the OS can revoke it between the check and the post (SecurityException).
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Notification permission was revoked — nothing else to do.
        }
    }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.wrapped_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.wrapped_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun pendingIntentFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private companion object {
        const val CHANNEL_ID = "wrapped_reports"
        const val NOTIFICATION_ID_BASE = 5100
    }
}

