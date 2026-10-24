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
enum class DetailCardType(val label: String) {
    Wind("Wind"),
    UvIndex("UV index"),
    Sun("Sunrise and sunset"),
    Humidity("Humidity"),
    Pressure("Pressure"),
    AirQuality("Air quality"),
    Pollen("Pollen"),
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
