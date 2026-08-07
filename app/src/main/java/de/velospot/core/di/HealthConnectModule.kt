package de.velospot.core.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.velospot.core.health.HealthConnectExporter
import javax.inject.Singleton

/**
 * Provides the Health Connect exporter (writes finished rides into the on-device
 * Health Connect store). A process-wide [Singleton] like the other core singletons.
 */
@Module
@InstallIn(SingletonComponent::class)
object HealthConnectModule {

    @Provides
    @Singleton
    fun provideHealthConnectExporter(
        @ApplicationContext context: Context
    ): HealthConnectExporter = HealthConnectExporter(context)
}

