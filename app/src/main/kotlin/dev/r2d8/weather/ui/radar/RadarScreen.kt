package dev.r2d8.weather.ui.radar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.r2d8.weather.R
import dev.r2d8.weather.domain.MapStyle
import dev.r2d8.weather.domain.Basemap
import dev.r2d8.weather.domain.LegendBand
import dev.r2d8.weather.ui.weather.asClock
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** Longest wait for the map to report that all frames have loaded before playing is allowed anyway. */
private const val FRAMES_READY_TIMEOUT_MS = 20_000L

@Composable
fun RadarRoute(onBack: () -> Unit, viewModel: RadarViewModel = viewModel(factory = RadarViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The animation must not keep running while the app is in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.pause() }
    // A new set of frames means the map has new tiles to fetch; allow playing once it reports idle.
    LaunchedEffect(state.frames) {
        if (state.frames.isNotEmpty()) {
            viewModel.onFramesChanged()
            delay(FRAMES_READY_TIMEOUT_MS)
            viewModel.onMapIdle()
        }
    }
    RadarScreen(
        state = state,
        onBack = onBack,
        onToggleBasemap = viewModel::toggleBasemap,
        onTogglePlay = viewModel::togglePlay,
        onSelect = viewModel::select,
        onOpacity = viewModel::setOpacity,
        onRetry = viewModel::retry,
        onLoadHistoryOnce = viewModel::loadHistoryOnce,
        onMapIdle = viewModel::onMapIdle,
        styleUrlOf = viewModel::styleUrl,
    )
}

@Composable
fun RadarScreen(
    state: RadarUiState,
    onBack: () -> Unit,
    onToggleBasemap: () -> Unit,
    onTogglePlay: () -> Unit,
    onSelect: (Int) -> Unit,
    onOpacity: (Float) -> Unit,
    onRetry: () -> Unit,
    onLoadHistoryOnce: () -> Unit,
    onMapIdle: () -> Unit,
    styleUrlOf: suspend (MapStyle) -> String = { it.url },
) {
    val place = state.place
    val layers = state.layers
    var controlsHeight by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (place != null && layers != null && state.frames.isNotEmpty()) {
            RadarMap(
                place = place,
                basemap = state.basemap,
                mapStyle = state.mapStyle,
                layers = layers,
                frames = state.frames,
                index = state.index,
                opacity = state.opacity,
                bottomInsetPx = controlsHeight,
                onIdle = onMapIdle,
                styleUrlOf = styleUrlOf,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CentreMessage(state, onRetry)
        }

        TopBar(
            title = place?.name?.let { "Radar · $it" } ?: "Radar",
            basemap = state.basemap,
            onBack = onBack,
            onToggleBasemap = onToggleBasemap,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        if (layers != null && state.frames.isNotEmpty()) {
            Controls(
                state = state,
                onTogglePlay = onTogglePlay,
                onSelect = onSelect,
                onOpacity = onOpacity,
                onLoadHistoryOnce = onLoadHistoryOnce,
                modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { controlsHeight = it.height },
            )
        }
    }
}

@Composable
private fun CentreMessage(state: RadarUiState, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            state.place == null && !state.isLoading -> Text(
                "Choose a place on the weather screen first.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
            state.failed -> {
                Icon(painterResource(R.drawable.ic_cloud_off), contentDescription = null, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text("Couldn't load the radar. Check your internet connection.", textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRetry) { Text("Retry") }
            }
            else -> CircularProgressIndicator()
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    basemap: Basemap,
    onBack: () -> Unit,
    onToggleBasemap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
    ) {
        Row(
            Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
            }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onToggleBasemap) {
                Icon(
                    painter = painterResource(if (basemap == Basemap.Normal) R.drawable.ic_satellite_alt else R.drawable.ic_map),
                    contentDescription = if (basemap == Basemap.Normal) "Show satellite view" else "Show map view",
                )
            }
        }
    }
}

@Composable
private fun Controls(
    state: RadarUiState,
    onTogglePlay: () -> Unit,
    onSelect: (Int) -> Unit,
    onOpacity: (Float) -> Unit,
    onLoadHistoryOnce: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layers = checkNotNull(state.layers)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            layers.notice?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            }
            if (state.historyAllowed) {
                Playback(state, layers.historyEstimateMb, onTogglePlay, onSelect)
            } else {
                NewestOnly(state, onLoadHistoryOnce)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Opacity", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
                Slider(value = state.opacity, onValueChange = onOpacity, valueRange = 0.1f..1f, modifier = Modifier.weight(1f))
            }
            layers.legend?.let { Legend(it) }
            Text(
                text = "${layers.attribution} · map credits: (i) on the map",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Playback(state: RadarUiState, estimateMb: Int, onTogglePlay: () -> Unit, onSelect: (Int) -> Unit) {
    val frame = state.frames.getOrNull(state.index) ?: return
    if (state.historyPending) {
        // Metered connection: only the newest picture is loaded until the user asks for the history.
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = onTogglePlay) {
                Icon(painterResource(R.drawable.ic_play_arrow), contentDescription = "Load the last 2 hours and play")
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Play loads the last 2 hours (about $estimateMb MB).", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Showing the newest picture.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(frame.time.asClock(ZoneId.systemDefault()), style = MaterialTheme.typography.titleMedium)
                Text(age(frame.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (state.frames.size > 1 && !state.framesReady) {
            // The map is still fetching the other frames; playing now would stutter.
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        } else {
            FilledIconButton(onClick = onTogglePlay, enabled = state.canPlay) {
                Icon(
                    painter = painterResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow),
                    contentDescription = if (state.playing) "Pause" else "Play",
                )
            }
        }
        Slider(
            value = state.index.toFloat(),
            onValueChange = { onSelect(it.roundToInt()) },
            valueRange = 0f..state.newestIndex.toFloat().coerceAtLeast(1f),
            steps = (state.frames.size - 2).coerceAtLeast(0),
            enabled = state.frames.size > 1,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = frame.time.asClock(ZoneId.systemDefault()),
                style = MaterialTheme.typography.titleMedium,
                color = if (frame.isForecast) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (frame.isForecast) "forecast +${frame.forecastMinutes} min" else age(frame.time),
                style = MaterialTheme.typography.labelSmall,
                color = if (frame.isForecast) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (frame.isForecast) {
        Text(
            "Forecast: the latest rain moved along its recent motion. New showers cannot be predicted.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
private fun NewestOnly(state: RadarUiState, onLoadHistoryOnce: () -> Unit) {
    val frame = state.frames.firstOrNull() ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "Showing the newest picture (${frame.time.asClock(ZoneId.systemDefault())}, ${age(frame.time)}).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "History is not loaded on mobile data. You can allow it in Settings.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onLoadHistoryOnce) { Text("Load once") }
    }
}

@Composable
private fun Legend(bands: List<LegendBand>) {
    Column {
        Row(Modifier.fillMaxWidth().height(10.dp)) {
            bands.forEach { band ->
                Box(Modifier.weight(1f).fillMaxSize().background(Color(band.colorArgb)))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val first = bands.first().from
            val last = bands.last().from
            Text("${first.trimmed()} mm/h", style = MaterialTheme.typography.labelSmall)
            Text("light to heavy rain", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${last.trimmed()}+", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun Double.trimmed(): String = if (this == this.toInt().toDouble()) toInt().toString() else toString()

/** "12 min ago" style text for a frame time. */
private fun age(time: Instant, now: Instant = Instant.now()): String {
    val minutes = Duration.between(time, now).toMinutes()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        else -> "${minutes / 60} h ${minutes % 60} min ago"
    }
}
