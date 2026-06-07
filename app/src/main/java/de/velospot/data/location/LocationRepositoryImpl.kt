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
     */
    override fun startLocationUpdates() {
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

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000  // Update interval: 5 seconds
        ).build()

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
                locationCallback!!,
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
    }
}

