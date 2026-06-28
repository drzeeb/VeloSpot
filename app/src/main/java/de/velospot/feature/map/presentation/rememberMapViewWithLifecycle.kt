package de.velospot.feature.map.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView

/**
 * Creates a MapLibre [MapView] that stays in sync with the Compose lifecycle.
 *
 * MapLibre requires explicit lifecycle forwarding (onCreate/onStart/onResume/
 * onPause/onStop/onDestroy). The view is initialised with [MapLibre.getInstance]
 * so that no API key is needed (MapLibre is open-source).
 *
 * Creation is gated on [enabled]: while it is `false` this returns `null` and does
 * **no** work, so the heavy one-off native renderer / GL-context initialisation
 * (`MapView(context)` + `onCreate`) can be **deferred** past the app's launch
 * animation — that init blocks the main thread for a frame or two and would
 * otherwise make the splash animation hitch right at the start. Flip [enabled] to
 * `true` once the splash is up and the entrance animation is running.
 */
@Composable
fun rememberMapViewWithLifecycle(enabled: Boolean = true): MapView? {
    val context = LocalContext.current
    val mapView = remember(enabled) {
        if (enabled) {
            MapLibre.getInstance(context)
            MapView(context)
        } else {
            null
        }
    }

    if (mapView != null) {
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, mapView) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_CREATE  -> mapView.onCreate(null)
                    Lifecycle.Event.ON_START   -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME  -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE   -> mapView.onPause()
                    Lifecycle.Event.ON_STOP    -> mapView.onStop()
                    // ON_DESTROY is intentionally handled in onDispose below (not here)
                    // so the MapView is destroyed exactly once, and also when the
                    // composable leaves composition while the host lifecycle is still active.
                    else                       -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                // Release the native renderer, GL context and all registered map
                // listeners. Without this the MapView leaks when the composable is
                // removed from composition before the Activity is destroyed.
                mapView.onDestroy()
            }
        }
    }

    return mapView
}
