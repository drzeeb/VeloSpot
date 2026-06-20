package de.velospot.data.location

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.repository.LocationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * F-Droid implementation of [LocationRepository].
 * Uses only the standard Android API (LocationManager),
 * without any dependency on Google Play Services.
 */
@Singleton
class LocationRepositoryImpl @Inject constructor(
    private val context: Context,
    private val locationManager: LocationManager
) : LocationRepository {

    private val _locationFlow = MutableStateFlow<GeoCoordinate?>(null)

    /**
     * Listeners per provider – we register GPS and Network simultaneously so whichever
     * gets a fix first (Network is usually faster indoors) emits immediately.
     */
    private val activeListeners = mutableMapOf<String, LocationListener>()

    override fun getCurrentLocationFlow(): Flow<GeoCoordinate?> =
        _locationFlow.asStateFlow()

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun startLocationUpdates(highAccuracy: Boolean) {
        if (!hasPermission()) return

        // Remove any previously registered listeners before re-registering.
        stopLocationUpdates()

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { locationManager.isProviderEnabled(it) }
            // Always try at least GPS even if reported as disabled (some devices lie)
            .ifEmpty { listOf(LocationManager.GPS_PROVIDER) }

        // Power-aware update cadence: tight intervals during active navigation,
        // a relaxed interval + larger minimum displacement while idle so the GPS
        // radio stays asleep most of the time.
        val minTimeMs    = if (highAccuracy) 3_000L else 15_000L
        val minDistanceM = if (highAccuracy) 5f else 20f

        for (provider in providers) {
            // Emit last known location for this provider immediately (best-effort)
            try {
                locationManager.getLastKnownLocation(provider)?.let { loc ->
                    _locationFlow.value = loc.toGeoCoordinate()
                }
            } catch (_: SecurityException) { }

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    _locationFlow.value = location.toGeoCoordinate()
                }

                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") // Required for API < 29
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }

            try {
                // Explicit Looper required on API 31+ to avoid deprecation / silent failure.
                locationManager.requestLocationUpdates(
                    provider,
                    minTimeMs,
                    minDistanceM,
                    listener,
                    Looper.getMainLooper()
                )
                activeListeners[provider] = listener
            } catch (_: SecurityException) { }
        }
    }

    override fun stopLocationUpdates() {
        activeListeners.values.forEach { locationManager.removeUpdates(it) }
        activeListeners.clear()
    }
}

/**
 * Maps an Android [Location] to the domain [GeoCoordinate], carrying the optional
 * bearing/speed sensor data used to drive the 3D navigation camera and the
 * heading arrow. Values absent on the fix are mapped to `null`.
 */
private fun Location.toGeoCoordinate(): GeoCoordinate = GeoCoordinate(
    latitude  = latitude,
    longitude = longitude,
    bearing   = if (hasBearing()) bearing else null,
    speedMetersPerSecond = if (hasSpeed()) speed else null,
    altitudeMeters = if (hasAltitude()) altitude else null,
    accuracyMeters = if (hasAccuracy()) accuracy else null
)

