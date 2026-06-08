package de.velospot.core.di

import android.content.Context
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
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
import de.velospot.data.geocoding.NominatimGeocoder
import de.velospot.data.local.BikeParkingCacheDataSource
import de.velospot.data.local.BikeParkingLocalDataSource
import de.velospot.data.local.dao.BikeParkingSpaceDao
import de.velospot.data.local.dao.FavoriteParkingSpaceDao
import de.velospot.data.local.database.BikeParkingDatabase
import de.velospot.data.location.LocationRepositoryImpl
import de.velospot.data.remote.api.NominatimApi
import de.velospot.data.remote.api.OsrmApi
import de.velospot.data.repository.BikeParkingRepositoryImpl
import de.velospot.data.repository.FavoritesRepositoryImpl
import de.velospot.data.repository.RoutingRepositoryImpl
import de.velospot.domain.repository.BikeParkingRepository
import de.velospot.domain.repository.FavoritesRepository
import de.velospot.domain.repository.LocationRepository
import de.velospot.domain.repository.RoutingRepository
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
            level = HttpLoggingInterceptor.Level.BASIC
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
    fun provideNominatimRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit {
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
    fun provideFavoriteParkingSpaceDao(database: BikeParkingDatabase): FavoriteParkingSpaceDao {
        return database.favoriteParkingSpaceDao()
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
    fun provideRoutingRepository(osrmApi: OsrmApi): RoutingRepository {
        return RoutingRepositoryImpl(osrmApi)
    }

    @Provides
    @Singleton
    fun provideFusedLocationClient(
        @ApplicationContext context: Context
    ): FusedLocationProviderClient {
        return LocationServices.getFusedLocationProviderClient(context)
    }

    @Provides
    @Singleton
    fun provideLocationRepository(
        @ApplicationContext context: Context,
        fusedLocationClient: FusedLocationProviderClient
    ): LocationRepository {
        return LocationRepositoryImpl(context, fusedLocationClient)
    }

    @Provides
    @Singleton
    fun provideFavoritesRepository(
        favoritesDao: FavoriteParkingSpaceDao
    ): FavoritesRepository {
        return FavoritesRepositoryImpl(favoritesDao)
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
