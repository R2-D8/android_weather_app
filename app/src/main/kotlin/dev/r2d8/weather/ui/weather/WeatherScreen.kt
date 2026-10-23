package dev.r2d8.weather.ui.weather

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.r2d8.weather.R
import dev.r2d8.weather.domain.AppSettings
import dev.r2d8.weather.domain.ForecastSection
import dev.r2d8.weather.domain.PlaceSelection
import dev.r2d8.weather.domain.WeatherReport
import java.time.Instant
import java.time.ZoneId

@Composable
fun WeatherRoute(
    onOpenPlaces: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: WeatherViewModel = viewModel(factory = WeatherViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Runs on first display and whenever the app comes back to the foreground; skipped while the cache is fresh.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    // Android shows its permission dialog (or returns at once if the user already refused twice).
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onLocationPermissionResult()
    }
    WeatherScreen(
        state = state,
        onRefresh = { viewModel.refresh(force = true) },
        onSelect = viewModel::select,
        onRequestLocationPermission = {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        },
        onOpenPlaces = onOpenPlaces,
        onOpenSettings = onOpenSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherScreen(
    state: WeatherUiState,
    onRefresh: () -> Unit,
    onSelect: (PlaceSelection) -> Unit,
    onRequestLocationPermission: () -> Unit,
    onOpenPlaces: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { PlaceTitle(state, onSelect, onOpenPlaces) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        val report = state.report
        when {
            // The phone's location is not known yet, so there is nothing to show.
            state.place == null -> when (state.locationStatus) {
                LocationStatus.PermissionNeeded -> LocationPrompt(onRequestLocationPermission, onOpenPlaces, modifier)
                LocationStatus.Unavailable -> LocationFailed(onRetry = onRefresh, onChoosePlace = onOpenPlaces, modifier)
                else -> Loading(modifier)
            }

            report != null -> PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh, modifier = modifier) {
                WeatherContent(report, state.error, state.settings)
            }

            state.error != null -> FullScreenError(state.error, onRetry = onRefresh, modifier)

            else -> Loading(modifier)
        }
    }
}

/** Place name with a drop-down arrow; tapping opens the list of places. */
@Composable
private fun PlaceTitle(state: WeatherUiState, onSelect: (PlaceSelection) -> Unit, onOpenPlaces: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { open = true }.padding(vertical = 4.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.usingGps) {
                        Icon(painterResource(R.drawable.ic_my_location), contentDescription = "Current location", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(state.place?.name ?: "Current location")
                }
                state.report?.let {
                    Text(
                        text = "Updated ${it.fetchedAt.asClock(ZoneId.systemDefault())}", // phone time, not the place's
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = "Choose place")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Current location") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_my_location), contentDescription = null) },
                trailingIcon = if (state.usingGps) ({ Text("✓") }) else null,
                onClick = { open = false; onSelect(PlaceSelection.Gps) },
            )
            state.savedPlaces.forEach { saved ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(saved.place.name)
                            saved.region?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_location_on), contentDescription = null) },
                    trailingIcon = if (state.selectedSavedId == saved.id) ({ Text("✓") }) else null,
                    onClick = { open = false; onSelect(PlaceSelection.Saved(saved.id)) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Add or edit places…") },
                onClick = { open = false; onOpenPlaces() },
            )
        }
    }
}

@Composable
private fun WeatherContent(report: WeatherReport, error: RefreshError?, settings: AppSettings) {
    // Freeze "now" per report so lists do not shift while scrolling; a new report or resume recomputes it.
    val now = remember(report) { Instant.now() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (error != null) item { StaleBanner(error, report) }
        item { CurrentCard(report, now) }
        if (ForecastSection.RainNextHour in settings.visibleSections) item { RainCard(report, now) }
        if (ForecastSection.HourlyStrip in settings.visibleSections) item { HourlyCard(report, now) }
        if (ForecastSection.DailyForecast in settings.visibleSections) item { DailyCard(report, now) }
        if (settings.visibleCards.isNotEmpty()) item { DetailCards(report, now, settings.visibleCards) }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun StaleBanner(error: RefreshError, report: WeatherReport) {
    val reason = when (error) {
        RefreshError.Offline -> "You're offline"
        RefreshError.Server -> "The weather service isn't answering"
        RefreshError.Unknown -> "Couldn't update"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text = "$reason. Showing data from ${report.fetchedAt.asClock(ZoneId.systemDefault())}.",
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun Loading(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun CenteredMessage(
    iconRes: Int,
    message: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        actions()
    }
}

@Composable
private fun FullScreenError(error: RefreshError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val message = when (error) {
        RefreshError.Offline -> "No internet connection."
        RefreshError.Server -> "The weather service isn't answering. Try again in a moment."
        RefreshError.Unknown -> "Something went wrong loading the weather."
    }
    CenteredMessage(R.drawable.ic_cloud_off, message, modifier) { Button(onClick = onRetry) { Text("Retry") } }
}

@Composable
private fun LocationPrompt(onAllow: () -> Unit, onChoosePlace: () -> Unit, modifier: Modifier = Modifier) {
    CenteredMessage(
        R.drawable.ic_my_location,
        "Allow location access to see the weather where you are. Approximate location is enough. " +
            "If nothing happens, allow it in the system settings for this app.",
        modifier,
    ) {
        Button(onClick = onAllow) { Text("Allow location") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onChoosePlace) { Text("Choose a place instead") }
    }
}

@Composable
private fun LocationFailed(onRetry: () -> Unit, onChoosePlace: () -> Unit, modifier: Modifier = Modifier) {
    CenteredMessage(
        R.drawable.ic_my_location,
        "Couldn't find your location. Check that location is turned on for the phone.",
        modifier,
    ) {
        Button(onClick = onRetry) { Text("Try again") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onChoosePlace) { Text("Choose a place instead") }
    }
}
