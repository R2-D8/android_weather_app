package dev.r2d8.weather.domain

import java.time.Instant
import java.time.ZoneId

/**
 * What a day's row in the daily list shows as icon. Open-Meteo's daily weather code is the
 * **worst** weather of the whole day, so one heavy shower at 1 am made the entire day look like a
 * downpour. Instead the icon describes the hours people are usually awake ([WAKING_HOURS]), and for
 * today only the hours still to come, so a morning shower is gone from the icon once it is over.
 */
object DayOverview {
    /** Local clock hours (inclusive) that decide a day's icon. */
    val WAKING_HOURS = 7..22

    /** Rain (or snow) needs at least this many hours to make the icon wet. */
    const val WET_HOURS = 2

    private val wetKinds = setOf(
        ConditionKind.Drizzle, ConditionKind.Rain, ConditionKind.HeavyRain,
        ConditionKind.Snow, ConditionKind.FreezingRain,
    )

    /**
     * [condition] and [rainChance] feed the icon (the chance turns a cloud into a rain icon from
     * 50 %, see [displayKind]); [shownRainChance] is the percentage printed in the row.
     */
    data class Icon(val condition: WeatherCondition, val rainChance: Int?, val shownRainChance: Int?)

    /**
     * The icon for [day]. [hours] may hold any hours; those of [day] at or after [from] count, so
     * for today pass the start of the current hour and for later days anything before them.
     */
    fun icon(day: DayForecast, hours: List<HourForecast>, zone: ZoneId, from: Instant): Icon {
        val left = DayParts.hoursOf(hours, day.date, zone, from)
        val isToday = hours.any { it.time.atZone(zone).toLocalDate() == day.date && it.time < from }
        // Today the printed chance covers what is left of the day; other days keep the daily maximum.
        val shown = if (isToday) left.mapNotNull { it.precipitationProbability }.maxOrNull() else day.precipitationProbabilityMax
        // Late evening, when no waking hour is left, the remaining night hours are all there is.
        val candidates = left.filter { it.time.atZone(zone).hour in WAKING_HOURS }.ifEmpty { left }
        if (candidates.isEmpty()) return Icon(day.condition, day.precipitationProbabilityMax, shown)
        val chance = candidates.mapNotNull { it.precipitationProbability }.maxOrNull()

        val thunder = candidates.filter { it.condition.kind == ConditionKind.Thunderstorm }
        if (thunder.isNotEmpty()) return Icon(thunder.maxBy { it.condition.code }.condition, chance, shown)

        val wet = candidates.filter { it.condition.kind in wetKinds }
        val pool = if (wet.size >= minOf(WET_HOURS, candidates.size)) wet else candidates - wet.toSet()
        return Icon(mostCommon(pool.map { it.condition }), chance, shown)
    }

    /** The condition seen in most hours; a tie goes to the more severe one (a cloudier sky, heavier rain). */
    private fun mostCommon(conditions: List<WeatherCondition>): WeatherCondition =
        conditions.groupingBy { it }.eachCount().entries
            .maxWith(compareBy<Map.Entry<WeatherCondition, Int>> { it.value }
                .thenBy { it.key.kind.severity }
                .thenBy { it.key.code })
            .key
}
