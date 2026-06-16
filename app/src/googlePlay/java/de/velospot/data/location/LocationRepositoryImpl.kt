package de.velospot.data.location

import android.Manifest
import android.content.Context
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.repository.LocationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of LocationRepository using Google Play Services.
 * Provides location updates and permission management.
 */
@Singleton
class LocationRepositoryImpl @Inject constructor(
    private val context: Context,
    private val fusedLocationClient: FusedLocationProviderClient
) : LocationRepository {

    private val _locationFlow = MutableStateFlow<GeoCoordinate?>(null)
    private var locationCallback: LocationCallback? = null

    override fun getCurrentLocationFlow(): Flow<GeoCoordinate?> {
        return _locationFlow.asStateFlow()
    }


    /**
     * Check if location permission is granted (synchronous).
     * Used internally for non-suspend contexts.
     */
    private fun checkPermissionSync(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start receiving location updates.
     * Must be called after permissions are granted.
     *
     * @param highAccuracy `true` for frequent GPS fixes during active navigation,
     *  `false` (default) for a battery-friendly balanced-power mode used while the
     *  user is just browsing the map.
     */
    override fun startLocationUpdates(highAccuracy: Boolean) {
        if (!checkPermissionSync()) return

        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { lastLocation ->
                if (lastLocation != null) {
                    _locationFlow.value = GeoCoordinate(lastLocation.latitude, lastLocation.longitude)
                }
            }
        } catch (e: SecurityException) {
            // Permission denied or not yet granted.
        }

        // Power-aware request: high-accuracy GPS only while navigating, otherwise a
        // balanced-power mode with a longer interval and a minimum displacement so
        // the GPS chip is not woken up while the user stands still.
        val priority = if (highAccuracy) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val intervalMs   = if (highAccuracy) 3_000L else 15_000L
        val minDistanceM = if (highAccuracy) 5f else 20f

        val locationRequest = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateDistanceMeters(minDistanceM)
            .setMinUpdateIntervalMillis(intervalMs)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val lastLocation = locationResult.lastLocation
                if (lastLocation != null) {
                    _locationFlow.value = GeoCoordinate(lastLocation.latitude, lastLocation.longitude)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback ?: return,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            // Permission denied or not yet granted
        }
    }

    override fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        // Drop the reference so the callback (and its captured state) can be
        // garbage-collected and a later stop() call does not remove it twice.
        locationCallback = null
    }
}

