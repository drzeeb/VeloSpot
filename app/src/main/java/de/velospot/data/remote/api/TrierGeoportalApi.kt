package de.velospot.data.remote.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface TrierGeoportalApi {

    /**
     * WFS GetFeature request against dedicated map files in the Trier Geoportal.
     * The service returns GML only, so the response is exposed as a raw body and
     * parsed inside the data layer.
     */
    @GET("trier/mod_ogc/wfs_getmap.php")
    suspend fun getBikeParkingLayerGml(
        @Query("mapfile") mapFile: String,
        @Query("service") service: String = "WFS",
        @Query("version") version: String = "2.0.0",
        @Query("request") request: String = "GetFeature",
        @Query("typeNames") typeNames: String,
        @Query("srsName") srsName: String = "urn:ogc:def:crs:EPSG::4326",
        @Query("count") count: Int = 1000,
        @Query("outputFormat") outputFormat: String = "text/xml; subtype=gml/3.2.1"
    ): Response<ResponseBody>
}
