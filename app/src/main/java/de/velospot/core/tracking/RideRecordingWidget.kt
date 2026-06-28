package de.velospot.core.tracking

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import de.velospot.MainActivity
import de.velospot.R
import de.velospot.core.format.formatRideDistance
import de.velospot.core.format.formatRideDuration
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

        val title = context.getString(
            if (recording) R.string.ride_recording else R.string.ride_record_start
        )
        views.setTextViewText(R.id.widget_title, title)

        val subtitle = if (recording) {
            (manager.trackingState.value as? RideTrackingUiState.Recording)?.stats?.let { s ->
                "${formatRideDuration(s.elapsedSeconds)} • ${formatRideDistance(s.distanceMeters)}"
            } ?: context.getString(R.string.ride_stop)
        } else {
            context.getString(R.string.widget_ride_recording_description)
        }
        views.setTextViewText(R.id.widget_subtitle, subtitle)

        // Tint the icon programmatically: app widgets use plain RemoteViews/ImageView,
        // which ignore android:tint/app:tint, so we apply a colour filter here instead.
        views.setInt(R.id.widget_icon, "setColorFilter", resolveTextColorPrimary(context))

        views.setOnClickPendingIntent(R.id.widget_root, togglePendingIntent(context))
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

    companion object {
        const val ACTION_TOGGLE = "de.velospot.action.WIDGET_RIDE_TOGGLE"
        const val ACTION_REFRESH = "de.velospot.action.WIDGET_RIDE_REFRESH"
    }
}


