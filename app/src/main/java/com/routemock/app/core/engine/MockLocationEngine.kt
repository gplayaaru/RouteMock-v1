package com.routemock.app.core.engine

import kotlin.math.roundToInt

/**
 * Pure mock-location engine: no Android classes, no map SDK, no I/O.
 *
 * The path is precomputed into cumulative distances at [start] time. Each
 * [tick] advances [travelledMeters] by speed x dt and interpolates the
 * position along the path. Speed is derived from the user-chosen duration:
 * speed = totalDistance / duration.
 *
 * The service owns the wall-clock tick loop and the LocationManager calls;
 * this class only owns the maths, so it can be unit-tested on the JVM.
 */
class MockLocationEngine {

    private var points: List<GeoPoint> = emptyList()
    private var cumulative: DoubleArray = doubleArrayOf()
    private var totalMeters: Double = 0.0
    private var speedMps: Double = 0.0
    private var durationMinutes: Int = 0

    /** Metres already travelled along the path. */
    var travelledMeters: Double = 0.0
        private set

    val totalDistanceMeters: Double get() = totalMeters
    val currentSpeedMps: Double get() = speedMps

    /** True once a path has been set and not yet cleared. */
    val hasPath: Boolean get() = points.size >= 2

    fun start(path: List<GeoPoint>, durationMinutes: Int) {
        require(path.size >= 2) { "Path needs at least 2 points" }
        require(durationMinutes > 0) { "Duration must be > 0 minutes" }
        this.points = path.toList()
        this.durationMinutes = durationMinutes
        cumulative = DoubleArray(path.size)
        var running = 0.0
        for (i in 1 until path.size) {
            running += GeoUtils.distanceMeters(path[i - 1], path[i])
            cumulative[i] = running
        }
        totalMeters = running
        speedMps = if (totalMeters > 0) totalMeters / (durationMinutes * 60.0) else 0.0
        travelledMeters = 0.0
    }

    /** Advance by [dtSeconds] and return the new position, or null if no path. */
    fun tick(dtSeconds: Double): EnginePosition? {
        if (!hasPath) return null
        travelledMeters = (travelledMeters + speedMps * dtSeconds).coerceAtMost(totalMeters)
        return currentPosition()
    }

    /** Position at the current travelled distance without advancing. */
    fun currentPosition(): EnginePosition? {
        if (!hasPath) return null
        val point = interpolate(travelledMeters)
        val next = interpolate((travelledMeters + 1.0).coerceAtMost(totalMeters))
        val bearing = if (travelledMeters >= totalMeters) lastBearing()
            else GeoUtils.bearingDegrees(point, next)
        return EnginePosition(
            point = point,
            bearing = bearing,
            speedMps = speedMps.toFloat(),
            travelledMeters = travelledMeters,
            totalMeters = totalMeters,
            finished = travelledMeters >= totalMeters
        )
    }

    /** Change the remaining time. Speed is recalculated from the current
     *  position, so the change is seamless — no jump, no restart. */
    fun editRemainingTime(newMinutes: Int) {
        require(newMinutes > 0) { "Remaining time must be > 0 minutes" }
        val remaining = (totalMeters - travelledMeters).coerceAtLeast(0.0)
        durationMinutes = newMinutes
        speedMps = if (remaining > 0) remaining / (newMinutes * 60.0) else 0.0
    }

    /** Move the mock position to the nearest point on the path to [target]
     *  (tap-to-jump) and continue from there. Returns false if no path. */
    fun jumpTo(target: GeoPoint): Boolean {
        if (!hasPath) return false
        var bestIndex = 0
        var bestDistance = Double.MAX_VALUE
        for (i in points.indices) {
            val d = GeoUtils.distanceMeters(target, points[i])
            if (d < bestDistance) {
                bestDistance = d
                bestIndex = i
            }
        }
        travelledMeters = cumulative[bestIndex]
        return true
    }

    /** Nearest cumulative distance to [target]; used by the UI to decide
     *  whether a map tap is close enough to the path to count as a jump. */
    fun nearestDistanceMeters(target: GeoPoint): Double {
        if (!hasPath) return Double.MAX_VALUE
        return points.minOf { GeoUtils.distanceMeters(target, it) }
    }

    /** Fraction of the route completed, 0.0–1.0. */
    fun progressFraction(): Double =
        if (totalMeters > 0) (travelledMeters / totalMeters).coerceIn(0.0, 1.0) else 0.0

    /** Whole minutes remaining at the current speed. */
    fun etaMinutesRemaining(): Int {
        if (speedMps <= 0) return 0
        val remaining = (totalMeters - travelledMeters).coerceAtLeast(0.0)
        return (remaining / speedMps / 60.0).roundToInt()
    }

    fun clear() {
        points = emptyList()
        cumulative = doubleArrayOf()
        totalMeters = 0.0
        speedMps = 0.0
        travelledMeters = 0.0
    }

    private fun interpolate(distance: Double): GeoPoint {
        if (distance <= 0.0) return points.first()
        if (distance >= totalMeters) return points.last()
        var segment = 1
        while (segment < cumulative.size && cumulative[segment] < distance) segment++
        val start = points[segment - 1]
        val end = points[segment]
        val segmentLength = cumulative[segment] - cumulative[segment - 1]
        if (segmentLength <= 0) return end
        val fraction = (distance - cumulative[segment - 1]) / segmentLength
        return GeoPoint(
            latitude = start.latitude + (end.latitude - start.latitude) * fraction,
            longitude = start.longitude + (end.longitude - start.longitude) * fraction
        )
    }

    private fun lastBearing(): Float {
        if (points.size < 2) return 0f
        return GeoUtils.bearingDegrees(points[points.size - 2], points.last())
    }
}
