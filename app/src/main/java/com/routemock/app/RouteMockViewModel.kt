package com.routemock.app

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.routemock.app.core.engine.GeoPoint
import com.routemock.app.core.engine.MockLocationService
import com.routemock.app.core.engine.MockSetupChecker
import com.routemock.app.core.engine.SessionState
import com.routemock.app.core.engine.SessionStore
import com.routemock.app.core.routes.RouteOption
import com.routemock.app.core.routes.RouteRepository
import com.routemock.app.core.routes.SearchResult
import com.routemock.app.feature.planner.Vehicle
import com.routemock.app.feature.planner.impliedSpeedKph
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Holds all planner + session UI state and talks to the service.
 * The engine itself lives in SessionStore / MockLocationService; this class
 * never injects locations directly.
 */
class RouteMockViewModel(
    application: Application,
    private val routes: RouteRepository = RouteRepository()
) : AndroidViewModel(application) {

    // --- Planner state ---
    var start by mutableStateOf<GeoPoint?>(null)
        private set
    var end by mutableStateOf<GeoPoint?>(null)
        private set
    var routeOptions by mutableStateOf<List<RouteOption>>(emptyList())
        private set
    var selectedRouteIndex by mutableStateOf(0)
        private set
    var isLoadingRoute by mutableStateOf(false)
        private set
    var isOfflineFallback by mutableStateOf(false)
        private set

    var durationText by mutableStateOf("10")
        private set
    var selectedVehicle by mutableStateOf<Vehicle?>(null)
        private set

    // --- Search state ---
    var searchQuery by mutableStateOf("")
        private set
    var searchResults by mutableStateOf<List<SearchResult>>(emptyList())
        private set
    var isSearching by mutableStateOf(false)
        private set
    /** Which point the next search result should fill: "start" or "end". */
    var searchTarget by mutableStateOf("end")
        private set

    // --- Setup / status ---
    var mockAppSelected by mutableStateOf(false)
        private set
    var hasLocationPermission by mutableStateOf(false)
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set

    val sessionState = SessionStore.state
    val sessionPosition = SessionStore.position
    val sessionError = SessionStore.error

    private var searchJob: Job? = null
    private var routeJob: Job? = null

    init {
        refreshSetupStatus()
    }

    val selectedRoute: RouteOption?
        get() = routeOptions.getOrNull(selectedRouteIndex)

    val distanceMeters: Double
        get() = selectedRoute?.distanceMeters ?: 0.0

    val durationMinutes: Int
        get() = durationText.toIntOrNull() ?: 0

    /** Implied average speed for the chosen duration, km/h. */
    val impliedSpeed: Double
        get() = impliedSpeedKph(distanceMeters, durationMinutes)

    val sessionActive: Boolean
        get() = sessionState.value == SessionState.MOVING ||
            sessionState.value == SessionState.PAUSED ||
            sessionState.value == SessionState.HOLDING

    fun refreshSetupStatus() {
        val context = getApplication<Application>()
        mockAppSelected = MockSetupChecker.isMockLocationAppSelected(context)
        hasLocationPermission = MockSetupChecker.hasLocationPermission(context)
    }

    fun openDeveloperOptions() {
        val context = getApplication<Application>()
        val intent = MockSetupChecker.developerOptionsIntent()
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // --- Map interaction ---

    /** Long-press: 1st = Start, 2nd = End, later presses move End.
     *  Route editing mid-session is not allowed (PRD rule). */
    fun onMapLongPress(point: GeoPoint) {
        if (sessionActive) {
            statusMessage = "Route cannot be edited mid-session. Stop first."
            return
        }
        statusMessage = null
        if (start == null) {
            start = point
        } else if (end == null) {
            end = point
            fetchRoute()
        } else {
            end = point
            fetchRoute()
        }
    }

    /** Map tap during a session: if the tap is close to the route, jump. */
    fun onMapClick(point: GeoPoint) {
        if (!sessionActive) return
        val engine = SessionStore.engine
        if (!engine.hasPath) return
        if (engine.nearestDistanceMeters(point) > 100.0) return
        val context = getApplication<Application>()
        context.startService(MockLocationService.jumpIntent(context, point))
    }

    fun clearRoute() {
        if (sessionActive) return
        start = null
        end = null
        routeOptions = emptyList()
        selectedRouteIndex = 0
        isOfflineFallback = false
        statusMessage = null
    }

    fun selectRoute(index: Int) {
        if (index in routeOptions.indices) {
            selectedRouteIndex = index
            isOfflineFallback = routeOptions[index].isFallback
            refillDurationFromVehicle()
        }
    }

    // --- Duration / vehicle ---

    fun setDurationText(text: String) {
        // Digits only, max 4 chars (up to 9999 minutes).
        durationText = text.filter { it.isDigit() }.take(4)
    }

    fun selectVehicle(vehicle: Vehicle?) {
        selectedVehicle = vehicle
        refillDurationFromVehicle()
    }

    private fun refillDurationFromVehicle() {
        val vehicle = selectedVehicle ?: return
        if (distanceMeters > 0) {
            durationText = vehicle.recommendedMinutes(distanceMeters).toString()
        }
    }

    // --- Route fetching ---

    private fun fetchRoute() {
        val from = start ?: return
        val to = end ?: return
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            isLoadingRoute = true
            val options = routes.getRoute(from, to)
            routeOptions = options
            selectedRouteIndex = 0
            isOfflineFallback = options.firstOrNull()?.isFallback == true
            isLoadingRoute = false
            refillDurationFromVehicle()
        }
    }

    // --- Search ---

    fun setSearchQuery(query: String) {
        searchQuery = query
        searchJob?.cancel()
        if (query.trim().length < 3) {
            searchResults = emptyList()
            isSearching = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(600) // debounce: Nominatim allows ~1 request/second
            isSearching = true
            searchResults = routes.search(query)
            isSearching = false
        }
    }

    fun setSearchTarget(target: String) {
        searchTarget = target
    }

    fun onSearchResultSelected(result: SearchResult) {
        if (sessionActive) return
        if (searchTarget == "start") {
            start = result.point
        } else {
            end = result.point
        }
        searchResults = emptyList()
        searchQuery = ""
        if (start != null && end != null) fetchRoute()
    }

    // --- Session control (all go through the foreground service) ---

    fun startSession() {
        val context = getApplication<Application>()
        refreshSetupStatus()
        val path = selectedRoute?.points
        if (path == null || path.size < 2) {
            statusMessage = "Pick Start and End on the map first."
            return
        }
        if (durationMinutes <= 0) {
            statusMessage = "Enter a duration in minutes."
            return
        }
        if (!hasLocationPermission) {
            statusMessage = "Location permission is needed first."
            return
        }
        if (!mockAppSelected) {
            statusMessage = "Select RouteMock as the mock-location app in Developer Options first."
            return
        }
        SessionStore.pendingPath = path
        SessionStore.pendingDurationMinutes = durationMinutes
        SessionStore.setError(null)
        statusMessage = null
        context.startForegroundService(MockLocationService.startIntent(context))
    }

    fun pauseSession() {
        val context = getApplication<Application>()
        context.startService(MockLocationService.pauseIntent(context))
    }

    fun resumeSession() {
        val context = getApplication<Application>()
        context.startService(MockLocationService.resumeIntent(context))
    }

    fun stopSession() {
        val context = getApplication<Application>()
        context.startService(MockLocationService.stopIntent(context))
    }

    fun editRemainingTime(minutes: Int) {
        if (minutes <= 0) return
        val context = getApplication<Application>()
        context.startService(MockLocationService.editTimeIntent(context, minutes))
    }

    fun dismissStatus() {
        statusMessage = null
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RouteMockViewModel(app) as T
        }
    }
}
