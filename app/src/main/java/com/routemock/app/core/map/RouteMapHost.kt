package com.routemock.app.core.map

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import com.routemock.app.core.engine.GeoPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/**
 * Thin wrapper around MapLibre's MapView.
 *
 * The rest of the app only talks to [GeoPoint] and this host — no other
 * file imports MapLibre classes. That is the seam where a different map
 * vendor (Google / Mapbox) can be swapped in later without touching the
 * engine, the routes layer, or the session UI.
 *
 * Style: the public MapLibre demo style (demotiles.maplibre.org). Free,
 * no key. It is a demo — a production build should self-host its tiles
 * or use a tile provider with an SLA.
 *
 * MapLibre API surface used here is the long-stable Mapbox-derived API
 * (MapView / getMapAsync / addMarker / addPolyline / camera updates).
 * If Android Studio reports a changed signature on a newer SDK version,
 * this file is the only place that needs fixing.
 */
class RouteMapHost(private val context: Context) {

    /** Default camera: New Delhi, India (zoomed to city level). */
    private val defaultCenter = LatLng(28.6139, 77.2090)

    var onLongPress: ((GeoPoint) -> Unit)? = null
    var onMapClick: ((GeoPoint) -> Unit)? = null

    val mapView: MapView by lazy {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                mapLibreMap = map
                map.setStyle(STYLE_URL) { applyPending() }
                map.addOnMapLongClickListener { point ->
                    onLongPress?.invoke(GeoPoint(point.latitude, point.longitude))
                    true
                }
                map.addOnMapClickListener { point ->
                    onMapClick?.invoke(GeoPoint(point.latitude, point.longitude))
                    true
                }
                map.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                    .target(defaultCenter)
                    .zoom(12.0)
                    .build()
            }
        }
    }

    private var mapLibreMap: MapLibreMap? = null
    private var styleReady = false

    private var startMarker: Marker? = null
    private var endMarker: Marker? = null
    private var currentMarker: Marker? = null
    private var routePolyline: Polyline? = null

    // Last requested state, applied once the style loads.
    private var pendingRoute: List<GeoPoint> = emptyList()
    private var pendingStart: GeoPoint? = null
    private var pendingEnd: GeoPoint? = null

    fun onStart() = mapView.onStart()
    fun onResume() = mapView.onResume()
    fun onPause() = mapView.onPause()
    fun onStop() = mapView.onStop()
    fun onDestroy() = mapView.onDestroy()
    fun onSaveInstanceState(outState: Bundle) = mapView.onSaveInstanceState(outState)

    /** Re-render markers + polyline from the current UI state. Called from
     *  the Compose AndroidView update block on every recomposition. */
    fun sync(route: List<GeoPoint>, start: GeoPoint?, end: GeoPoint?, current: GeoPoint?) {
        pendingRoute = route
        pendingStart = start
        pendingEnd = end
        if (!styleReady) return
        drawRoute(route)
        drawMarker(start, true)
        drawMarker(end, false)
        drawCurrent(current)
    }

    fun moveCameraToRoute(route: List<GeoPoint>) {
        val map = mapLibreMap ?: return
        if (route.size < 2) return
        val bounds = LatLngBounds.Builder()
        route.forEach { bounds.include(LatLng(it.latitude, it.longitude)) }
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 120))
    }

    private fun applyPending() {
        styleReady = true
        drawRoute(pendingRoute)
        drawMarker(pendingStart, true)
        drawMarker(pendingEnd, false)
    }

    private fun drawRoute(route: List<GeoPoint>) {
        val map = mapLibreMap ?: return
        routePolyline?.remove()
        routePolyline = null
        if (route.size < 2) return
        routePolyline = map.addPolyline(
            PolylineOptions()
                .addAll(route.map { LatLng(it.latitude, it.longitude) })
                .color(Color.parseColor("#1565C0"))
                .width(6f)
        )
    }

    private fun drawMarker(point: GeoPoint?, isStart: Boolean) {
        val map = mapLibreMap ?: return
        if (isStart) {
            startMarker?.remove()
            startMarker = null
            if (point != null) {
                startMarker = map.addMarker(
                    MarkerOptions()
                        .position(LatLng(point.latitude, point.longitude))
                        .title("Start")
                )
            }
        } else {
            endMarker?.remove()
            endMarker = null
            if (point != null) {
                endMarker = map.addMarker(
                    MarkerOptions()
                        .position(LatLng(point.latitude, point.longitude))
                        .title("End")
                )
            }
        }
    }

    private fun drawCurrent(point: GeoPoint?) {
        val map = mapLibreMap ?: return
        if (point == null) {
            currentMarker?.remove()
            currentMarker = null
            return
        }
        val latLng = LatLng(point.latitude, point.longitude)
        val existing = currentMarker
        if (existing == null) {
            currentMarker = map.addMarker(
                MarkerOptions().position(latLng).title("You (mock)")
            )
        } else {
            existing.position = latLng
        }
    }

    companion object {
        const val STYLE_URL = "https://demotiles.maplibre.org/tiles/tiles.json"
    }
}
