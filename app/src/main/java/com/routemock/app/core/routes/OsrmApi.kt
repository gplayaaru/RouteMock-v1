package com.routemock.app.core.routes

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * OSRM public demo server (router.project-osrm.org). No API key.
 * Demo server only — no uptime guarantee, not for production traffic.
 * Coordinates in the URL are ALWAYS longitude,latitude (OSRM convention).
 *
 * Response shape used here (geometries=geojson, overview=full):
 *   { "code": "Ok",
 *     "routes": [ { "distance": 1234.5, "duration": 345.6,
 *                   "geometry": { "type": "LineString",
 *                                 "coordinates": [[lon, lat], ...] } } ] }
 */
interface OsrmApi {
    @GET("route/v1/driving/{coords}")
    suspend fun route(
        @Path(value = "coords", encoded = true) coords: String,
        @Query("alternatives") alternatives: Boolean = true,
        @Query("overview") overview: String = "full",
        @Query("geometries") geometries: String = "geojson"
    ): OsrmRouteResponse
}

data class OsrmRouteResponse(
    val code: String?,
    val routes: List<OsrmRoute>?
)

data class OsrmRoute(
    val distance: Double?,
    val duration: Double?,
    val geometry: OsrmGeometry?
)

data class OsrmGeometry(
    val type: String?,
    val coordinates: List<List<Double>>?
)

/**
 * Nominatim (OpenStreetMap search). Usage policy requires a descriptive
 * User-Agent and at most ~1 request/second — the app debounces and caches,
 * and the interceptor below sets the User-Agent on every call.
 * Response items: [{ "display_name": "...", "lat": "28.6", "lon": "77.2" }]
 */
interface NominatimApi {
    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("format") format: String = "json",
        @Query("limit") limit: Int = 5
    ): List<NominatimResult>
}

data class NominatimResult(
    @SerializedName("display_name") val displayName: String?,
    val lat: String?,
    val lon: String?
)

object ApiClients {
    private const val USER_AGENT = "RouteMock/0.1 (Android prototype; development use)"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    val osrm: OsrmApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://router.project-osrm.org/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OsrmApi::class.java)
    }

    val nominatim: NominatimApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://nominatim.openstreetmap.org/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NominatimApi::class.java)
    }
}
