package dev.r2d8.weather.ui.radar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.r2d8.weather.WeatherApplication
import dev.r2d8.weather.data.radar.RadarRepository
import dev.r2d8.weather.domain.AppSettings
import dev.r2d8.weather.domain.Basemap
import dev.r2d8.weather.domain.MapStyle
import dev.r2d8.weather.domain.Place
import dev.r2d8.weather.domain.RadarFrame
import dev.r2d8.weather.domain.RadarLayerSet
import dev.r2d8.weather.domain.RadarPalette
import dev.r2d8.weather.domain.SavedPlace
import dev.r2d8.weather.domain.resolvePlace
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the radar screen shows. [frames] is what the map should load: all of them, or only the newest. */
data class RadarUiState(
    val place: Place? = null,
    val basemap: Basemap = Basemap.Normal,
    val mapStyle: MapStyle = MapStyle.Detailed,
    val isLoading: Boolean = true,
    /** The frame list could not be loaded (no internet, or RainViewer is down). */
    val failed: Boolean = false,
    val layers: RadarLayerSet? = null,
    val frames: List<RadarFrame> = emptyList(),
    /** Index into [frames] of the picture on screen. */
    val index: Int = 0,
    /** Index into [frames] of the newest observed picture ("now"); later ones are forecast. */
    val nowIndex: Int = 0,
    val playing: Boolean = false,
    /** All frames have been fetched by the map, so playing will not stutter. */
    val framesReady: Boolean = false,
    val opacity: Float = DEFAULT_OPACITY,
    val metered: Boolean = false,
    /** False on mobile data unless the setting is on or the user chose to load once. */
    val historyAllowed: Boolean = true,
    /**
     * History is allowed but not fetched yet: on a metered connection it is only loaded when the
     * user presses Play, so looking at the map costs one picture's tiles, not thirteen.
     */
    val historyPending: Boolean = false,
) {
    val canPlay: Boolean get() = historyAllowed && frames.size > 1 && framesReady
    val newestIndex: Int get() = (frames.size - 1).coerceAtLeast(0)

    companion object {
        const val DEFAULT_OPACITY = 0.8f
    }
}

