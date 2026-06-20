package de.velospot.core.di

import android.content.Context
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.velospot.BuildConfig
import de.velospot.data.brouter.BRouterEngine
import de.velospot.data.brouter.BRouterSegmentManager
import de.velospot.data.geocoding.NominatimGeocoder
import de.velospot.data.local.BikeParkingCacheDataSource
import de.velospot.data.local.BikeParkingLocalDataSource
import de.velospot.data.local.dao.BikeParkingSpaceDao
import de.velospot.data.local.dao.FavoriteSpaceDao
import de.velospot.data.local.dao.RecordedRideDao
import de.velospot.data.local.dao.SavedPlaceDao
import de.velospot.data.local.database.BikeParkingDatabase
import de.velospot.data.local.database.FavoritesDatabase
import de.velospot.data.local.database.RidesDatabase
import de.velospot.data.local.database.SavedPlacesDatabase
import de.velospot.data.remote.NominatimRateLimitInterceptor
import de.velospot.data.remote.api.NominatimApi
import de.velospot.data.remote.api.OsrmApi
import de.velospot.data.repository.BikeParkingRepositoryImpl
import de.velospot.data.repository.FavoritesRepositoryImpl
import de.velospot.data.repository.ParkedBikeRepositoryImpl
import de.velospot.data.repository.RecordedRidesRepositoryImpl
import de.velospot.data.repository.RoutingRepositoryImpl
import de.velospot.data.repository.SavedPlacesRepositoryImpl
import de.velospot.domain.repository.BikeParkingRepository
import de.velospot.domain.repository.FavoritesRepository
import de.velospot.domain.repository.ParkedBikeRepository
import de.velospot.domain.repository.RecordedRidesRepository
import de.velospot.domain.repository.RoutingRepository
import de.velospot.domain.repository.SavedPlacesRepository
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val OSRM_BASE_URL = "https://router.project-osrm.org/"
    private const val NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org/"

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                    else HttpLoggingInterceptor.Level.NONE
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(loggingInterceptor: HttpLoggingInterceptor): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    /**
     * Dedicated client for Nominatim: carries the [NominatimRateLimitInterceptor]
     * so the app can never exceed the OSM policy of 1 request/second. Slightly
     * shorter connect timeout since the endpoint is normally fast.
     */
    @Provides
    @Singleton
    @Named("nominatim")
    fun provideNominatimOkHttpClient(loggingInterceptor: HttpLoggingInterceptor): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(NominatimRateLimitInterceptor())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    /**
     * Dedicated client for BRouter segment downloads, which can be 100+ MB. Uses a
     * much longer read timeout so a slow-but-progressing download is not aborted,
     * while keeping a sane connect timeout.
     */
    @Provides
    @Singleton
    @Named("segments")
    fun provideSegmentsOkHttpClient(loggingInterceptor: HttpLoggingInterceptor): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    @Provides
    @Singleton
    fun provideMoshi(): Moshi {
        return Moshi.Builder()
            .addLast(LenientJsonAdapterFactory)
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    @Provides
    @Singleton
    @Named("osrm")
    fun provideRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit {
        return Retrofit.Builder()
            .baseUrl(OSRM_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    @Named("nominatim")
    fun provideNominatimRetrofit(@Named("nominatim") okHttpClient: OkHttpClient, moshi: Moshi): Retrofit {
        return Retrofit.Builder()
            .baseUrl(NOMINATIM_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideOsrmApi(@Named("osrm") retrofit: Retrofit): OsrmApi {
        return retrofit.create(OsrmApi::class.java)
    }

    @Provides
    @Singleton
    fun provideNominatimApi(@Named("nominatim") retrofit: Retrofit): NominatimApi {
        return retrofit.create(NominatimApi::class.java)
    }

    @Provides
    @Singleton
    fun provideBikeParkingDatabase(
        @ApplicationContext context: Context
    ): BikeParkingDatabase {
        return BikeParkingDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideBikeParkingSpaceDao(database: BikeParkingDatabase): BikeParkingSpaceDao {
        return database.bikeParkingSpaceDao()
    }

    @Provides
    @Singleton
    fun provideFavoritesDatabase(
        @ApplicationContext context: Context
    ): FavoritesDatabase {
        return FavoritesDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideFavoriteSpaceDao(database: FavoritesDatabase): FavoriteSpaceDao {
        return database.favoriteSpaceDao()
    }

    @Provides
    @Singleton
    fun provideBikeParkingLocalDataSource(
        bikeParkingSpaceDao: BikeParkingSpaceDao
    ): BikeParkingLocalDataSource {
        return BikeParkingCacheDataSource(bikeParkingSpaceDao)
    }

    @Provides
    @Singleton
    fun provideBikeParkingRepository(
        localDataSource: BikeParkingLocalDataSource,
        nominatimGeocoder: NominatimGeocoder
    ): BikeParkingRepository {
        return BikeParkingRepositoryImpl(localDataSource, nominatimGeocoder)
    }


    @Provides
    @Singleton
    fun provideBRouterSegmentManager(
        @ApplicationContext context: Context,
        @Named("segments") okHttpClient: OkHttpClient
    ): BRouterSegmentManager = BRouterSegmentManager(context, okHttpClient)

    @Provides
    @Singleton
    fun provideBRouterEngine(
        @ApplicationContext context: Context,
        segmentManager: BRouterSegmentManager
    ): BRouterEngine = BRouterEngine(context, segmentManager.segmentsDir)

    @Provides
    @Singleton
    fun provideRoutingRepository(
        brouterEngine: BRouterEngine,
        segmentManager: BRouterSegmentManager,
        osrmApi: OsrmApi,
        @ApplicationContext context: Context
    ): RoutingRepository = RoutingRepositoryImpl(brouterEngine, segmentManager, osrmApi, context)


    @Provides
    @Singleton
    fun provideFavoritesRepository(
        favoritesDao: FavoriteSpaceDao
    ): FavoritesRepository {
        return FavoritesRepositoryImpl(favoritesDao)
    }

    @Provides
    @Singleton
    fun provideSavedPlacesDatabase(
        @ApplicationContext context: Context
    ): SavedPlacesDatabase {
        return SavedPlacesDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideSavedPlaceDao(database: SavedPlacesDatabase): SavedPlaceDao {
        return database.savedPlaceDao()
    }

    @Provides
    @Singleton
    fun provideSavedPlacesRepository(
        savedPlaceDao: SavedPlaceDao
    ): SavedPlacesRepository {
        return SavedPlacesRepositoryImpl(savedPlaceDao)
    }

    @Provides
    @Singleton
    fun provideParkedBikeRepository(
        @ApplicationContext context: Context
    ): ParkedBikeRepository {
        return ParkedBikeRepositoryImpl(context)
    }

    @Provides
    @Singleton
    fun provideRidesDatabase(
        @ApplicationContext context: Context
    ): RidesDatabase {
        return RidesDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideRecordedRideDao(database: RidesDatabase): RecordedRideDao {
        return database.recordedRideDao()
    }

    @Provides
    @Singleton
    fun provideRecordedRidesRepository(
        recordedRideDao: RecordedRideDao,
        moshi: Moshi
    ): RecordedRidesRepository {
        return RecordedRidesRepositoryImpl(recordedRideDao, moshi)
    }
}

/**
 * Sets [JsonReader.isLenient] = true on all adapters.
 */
private object LenientJsonAdapterFactory : JsonAdapter.Factory {
    override fun create(type: Type, annotations: MutableSet<out Annotation>, moshi: Moshi): JsonAdapter<*> {
        val delegate = moshi.nextAdapter<Any>(this, type, annotations)
        return object : JsonAdapter<Any?>() {
            override fun fromJson(reader: JsonReader): Any? {
                reader.isLenient = true
                return delegate.fromJson(reader)
            }
            override fun toJson(writer: JsonWriter, value: Any?) = delegate.toJson(writer, value)
        }
    }
}
