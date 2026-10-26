package dev.r2d8.weather.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DayOverviewTest {
    private val zone = ZoneId.of("Europe/Helsinki")
    private val date = LocalDate.of(2026, 10, 27)

    private fun at(h: Int): Instant = date.atTime(h, 0).atZone(zone).toInstant()

    private fun hour(h: Int, condition: WeatherCondition = WeatherCondition.Overcast, probability: Int = 10) = HourForecast(
        time = at(h), temperature = 10.0, precipitationProbability = probability, precipitation = 0.0,
        condition = condition, isDay = h in 8..17, windSpeed = null, windGusts = null, uvIndex = null,
    )

    private fun day(worst: WeatherCondition = WeatherCondition.HeavyRain, maxChance: Int = 75) = DayForecast(
        date = date, condition = worst, temperatureMax = 12.0, temperatureMin = 7.0, sunrise = null, sunset = null,
        uvIndexMax = null, precipitationSum = 4.0, precipitationProbabilityMax = maxChance, windSpeedMax = null, windGustsMax = null,
    )

    private val beforeTheDay: Instant = at(0).minusSeconds(3600)

    @Test
    fun `heavy rain only at night does not make the day rainy`() {
        val hours = (0..23).map { h ->
            if (h == 1) hour(h, WeatherCondition.HeavyRain, probability = 75) else hour(h)
        }
        val icon = DayOverview.icon(day(), hours, zone, beforeTheDay)
        assertEquals(WeatherCondition.Overcast, icon.condition)
        // The 75 % at 1 am must not turn the cloud into a rain icon either...
        assertEquals(10, icon.rainChance)
        // ...but the row still prints the day's real maximum.
        assertEquals(75, icon.shownRainChance)
    }

    @Test
    fun `rain for two or more waking hours makes the day rainy, with the most common kind`() {
        val hours = (0..23).map { h ->
            when (h) {
                in 14..16 -> hour(h, WeatherCondition.LightRain)
                17 -> hour(h, WeatherCondition.HeavyRain)
                else -> hour(h, WeatherCondition.PartlyCloudy)
            }
        }
        assertEquals(WeatherCondition.LightRain, DayOverview.icon(day(), hours, zone, beforeTheDay).condition)
    }

    @Test
    fun `a single rainy waking hour does not, but thunder at any waking hour does`() {
        val oneShower = (0..23).map { h -> if (h == 15) hour(h, WeatherCondition.Showers) else hour(h, WeatherCondition.MainlyClear) }
        assertEquals(WeatherCondition.MainlyClear, DayOverview.icon(day(), oneShower, zone, beforeTheDay).condition)

        val storm = (0..23).map { h -> if (h == 15) hour(h, WeatherCondition.Thunderstorm) else hour(h, WeatherCondition.MainlyClear) }
        assertEquals(WeatherCondition.Thunderstorm, DayOverview.icon(day(), storm, zone, beforeTheDay).condition)
    }

    @Test
    fun `today only looks at the hours still to come`() {
        // Rain all morning, dry afternoon; it is now 13:00.
        val hours = (0..23).map { h -> if (h < 13) hour(h, WeatherCondition.Rain, probability = 90) else hour(h, WeatherCondition.PartlyCloudy, 5) }
        val icon = DayOverview.icon(day(WeatherCondition.Rain, 90), hours, zone, from = at(13))
        assertEquals(WeatherCondition.PartlyCloudy, icon.condition)
        assertEquals(5, icon.shownRainChance)
    }

    @Test
    fun `late in the evening the remaining night hours are used`() {
        val hours = (0..23).map { h -> if (h == 23) hour(h, WeatherCondition.Fog) else hour(h, WeatherCondition.Rain) }
        assertEquals(WeatherCondition.Fog, DayOverview.icon(day(), hours, zone, from = at(23)).condition)
    }

    @Test
    fun `without hourly data the daily code is kept`() {
        val icon = DayOverview.icon(day(WeatherCondition.Snow, 60), emptyList(), zone, beforeTheDay)
        assertEquals(WeatherCondition.Snow, icon.condition)
        assertEquals(60, icon.shownRainChance)
    }
}
