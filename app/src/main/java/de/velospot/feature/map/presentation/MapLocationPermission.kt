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
    return hasLocationPermission(fineGranted = fineGranted, coarseGranted = coarseGranted)
}

internal fun hasLocationPermission(fineGranted: Boolean, coarseGranted: Boolean): Boolean {
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
    requestLocationAccessIfNeeded(
        hasPermission = hasLocationPermission(context),
        onPermissionGranted = onPermissionGranted,
        requestPermissions = requestPermissions,
        permissions = locationPermissions()
    )
}

internal fun requestLocationAccessIfNeeded(
    hasPermission: Boolean,
    onPermissionGranted: () -> Unit,
    requestPermissions: (Array<String>) -> Unit,
    permissions: Array<String>
) {
    if (hasPermission) {
        onPermissionGranted()
    } else {
        requestPermissions(permissions)
    }
}

