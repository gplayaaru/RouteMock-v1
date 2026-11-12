package com.routemock.app.core.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.routemock.app.MainActivity
import com.routemock.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the mock-location tick loop.
 *
 * Flow: UI writes the path into [SessionStore] and starts the service with
 * [ACTION_START]. The service registers the GPS / Network / Passive test
 * providers, then ticks every 1 s while MOVING and re-injects the frozen
 * position every 2 s while PAUSED or HOLDING (anti snap-back). STOP
 * unregisters the providers so the real location resumes.
 *
 * If the mock-location app has not been selected in Developer Options,
 * addTestProvider throws SecurityException — the service then stops itself
 * and reports the error through [SessionStore.error] instead of crashing.
 */
class MockLocationService : Service() {

    companion object {
        const val ACTION_START = "com.routemock.app.action.START"
        const val ACTION_PAUSE = "com.routemock.app.action.PAUSE"
        const val ACTION_RESUME = "com.routemock.app.action.RESUME"
        const val ACTION_STOP = "com.routemock.app.action.STOP"
        const val ACTION_JUMP = "com.routemock.app.action.JUMP"
        const val ACTION_EDIT_TIME = "com.routemock.app.action.EDIT_TIME"

        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_MINUTES = "minutes"

        const val CHANNEL_ID = "routemock_session"
        const val NOTIFICATION_ID = 1001

        private val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        fun startIntent(context: Context): Intent =
            Intent(context, MockLocationService::class.java).setAction(ACTION_START)

        fun pauseIntent(context: Context): Intent =
            Intent(context, MockLocationService::class.java).setAction(ACTION_PAUSE)

        fun resumeIntent(context: Context): Intent =
            Intent(context, MockLocationService::class.java).setAction(ACTION_RESUME)

        fun stopIntent(context: Context): Intent =
            Intent(context, MockLocationService::class.java).setAction(ACTION_STOP)

        fun jumpIntent(context: Context, point: GeoPoint): Intent =
            Intent(context, MockLocationService::class.java)
                .setAction(ACTION_JUMP)
                .putExtra(EXTRA_LATITUDE, point.latitude)
                .putExtra(EXTRA_LONGITUDE, point.longitude)

        fun editTimeIntent(context: Context, minutes: Int): Intent =
            Intent(context, MockLocationService::class.java)
                .setAction(ACTION_EDIT_TIME)
                .putExtra(EXTRA_MINUTES, minutes)
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var tickJob: Job? = null
    private var locationManager: LocationManager? = null
    private var providersRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession()
            ACTION_PAUSE -> {
                if (SessionStore.state.value == SessionState.MOVING) {
                    SessionStore.setState(SessionState.PAUSED)
                    updateNotification()
                }
            }
            ACTION_RESUME -> {
                if (SessionStore.state.value == SessionState.PAUSED) {
                    SessionStore.setState(SessionState.MOVING)
                    updateNotification()
                }
            }
            ACTION_STOP -> stopSession()
            ACTION_JUMP -> {
                val lat = intent.getDoubleExtra(EXTRA_LATITUDE, Double.NaN)
                val lon = intent.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN)
                if (!lat.isNaN() && !lon.isNaN()) {
                    SessionStore.engine.jumpTo(GeoPoint(lat, lon))
                    SessionStore.engine.currentPosition()?.let { inject(it) }
                }
            }
            ACTION_EDIT_TIME -> {
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)
                if (minutes > 0) {
                    SessionStore.engine.editRemainingTime(minutes)
                    updateNotification()
                }
            }
            null -> {
                // System restarted the service after a kill: resume ticking
                // only if a session was actually in progress.
                if (SessionStore.engine.hasPath &&
                    SessionStore.state.value == SessionState.MOVING
                ) {
                    startForegroundIfNeeded()
                    startTickLoop()
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startSession() {
        val path = SessionStore.pendingPath
        val duration = SessionStore.pendingDurationMinutes
        if (path.size < 2 || duration <= 0) {
            SessionStore.setError("No route set. Pick Start and End first.")
            stopSelf()
            return
        }
        SessionStore.setError(null)
        SessionStore.setState(SessionState.ARMING)
        startForegroundIfNeeded()
        try {
            registerTestProviders()
        } catch (e: SecurityException) {
            SessionStore.setError(
                "RouteMock is not selected as the mock-location app. " +
                    "Open Developer Options and select RouteMock, then try again."
            )
            SessionStore.setState(SessionState.IDLE)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        SessionStore.engine.start(path, duration)
        SessionStore.setState(SessionState.MOVING)
        SessionStore.setPosition(SessionStore.engine.currentPosition())
        updateNotification()
        startTickLoop()
    }

    private fun startTickLoop() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (SessionStore.state.value == SessionState.MOVING ||
                SessionStore.state.value == SessionState.PAUSED ||
                SessionStore.state.value == SessionState.HOLDING
            ) {
                when (SessionStore.state.value) {
                    SessionState.MOVING -> {
                        val position = SessionStore.engine.tick(1.0)
                        if (position != null) {
                            inject(position)
                            SessionStore.setPosition(position)
                            if (position.finished) {
                                SessionStore.setState(SessionState.HOLDING)
                            }
                        }
                        updateNotification()
                        delay(1000)
                    }
                    SessionState.PAUSED, SessionState.HOLDING -> {
                        // Anti snap-back: keep re-injecting the frozen
                        // position so consumer apps never see the real GPS.
                        SessionStore.engine.currentPosition()?.let { inject(it) }
                        updateNotification()
                        delay(2000)
                    }
                    else -> return@launch
                }
            }
        }
    }

    private fun stopSession() {
        tickJob?.cancel()
        tickJob = null
        unregisterTestProviders()
        SessionStore.setState(SessionState.STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        SessionStore.reset()
        stopSelf()
    }

    private fun registerTestProviders() {
        val manager = locationManager ?: return
        for (provider in PROVIDERS) {
            try {
                manager.removeTestProvider(provider)
            } catch (_: Exception) {
                // Provider was never added — nothing to remove.
            }
            manager.addTestProvider(
                provider,
                false, // requiresNetwork
                false, // requiresSatellite
                false, // requiresCell
                false, // hasMonetaryCost
                true,  // supportsAltitude
                true,  // supportsSpeed
                true,  // supportsBearing
                android.location.Criteria.POWER_LOW,
                android.location.Criteria.ACCURACY_FINE
            )
            manager.setTestProviderEnabled(provider, true)
        }
        providersRegistered = true
    }

    private fun unregisterTestProviders() {
        if (!providersRegistered) return
        val manager = locationManager ?: return
        for (provider in PROVIDERS) {
            try {
                manager.removeTestProvider(provider)
            } catch (_: Exception) {
                // Already gone — fine.
            }
        }
        providersRegistered = false
    }

    /** Inject the same fix into all three providers, so consumer apps reading
     *  the fused provider resolve to the mock position. */
    private fun inject(position: EnginePosition) {
        val manager = locationManager ?: return
        for (provider in PROVIDERS) {
            val location = Location(provider).apply {
                latitude = position.point.latitude
                longitude = position.point.longitude
                accuracy = 3f
                bearing = position.bearing
                bearingAccuracyDegrees = 5f
                speed = position.speedMps
                speedAccuracyMetersPerSecond = 0.5f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                // NOTE: there is no Location.makeComplete() in the public
                // Android API (verified against the official Location
                // reference — only isComplete() exists). It was an invented
                // call and broke compileDebugKotlin in CI run #5, so it is
                // removed. The fields above already make the fix complete:
                // provider + accuracy + non-zero time + elapsed realtime.
            }
            try {
                manager.setTestProviderLocation(provider, location)
            } catch (_: Exception) {
                // One provider failing must not kill the tick loop; the
                // other two still carry the fix.
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows the active RouteMock session and its controls."
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundIfNeeded() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val state = SessionStore.state.value
        val position = SessionStore.position.value
        val progress = position?.let {
            if (it.totalMeters > 0) {
                ((it.travelledMeters / it.totalMeters) * 100).toInt()
            } else 0
        } ?: 0
        val text = when (state) {
            SessionState.MOVING -> "Moving — $progress% complete"
            SessionState.PAUSED -> "Paused at $progress%"
            SessionState.HOLDING -> "Route complete — holding position"
            SessionState.ARMING -> "Starting mock session…"
            else -> "RouteMock session"
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        val pauseOrResume = if (state == SessionState.PAUSED) {
            NotificationCompat.Action.Builder(
                0, getString(R.string.notification_action_resume),
                PendingIntent.getService(
                    this, 1, resumeIntent(this),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            ).build()
        } else {
            NotificationCompat.Action.Builder(
                0, getString(R.string.notification_action_pause),
                PendingIntent.getService(
                    this, 1, pauseIntent(this),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            ).build()
        }
        builder.addAction(pauseOrResume)
        builder.addAction(
            NotificationCompat.Action.Builder(
                0, getString(R.string.notification_action_stop),
                PendingIntent.getService(
                    this, 2, stopIntent(this),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            ).build()
        )
        return builder.build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Foreground service keeps running when the app is swiped away from
        // Recents; the session only ends on Stop. Nothing to do here.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        tickJob?.cancel()
        unregisterTestProviders()
        scope.cancel()
        super.onDestroy()
    }
}
