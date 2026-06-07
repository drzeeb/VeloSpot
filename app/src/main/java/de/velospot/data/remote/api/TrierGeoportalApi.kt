package de.velospot.data.remote.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.QueryMap

fun interface TrierGeoportalApi {

    /**
     * WFS GetFeature request against dedicated map files in the Trier Geoportal.
     * The service returns GML only, so the response is exposed as a raw body and
     * parsed inside the data layer.
     */
    @GET("trier/mod_ogc/wfs_getmap.php")
    suspend fun getBikeParkingLayerGml(
        @Query("mapfile") mapFile: String,
        @QueryMap options: Map<String, String>
    ): Response<ResponseBody>
}
