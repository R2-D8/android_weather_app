package dev.r2d8.weather.domain

import java.time.Instant

/** What the radar map draws underneath the radar layer. */
enum class Basemap(val label: String) { Normal("Map"), Satellite("Satellite") }

/** Vector basemap styles from OpenFreeMap (free, no key). Each id is a style on tiles.openfreemap.org. */
enum class MapStyle(val label: String, val url: String) {
    Detailed("Detailed", "https://tiles.openfreemap.org/styles/liberty"),
    Bright("Bright", "https://tiles.openfreemap.org/styles/bright"),
    Light("Light", "https://tiles.openfreemap.org/styles/positron"),
    Dark("Dark", "https://tiles.openfreemap.org/styles/dark"),
}

/** Where radar frames come from. */
enum class RadarSourceKind(val label: String) {
    RainViewer("RainViewer"),
    HomeServer("Home server"),
}

/** Colour palettes of the home server (their ids are part of the server's tile URLs). */
enum class RadarPalette(val id: String, val label: String) {
    Classic("classic", "Classic"),
    Blues("blues", "Blues"),
}

/**
 * One radar picture in time. [tileUrl] is a MapLibre template with {z}, {x} and {y}.
 * [forecastMinutes] is null for an observed picture, else how far past the newest observation it is
 * (the radar nowcast). [isModel] marks weather-model rain from the home server: the rain in the hour
 * ending at [time]. [maxZoom] and [attribution] override the layer set's for this frame (model
 * frames after RainViewer's).
 */
data class RadarFrame(
    val time: Instant,
    val tileUrl: String,
    val forecastMinutes: Int? = null,
    val isModel: Boolean = false,
    val maxZoom: Int? = null,
    val attribution: String? = null,
) {
    val isForecast: Boolean get() = forecastMinutes != null || isModel
}

/** From [from] mm/h upwards a tile is drawn in [colorArgb] (ARGB int, alpha included). */
data class LegendBand(val from: Double, val colorArgb: Int)

/** Everything the map needs to draw radar for one place. */
data class RadarLayerSet(
    val source: RadarSourceKind,
    /** Observed frames oldest first, then forecast frames (if any). Has at least one observed frame. */
    val frames: List<RadarFrame>,
    val maxZoom: Int,
    val tileSize: Int,
    val attribution: String,
    /**
     * The home server's rain legend, or null without one. With RainViewer it describes only the
     * model frames (RainViewer gives no legend).
     */
    val legend: List<LegendBand>?,
    /** Something worth telling the user, e.g. that the home server was not used. */
    val notice: String?,
    /** Rough mobile data cost, in MB, of loading all frames for a regional view (for the user). */
    val historyEstimateMb: Int,
    /** Colours of snow and of mixed rain and snow, when the server draws them (else empty). */
    val snowLegend: List<LegendBand> = emptyList(),
    val mixedLegend: List<LegendBand> = emptyList(),
) {
    /** The newest observed picture ("now"). */
    val newest: RadarFrame get() = frames.last { !it.isForecast }
    val newestIndex: Int get() = frames.indexOfLast { !it.isForecast }
}

/** What the map draws over the basemap. Temperature needs the home server. */
enum class MapOverlay(val label: String) { Rain("Rain"), Temperature("Temperature") }

/** One hour of the temperature map; [tileUrl] is a MapLibre template with {z}, {x} and {y}. */
data class TemperatureHour(val time: Instant, val id: Long, val tileUrl: String)

/** The home server's hourly temperature maps (model data corrected for ground height). */
data class TemperatureLayerSet(
    /** Oldest first: about a day back and two days ahead. */
    val hours: List<TemperatureHour>,
    /** Index of the current hour (or the closest one). */
    val nowIndex: Int,
    val maxZoom: Int,
    val tileSize: Int,
    /** Lower bound in °C and colour of each band, ascending. */
    val legend: List<LegendBand>,
)

/** The temperature at a point the user tapped, for one hour. [celsius] is null outside the model area. */
data class PointTemperature(val celsius: Double?, val groundMetres: Int)
