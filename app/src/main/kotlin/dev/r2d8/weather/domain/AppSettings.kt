package dev.r2d8.weather.domain

/** Light/dark behaviour of the app. */
enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

/** Colour scheme source: [Dynamic] follows the wallpaper (Material You), the others are fixed. */
enum class ColorPalette(val label: String) {
    Dynamic("Dynamic"),
    Blue("Blue"),
    Green("Green"),
    Orange("Orange"),
    Purple("Purple"),
}

/** The big blocks of the main screen that can be switched off. The current conditions always show. */
enum class ForecastSection(val label: String) {
    RainNextHour("Rain in the next hour"),
    HourlyStrip("Hourly forecast"),
    DailyForecast("10-day forecast"),
}

/** What an expanded day in the daily list shows. */
enum class DayDetail(val label: String) {
    DayParts("Night, morning, afternoon and evening"),
    Hourly("Hour by hour"),
}

/** The small detail cards under the forecast. */
enum class DetailCardType(val label: String, val title: String) {
    Wind("Wind", "Wind"),
    UvIndex("UV index", "UV index"),
    Sun("Sunrise and sunset", "Sun"),
    Moon("Moonrise, moonset and phase", "Moon"),
    Humidity("Humidity", "Humidity"),
    Pressure("Pressure", "Pressure"),
    AirQuality("Air quality", "Air quality"),
    Pollen("Pollen", "Pollen"),
}

/**
 * [card] moved [by] places (negative: earlier) in this order, clamped to the ends. The detail
 * cards are laid out two per row in this order.
 */
fun List<DetailCardType>.moved(card: DetailCardType, by: Int): List<DetailCardType> {
    val from = indexOf(card)
    if (from < 0) return this
    val to = (from + by).coerceIn(0, lastIndex)
    return toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * [card] moved one step up ([down] false) or down. A shown card jumps past hidden ones to swap with
 * the next shown card, so every tap changes the screen; a hidden card moves one place.
 */
fun List<DetailCardType>.step(card: DetailCardType, down: Boolean, visible: Set<DetailCardType>): List<DetailCardType> {
    val from = indexOf(card)
    if (from < 0) return this
    val direction = if (down) 1 else -1
    if (card !in visible) return moved(card, direction)
    var target = from + direction
    while (target in indices && this[target] !in visible) target += direction
    if (target !in indices) return this
    return moved(card, target - from)
}

/** Whether [step] would change anything for [card]. */
fun List<DetailCardType>.canStep(card: DetailCardType, down: Boolean, visible: Set<DetailCardType>): Boolean =
    step(card, down, visible) != this

/** A stored order: known names in their stored order, then cards it does not name (added later) in their default order. */
fun cardOrderOf(names: List<String>): List<DetailCardType> {
    val known = names.mapNotNull { name -> DetailCardType.entries.firstOrNull { it.name == name } }.distinct()
    return known + DetailCardType.entries.filter { it !in known }
}

/** Which place the main screen shows: the phone's location or one of the saved places. */
sealed interface PlaceSelection {
    data object Gps : PlaceSelection
    data class Saved(val id: Long) : PlaceSelection
}

/** A place the user saved; [id] is the database key. [region] is e.g. "Lazio, Italy" for disambiguation. */
data class SavedPlace(val id: Long, val place: Place, val region: String?)

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val palette: ColorPalette = ColorPalette.Dynamic,
    val visibleSections: Set<ForecastSection> = ForecastSection.entries.toSet(),
    val visibleCards: Set<DetailCardType> = DetailCardType.entries.toSet(),
    /** Every card, in the order they are shown (two per row), hidden ones included. */
    val cardOrder: List<DetailCardType> = DetailCardType.entries,
    /** Shown when a day is expanded; with none of them a day cannot be expanded. */
    val visibleDayDetails: Set<DayDetail> = DayDetail.entries.toSet(),
    val selection: PlaceSelection = PlaceSelection.Gps,
    /** Last successfully found phone location, so the screen has a place while GPS is still working. */
    val lastGpsPlace: Place? = null,
    /** The home radar server is optional: off and without an address until the user sets it up. */
    val homeServerEnabled: Boolean = false,
    /** As typed by the user, e.g. "100.x.y.z:8080"; normalised only when used. Empty by default. */
    val homeServerAddress: String = "",
    /** Radar history (many frames) is only loaded on Wi-Fi unless this is on. */
    val radarHistoryOnMobileData: Boolean = false,
    val radarPalette: RadarPalette = RadarPalette.Classic,
    val basemap: Basemap = Basemap.Normal,
    /** Which OpenFreeMap style the normal map uses; the satellite view borrows labels and roads from [MapStyle.Detailed]. */
    val mapStyle: MapStyle = MapStyle.Detailed,
)
