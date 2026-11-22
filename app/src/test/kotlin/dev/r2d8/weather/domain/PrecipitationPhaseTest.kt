package dev.r2d8.weather.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrecipitationPhaseTest {
    @Test
    fun `wet bulb matches reference values`() {
        // Stull (2011): 20 °C at 50 % gives 13.7 °C.
        assertEquals(13.7, PrecipitationPhase.wetBulb(20.0, 50.0), 0.1)
        // Saturated air: wet bulb is the temperature (within the formula's 0.3 °C).
        assertEquals(5.0, PrecipitationPhase.wetBulb(5.0, 99.0), 0.3)
    }

    @Test
    fun `Turku 22 Nov 2026 - drizzle at 0_6 degrees and 86 percent becomes light snow`() {
        // MET Nordic: 0.6 °C, 86 %, 0.2 mm rain, code 51. ECMWF: 0 mm rain, 0.14 cm snow.
        val wetBulb = PrecipitationPhase.wetBulb(0.6, 86.0)
        assertEquals(Phase.Snow, PrecipitationPhase.fromWetBulb(wetBulb))
        val phase = PrecipitationPhase.decide(0.6, 86.0, PrecipitationPhase.fromModel(0.0, 0.14))
        assertEquals(Phase.Snow, phase)
        assertEquals(WeatherCondition.LightSnow, WeatherCondition.LightDrizzle.withPhase(phase))
    }

    @Test
    fun `second model phase from rain and snowfall`() {
        assertEquals(Phase.Snow, PrecipitationPhase.fromModel(0.0, 0.14))
        assertEquals(Phase.Rain, PrecipitationPhase.fromModel(1.0, 0.0))
        assertEquals(Phase.Mixed, PrecipitationPhase.fromModel(0.5, 0.35)) // 0.5 mm water each
        assertNull(PrecipitationPhase.fromModel(0.01, 0.01))
        assertNull(PrecipitationPhase.fromModel(null, null))
    }

    @Test
    fun `agreeing, unsure and disagreeing opinions`() {
        // Wet bulb about 1 °C (mixed band): the second model decides.
        assertEquals(Phase.Snow, PrecipitationPhase.decide(1.5, 90.0, Phase.Snow))
        assertEquals(Phase.Rain, PrecipitationPhase.decide(1.5, 90.0, Phase.Rain))
        assertEquals(Phase.Mixed, PrecipitationPhase.decide(1.5, 90.0, null))
        // Wet bulb says snow (about 0 °C), second model unsure: snow.
        assertEquals(Phase.Snow, PrecipitationPhase.decide(0.6, 86.0, Phase.Mixed))
        // Wet bulb about 2 °C says rain, second model snow: both.
        assertEquals(Phase.Mixed, PrecipitationPhase.decide(2.3, 95.0, Phase.Snow))
    }

    @Test
    fun `a clear wet bulb overrules the second model`() {
        // A mountain valley where ECMWF's smoothed ground is too high: still rain at 6 °C.
        assertEquals(Phase.Rain, PrecipitationPhase.decide(6.0, 90.0, Phase.Snow))
        assertEquals(Phase.Snow, PrecipitationPhase.decide(-3.0, 90.0, Phase.Rain))
    }

    @Test
    fun `missing data`() {
        assertEquals(Phase.Snow, PrecipitationPhase.decide(null, null, Phase.Snow))
        assertNull(PrecipitationPhase.decide(null, 80.0, null))
    }

    @Test
    fun `intensity is kept`() {
        assertEquals(WeatherCondition.Snow, WeatherCondition.Rain.withPhase(Phase.Snow))
        assertEquals(WeatherCondition.HeavySnow, WeatherCondition.HeavyRain.withPhase(Phase.Snow))
        assertEquals(WeatherCondition.HeavySnowShowers, WeatherCondition.HeavyShowers.withPhase(Phase.Snow))
        assertEquals(WeatherCondition.LightRainAndSnow, WeatherCondition.LightRain.withPhase(Phase.Mixed))
        assertEquals(WeatherCondition.RainAndSnowShowers, WeatherCondition.Showers.withPhase(Phase.Mixed))
        assertEquals(WeatherCondition.Rain, WeatherCondition.Snow.withPhase(Phase.Rain))
        assertEquals(WeatherCondition.LightShowers, WeatherCondition.LightSnowShowers.withPhase(Phase.Rain))
        assertEquals(ConditionKind.RainAndSnow, WeatherCondition.LightRainAndSnow.kind)
    }

    @Test
    fun `drizzle stays drizzle when it is rain`() {
        assertEquals(WeatherCondition.LightDrizzle, WeatherCondition.LightDrizzle.withPhase(Phase.Rain))
    }

    @Test
    fun `dry, freezing and thunder codes are never changed`() {
        listOf(
            WeatherCondition.Overcast, WeatherCondition.Fog, WeatherCondition.FreezingRain,
            WeatherCondition.LightFreezingDrizzle, WeatherCondition.SnowGrains, WeatherCondition.Thunderstorm,
        ).forEach { condition ->
            Phase.entries.forEach { assertEquals(condition, condition.withPhase(it)) }
        }
        assertEquals(WeatherCondition.LightRain, WeatherCondition.LightRain.withPhase(null))
    }
}
