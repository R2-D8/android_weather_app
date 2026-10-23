package dev.r2d8.weather.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherConditionDisplayTest {
    @Test
    fun `overcast with a high rain chance is shown as rain`() {
        assertEquals(ConditionKind.Drizzle, WeatherCondition.Overcast.displayKind(67))
        assertEquals("Chance of rain", WeatherCondition.Overcast.displayLabel(67))
    }

    @Test
    fun `the threshold is inclusive`() {
        assertEquals(ConditionKind.Drizzle, WeatherCondition.Overcast.displayKind(RAIN_LIKELY_PERCENT))
        assertEquals(ConditionKind.Cloudy, WeatherCondition.Overcast.displayKind(RAIN_LIKELY_PERCENT - 1))
    }

    @Test
    fun `partly cloudy and fog are also upgraded`() {
        assertEquals(ConditionKind.Drizzle, WeatherCondition.PartlyCloudy.displayKind(80))
        assertEquals(ConditionKind.Drizzle, WeatherCondition.Fog.displayKind(80))
    }

    @Test
    fun `clear sky is never turned into rain`() {
        assertEquals(ConditionKind.Clear, WeatherCondition.ClearSky.displayKind(90))
        assertEquals("Clear sky", WeatherCondition.ClearSky.displayLabel(90))
    }

    @Test
    fun `real precipitation codes keep their own icon and text`() {
        assertEquals(ConditionKind.HeavyRain, WeatherCondition.HeavyRain.displayKind(100))
        assertEquals("Heavy rain", WeatherCondition.HeavyRain.displayLabel(100))
        assertEquals(ConditionKind.Snow, WeatherCondition.Snow.displayKind(90))
    }

    @Test
    fun `unknown chance changes nothing`() {
        assertEquals(ConditionKind.Cloudy, WeatherCondition.Overcast.displayKind(null))
        assertEquals("Overcast", WeatherCondition.Overcast.displayLabel(null))
    }
}
