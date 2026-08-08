package de.velospot.core.di

import android.content.Context
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.velospot.feature.wrapped.data.WrappedRepositoryImpl
import de.velospot.feature.wrapped.data.local.WrappedDatabase
import de.velospot.feature.wrapped.data.local.WrappedReportDao
import de.velospot.feature.wrapped.domain.WrappedRepository
import javax.inject.Singleton

/**
 * Provides the "VeloSpot Wrapped" persistence stack: its dedicated Room store, DAO
 * and repository. Kept in its own module (mirroring [SensorModule] / [LocationModule])
 * so the whole feature can move to a `:feature:wrapped` Gradle module later without
 * disturbing [NetworkModule]. Reuses the shared [Moshi] for the report snapshots.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object WrappedModule {

    @Provides
    @Singleton
    fun provideWrappedDatabase(
        @ApplicationContext context: Context
    ): WrappedDatabase = WrappedDatabase.getInstance(context)

    @Provides
    @Singleton
    fun provideWrappedReportDao(database: WrappedDatabase): WrappedReportDao =
        database.wrappedReportDao()

    @Provides
    @Singleton
    fun provideWrappedRepository(
        dao: WrappedReportDao,
        moshi: Moshi
    ): WrappedRepository = WrappedRepositoryImpl(dao, moshi)
}

