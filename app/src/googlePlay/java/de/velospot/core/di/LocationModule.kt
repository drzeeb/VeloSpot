package de.velospot.core.di

import android.content.Context
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.velospot.data.location.LocationRepositoryImpl
import de.velospot.domain.repository.LocationRepository
import javax.inject.Singleton

/**
 * Google-Play-Flavor: stellt FusedLocationProviderClient (Google Play Services) bereit.
 * Wird im F-Droid-Flavor durch LocationModule aus dem fdroid-Source-Set ersetzt.
 */
@Module
@InstallIn(SingletonComponent::class)
object LocationModule {

    @Provides
    @Singleton
    fun provideFusedLocationClient(
        @ApplicationContext context: Context
    ): FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    @Provides
    @Singleton
    fun provideLocationRepository(
        @ApplicationContext context: Context,
        fusedLocationClient: FusedLocationProviderClient
    ): LocationRepository = LocationRepositoryImpl(context, fusedLocationClient)
}

