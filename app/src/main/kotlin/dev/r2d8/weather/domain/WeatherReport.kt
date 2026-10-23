package dev.r2d8.weather.domain

import java.time.Instant
import java.time.ZoneId

/** A place we show weather for: the phone's location or one of the saved places. */
data class Place(val name: String, val latitude: Double, val longitude: Double) {
    companion object {
        /** Only used by tests; the app shows the phone's location or a saved place. */
        val Rome = Place("Rome", 41.89, 12.48)
    }
}

/** Everything the main screen shows, already converted to app types. Units: °C, km/h, mm, hPa. */
data class WeatherReport(
    val zone: ZoneId,
    val fetchedAt: Instant,
    val current: CurrentConditions,
    /** 15-minute precipitation slots, see [RainSlot]. Empty if the model has no 15-minute data. */
    val rainSlots: List<RainSlot>,
    val hours: List<HourForecast>,
    val days: List<DayForecast>,
    /** Null when the air-quality request failed and nothing was cached. */
    val airQuality: AirQuality?,
)

data class CurrentConditions(
    val time: Instant,
    val temperature: Double?,
    val apparentTemperature: Double?,
    val humidity: Int?,
    val isDay: Boolean,
    val precipitation: Double?,
    val condition: WeatherCondition,
    val cloudCover: Int?,
    val pressure: Double?,
    val windSpeed: Double?,
    val windDirection: Int?,
    val windGusts: Double?,
)

data class HourForecast(
    val time: Instant,
    val temperature: Double?,
    val precipitationProbability: Int?,
    val precipitation: Double?,
    val condition: WeatherCondition,
    val isDay: Boolean,
    val windSpeed: Double?,
    val windGusts: Double?,
    val uvIndex: Double?,
)

data class DayForecast(
    /** Start of the local day. */
    val date: java.time.LocalDate,
    val condition: WeatherCondition,
    val temperatureMax: Double?,
    val temperatureMin: Double?,
    val sunrise: Instant?,
    val sunset: Instant?,
    val uvIndexMax: Double?,
    val precipitationSum: Double?,
    val precipitationProbabilityMax: Int?,
    val windSpeedMax: Double?,
    val windGustsMax: Double?,
)

/**
 * Precipitation in the 15 minutes **ending** at [end]. This matches Open-Meteo, whose
 * accumulated variables always describe the preceding interval.
 */
data class RainSlot(val end: Instant, val millimetres: Double) {
    val start: Instant get() = end.minusSeconds(SLOT_SECONDS)

    companion object {
        const val SLOT_SECONDS = 15L * 60
    }
}

data class AirQuality(
    val time: Instant,
    /** European Air Quality Index, 0 (best) to 100+ (worst). */
    val europeanAqi: Int?,
    val pm10: Double?,
    val pm25: Double?,
    val ozone: Double?,
    val nitrogenDioxide: Double?,
    val sulphurDioxide: Double?,
    /** Only species the model reports; empty outside the pollen region or season. */
    val pollen: List<PollenReading>,
)

data class PollenReading(val type: PollenType, val grainsPerCubicMetre: Double)

enum class PollenType(val label: String) {
    Alder("Alder"),
    Birch("Birch"),
    Grass("Grass"),
    Mugwort("Mugwort"),
    Olive("Olive"),
    Ragweed("Ragweed"),
}
