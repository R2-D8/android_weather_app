package dev.r2d8.weather.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoriesTest {
    @Test
    fun `compass points round to the nearest of 16`() {
        assertEquals("N", Categories.compassPoint(0))
        assertEquals("N", Categories.compassPoint(360))
        assertEquals("NNW", Categories.compassPoint(348))
        assertEquals("N", Categories.compassPoint(349))
        assertEquals("NNE", Categories.compassPoint(22))
        assertEquals("E", Categories.compassPoint(90))
        assertEquals("SSE", Categories.compassPoint(155))
        assertEquals("S", Categories.compassPoint(180))
        assertEquals("W", Categories.compassPoint(270))
        assertEquals("NNW", Categories.compassPoint(337))
    }

    @Test
    fun `uv bands`() {
        assertEquals("Low", Categories.uvIndex(2.9).label)
        assertEquals("Moderate", Categories.uvIndex(3.0).label)
        assertEquals("High", Categories.uvIndex(6.0).label)
        assertEquals("Very high", Categories.uvIndex(8.0).label)
        assertEquals("Extreme", Categories.uvIndex(11.0).label)
    }

    @Test
    fun `european aqi bands`() {
        assertEquals("Good", Categories.europeanAqi(20).label)
        assertEquals("Fair", Categories.europeanAqi(39).label)
        assertEquals("Moderate", Categories.europeanAqi(60).label)
        assertEquals("Poor", Categories.europeanAqi(61).label)
        assertEquals("Very poor", Categories.europeanAqi(100).label)
        assertEquals("Extremely poor", Categories.europeanAqi(101).label)
    }

    @Test
    fun `weather codes map to conditions and unknown codes are safe`() {
        assertEquals(WeatherCondition.PartlyCloudy, WeatherCondition.fromCode(2))
        assertEquals(WeatherCondition.ThunderstormHeavyHail, WeatherCondition.fromCode(99))
        assertEquals(WeatherCondition.Unknown, WeatherCondition.fromCode(42))
        assertEquals(WeatherCondition.Unknown, WeatherCondition.fromCode(null))
    }
}
