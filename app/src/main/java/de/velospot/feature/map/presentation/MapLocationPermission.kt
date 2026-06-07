package de.velospot.feature.map.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

internal fun hasLocationPermission(context: Context): Boolean {
    val fineGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarseGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return fineGranted || coarseGranted
}

internal fun locationPermissions(): Array<String> = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION
)

internal fun requestLocationAccessIfNeeded(
    context: Context,
    onPermissionGranted: () -> Unit,
    requestPermissions: (Array<String>) -> Unit
) {
    if (hasLocationPermission(context)) {
        onPermissionGranted()
    } else {
        requestPermissions(locationPermissions())
    }
}

