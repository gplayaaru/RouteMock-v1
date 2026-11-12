package com.routemock.app.core.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single in-process home for the active mock session.
 *
 * The ViewModel writes the path here before starting the service, and the
 * service drives the engine from here. The UI observes [state] and
 * [position] to render progress. This keeps the Activity, ViewModel and
 * Service in sync without a Binder interface (v0.1 simplicity; a Binder
 * can replace this later without touching the engine).
 */
object SessionStore {
    val engine = MockLocationEngine()

    private val _state = MutableStateFlow(SessionState.IDLE)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _position = MutableStateFlow<EnginePosition?>(null)
    val position: StateFlow<EnginePosition?> = _position.asStateFlow()

    /** Human-readable error from the last failed start (e.g. mock app not
     *  selected). Shown in the UI; cleared on the next start attempt. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** The path the UI handed over for the next session (set by the
     *  ViewModel just before starting the service). */
    var pendingPath: List<GeoPoint> = emptyList()
    var pendingDurationMinutes: Int = 0

    fun setState(newState: SessionState) {
        _state.value = newState
    }

    fun setPosition(position: EnginePosition?) {
        _position.value = position
    }

    fun setError(message: String?) {
        _error.value = message
    }

    fun reset() {
        engine.clear()
        pendingPath = emptyList()
        pendingDurationMinutes = 0
        _position.value = null
        _error.value = null
        _state.value = SessionState.IDLE
    }
}
