package de.velospot.data.remote.api

import de.velospot.data.remote.dto.OsrmRouteResponseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

fun interface OsrmApi {

    /**
     * Fetches a bicycle route. [url] is resolved against the Retrofit base URL, so
     * callers pass a **relative** path (`route/v1/bicycle/…`) — keeping the OSRM
     * host defined in exactly one place (`NetworkModule`). Wrapped in [Response] so
     * HTTP errors (4xx/5xx) can be handled explicitly instead of throwing.
     */
    @GET
    suspend fun getBikeRoute(@Url url: String): Response<OsrmRouteResponseDto>
}

