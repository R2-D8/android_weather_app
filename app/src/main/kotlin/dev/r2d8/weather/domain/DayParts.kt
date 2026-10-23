package dev.r2d8.weather.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Four-part split of a day, as Italian forecasts usually present it. [hours] are local clock hours. */
enum class DayPartType(val label: String, val hours: IntRange) {
    Night("Night", 0..5),
    Morning("Morning", 6..11),
    Afternoon("Afternoon", 12..17),
    Evening("Evening", 18..23),
}

data class DayPartSummary(
    val type: DayPartType,
    /** The most severe condition in the part, so a storm at 17:00 is not hidden by a sunny morning. */
    val condition: WeatherCondition,
    /** Whether to draw the day or night variant of the icon (taken from the hour that gave [condition]). */
    val isDay: Boolean,
    val temperatureMin: Double?,
    val temperatureMax: Double?,
    val precipitationMm: Double,
    val maxPrecipitationProbability: Int?,
)

object DayParts {
    /**
     * Hours of [date] (in [zone]) that are not before [from]. For today, pass the current time so
     * past hours disappear; for later days every hour is kept.
     */
    fun hoursOf(hours: List<HourForecast>, date: LocalDate, zone: ZoneId, from: Instant): List<HourForecast> =
        hours.filter { it.time.atZone(zone).toLocalDate() == date && it.time >= from }

    /** Summaries of [dayHours] (all from one day); parts without hours are left out. */
    fun summarize(dayHours: List<HourForecast>, zone: ZoneId): List<DayPartSummary> =
        DayPartType.entries.mapNotNull { type ->
            val inPart = dayHours.filter { it.time.atZone(zone).hour in type.hours }
            if (inPart.isEmpty()) return@mapNotNull null
            val worst = inPart.maxWith(
                compareBy<HourForecast> { it.condition.kind.severity }.thenBy { it.condition.code },
            )
            val temperatures = inPart.mapNotNull { it.temperature }
            DayPartSummary(
                type = type,
                condition = worst.condition,
                isDay = worst.isDay,
                temperatureMin = temperatures.minOrNull(),
                temperatureMax = temperatures.maxOrNull(),
                precipitationMm = inPart.sumOf { it.precipitation ?: 0.0 },
                maxPrecipitationProbability = inPart.mapNotNull { it.precipitationProbability }.maxOrNull(),
            )
        }
}
