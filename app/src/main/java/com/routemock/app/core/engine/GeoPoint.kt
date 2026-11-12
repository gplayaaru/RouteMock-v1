package com.routemock.app.core.engine

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A single point on a route. Plain data — no Android or map-SDK dependency,
 *  so the engine can be unit-tested without a device. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/** Pure geometry helpers shared by the engine and the route layer. */
object GeoUtils {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Haversine distance in metres between two points. */
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Initial bearing in degrees (0 = north, 90 = east) from [a] to [b]. */
    fun bearingDegrees(a: GeoPoint, b: GeoPoint): Float {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val bearing = Math.toDegrees(atan2(y, x))
        return ((bearing + 360) % 360).toFloat()
    }

    /** Total path length in metres. */
    fun totalDistanceMeters(points: List<GeoPoint>): Double {
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += distanceMeters(points[i], points[i + 1])
        }
        return total
    }
}

/** Session states. PAUSED is its own state (not a flag inside MOVING), so the
 *  service can re-inject the frozen position on a different schedule. */
enum class SessionState {
    IDLE, ARMING, MOVING, PAUSED, HOLDING, STOPPED
}

/** One tick's worth of engine output. */
data class EnginePosition(
    val point: GeoPoint,
    val bearing: Float,
    val speedMps: Float,
    val travelledMeters: Double,
    val totalMeters: Double,
    val finished: Boolean
)
