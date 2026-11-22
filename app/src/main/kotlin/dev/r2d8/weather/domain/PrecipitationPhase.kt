package dev.r2d8.weather.domain

import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.sqrt

/** Whether falling precipitation reaches the ground as rain, snow or both. */
enum class Phase { Rain, Mixed, Snow }

/**
 * Corrects rain versus snow in the main forecast. Open-Meteo's main model for a place (MET Nordic
 * in Finland) sometimes calls wet snow "drizzle" at +0.6 °C, while ECMWF and ICON call the same
 * hour "light snow" (Turku, 22 Nov 2026). Two independent opinions decide instead:
 *
 * 1. The **wet-bulb temperature** from the main model's temperature and humidity: falling flakes
 *    cool by evaporation, so in dry air snow survives above 0 °C. At most [SNOW_WET_BULB] snow,
 *    up to [RAIN_WET_BULB] mixed, above that rain.
 * 2. **ECMWF's split** of the same hour into rain and snow (a second, ~1 KB request).
 *
 * If both say the same, that is it. If one is unsure (mixed), the other decides. If they clearly
 * disagree, it is rain and snow. A wet-bulb of at least [SURE_RAIN_WET_BULB] or at most
 * [SURE_SNOW_WET_BULB] decides alone, because ECMWF's 25 km grid gets the ground height wrong in
 * the mountains. The amount and the intensity always stay the main model's.
 */
object PrecipitationPhase {
    const val SNOW_WET_BULB = 0.5
    const val RAIN_WET_BULB = 1.5
    const val SURE_SNOW_WET_BULB = -1.0
    const val SURE_RAIN_WET_BULB = 3.0

    /** Less water than this (mm in the hour) in the second model is not an opinion. */
    const val MIN_SECOND_MODEL_MM = 0.05

    /** Open-Meteo converts snow water to snow depth as 1 mm = 0.7 cm. */
    private const val CM_SNOW_PER_MM = 0.7

    /**
     * Wet-bulb temperature in °C (Stull 2011), accurate to about 0.3 °C for 5-99 % humidity at
     * normal pressure.
     */
    fun wetBulb(temperature: Double, relativeHumidity: Double): Double {
        val rh = relativeHumidity.coerceIn(5.0, 99.0)
        val t = temperature
        return t * atan(0.151977 * sqrt(rh + 8.313659)) + atan(t + rh) - atan(rh - 1.676331) +
            0.00391838 * rh.pow(1.5) * atan(0.023101 * rh) - 4.686035
    }

    fun fromWetBulb(wetBulb: Double): Phase = when {
        wetBulb <= SNOW_WET_BULB -> Phase.Snow
        wetBulb <= RAIN_WET_BULB -> Phase.Mixed
        else -> Phase.Rain
    }

    /** The phase a model gives with [rainMm] (rain plus showers) and [snowfallCm]; null when it is (almost) dry. */
    fun fromModel(rainMm: Double?, snowfallCm: Double?): Phase? {
        val rain = rainMm ?: 0.0
        val snow = (snowfallCm ?: 0.0) / CM_SNOW_PER_MM
        val total = rain + snow
        if (total < MIN_SECOND_MODEL_MM) return null
        val snowShare = snow / total
        return when {
            snowShare >= 0.8 -> Phase.Snow
            snowShare > 0.2 -> Phase.Mixed
            else -> Phase.Rain
        }
    }

    /** The combined phase, or null when neither opinion is available. */
    fun decide(temperature: Double?, relativeHumidity: Double?, secondModel: Phase?): Phase? {
        val wetBulb = if (temperature != null && relativeHumidity != null) wetBulb(temperature, relativeHumidity) else null
        if (wetBulb != null && (wetBulb <= SURE_SNOW_WET_BULB || wetBulb >= SURE_RAIN_WET_BULB)) return fromWetBulb(wetBulb)
        val first = wetBulb?.let(::fromWetBulb)
        return when {
            first == null -> secondModel
            secondModel == null || secondModel == first -> first
            first == Phase.Mixed -> secondModel
            secondModel == Phase.Mixed -> first
            else -> Phase.Mixed // One says rain, the other snow.
        }
    }
}

/**
 * This condition with its precipitation turned into [phase], keeping the intensity. Dry weather,
 * freezing rain, snow grains and thunderstorms are left alone; so is everything when [phase] is null.
 */
fun WeatherCondition.withPhase(phase: Phase?): WeatherCondition {
    if (phase == null) return this
    val showers = this in showerCodes
    val strength = strengths[this] ?: return this
    return when (phase) {
        Phase.Rain -> if (showers) listOf(WeatherCondition.LightShowers, WeatherCondition.Showers, WeatherCondition.HeavyShowers)[strength]
            else if (kind == ConditionKind.Drizzle) this
            else listOf(WeatherCondition.LightRain, WeatherCondition.Rain, WeatherCondition.HeavyRain)[strength]
        Phase.Mixed -> if (showers) listOf(WeatherCondition.LightRainAndSnowShowers, WeatherCondition.RainAndSnowShowers, WeatherCondition.RainAndSnowShowers)[strength]
            else listOf(WeatherCondition.LightRainAndSnow, WeatherCondition.RainAndSnow, WeatherCondition.RainAndSnow)[strength]
        Phase.Snow -> if (showers) listOf(WeatherCondition.LightSnowShowers, WeatherCondition.LightSnowShowers, WeatherCondition.HeavySnowShowers)[strength]
            else listOf(WeatherCondition.LightSnow, WeatherCondition.Snow, WeatherCondition.HeavySnow)[strength]
    }
}

private val showerCodes = setOf(
    WeatherCondition.LightShowers, WeatherCondition.Showers, WeatherCondition.HeavyShowers,
    WeatherCondition.LightRainAndSnowShowers, WeatherCondition.RainAndSnowShowers,
    WeatherCondition.LightSnowShowers, WeatherCondition.HeavySnowShowers,
)

/** 0 light, 1 moderate, 2 heavy, for the conditions [withPhase] may change. Drizzle counts as light. */
private val strengths = mapOf(
    WeatherCondition.LightDrizzle to 0, WeatherCondition.Drizzle to 0, WeatherCondition.DenseDrizzle to 1,
    WeatherCondition.LightRain to 0, WeatherCondition.Rain to 1, WeatherCondition.HeavyRain to 2,
    WeatherCondition.LightRainAndSnow to 0, WeatherCondition.RainAndSnow to 1,
    WeatherCondition.LightSnow to 0, WeatherCondition.Snow to 1, WeatherCondition.HeavySnow to 2,
    WeatherCondition.LightShowers to 0, WeatherCondition.Showers to 1, WeatherCondition.HeavyShowers to 2,
    WeatherCondition.LightRainAndSnowShowers to 0, WeatherCondition.RainAndSnowShowers to 1,
    WeatherCondition.LightSnowShowers to 0, WeatherCondition.HeavySnowShowers to 2,
)
