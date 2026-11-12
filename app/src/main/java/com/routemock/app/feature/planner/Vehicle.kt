package com.routemock.app.feature.planner

import kotlin.math.roundToInt

/**
 * Vehicle recommendation (PRD §5.3).
 * recommended_minutes = round((distance_km / speed_kph) * 60).
 *
 * NOTE — review before any release: these PRD speeds are rough. 70 km/h
 * is unrealistic on most city routes, and a motorcycle being slower than
 * a car is questionable. The formula is kept as specified; only the
 * numbers should be revisited with real route data.
 */
enum class Vehicle(val label: String, val speedKph: Int) {
    CAR("Car", 70),
    MOTORCYCLE("Motorcycle", 50),
    BICYCLE("Bicycle", 10);

    fun recommendedMinutes(distanceMeters: Double): Int {
        val distanceKm = distanceMeters / 1000.0
        return ((distanceKm / speedKph) * 60).roundToInt().coerceAtLeast(1)
    }
}

/** Implied average speed in km/h for a chosen duration. */
fun impliedSpeedKph(distanceMeters: Double, durationMinutes: Int): Double {
    if (durationMinutes <= 0) return 0.0
    return (distanceMeters / 1000.0) / (durationMinutes / 60.0)
}
