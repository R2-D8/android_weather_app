package dev.r2d8.weather.data

import dev.r2d8.weather.data.remote.AirQualityResponse
import dev.r2d8.weather.data.remote.ForecastResponse
import dev.r2d8.weather.domain.PollenType
import dev.r2d8.weather.domain.WeatherCondition
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MappersTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(name: String) = checkNotNull(javaClass.classLoader?.getResource(name)).readText()

    private val forecast = json.decodeFromString<ForecastResponse>(resource("forecast_rome.json"))
    private val air = json.decodeFromString<AirQualityResponse>(resource("air_quality_rome.json"))
    private val fetchedAt = Instant.parse("2026-10-23T22:00:00Z")

    @Test
    fun `maps a real forecast response`() {
        val report = forecast.toReport(air, fetchedAt)

        assertEquals(ZoneId.of("Europe/Rome"), report.zone)
        assertEquals(fetchedAt, report.fetchedAt)
        assertEquals(17.2, report.current.temperature!!, 0.0)
        assertEquals(83, report.current.humidity)
        assertEquals(WeatherCondition.PartlyCloudy, report.current.condition)
        assertEquals(false, report.current.isDay)
        assertEquals(155, report.current.windDirection)
        assertEquals(240, report.hours.size)
        assertEquals(10, report.days.size)
        assertEquals(8, report.rainSlots.size)
    }

    @Test
    fun `daily dates are local calendar days`() {
        val report = forecast.toReport(null, fetchedAt)
        // Daily timestamps are local midnight; at UTC+2 that is 22:00Z the day before, so a naive UTC date would be off by one.
        assertEquals(report.days[0].date.plusDays(1), report.days[1].date)
        assertEquals(0, report.days[0].date.atStartOfDay(report.zone).toInstant().compareTo(Instant.ofEpochSecond(forecast.daily.time[0])))
    }

    @Test
    fun `daily dates stay consecutive across the autumn DST change`() {
        // Real Open-Meteo daily timestamps for Rome, 23-31 Oct 2026: a fixed 86400 s apart, so after
        // the change on 25 Oct they sit at 23:00 of the previous local day.
        val driftedTimes = listOf(
            1792706400L, 1792792800L, 1792879200L, 1792965600L, 1793052000L,
            1793138400L, 1793224800L, 1793311200L, 1793397600L, 1793484000L,
        )
        val report = forecast.copy(daily = forecast.daily.copy(time = driftedTimes)).toReport(null, fetchedAt)

        assertEquals(
            (23..31).map { LocalDate.of(2026, 10, it) } + LocalDate.of(2026, 11, 1),
            report.days.map { it.date },
        )
    }

    @Test
    fun `air quality and pollen are mapped`() {
        val report = forecast.toReport(air, fetchedAt)
        val airQuality = checkNotNull(report.airQuality)

        assertEquals(39, airQuality.europeanAqi)
        assertEquals(6.9, airQuality.pm25!!, 0.0)
        assertEquals(PollenType.entries.toSet(), airQuality.pollen.map { it.type }.toSet())
    }

    @Test
    fun `missing air quality is allowed`() {
        assertNull(forecast.toReport(null, fetchedAt).airQuality)
    }
}
