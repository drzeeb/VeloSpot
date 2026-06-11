package de.velospot.data.location

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
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
    private var locationListener: LocationListener? = null

    override fun getCurrentLocationFlow(): Flow<GeoCoordinate?> =
        _locationFlow.asStateFlow()

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun startLocationUpdates() {
        if (!hasPermission()) return

        // Remove any previously registered listener to prevent leaking multiple active listeners
        // when startLocationUpdates() is called more than once (e.g. initial + permission grant).
        stopLocationUpdates()

        // Select best available provider (GPS preferred, network as fallback)
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> LocationManager.GPS_PROVIDER
        }

        // Emit last known location immediately
        try {
            locationManager.getLastKnownLocation(provider)?.let {
                _locationFlow.value = GeoCoordinate(it.latitude, it.longitude)
            }
        } catch (_: SecurityException) { }

        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                _locationFlow.value = GeoCoordinate(location.latitude, location.longitude)
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") // Required for API < 29
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        try {
            locationManager.requestLocationUpdates(
                provider,
                5_000L, // minTime: 5 seconds
                5f,      // minDistance: 5 metres
                locationListener!!
            )
        } catch (_: SecurityException) { }
    }

    override fun stopLocationUpdates() {
        locationListener?.let { locationManager.removeUpdates(it) }
        locationListener = null
    }
}

