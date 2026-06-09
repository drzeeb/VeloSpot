package de.velospot.data.remote.api

import de.velospot.data.remote.dto.NominatimReverseDto
import de.velospot.data.remote.dto.NominatimSearchResultDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

/**
 * Retrofit interface for Nominatim geocoding endpoints.
 *
 * Base URL: https://nominatim.openstreetmap.org/
 *
 * Nominatim Usage Policy:
 *  - User-Agent header identifying the application is mandatory  ✓
 *  - At most 1 request per second                               ✓ (user-triggered + debounced)
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

    /**
     * Forward geocoding: converts a free-text address query to a list of geo-coordinates.
     *
     * Results are restricted to Germany (`countrycodes=de`) since VeloSpot covers DE only.
     */
    @GET("search")
    @Headers("User-Agent: VeloSpot/1.0 (https://github.com/velospot)")
    suspend fun search(
        @Query("q")            query: String,
        @Query("format")       format: String = "json",
        @Query("limit")        limit: Int = 5,
        @Query("countrycodes") countryCodes: String = "de",
        @Query("accept-language") language: String = "de"
    ): Response<List<NominatimSearchResultDto>>
}
