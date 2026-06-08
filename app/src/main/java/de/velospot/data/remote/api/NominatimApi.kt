package de.velospot.data.remote.api

import de.velospot.data.remote.dto.NominatimReverseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

/**
 * Retrofit interface for the Nominatim reverse geocoding endpoint.
 *
 * Base URL: https://nominatim.openstreetmap.org/
 *
 * Nominatim Usage Policy:
 *  - User-Agent header identifying the application is mandatory  ✓
 *  - At most 1 request per second                               ✓ (user-triggered only)
 *  - No automated bulk requests                                  ✓
 */
interface NominatimApi {

    @GET("reverse")
    @Headers("User-Agent: VeloSpot/1.0 (https://github.com/velospot)")
    suspend fun reverseGeocode(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("format") format: String = "json",
        @Query("addressdetails") addressDetails: Int = 1,
        @Query("accept-language") language: String = "de"
    ): Response<NominatimReverseDto>
}
