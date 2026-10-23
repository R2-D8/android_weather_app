package dev.r2d8.weather

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import dev.r2d8.weather.data.LocationProvider
import dev.r2d8.weather.data.PlacesRepository
import dev.r2d8.weather.data.SettingsRepository
import dev.r2d8.weather.data.WeatherRepository
import dev.r2d8.weather.data.local.WeatherDatabase
import dev.r2d8.weather.data.remote.OpenMeteoClient
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// One DataStore file per app; the delegate must be declared once at top level.
private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * Manual dependency injection: builds the app's long-lived objects once. It lives in
 * [WeatherApplication], and ViewModels take what they need from here.
 */
class AppContainer(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val database = Room.databaseBuilder(context, WeatherDatabase::class.java, "weather.db").build()

    private val openMeteo = OpenMeteoClient(okHttp, json)

    val weatherRepository = WeatherRepository(
        forecastApi = openMeteo.forecast,
        airQualityApi = openMeteo.airQuality,
        dao = database.cachedWeatherDao(),
        json = json,
    )

    val placesRepository = PlacesRepository(database.savedPlaceDao(), openMeteo.geocoding)

    val settingsRepository = SettingsRepository(context.settingsDataStore)

    val locationProvider = LocationProvider(context)
}
