package com.routemock.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.routemock.app.core.engine.SessionState
import com.routemock.app.core.engine.SessionStore
import com.routemock.app.core.map.RouteMapHost
import com.routemock.app.feature.planner.Vehicle

/**
 * v0.1 single screen: MapLibre map on top, planner / session sheet below.
 *
 * How to use (also in README.md):
 *  1. Grant location permission, then select RouteMock as the mock-location
 *     app in Developer Options (the app shows a button for this).
 *  2. Long-press the map once for Start, again for End (or search an
 *     address). A road route appears.
 *  3. Pick a vehicle (optional) and a duration in minutes, tap Start.
 *  4. Open Google Maps (or any consumer app) — it should show the mock
 *     position moving along the route.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                RouteMockScreen()
            }
        }
    }
}

@Composable
fun RouteMockScreen() {
    val context = LocalContext.current
    val application = context.applicationContext as android.app.Application
    val vm: RouteMockViewModel = viewModel(factory = RouteMockViewModel.Factory(application))
    val host = remember { RouteMapHost(context) }

    val sessionState by vm.sessionState.collectAsState()
    val sessionPosition by vm.sessionPosition.collectAsState()
    val sessionError by vm.sessionError.collectAsState()

    // Forward the Activity lifecycle to the MapView.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> host.onStart()
                Lifecycle.Event.ON_RESUME -> host.onResume()
                Lifecycle.Event.ON_PAUSE -> host.onPause()
                Lifecycle.Event.ON_STOP -> host.onStop()
                Lifecycle.Event.ON_DESTROY -> host.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            host.onDestroy()
        }
    }

    // Permissions: location always; notifications on Android 13+.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.refreshSetupStatus() }
    DisposableEffect(Unit) {
        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (!vm.hasLocationPermission) permissionLauncher.launch(wanted.toTypedArray())
        onDispose { }
    }

    var showStopConfirm by remember { mutableStateOf(false) }
    var showEditTime by remember { mutableStateOf(false) }
    var editTimeText by remember { mutableStateOf("") }

    val routePoints = vm.selectedRoute?.points ?: emptyList()
    val currentPoint = sessionPosition?.point

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { host.mapView },
            update = { host.sync(routePoints, vm.start, vm.end, currentPoint) },
            modifier = Modifier.fillMaxSize()
        )

        // Top bar: title + setup status.
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("RouteMock", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    if (vm.mockAppSelected) "Mock: ON" else "Mock: OFF",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (vm.mockAppSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
        }

        // Bottom sheet.
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (!vm.mockAppSelected) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Mock-location app not selected")
                            Text(
                                "Open Developer Options, tap 'Select mock location app', " +
                                    "then choose RouteMock. Without this, Start will fail.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row {
                                Button(onClick = { vm.openDeveloperOptions() }) {
                                    Text("Open Developer Options")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                OutlinedButton(onClick = { vm.refreshSetupStatus() }) {
                                    Text("Check again")
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (vm.isOfflineFallback) {
                    Text(
                        "Offline — following straight line (OSRM unreachable)",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                (vm.statusMessage ?: sessionError)?.let { message ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(message, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                vm.dismissStatus()
                            }) { Text("OK") }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                when (sessionState) {
                    SessionState.MOVING, SessionState.PAUSED, SessionState.HOLDING -> {
                        SessionPanel(
                            vm = vm,
                            state = sessionState,
                            onPause = { vm.pauseSession() },
                            onResume = { vm.resumeSession() },
                            onStopClick = { showStopConfirm = true },
                            onEditTimeClick = {
                                editTimeText = ""
                                showEditTime = true
                            }
                        )
                    }
                    else -> {
                        PlannerPanel(vm = vm, onStartClick = { vm.startSession() })
                    }
                }
            }
        }
    }

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text("Stop session?") },
            text = { Text("The mock location stops and your real location resumes.") },
            confirmButton = {
                TextButton(onClick = {
                    showStopConfirm = false
                    vm.stopSession()
                }) { Text("Stop") }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showEditTime) {
        AlertDialog(
            onDismissRequest = { showEditTime = false },
            title = { Text("Edit remaining time") },
            text = {
                OutlinedTextField(
                    value = editTimeText,
                    onValueChange = { editTimeText = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("Minutes remaining") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editTimeText.toIntOrNull()?.let { vm.editRemainingTime(it) }
                    showEditTime = false
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showEditTime = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PlannerPanel(vm: RouteMockViewModel, onStartClick: () -> Unit) {
    Text("Plan a route", style = MaterialTheme.typography.titleMedium)
    Text(
        "Long-press the map: 1st press = Start, 2nd press = End. " +
            "Or search an address below.",
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(modifier = Modifier.height(8.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Start: " + (vm.start?.let { "${"%.4f".format(it.latitude)}, ${"%.4f".format(it.longitude)}" } ?: "not set"),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { vm.setSearchTarget("start") }) {
            Text(if (vm.searchTarget == "start") "● Start" else "Start")
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "End: " + (vm.end?.let { "${"%.4f".format(it.latitude)}, ${"%.4f".format(it.longitude)}" } ?: "not set"),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { vm.setSearchTarget("end") }) {
            Text(if (vm.searchTarget == "end") "● End" else "End")
        }
    }

    OutlinedTextField(
        value = vm.searchQuery,
        onValueChange = { vm.setSearchQuery(it) },
        label = { Text("Search address (fills ${vm.searchTarget})") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    if (vm.isSearching) {
        Text("Searching…", style = MaterialTheme.typography.bodySmall)
    }
    vm.searchResults.forEach { result ->
        TextButton(
            onClick = { vm.onSearchResultSelected(result) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                result.displayName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    if (vm.isLoadingRoute) {
        Text("Fetching route…", style = MaterialTheme.typography.bodyMedium)
    } else if (vm.routeOptions.isNotEmpty()) {
        Text(
            "Distance: ${formatKm(vm.distanceMeters)}",
            style = MaterialTheme.typography.bodyMedium
        )
        if (vm.routeOptions.size > 1) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.routeOptions.forEachIndexed { index, option ->
                    OutlinedButton(onClick = { vm.selectRoute(index) }) {
                        Text(
                            "Route ${index + 1} • ${formatKm(option.distanceMeters)}",
                            color = if (index == vm.selectedRouteIndex)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text("Vehicle (optional — fills a recommended time):")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Vehicle.values().forEach { vehicle ->
                OutlinedButton(onClick = { vm.selectVehicle(vehicle) }) {
                    Text(
                        vehicle.label,
                        color = if (vm.selectedVehicle == vehicle)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            OutlinedButton(onClick = { vm.selectVehicle(null) }) {
                Text("Skip")
            }
        }
        vm.selectedVehicle?.let { vehicle ->
            Text(
                "Recommended for ${vehicle.label}: " +
                    "${vehicle.recommendedMinutes(vm.distanceMeters)} min " +
                    "(avg ${vehicle.speedKph} km/h)",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = vm.durationText,
            onValueChange = { vm.setDurationText(it) },
            label = { Text("Duration (minutes)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (vm.impliedSpeed > 130) {
            Text(
                "This implies ~${vm.impliedSpeed.toInt()} km/h — " +
                    "movement may look unrealistic.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row {
            Button(
                onClick = onStartClick,
                enabled = vm.mockAppSelected && vm.durationMinutes > 0,
                modifier = Modifier.weight(1f)
            ) { Text("Start mocking") }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.clearRoute() }) { Text("Clear") }
        }
    } else {
        Text(
            "Set Start and End to fetch a road route.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.clearRoute() }) { Text("Clear") }
    }
}

@Composable
private fun SessionPanel(
    vm: RouteMockViewModel,
    state: SessionState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopClick: () -> Unit,
    onEditTimeClick: () -> Unit
) {
    val position by vm.sessionPosition.collectAsState()
    val title = when (state) {
        SessionState.MOVING -> "Moving…"
        SessionState.PAUSED -> "Paused"
        SessionState.HOLDING -> "Route complete — holding position"
        else -> "Session"
    }
    Text(title, style = MaterialTheme.typography.titleMedium)
    position?.let { pos ->
        val fraction = if (pos.totalMeters > 0) {
            (pos.travelledMeters / pos.totalMeters).toFloat().coerceIn(0f, 1f)
        } else 0f
        LinearProgressIndicator(progress = fraction, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "${formatKm(pos.travelledMeters)} / ${formatKm(pos.totalMeters)}  •  " +
                "${"%.0f".format(pos.speedMps * 3.6f)} km/h  •  " +
                "ETA ${SessionStore.engine.etaMinutesRemaining()} min",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "Tap the route line on the map to jump there.",
            style = MaterialTheme.typography.bodySmall
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
    Row {
        if (state == SessionState.PAUSED) {
            Button(onClick = onResume, modifier = Modifier.weight(1f)) { Text("Resume") }
        } else {
            Button(onClick = onPause, modifier = Modifier.weight(1f)) { Text("Pause") }
        }
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(onClick = onEditTimeClick, modifier = Modifier.weight(1f)) {
            Text("Edit time")
        }
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(onClick = onStopClick, modifier = Modifier.weight(1f)) {
            Text("Stop")
        }
    }
}

private fun formatKm(meters: Double): String {
    return if (meters < 1000) "${meters.toInt()} m" else "%.1f km".format(meters / 1000.0)
}
