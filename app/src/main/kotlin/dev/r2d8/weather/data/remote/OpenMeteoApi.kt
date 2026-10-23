package dev.r2d8.weather.data.remote

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

interface ForecastApi {
    @GET("v1/forecast")
    suspend fun forecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = CURRENT,
        @Query("minutely_15") minutely15: String = MINUTELY_15,
        @Query("hourly") hourly: String = HOURLY,
        @Query("daily") daily: String = DAILY,
        @Query("forecast_days") forecastDays: Int = 10,
        @Query("forecast_minutely_15") forecastMinutely15: Int = 16,
        @Query("timezone") timezone: String = "auto",
        @Query("timeformat") timeFormat: String = "unixtime",
    ): ForecastResponse

    companion object {
        const val CURRENT = "temperature_2m,apparent_temperature,relative_humidity_2m,is_day,precipitation," +
            "weather_code,cloud_cover,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m"
        const val MINUTELY_15 = "precipitation"
        const val HOURLY = "temperature_2m,precipitation_probability,precipitation,weather_code,is_day," +
            "wind_speed_10m,wind_gusts_10m,uv_index"
        const val DAILY = "weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max," +
            "precipitation_sum,precipitation_probability_max,wind_speed_10m_max,wind_gusts_10m_max"
    }
}

interface AirQualityApi {
    @GET("v1/air-quality")
    suspend fun airQuality(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = CURRENT,
        @Query("timezone") timezone: String = "auto",
        @Query("timeformat") timeFormat: String = "unixtime",
    ): AirQualityResponse

    companion object {
        const val CURRENT = "european_aqi,pm10,pm2_5,ozone,nitrogen_dioxide,sulphur_dioxide," +
            "alder_pollen,birch_pollen,grass_pollen,mugwort_pollen,olive_pollen,ragweed_pollen"
    }
}

interface GeocodingApi {
    @GET("v1/search")
    suspend fun search(
        @Query("name") name: String,
        @Query("count") count: Int = 10,
        @Query("language") language: String = "en",
        @Query("format") format: String = "json",
    ): GeocodingResponse
}

/** Base URLs of the three Open-Meteo hosts; tests point them at a mock server. */
data class OpenMeteoUrls(
    val forecast: String = "https://api.open-meteo.com/",
    val airQuality: String = "https://air-quality-api.open-meteo.com/",
    val geocoding: String = "https://geocoding-api.open-meteo.com/",
)

class OpenMeteoClient(
    private val okHttp: OkHttpClient,
    json: Json,
    urls: OpenMeteoUrls = OpenMeteoUrls(),
) {
    private val converter = json.asConverterFactory("application/json".toMediaType())

    private fun retrofit(baseUrl: String) = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttp)
        .addConverterFactory(converter)
        .build()

    val forecast: ForecastApi = retrofit(urls.forecast).create(ForecastApi::class.java)
    val airQuality: AirQualityApi = retrofit(urls.airQuality).create(AirQualityApi::class.java)
    val geocoding: GeocodingApi = retrofit(urls.geocoding).create(GeocodingApi::class.java)
}
