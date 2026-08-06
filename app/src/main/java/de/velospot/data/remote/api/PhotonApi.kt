package de.velospot.data.remote.api

import de.velospot.data.remote.dto.PhotonResponseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

/**
 * Retrofit interface for Komoot's Photon geocoding endpoints.
 *
 * Base URL: https://photon.komoot.io/
 *
 * Photon does not require a User-Agent header and only asks for fair use of its
 * public instance, but a self-identifying header is kept for good measure.
 */
interface PhotonApi {

    /**
     * Forward geocoding: converts a free-text query to a list of GeoJSON features.
     *
     * Photon has no country-code filter; when the user's location is known,
     * [lat]/[lon] bias the ranking toward the surrounding area. Country
     * restriction (DE/FR/LU) is applied client-side.
     */
    @GET("api")
    @Headers("User-Agent: VeloSpot/1.0 (https://github.com/velospot)")
    suspend fun search(
        @Query("q")     query: String,
        @Query("lang")  lang: String = "de",
        @Query("limit") limit: Int = 5,
        @Query("lat")   lat: Double? = null,
        @Query("lon")   lon: Double? = null
    ): Response<PhotonResponseDto>

    /**
     * Reverse geocoding: converts a coordinate to a GeoJSON feature.
     */
    @GET("reverse")
    @Headers("User-Agent: VeloSpot/1.0 (https://github.com/velospot)")
    suspend fun reverse(
        @Query("lat")  lat: Double,
        @Query("lon")  lon: Double,
        @Query("lang") lang: String = "de"
    ): Response<PhotonResponseDto>
}

