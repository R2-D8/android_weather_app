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
 * [forecastMinutes] is null for an observed picture, else how far past the newest observation it is.
 */
data class RadarFrame(val time: Instant, val tileUrl: String, val forecastMinutes: Int? = null) {
    val isForecast: Boolean get() = forecastMinutes != null
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
    /** The provider's legend, or null when it gives none (RainViewer). */
    val legend: List<LegendBand>?,
    /** Something worth telling the user, e.g. that the home server was not used. */
    val notice: String?,
    /** Rough mobile data cost, in MB, of loading all frames for a regional view (for the user). */
    val historyEstimateMb: Int,
) {
    /** The newest observed picture ("now"). */
    val newest: RadarFrame get() = frames.last { !it.isForecast }
    val newestIndex: Int get() = frames.indexOfLast { !it.isForecast }
}
