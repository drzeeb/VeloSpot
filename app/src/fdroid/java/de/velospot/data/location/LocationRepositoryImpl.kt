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

    override fun startLocationUpdates() {
        if (!hasPermission()) return

        // Remove any previously registered listeners before re-registering.
        stopLocationUpdates()

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { locationManager.isProviderEnabled(it) }
            // Always try at least GPS even if reported as disabled (some devices lie)
            .ifEmpty { listOf(LocationManager.GPS_PROVIDER) }

        for (provider in providers) {
            // Emit last known location for this provider immediately (best-effort)
            try {
                locationManager.getLastKnownLocation(provider)?.let { loc ->
                    _locationFlow.value = GeoCoordinate(loc.latitude, loc.longitude)
                }
            } catch (_: SecurityException) { }

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    _locationFlow.value = GeoCoordinate(location.latitude, location.longitude)
                }

                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") // Required for API < 29
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }

            try {
                // Explicit Looper required on API 31+ to avoid deprecation / silent failure.
                locationManager.requestLocationUpdates(
                    provider,
                    5_000L,            // minTime: 5 seconds
                    5f,                // minDistance: 5 metres
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