class RadarViewModel(
    private val radar: RadarRepository,
    settings: Flow<AppSettings>,
    places: Flow<List<SavedPlace>>,
    metered: Flow<Boolean>,
    private val saveBasemap: suspend (Basemap) -> Unit,
    /** Where the map loads [MapStyle] from; the pinned local copy in the app. */
    private val resolveStyleUrl: suspend (MapStyle) -> String = { it.url },
    private val frameMillis: Long = 600,
    private val holdMillis: Long = 1500,
) : ViewModel() {

    private sealed interface Load {
        data object Loading : Load
        data object NoPlace : Load
        data object Failed : Load
        data class Loaded(val layers: RadarLayerSet) : Load
    }

    /** Things the screen controls, kept in one place so they change together. */
    private data class Interaction(
        /** Null means "the newest frame". */
        val selected: Int? = null,
        val playing: Boolean = false,
        val framesReady: Boolean = false,
        val opacity: Float = RadarUiState.DEFAULT_OPACITY,
        /** The user chose to load history on mobile data this once. */
        val allowMobileOnce: Boolean = false,
        /** The user pressed Play: on a metered connection that is what fetches the history. */
        val historyRequested: Boolean = false,
        /** Start playing as soon as the requested frames have loaded. */
        val autoPlay: Boolean = false,
    )

    private data class Request(
        val place: Place?,
        val serverOn: Boolean,
        val address: String,
        val palette: RadarPalette,
        val retry: Int,
    )

    private val load = MutableStateFlow<Load>(Load.Loading)
    private val interaction = MutableStateFlow(Interaction())
    private val retry = MutableStateFlow(0)
    private var playJob: Job? = null
    private var loadJob: Job? = null

    val uiState: StateFlow<RadarUiState> = combine(settings, places, metered, load, interaction) {
            appSettings, saved, isMetered, loadState, ui ->
        val layers = (loadState as? Load.Loaded)?.layers
        val allowed = !isMetered || appSettings.radarHistoryOnMobileData || ui.allowMobileOnce
        // On Wi-Fi all frames load at once (smooth playing); on a metered connection only when asked.
        val loadAll = allowed && (!isMetered || ui.historyRequested)
        val frames = when {
            layers == null -> emptyList()
            loadAll -> layers.frames
            else -> listOf(layers.newest)
        }
        RadarUiState(
            place = resolvePlace(appSettings, saved),
            basemap = appSettings.basemap,
            mapStyle = appSettings.mapStyle,
            isLoading = loadState is Load.Loading,
            failed = loadState is Load.Failed,
            layers = layers,
            frames = frames,
            // Opens on "now", not on the last forecast frame.
            index = (ui.selected ?: nowIndexOf(frames)).coerceIn(0, (frames.size - 1).coerceAtLeast(0)),
            nowIndex = nowIndexOf(frames),
            playing = ui.playing && allowed,
            framesReady = ui.framesReady,
            opacity = ui.opacity,
            metered = isMetered,
            historyAllowed = allowed,
            historyPending = layers != null && allowed && !loadAll && layers.frames.size > 1,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RadarUiState())

    init {
        // (Re)load the frame list when the place or the radar-related settings change, or on retry.
        viewModelScope.launch {
            combine(settings, places, retry) { s, p, r ->
                Request(resolvePlace(s, p), s.homeServerEnabled, s.homeServerAddress, s.radarPalette, r) to s
            }.distinctUntilChanged { old, new -> old.first == new.first }.collect { (request, appSettings) ->
                loadFor(request, appSettings)
            }
        }
    }

    private fun nowIndexOf(frames: List<RadarFrame>): Int =
        frames.indexOfLast { !it.isForecast }.coerceAtLeast(0)

    private fun loadFor(request: Request, appSettings: AppSettings) {
        loadJob?.cancel()
        pause()
        interaction.value = Interaction(
            opacity = interaction.value.opacity,
            allowMobileOnce = interaction.value.allowMobileOnce,
        )
        val place = request.place
        if (place == null) {
            load.value = Load.NoPlace
            return
        }
        load.value = Load.Loading
        loadJob = viewModelScope.launch {
            load.value = radar.load(place, appSettings).fold(
                onSuccess = { Load.Loaded(it) },
                onFailure = { Load.Failed },
            )
        }
    }

    fun retry() {
        retry.update { it + 1 }
    }

    fun toggleBasemap() {
        val next = if (uiState.value.basemap == Basemap.Normal) Basemap.Satellite else Basemap.Normal
        viewModelScope.launch { saveBasemap(next) }
    }

    /** The style address for the map, falling back to OpenFreeMap's own when no pinned copy is there. */
    suspend fun styleUrl(style: MapStyle): String = resolveStyleUrl(style)

    fun setOpacity(value: Float) = interaction.update { it.copy(opacity = value.coerceIn(0.1f, 1f)) }

    /** The user dragged the slider: show that frame and stop playing. */
    fun select(index: Int) {
        pause()
        interaction.update { it.copy(selected = index) }
    }

    /** "Load once" on mobile data: allow it for this visit, and load and play right away. */
    fun loadHistoryOnce() = interaction.update {
        it.copy(allowMobileOnce = true, historyRequested = true, autoPlay = true, framesReady = false)
    }

    /** The map reports it has no more tiles to fetch, so the frames can be played smoothly. */
    fun onMapIdle() {
        val before = interaction.value
        if (before.framesReady) return
        interaction.update { it.copy(framesReady = true, autoPlay = false) }
        if (before.autoPlay) play()
    }

    /** The map is about to load a different set of frames: wait for it again. */
    fun onFramesChanged() = interaction.update { it.copy(framesReady = false) }

    fun togglePlay() {
        when {
            // Metered connection, history not fetched yet: Play is what asks for it.
            uiState.value.historyPending ->
                interaction.update { it.copy(historyRequested = true, autoPlay = true, framesReady = false) }
            playJob?.isActive == true -> pause()
            else -> play()
        }
    }

    fun pause() {
        playJob?.cancel()
        playJob = null
        interaction.update { it.copy(playing = false, autoPlay = false) }
    }

    private fun play() {
        playJob?.cancel()
        playJob = viewModelScope.launch {
            // The state is derived asynchronously, so wait until it shows the frames are ready.
            uiState.first { it.canPlay }
            interaction.update { it.copy(playing = true) }
            // The loop keeps its own position: uiState is derived asynchronously, so reading the
            // index back right after changing it would still show the old one and repeat a frame.
            var index = uiState.value.index
            while (true) {
                val count = uiState.value.frames.size
                if (count < 2) break
                val atEnd = index >= count - 1
                // Pause on the newest picture before starting over.
                delay(if (atEnd) holdMillis else frameMillis)
                index = if (atEnd) 0 else index + 1
                interaction.update { it.copy(selected = index) }
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as WeatherApplication).container
                RadarViewModel(
                    radar = container.radarRepository,
                    settings = container.settingsRepository.settings,
                    places = container.placesRepository.places,
                    metered = container.networkMonitor.isMetered,
                    saveBasemap = { container.settingsRepository.setBasemap(it) },
                    resolveStyleUrl = { style -> container.mapDataRepository.ensureStyle(style).getOrElse { style.url } },
                )
            }
        }
    }
}
