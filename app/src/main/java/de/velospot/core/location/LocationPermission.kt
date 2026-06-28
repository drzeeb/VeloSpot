package de.velospot.core.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Location-permission helpers shared by the map UI and the background recording
 * entry points (Quick Settings tile, home-screen widget). Kept in `core.location`
 * so the recording stack does not depend "upwards" on the presentation layer.
 */
internal fun hasLocationPermission(context: Context): Boolean {
    val fineGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarseGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return hasLocationPermission(fineGranted = fineGranted, coarseGranted = coarseGranted)
}

internal fun hasLocationPermission(fineGranted: Boolean, coarseGranted: Boolean): Boolean {
    return fineGranted || coarseGranted
}

