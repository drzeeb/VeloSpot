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
import de.velospot.data.remote.api.TrierGeoportalApi
import de.velospot.data.remote.parser.BikeParkingGmlParser
import de.velospot.data.repository.BikeParkingRepositoryImpl
import de.velospot.domain.repository.BikeParkingRepository
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
            // BODY-Level → voller Response-Body im Logcat sichtbar (hilft bei WFS-Debugging)
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
            // Lenient-Factory als erste Schicht: toleriert kleine JSON-Quirks des WFS-Servers
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
    fun provideBikeParkingRepository(
        geoportalApi: TrierGeoportalApi,
        @ApplicationContext context: Context,
        moshi: Moshi
    ): BikeParkingRepository {
        val cache = BikeParkingCacheDataSource(context, moshi)
        val gmlParser = BikeParkingGmlParser()
        return BikeParkingRepositoryImpl(geoportalApi, cache, gmlParser)
    }
}

/**
 * Setzt [JsonReader.isLenient] = true auf allen Adaptern.
 * Erlaubt z.B. führende BOM-Bytes, einfache Anführungszeichen oder
 * leicht fehlerhafte Zahlenformate, die manche GeoServer-Instanzen liefern.
 * Schlägt die Antwort jedoch komplett fehl (XML/HTML statt JSON), wird die
 * Exception mit dem HTTP-Body aus dem Repository geworfen, nicht hier.
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
