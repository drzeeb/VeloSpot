package de.velospot.core.map

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import de.velospot.domain.model.BikeParkingSpace

/**
 * Abstracts a navigation operation for a [BikeParkingSpace].
 *
 * By using a simple function type, the implementation can be swapped
 * at any time for an in-app routing function:
 *
 * ```kotlin
 * // External (current):
 * val handler = externalNavigationHandler(context)
 *
 * // Internal (future):
 * val handler: NavigationHandler = { space -> appNavController.navigate(Route.Navigate(space.id)) }
 * ```
 */
typealias NavigationHandler = (space: BikeParkingSpace) -> Unit

/**
 * Creates a [NavigationHandler] that delegates navigation to an external app
 * (Google Maps, OsmAnd, etc.).
 */
fun externalNavigationHandler(context: Context): NavigationHandler = { space ->
    startExternalNavigation(context, space)
}

/**
 * Fires a `geo:` intent that can be intercepted by any installed navigation app.
 * If no app is available, Google Maps opens as a fallback in the browser.
 */
fun startExternalNavigation(context: Context, space: BikeParkingSpace) {
    val label = Uri.encode(space.name ?: "Fahrradstellplatz")
    val geoUri = Uri.parse(
        "geo:${space.latitude},${space.longitude}" +
            "?q=${space.latitude},${space.longitude}($label)"
    )
    val intent = Intent(Intent.ACTION_VIEW, geoUri)

     try {
         context.startActivity(intent)
     } catch (_: ActivityNotFoundException) {
         // Fallback: Open Google Maps in browser
         val fallbackUri = Uri.parse(
            "https://maps.google.com/?q=${space.latitude},${space.longitude}"
        )
        context.startActivity(Intent(Intent.ACTION_VIEW, fallbackUri))
    }
}

