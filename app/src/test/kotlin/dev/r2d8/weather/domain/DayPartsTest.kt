package dev.r2d8.weather.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DayPartsTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val date = LocalDate.of(2026, 10, 24) // UTC+2, CEST until 25 Oct 2026

    private fun hour(
        h: Int,
        temp: Double = 15.0,
        condition: WeatherCondition = WeatherCondition.ClearSky,
        rain: Double = 0.0,
        probability: Int? = 0,
    ) = HourForecast(
        time = date.atTime(h, 0).atZone(zone).toInstant(),
        temperature = temp,
        precipitationProbability = probability,
        precipitation = rain,
        condition = condition,
        isDay = h in 7..18,
        windSpeed = null,
        windGusts = null,
        uvIndex = null,
    )

    private fun fullDay(block: (Int) -> HourForecast = { hour(it) }) = (0..23).map(block)

    @Test
    fun `a full day gives four parts in order`() {
        val parts = DayParts.summarize(fullDay(), zone)
        assertEquals(DayPartType.entries, parts.map { it.type })
    }

    @Test
    fun `part uses the most severe condition`() {
        val hours = fullDay { h ->
            when (h) {
                15 -> hour(h, condition = WeatherCondition.Thunderstorm)
                13 -> hour(h, condition = WeatherCondition.LightRain)
                else -> hour(h)
            }
        }
        val afternoon = DayParts.summarize(hours, zone).first { it.type == DayPartType.Afternoon }
        assertEquals(WeatherCondition.Thunderstorm, afternoon.condition)
        val morning = DayParts.summarize(hours, zone).first { it.type == DayPartType.Morning }
        assertEquals(WeatherCondition.ClearSky, morning.condition)
    }

    @Test
    fun `heavier code wins within the same kind`() {
        val hours = fullDay { h ->
            when (h) {
                12 -> hour(h, condition = WeatherCondition.LightRain)
                14 -> hour(h, condition = WeatherCondition.Rain)
                else -> hour(h)
            }
        }
        assertEquals(WeatherCondition.Rain, DayParts.summarize(hours, zone).first { it.type == DayPartType.Afternoon }.condition)
    }

    @Test
    fun `temperature range, rain total and max probability`() {
        val hours = fullDay { h ->
            when (h) {
                6 -> hour(h, temp = 9.0, rain = 0.4, probability = 30)
                9 -> hour(h, temp = 14.5, rain = 1.1, probability = 80)
                else -> hour(h)
            }
        }
        val morning = DayParts.summarize(hours, zone).first { it.type == DayPartType.Morning }
        assertEquals(9.0, morning.temperatureMin!!, 0.0)
        assertEquals(15.0, morning.temperatureMax!!, 0.0)
        assertEquals(1.5, morning.precipitationMm, 1e-9)
        assertEquals(80, morning.maxPrecipitationProbability)
    }

    @Test
    fun `parts without hours are left out`() {
        val afternoonOnward = (14..23).map { hour(it) }
        val parts = DayParts.summarize(afternoonOnward, zone)
        assertEquals(listOf(DayPartType.Afternoon, DayPartType.Evening), parts.map { it.type })
    }

    @Test
    fun `hoursOf keeps the local day and drops past hours`() {
        val twoDays = fullDay() + (0..23).map { h ->
            hour(h).copy(time = date.plusDays(1).atTime(h, 0).atZone(zone).toInstant())
        }
        val from = date.atTime(10, 0).atZone(zone).toInstant()

        val today = DayParts.hoursOf(twoDays, date, zone, from)
        assertEquals(14, today.size)
        assertEquals(from, today.first().time)

        val tomorrow = DayParts.hoursOf(twoDays, date.plusDays(1), zone, from)
        assertEquals(24, tomorrow.size)
        assertTrue(tomorrow.all { it.time.atZone(zone).toLocalDate() == date.plusDays(1) })
    }

    @Test
    fun `local day is used not utc day`() {
        // 00:00 Rome time is 22:00Z the previous day; it must still belong to the Rome date.
        val midnight = hour(0)
        assertEquals(Instant.parse("2026-10-23T22:00:00Z"), midnight.time)
        assertEquals(1, DayParts.hoursOf(listOf(midnight), date, zone, midnight.time).size)
    }
}
