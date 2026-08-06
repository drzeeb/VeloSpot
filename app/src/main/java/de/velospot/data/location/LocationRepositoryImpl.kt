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
import de.velospot.domain.repository.LocationPowerProfile
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
     * @param profile the requested GPS-radio power profile. [LocationPowerProfile.NAVIGATION_OR_MOVING]
     *  requests frequent GPS fixes (active navigation or moving recording),
     *  [LocationPowerProfile.IDLE_RECORDING] drops the GNSS engine to a power-saving
     *  cadence while the rider stands still, and [LocationPowerProfile.BROWSE] is the
     *  battery-friendly mode used while just viewing the map.
     */
    override fun startLocationUpdates(profile: LocationPowerProfile) {
        if (!checkPermissionSync()) return

        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { lastLocation ->
                if (lastLocation != null) {
                    _locationFlow.value = lastLocation.toGeoCoordinate()
                }
            }
        } catch (e: SecurityException) {
            // Permission denied or not yet granted.
        }

        // Power-aware request. High-accuracy GPS only while navigating or actively
        // moving during a recording; when the rider has been standing still for a
        // sustained period (or paused) we drop to a balanced-power request with a
        // longer interval and a small min-displacement so the GNSS engine can idle
        // and the battery lasts a full-day tour. Map browsing keeps today's mode.
        val priority = when (profile) {
            LocationPowerProfile.NAVIGATION_OR_MOVING -> Priority.PRIORITY_HIGH_ACCURACY
            LocationPowerProfile.IDLE_RECORDING,
            LocationPowerProfile.BROWSE -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val intervalMs = when (profile) {
            LocationPowerProfile.NAVIGATION_OR_MOVING -> 3_000L
            LocationPowerProfile.IDLE_RECORDING -> 12_000L
            LocationPowerProfile.BROWSE -> 15_000L
        }
        val minDistanceM = when (profile) {
            LocationPowerProfile.NAVIGATION_OR_MOVING -> 5f
            LocationPowerProfile.IDLE_RECORDING -> 10f
            LocationPowerProfile.BROWSE -> 20f
        }

        val locationRequest = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateDistanceMeters(minDistanceM)
            .setMinUpdateIntervalMillis(intervalMs)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val lastLocation = locationResult.lastLocation
                if (lastLocation != null) {
                    _locationFlow.value = lastLocation.toGeoCoordinate()
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

/**
 * Maps a Play-Services [android.location.Location] to the domain [GeoCoordinate],
 * carrying the optional bearing/speed sensor data used to drive the 3D navigation
 * camera and the heading arrow. Values absent on the fix are mapped to `null`.
 */
private fun android.location.Location.toGeoCoordinate(): GeoCoordinate = GeoCoordinate(
    latitude  = latitude,
    longitude = longitude,
    bearing   = if (hasBearing()) bearing else null,
    speedMetersPerSecond = if (hasSpeed()) speed else null,
    altitudeMeters = if (hasAltitude()) altitude else null,
    accuracyMeters = if (hasAccuracy()) accuracy else null
)

