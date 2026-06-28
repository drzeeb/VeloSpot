package de.velospot.feature.map.presentation

import android.Manifest
import android.content.Context
import de.velospot.core.location.hasLocationPermission

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

