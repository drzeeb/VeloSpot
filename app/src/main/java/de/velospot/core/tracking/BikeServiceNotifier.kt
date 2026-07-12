package de.velospot.core.tracking

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
import de.velospot.MainActivity
import de.velospot.R
import de.velospot.domain.model.BikeServiceReminder

/**
 * Posts a **one-shot "service due" notification** for a bike once its total ridden
 * distance crosses a new service milestone (see [BikeServiceReminder]).
 *
 * Unlike the ongoing ride-recording notification, this is a normal, dismissible
 * alert on its own low-priority channel. It is best-effort: if the user denied the
 * `POST_NOTIFICATIONS` permission (Android 13+), the reminder is silently skipped —
 * the milestone is still recorded so it won't be re-evaluated later.
 */
internal class BikeServiceNotifier(private val context: Context) {

    fun notifyServiceDue(reminder: BikeServiceReminder) {
        if (!hasPermission()) return
        ensureChannel()

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingIntentFlags()
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ride_recording)
            .setContentTitle(context.getString(R.string.bike_service_notification_title))
            .setContentText(
                context.getString(
                    R.string.bike_service_notification_text,
                    reminder.bikeName,
                    reminder.milestoneKm
                )
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(
                        R.string.bike_service_notification_text,
                        reminder.bikeName,
                        reminder.milestoneKm
                    )
                )
            )
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent)
            .build()

        // Stable per bike + milestone so re-posting can't stack duplicates.
        val id = (reminder.bikeId.hashCode() * 31 + reminder.milestoneKm)
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
            context.getString(R.string.bike_service_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.bike_service_channel_description)
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
        const val CHANNEL_ID = "bike_service_reminders"
    }
}

