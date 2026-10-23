package dev.r2d8.weather.ui.weather

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.r2d8.weather.WeatherApplication
import dev.r2d8.weather.data.LocationException
import dev.r2d8.weather.data.LocationFailure
import dev.r2d8.weather.data.LocationProvider
import dev.r2d8.weather.data.PlacesRepository
import dev.r2d8.weather.data.SettingsRepository
import dev.r2d8.weather.data.WeatherRepository
import dev.r2d8.weather.domain.AppSettings
import dev.r2d8.weather.domain.Place
import dev.r2d8.weather.domain.PlaceSelection
import dev.r2d8.weather.domain.SavedPlace
import dev.r2d8.weather.domain.WeatherReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import java.time.Duration

enum class RefreshError { Offline, Server, Unknown }

/** Progress of finding the phone's location; only matters while "Current location" is selected. */
enum class LocationStatus { Ok, Locating, PermissionNeeded, Unavailable }

data class WeatherUiState(
    val settings: AppSettings = AppSettings(),
    val savedPlaces: List<SavedPlace> = emptyList(),
    /** The place being shown; null while the phone's location is not known yet. */
    val place: Place? = null,
    val usingGps: Boolean = true,
    val selectedSavedId: Long? = null,
    /** Last known data: fresh, or cached when the latest refresh failed. */
    val report: WeatherReport? = null,
    val isRefreshing: Boolean = false,
    /** Set when the last refresh failed. With a [report] the screen shows a banner, without one a full error. */
    val error: RefreshError? = null,
    val locationStatus: LocationStatus = LocationStatus.Ok,
)

@OptIn(ExperimentalCoroutinesApi::class)
class WeatherViewModel(
    private val weatherRepository: WeatherRepository,
    placesRepository: PlacesRepository,
    private val settingsRepository: SettingsRepository,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private data class RefreshState(val isRefreshing: Boolean = false, val error: RefreshError? = null)

    private val refreshState = MutableStateFlow(RefreshState())
    private val locationStatus = MutableStateFlow(LocationStatus.Ok)
    private var refreshJob: Job? = null
    private var lastLocatedAtMs: Long? = null

    /** The selected place resolved to coordinates; changes when the user switches or the phone moves. */
    private val currentPlace: Flow<Place?> = combine(settingsRepository.settings, placesRepository.places) { settings, saved ->
        resolve(settings, saved)
    }

    val uiState: StateFlow<WeatherUiState> = combine(
        settingsRepository.settings,
        placesRepository.places,
        currentPlace.flatMapLatest { place -> if (place == null) flowOf(null) else weatherRepository.observe(place) },
        refreshState,
        locationStatus,
    ) { settings, saved, report, refresh, location ->
        val selectedSaved = (settings.selection as? PlaceSelection.Saved)?.let { sel -> saved.firstOrNull { it.id == sel.id } }
        WeatherUiState(
            settings = settings,
            savedPlaces = saved,
            place = resolve(settings, saved),
            usingGps = selectedSaved == null,
            selectedSavedId = selectedSaved?.id,
            report = report,
            isRefreshing = refresh.isRefreshing,
            error = refresh.error,
            locationStatus = location,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeatherUiState())

    /** A saved selection whose place was deleted falls back to the phone's location. */
    private fun resolve(settings: AppSettings, saved: List<SavedPlace>): Place? =
        (settings.selection as? PlaceSelection.Saved)?.let { sel -> saved.firstOrNull { it.id == sel.id }?.place }
            ?: settings.lastGpsPlace

    /** Refreshes unless the cache is fresh. [force] is for pull-to-refresh and also re-checks the location. */
    fun refresh(force: Boolean = false) = startRefresh(force = force, restart = false)

    /** Switches the main screen to [selection] and loads its weather. */
    fun select(selection: PlaceSelection) = startRefresh(force = false, restart = true) {
        settingsRepository.select(selection)
    }

    /** Call after the user answered the location permission dialog. */
    fun onLocationPermissionResult() = startRefresh(force = true, restart = true)

    private fun startRefresh(force: Boolean, restart: Boolean, beforeLoading: suspend () -> Unit = {}) {
        if (restart) refreshJob?.cancel() else if (refreshJob?.isActive == true) return
        // A cancelled job leaves the state alone: the job that replaces it sets it again right away.
        refreshJob = viewModelScope.launch {
            refreshState.value = RefreshState(isRefreshing = true)
            beforeLoading()
            val settings = settingsRepository.settings.first()
            if (settings.selection !is PlaceSelection.Saved && (force || shouldLocate(settings))) locate()
            val place = currentPlace.first()
            if (place == null) {
                refreshState.value = RefreshState()
                return@launch
            }
            val result = weatherRepository.refresh(place, if (force) Duration.ZERO else STALE_AFTER)
            refreshState.value = RefreshState(error = result.exceptionOrNull()?.toRefreshError())
        }
    }

    private fun shouldLocate(settings: AppSettings): Boolean {
        val last = lastLocatedAtMs ?: return true
        return settings.lastGpsPlace == null || SystemClock.elapsedRealtime() - last > RELOCATE_AFTER_MS
    }

    private suspend fun locate() {
        if (!locationProvider.hasPermission()) {
            locationStatus.value = LocationStatus.PermissionNeeded
            return
        }
        locationStatus.value = LocationStatus.Locating
        locationProvider.currentPlace()
            .onSuccess {
                settingsRepository.setLastGpsPlace(it)
                lastLocatedAtMs = SystemClock.elapsedRealtime()
                locationStatus.value = LocationStatus.Ok
            }
            .onFailure {
                locationStatus.value = if ((it as? LocationException)?.reason == LocationFailure.PermissionDenied) {
                    LocationStatus.PermissionNeeded
                } else {
                    LocationStatus.Unavailable
                }
            }
    }

    private fun Throwable.toRefreshError() = when (this) {
        is IOException -> RefreshError.Offline
        is HttpException -> RefreshError.Server
        else -> RefreshError.Unknown
    }

    companion object {
        /** Opening the app within this time of the last download reuses the cache. */
        val STALE_AFTER: Duration = Duration.ofMinutes(10)

        /** The phone's location is looked up again on resume only after this long. */
        private const val RELOCATE_AFTER_MS = 5 * 60 * 1000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as WeatherApplication).container
                WeatherViewModel(
                    container.weatherRepository,
                    container.placesRepository,
                    container.settingsRepository,
                    container.locationProvider,
                )
            }
        }
    }
}
