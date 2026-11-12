package com.routemock.app.core.routes

import com.routemock.app.core.engine.GeoPoint
import com.routemock.app.core.engine.GeoUtils
import kotlinx.coroutines.delay

/** One fetched route option. [isFallback] means OSRM failed and this is a
 *  straight line — the UI must show the offline banner for it. */
data class RouteOption(
    val points: List<GeoPoint>,
    val distanceMeters: Double,
    val isFallback: Boolean = false
)

data class SearchResult(
    val displayName: String,
    val point: GeoPoint
)

/**
 * Route + search layer. All network goes through here so the ViewModel
 * never touches Retrofit directly.
 *
 * - Route results are cached in memory by coordinate key, so re-picking the
 *   same Start/End costs no second API call.
 * - Route fetch retries twice with backoff, then falls back to a straight
 *   line (PRD behaviour) instead of failing the session.
 * - Search results are cached by query string; the ViewModel debounces
 *   keystrokes before calling here.
 */
class RouteRepository(
    private val osrm: OsrmApi = ApiClients.osrm,
    private val nominatim: NominatimApi = ApiClients.nominatim
) {
    private val routeCache = mutableMapOf<String, List<RouteOption>>()
    private val searchCache = mutableMapOf<String, List<SearchResult>>()

    /** Fetch up to 2 route options (main + 1 alternative) for Start → End. */
    suspend fun getRoute(start: GeoPoint, end: GeoPoint): List<RouteOption> {
        val key = "${start.latitude},${start.longitude}|${end.latitude},${end.longitude}"
        routeCache[key]?.let { return it }
        val coords = "${start.longitude},${start.latitude};${end.longitude},${end.latitude}"
        repeat(3) { attempt ->
            try {
                val response = osrm.route(coords)
                if (response.code == "Ok") {
                    val options = (response.routes ?: emptyList())
                        .take(2)
                        .mapNotNull { route ->
                            val coordsList = route.geometry?.coordinates ?: return@mapNotNull null
                            val points = coordsList.mapNotNull { pair ->
                                if (pair.size >= 2) GeoPoint(pair[1], pair[0]) else null
                            }
                            if (points.size < 2) null
                            else RouteOption(
                                points = points,
                                distanceMeters = route.distance
                                    ?: GeoUtils.totalDistanceMeters(points)
                            )
                        }
                    if (options.isNotEmpty()) {
                        routeCache[key] = options
                        return options
                    }
                }
            } catch (_: Exception) {
                // Retry below; after 3 attempts the straight-line fallback
                // is returned instead of failing.
            }
            if (attempt < 2) delay(500L * (attempt + 1))
        }
        // Offline fallback: straight line, clearly flagged.
        val fallback = RouteOption(
            points = listOf(start, end),
            distanceMeters = GeoUtils.distanceMeters(start, end),
            isFallback = true
        )
        // Do not cache the fallback — the next fetch should retry OSRM.
        return listOf(fallback)
    }

    /** Address search. Returns empty list on failure (never throws). */
    suspend fun search(query: String): List<SearchResult> {
        val trimmed = query.trim()
        if (trimmed.length < 3) return emptyList()
        searchCache[trimmed.lowercase()]?.let { return it }
        return try {
            val results = nominatim.search(trimmed).mapNotNull { item ->
                val lat = item.lat?.toDoubleOrNull()
                val lon = item.lon?.toDoubleOrNull()
                val name = item.displayName
                if (lat == null || lon == null || name.isNullOrBlank()) null
                else SearchResult(name, GeoPoint(lat, lon))
            }
            searchCache[trimmed.lowercase()] = results
            results
        } catch (_: Exception) {
            emptyList()
        }
    }
}
