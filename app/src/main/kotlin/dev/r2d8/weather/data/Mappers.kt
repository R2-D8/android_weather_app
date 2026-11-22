package dev.r2d8.weather.data

import dev.r2d8.weather.data.remote.AirQualityCurrentDto
import dev.r2d8.weather.data.remote.AirQualityResponse
import dev.r2d8.weather.data.remote.ForecastResponse
import dev.r2d8.weather.data.remote.PhaseCheckResponse
import dev.r2d8.weather.domain.AirQuality
import dev.r2d8.weather.domain.CurrentConditions
import dev.r2d8.weather.domain.DayForecast
import dev.r2d8.weather.domain.HourForecast
import dev.r2d8.weather.domain.PollenReading
import dev.r2d8.weather.domain.PollenType
import dev.r2d8.weather.domain.PrecipitationPhase
import dev.r2d8.weather.domain.RainSlot
import dev.r2d8.weather.domain.WeatherCondition
import dev.r2d8.weather.domain.WeatherReport
import dev.r2d8.weather.domain.withPhase
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Combines a forecast, an optional air-quality response and an optional second model's rain/snow
 * split ([phaseCheck]) into the app's model. Rain or snow in the current and hourly conditions is
 * corrected by [PrecipitationPhase]; the daily code is only a fallback for the icon and stays as sent.
 */
fun ForecastResponse.toReport(air: AirQualityResponse?, fetchedAt: Instant, phaseCheck: PhaseCheckResponse? = null): WeatherReport {
    val zone = ZoneId.of(timezone)
    val secondModel = phaseCheck?.hourly?.let { h ->
        h.time.indices.associate { i ->
            h.time[i] to PrecipitationPhase.fromModel(
                rainMm = (h.rain.getOrNull(i) ?: 0.0) + (h.showers.getOrNull(i) ?: 0.0),
                snowfallCm = h.snowfall.getOrNull(i),
            )
        }
    }.orEmpty()
    // Hourly values cover the hour before their time, so "now" belongs to the hour ending next.
    val currentHourEnd = (current.time + 3599) / 3600 * 3600
    // Open-Meteo steps its daily timestamps by exactly 24 h and ignores clock changes, so after a
    // DST change they land at 23:00 of the previous day. Only the first one is a true local
    // midnight: take its date and count days from there instead of converting every timestamp.
    val firstDate = daily.time.firstOrNull()?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }
    return WeatherReport(
        zone = zone,
        latitude = latitude,
        longitude = longitude,
        fetchedAt = fetchedAt,
        current = CurrentConditions(
            time = Instant.ofEpochSecond(current.time),
            temperature = current.temperature,
            apparentTemperature = current.apparentTemperature,
            humidity = current.humidity?.roundToInt(),
            dewPoint = current.dewPoint,
            isDay = current.isDay != 0,
            precipitation = current.precipitation,
            condition = WeatherCondition.fromCode(current.weatherCode).withPhase(
                PrecipitationPhase.decide(current.temperature, current.humidity, secondModel[currentHourEnd]),
            ),
            cloudCover = current.cloudCover?.roundToInt(),
            cloudCoverLow = current.cloudCoverLow?.roundToInt(),
            cloudCoverMid = current.cloudCoverMid?.roundToInt(),
            cloudCoverHigh = current.cloudCoverHigh?.roundToInt(),
            pressure = current.pressure,
            windSpeed = current.windSpeed,
            windDirection = current.windDirection?.roundToInt(),
            windGusts = current.windGusts,
        ),
        rainSlots = minutely15?.let { m ->
            m.time.mapIndexedNotNull { i, t ->
                m.precipitation.getOrNull(i)?.let { RainSlot(Instant.ofEpochSecond(t), it) }
            }
        }.orEmpty(),
        hours = hourly.time.mapIndexed { i, t ->
            HourForecast(
                time = Instant.ofEpochSecond(t),
                temperature = hourly.temperature.getOrNull(i),
                precipitationProbability = hourly.precipitationProbability.getOrNull(i)?.roundToInt(),
                precipitation = hourly.precipitation.getOrNull(i),
                condition = WeatherCondition.fromCode(hourly.weatherCode.getOrNull(i)).withPhase(
                    PrecipitationPhase.decide(hourly.temperature.getOrNull(i), hourly.humidity.getOrNull(i), secondModel[t]),
                ),
                isDay = hourly.isDay.getOrNull(i) != 0,
                windSpeed = hourly.windSpeed.getOrNull(i),
                windGusts = hourly.windGusts.getOrNull(i),
                uvIndex = hourly.uvIndex.getOrNull(i),
            )
        },
        days = daily.time.indices.map { i ->
            DayForecast(
                date = checkNotNull(firstDate).plusDays(i.toLong()),
                condition = WeatherCondition.fromCode(daily.weatherCode.getOrNull(i)),
                temperatureMax = daily.temperatureMax.getOrNull(i),
                temperatureMin = daily.temperatureMin.getOrNull(i),
                sunrise = daily.sunrise.getOrNull(i)?.let(Instant::ofEpochSecond),
                sunset = daily.sunset.getOrNull(i)?.let(Instant::ofEpochSecond),
                uvIndexMax = daily.uvIndexMax.getOrNull(i),
                precipitationSum = daily.precipitationSum.getOrNull(i),
                precipitationProbabilityMax = daily.precipitationProbabilityMax.getOrNull(i)?.roundToInt(),
                windSpeedMax = daily.windSpeedMax.getOrNull(i),
                windGustsMax = daily.windGustsMax.getOrNull(i),
            )
        },
        airQuality = air?.current?.toDomain(),
    )
}

private fun AirQualityCurrentDto.toDomain() = AirQuality(
    time = Instant.ofEpochSecond(time),
    europeanAqi = europeanAqi?.roundToInt(),
    pm10 = pm10,
    pm25 = pm25,
    ozone = ozone,
    nitrogenDioxide = nitrogenDioxide,
    sulphurDioxide = sulphurDioxide,
    pollen = listOf(
        PollenType.Alder to alderPollen,
        PollenType.Birch to birchPollen,
        PollenType.Grass to grassPollen,
        PollenType.Mugwort to mugwortPollen,
        PollenType.Olive to olivePollen,
        PollenType.Ragweed to ragweedPollen,
    ).mapNotNull { (type, value) -> value?.let { PollenReading(type, it) } },
)
