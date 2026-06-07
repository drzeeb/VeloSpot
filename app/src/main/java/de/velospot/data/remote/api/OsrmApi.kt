package de.velospot.data.remote.api

import de.velospot.data.remote.dto.OsrmRouteResponseDto
import retrofit2.http.GET
import retrofit2.http.Url

fun interface OsrmApi {

    @GET
    suspend fun getBikeRoute(@Url url: String): OsrmRouteResponseDto
}

