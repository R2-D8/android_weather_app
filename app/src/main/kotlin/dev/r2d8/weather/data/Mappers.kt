package dev.r2d8.weather.data

import dev.r2d8.weather.data.remote.AirQualityCurrentDto
import dev.r2d8.weather.data.remote.AirQualityResponse
import dev.r2d8.weather.data.remote.ForecastResponse
import dev.r2d8.weather.domain.AirQuality
import dev.r2d8.weather.domain.CurrentConditions
import dev.r2d8.weather.domain.DayForecast
import dev.r2d8.weather.domain.HourForecast
import dev.r2d8.weather.domain.PollenReading
import dev.r2d8.weather.domain.PollenType
import dev.r2d8.weather.domain.RainSlot
import dev.r2d8.weather.domain.WeatherCondition
import dev.r2d8.weather.domain.WeatherReport
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** Combines a forecast and an optional air-quality response into the app's model. */
fun ForecastResponse.toReport(air: AirQualityResponse?, fetchedAt: Instant): WeatherReport {
    val zone = ZoneId.of(timezone)
    // Open-Meteo steps its daily timestamps by exactly 24 h and ignores clock changes, so after a
    // DST change they land at 23:00 of the previous day. Only the first one is a true local
    // midnight: take its date and count days from there instead of converting every timestamp.
    val firstDate = daily.time.firstOrNull()?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }
    return WeatherReport(
        zone = zone,
        fetchedAt = fetchedAt,
        current = CurrentConditions(
            time = Instant.ofEpochSecond(current.time),
            temperature = current.temperature,
            apparentTemperature = current.apparentTemperature,
            humidity = current.humidity?.roundToInt(),
            isDay = current.isDay != 0,
            precipitation = current.precipitation,
            condition = WeatherCondition.fromCode(current.weatherCode),
            cloudCover = current.cloudCover?.roundToInt(),
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
                condition = WeatherCondition.fromCode(hourly.weatherCode.getOrNull(i)),
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
