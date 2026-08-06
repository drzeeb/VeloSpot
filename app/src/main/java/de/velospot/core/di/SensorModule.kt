package de.velospot.core.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.velospot.core.sensors.BleSensorController
import de.velospot.data.sensors.SensorRepositoryImpl
import de.velospot.domain.repository.SensorRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Provides the external-BLE-sensor stack (open speed/cadence/power/heart-rate
 * profiles). See [SensorRepository]; proprietary e-bike telemetry is out of scope.
 */
@Module
@InstallIn(SingletonComponent::class)
object SensorModule {

    @Provides
    @Singleton
    fun provideBleSensorController(
        @ApplicationContext context: Context
    ): BleSensorController = BleSensorController(context)

    @Provides
    @Singleton
    fun provideSensorRepository(
        @ApplicationContext context: Context,
        controller: BleSensorController
    ): SensorRepository = SensorRepositoryImpl(
        context = context,
        controller = controller,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    )
}

