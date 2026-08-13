package de.velospot.core.di

import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.velospot.core.backup.BackupSerializer
import javax.inject.Singleton

/**
 * Provides the local **Backup & Restore** stack. The pure [BackupSerializer] only
 * needs the shared [Moshi]; [de.velospot.data.backup.BackupManager] is
 * constructor-injected from the already-provided DAOs, so it needs no explicit
 * `@Provides`. Kept in its own module (mirroring [WrappedModule]) so the feature
 * stays self-contained.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object BackupModule {

    @Provides
    @Singleton
    fun provideBackupSerializer(moshi: Moshi): BackupSerializer = BackupSerializer(moshi)
}

