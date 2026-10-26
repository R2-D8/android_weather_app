package dev.r2d8.weather.ui.weather

import androidx.annotation.DrawableRes
import dev.r2d8.weather.R
import dev.r2d8.weather.domain.ConditionKind
import dev.r2d8.weather.domain.WeatherCondition
import dev.r2d8.weather.domain.displayKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private const val MISSING = "–"

// The app is English-only and metric-only (see docs/plan.md), so formats are fixed.

fun Double?.asTemperature(): String = this?.let { "${it.roundToInt()}°" } ?: MISSING

fun Double?.asSpeed(): String = this?.let { "${it.roundToInt()} km/h" } ?: MISSING

fun Int?.asPercent(): String = this?.let { "$it%" } ?: MISSING

fun Double?.asPressure(): String = this?.let { "${it.roundToInt()} hPa" } ?: MISSING

fun Double?.asMillimetres(): String = this?.let { String.format(Locale.ROOT, "%.1f mm", it) } ?: MISSING

fun Double?.asUvIndex(): String = this?.let { String.format(Locale.ROOT, "%.0f", it) } ?: MISSING

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val hourFormat = DateTimeFormatter.ofPattern("HH", Locale.ENGLISH)

private val dateFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

fun Instant.asClock(zone: ZoneId): String = clockFormat.format(atZone(zone))

/** Weekday, day and month in [zone], e.g. "Fri 23 Oct". */
fun Instant.asDate(zone: ZoneId): String = dateFormat.format(atZone(zone))

fun java.time.LocalDate.asDate(): String = dateFormat.format(this)

fun Instant.asHour(zone: ZoneId): String = hourFormat.format(atZone(zone))

/** Icon for [condition], showing rain when the [rainChance] is high even if the weather code is cloudy. */
@DrawableRes
fun iconFor(condition: WeatherCondition, isDay: Boolean, rainChance: Int?): Int =
    iconFor(condition.displayKind(rainChance), isDay)

@DrawableRes
fun iconFor(kind: ConditionKind, isDay: Boolean): Int = when (kind) {
    ConditionKind.Clear -> if (isDay) R.drawable.ic_clear_day else R.drawable.ic_clear_night
    ConditionKind.PartlyCloudy -> if (isDay) R.drawable.ic_partly_cloudy_day else R.drawable.ic_partly_cloudy_night
    ConditionKind.Cloudy -> R.drawable.ic_cloud
    ConditionKind.Fog -> R.drawable.ic_foggy
    ConditionKind.PossibleRain -> R.drawable.ic_rain_possible
    ConditionKind.Drizzle -> R.drawable.ic_rainy_light
    ConditionKind.Rain -> R.drawable.ic_rainy
    ConditionKind.HeavyRain -> R.drawable.ic_rainy_heavy
    ConditionKind.FreezingRain -> R.drawable.ic_weather_mix
    ConditionKind.Snow -> R.drawable.ic_weather_snowy
    ConditionKind.Thunderstorm -> R.drawable.ic_thunderstorm
}
