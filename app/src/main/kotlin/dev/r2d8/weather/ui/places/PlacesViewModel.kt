package dev.r2d8.weather.ui.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.r2d8.weather.WeatherApplication
import dev.r2d8.weather.data.PlaceSearchResult
import dev.r2d8.weather.data.PlacesRepository
import dev.r2d8.weather.data.SettingsRepository
import dev.r2d8.weather.domain.PlaceSelection
import dev.r2d8.weather.domain.SavedPlace
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Results(val items: List<PlaceSearchResult>) : SearchState
    data object Failed : SearchState
}

data class PlacesUiState(
    val places: List<SavedPlace> = emptyList(),
    val selectedId: Long? = null,
    val query: String = "",
    val search: SearchState = SearchState.Idle,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class PlacesViewModel(
    private val placesRepository: PlacesRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val search = MutableStateFlow<SearchState>(SearchState.Idle)

    init {
        // Search as the user types, but wait for a pause so each keystroke is not a request.
        viewModelScope.launch {
            query.debounce(SEARCH_DELAY_MS).distinctUntilChanged().collectLatest { text ->
                if (text.trim().length < PlacesRepository.MIN_QUERY_LENGTH) {
                    search.value = SearchState.Idle
                } else {
                    search.value = SearchState.Loading
                    search.value = placesRepository.search(text).fold(
                        onSuccess = { SearchState.Results(it) },
                        onFailure = { SearchState.Failed },
                    )
                }
            }
        }
    }

    val uiState: StateFlow<PlacesUiState> = combine(
        placesRepository.places,
        settingsRepository.settings,
        query,
        search,
    ) { places, settings, text, searchState ->
        PlacesUiState(places, (settings.selection as? PlaceSelection.Saved)?.id, text, searchState)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlacesUiState())

    fun onQueryChange(text: String) {
        query.value = text
        if (text.trim().length < PlacesRepository.MIN_QUERY_LENGTH) search.value = SearchState.Idle
    }

    /** Saves the place and makes it the one shown on the main screen. */
    fun add(result: PlaceSearchResult, onDone: () -> Unit) {
        viewModelScope.launch {
            val id = placesRepository.add(result)
            settingsRepository.select(PlaceSelection.Saved(id))
            onDone()
        }
    }

    fun select(id: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.select(PlaceSelection.Saved(id))
            onDone()
        }
    }

    fun remove(id: Long) {
        viewModelScope.launch {
            // If the shown place is deleted, go back to the phone's location instead of a dangling selection.
            if ((uiState.value.selectedId) == id) settingsRepository.select(PlaceSelection.Gps)
            placesRepository.remove(id)
        }
    }

    fun move(id: Long, delta: Int) {
        viewModelScope.launch { placesRepository.move(id, delta) }
    }

    companion object {
        private const val SEARCH_DELAY_MS = 400L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as WeatherApplication).container
                PlacesViewModel(container.placesRepository, container.settingsRepository)
            }
        }
    }
}
