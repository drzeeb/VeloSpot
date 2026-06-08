package de.velospot.data.remote.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import retrofit2.http.Url

fun interface OverpassApi {

    @FormUrlEncoded
    @POST
    suspend fun query(
        @Url url: String,
        @Field("data") query: String
    ): Response<ResponseBody>
}
