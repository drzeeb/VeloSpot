package de.velospot.data.location

import android.Manifest
import android.content.Context
import android.location.Criteria
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
 * F-Droid-Implementierung von [LocationRepository].
 * Nutzt ausschließlich die Standard-Android-API (LocationManager),
 * ohne jede Abhängigkeit zu Google Play Services.
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

        // Besten verfügbaren Provider ermitteln (GPS bevorzugt, Netzwerk als Fallback)
        val criteria = Criteria().apply {
            accuracy = Criteria.ACCURACY_FINE
            powerRequirement = Criteria.POWER_HIGH
        }
        val provider = locationManager.getBestProvider(criteria, true)
            ?: LocationManager.GPS_PROVIDER

        // Letzten bekannten Standort sofort emittieren
        try {
            locationManager.getLastKnownLocation(provider)?.let {
                _locationFlow.value = GeoCoordinate(it.latitude, it.longitude)
            }
        } catch (_: SecurityException) { }

        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                _locationFlow.value = GeoCoordinate(location.latitude, location.longitude)
            }

            @Suppress("DEPRECATION") // Für API < 29 erforderlich
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        try {
            locationManager.requestLocationUpdates(
                provider,
                5_000L, // minTime: 5 Sekunden
                5f,      // minDistance: 5 Meter
                locationListener!!
            )
        } catch (_: SecurityException) { }
    }

    override fun stopLocationUpdates() {
        locationListener?.let { locationManager.removeUpdates(it) }
        locationListener = null
    }
}

