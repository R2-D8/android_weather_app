package dev.r2d8.weather.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.r2d8.weather.WeatherApplication
import dev.r2d8.weather.data.SettingsRepository
import dev.r2d8.weather.data.map.MapDataState
import dev.r2d8.weather.data.map.PackStatus
import dev.r2d8.weather.data.radar.RadarRepository
import dev.r2d8.weather.data.radar.ServerTestResult
import dev.r2d8.weather.domain.AppSettings
import dev.r2d8.weather.domain.ColorPalette
import dev.r2d8.weather.domain.DayDetail
import dev.r2d8.weather.domain.DetailCardType
import dev.r2d8.weather.domain.ForecastSection
import dev.r2d8.weather.domain.MapStyle
import dev.r2d8.weather.domain.RadarPalette
import dev.r2d8.weather.domain.SavedPlace
import dev.r2d8.weather.domain.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The offline maps part of Settings: the pinned map data and one pack per saved place. */
data class OfflineMapsUi(
    val mapData: MapDataState = MapDataState(null, null),
    val packs: List<Pair<SavedPlace, PackStatus>> = emptyList(),
)

/** Progress of the "Test connection" button for the home server. */
sealed interface ServerTestState {
    data object Idle : ServerTestState
    data object Testing : ServerTestState
    data class Done(val result: ServerTestResult) : ServerTestState
}

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val radar: RadarRepository,
    offlineMaps: Flow<OfflineMapsUi> = flowOf(OfflineMapsUi()),
) : ViewModel() {
    val offlineMaps: StateFlow<OfflineMapsUi> =
        offlineMaps.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OfflineMapsUi())

    val settings: StateFlow<AppSettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _serverTest = MutableStateFlow<ServerTestState>(ServerTestState.Idle)
    val serverTest: StateFlow<ServerTestState> = _serverTest
    private var testJob: Job? = null

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { repository.setThemeMode(mode) }

    fun setPalette(palette: ColorPalette) = viewModelScope.launch { repository.setPalette(palette) }

    fun setSectionVisible(section: ForecastSection, visible: Boolean) =
        viewModelScope.launch { repository.setSectionVisible(section, visible) }

    fun setDayDetailVisible(detail: DayDetail, visible: Boolean) =
        viewModelScope.launch { repository.setDayDetailVisible(detail, visible) }

    fun setCardVisible(card: DetailCardType, visible: Boolean) =
        viewModelScope.launch { repository.setCardVisible(card, visible) }

    fun setRadarHistoryOnMobileData(enabled: Boolean) =
        viewModelScope.launch { repository.setRadarHistoryOnMobileData(enabled) }

    fun setMapStyle(style: MapStyle) = viewModelScope.launch { repository.setMapStyle(style) }

    fun setHomeServerEnabled(enabled: Boolean) =
        viewModelScope.launch { repository.setHomeServerEnabled(enabled) }

    fun setRadarPalette(palette: RadarPalette) = viewModelScope.launch { repository.setRadarPalette(palette) }

    fun setHomeServerAddress(address: String) {
        // An old test result no longer describes the new text.
        testJob?.cancel()
        _serverTest.value = ServerTestState.Idle
        viewModelScope.launch { repository.setHomeServerAddress(address) }
    }

    fun testServer(address: String) {
        testJob?.cancel()
        _serverTest.value = ServerTestState.Testing
        testJob = viewModelScope.launch {
            _serverTest.value = ServerTestState.Done(radar.testServer(address))
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as WeatherApplication).container
                SettingsViewModel(
                    container.settingsRepository,
                    container.radarRepository,
                    offlineMaps = combine(
                        container.mapDataRepository.state,
                        container.placesRepository.places,
                        container.offlinePacks.status,
                    ) { mapData, places, status ->
                        OfflineMapsUi(mapData, places.map { it to (status[it.id] ?: PackStatus.Waiting) })
                    },
                )
            }
        }
    }
}
