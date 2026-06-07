package de.velospot.core.di

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.velospot.data.local.BikeParkingCacheDataSource
import de.velospot.data.local.BikeParkingLocalDataSource
import de.velospot.data.local.dao.BikeParkingSpaceDao
import de.velospot.data.local.dao.FavoriteParkingSpaceDao
import de.velospot.data.local.database.BikeParkingDatabase
import de.velospot.data.location.LocationRepositoryImpl
import de.velospot.data.remote.api.OsrmApi
import de.velospot.data.remote.api.TrierGeoportalApi
import de.velospot.data.remote.parser.BikeParkingGmlParser
import de.velospot.data.repository.BikeParkingRepositoryImpl
import de.velospot.data.repository.FavoritesRepositoryImpl
import de.velospot.data.repository.RoutingRepositoryImpl
import de.velospot.domain.repository.BikeParkingRepository
import de.velospot.domain.repository.FavoritesRepository
import de.velospot.domain.repository.LocationRepository
import de.velospot.domain.repository.RoutingRepository
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val BASE_URL = "https://geoportal.trier.de/"

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            // BODY level → full response body visible in logcat (helps with WFS debugging)
            level = HttpLoggingInterceptor.Level.BODY
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
            // Lenient factory as first layer: tolerates minor JSON quirks from WFS server
            .addLast(LenientJsonAdapterFactory)
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideTrierGeoportalApi(retrofit: Retrofit): TrierGeoportalApi {
        return retrofit.create(TrierGeoportalApi::class.java)
    }

    @Provides
    @Singleton
    fun provideOsrmApi(retrofit: Retrofit): OsrmApi {
        return retrofit.create(OsrmApi::class.java)
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
        geoportalApi: TrierGeoportalApi,
        cache: BikeParkingLocalDataSource,
        gmlParser: BikeParkingGmlParser
    ): BikeParkingRepository {
        return BikeParkingRepositoryImpl(geoportalApi, cache, gmlParser)
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
 * Allows e.g. leading BOM bytes, single quotes, or slightly incorrect number formats
 * that some GeoServer instances return.
 * If the response completely fails (XML/HTML instead of JSON), the exception is thrown
 * from the repository with the HTTP body, not here.
 */
private object LenientJsonAdapterFactory : JsonAdapter.Factory {
    override fun create(type: Type, annotations: MutableSet<out Annotation>, moshi: Moshi): JsonAdapter<*>? {
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
