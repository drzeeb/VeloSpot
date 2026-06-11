package de.velospot.core.di

import android.content.Context
import android.location.LocationManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.velospot.data.location.LocationRepositoryImpl
import de.velospot.domain.repository.LocationRepository
import javax.inject.Singleton

/**
 * F-Droid-Flavor: stellt den Standard-Android-LocationManager bereit.
 * Keine Abhängigkeit zu Google Play Services.
 */
@Module
@InstallIn(SingletonComponent::class)
object LocationModule {

    @Provides
    @Singleton
    fun provideLocationManager(
        @ApplicationContext context: Context
    ): LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Provides
    @Singleton
    fun provideLocationRepository(
        @ApplicationContext context: Context,
        locationManager: LocationManager
    ): LocationRepository = LocationRepositoryImpl(context, locationManager)
}

