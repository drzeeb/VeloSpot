package de.velospot.core.tracking

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import de.velospot.MainActivity
import de.velospot.R
import de.velospot.core.format.formatRideDistance
import de.velospot.core.location.hasLocationPermission
import javax.inject.Inject

/**
 * Home-screen widget with a single start/stop control for ride recording.
 *
 * Shares the singleton [RideRecordingManager] (via Hilt) with the app UI, the
 * Quick Settings tile and the foreground service, so a tap starts/stops the very
 * same recording shown everywhere else. The manager broadcasts [ACTION_REFRESH]
 * on every state change so the widget label stays in sync even when the app's UI
 * is closed.
 */
@AndroidEntryPoint
class RideRecordingWidget : AppWidgetProvider() {

    @Inject lateinit var manager: RideRecordingManager

    override fun onReceive(context: Context, intent: Intent) {
        // super.onReceive triggers Hilt field injection (so `manager` is set) and
        // dispatches the standard widget broadcasts (onUpdate/onEnabled/…).
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE -> {
                if (!manager.isRecording && !hasLocationPermission(context)) {
                    // No GPS permission — open the app so the user can grant it.
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } else {
                    manager.toggle()
                }
                renderAll(context)
            }
            ACTION_REFRESH -> renderAll(context)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { render(context, appWidgetManager, it) }
    }

    private fun renderAll(context: Context) {
        val awm = AppWidgetManager.getInstance(context)
        val ids = awm.getAppWidgetIds(ComponentName(context, RideRecordingWidget::class.java))
        ids.forEach { render(context, awm, it) }
    }

    private fun render(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
        val recording = manager.isRecording
        val views = RemoteViews(context.packageName, R.layout.widget_ride_recording)

        val stats = (manager.trackingState.value as? RideTrackingUiState.Recording)?.stats

        // Title reflects the current state.
        views.setTextViewText(
            R.id.widget_title,
            context.getString(if (recording) R.string.ride_recording else R.string.ride_record_start)
        )

        // Live elapsed time: a Chronometer ticks on its own in the launcher process,
        // so the running duration stays live without a per-second broadcast. Its base
        // is anchored to when the recording started (derived from the elapsed seconds
        // already accumulated), then it counts up by itself.
        if (recording) {
            val elapsedMillis = (stats?.elapsedSeconds ?: 0L) * 1_000L
            views.setChronometer(
                R.id.widget_chronometer,
                SystemClock.elapsedRealtime() - elapsedMillis,
                null,
                true
            )
            views.setViewVisibility(R.id.widget_chronometer, View.VISIBLE)
        } else {
            views.setChronometer(R.id.widget_chronometer, SystemClock.elapsedRealtime(), null, false)
            views.setViewVisibility(R.id.widget_chronometer, View.GONE)
        }

        // Subtitle: live distance while recording, otherwise the idle hint.
        val subtitle = if (recording) {
            stats?.let { formatRideDistance(it.distanceMeters) }
                ?: context.getString(R.string.ride_recording)
        } else {
            context.getString(R.string.widget_ride_recording_description)
        }
        views.setTextViewText(R.id.widget_subtitle, subtitle)

        // Tint the header icon programmatically: app widgets use plain
        // RemoteViews/ImageView, which ignore android:tint/app:tint.
        views.setInt(R.id.widget_icon, "setColorFilter", resolveTextColorPrimary(context))

        // Start/Stop button: swap its icon, label and background colour.
        views.setImageViewResource(
            R.id.widget_button_icon,
            if (recording) R.drawable.ic_widget_stop else R.drawable.ic_widget_play
        )
        views.setTextViewText(
            R.id.widget_button_label,
            context.getString(if (recording) R.string.ride_stop else R.string.ride_record_start)
        )
        views.setInt(
            R.id.widget_button,
            "setBackgroundResource",
            if (recording) R.drawable.widget_button_stop_background
            else R.drawable.widget_button_start_background
        )

        // The button carries the toggle; tapping the whole widget opens the app.
        views.setOnClickPendingIntent(R.id.widget_button, togglePendingIntent(context))
        views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent(context))
        appWidgetManager.updateAppWidget(widgetId, views)
    }

    private fun resolveTextColorPrimary(context: Context): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)) {
            if (tv.resourceId != 0) ContextCompat.getColor(context, tv.resourceId) else tv.data
        } else {
            ContextCompat.getColor(context, android.R.color.white)
        }
    }

    private fun togglePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, RideRecordingWidget::class.java)
            .setAction(ACTION_TOGGLE)
            .setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Tapping the widget body (outside the button) opens the app. */
    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_TOGGLE = "de.velospot.action.WIDGET_RIDE_TOGGLE"
        const val ACTION_REFRESH = "de.velospot.action.WIDGET_RIDE_REFRESH"
    }
}


