package dev.r2d8.weather

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import dev.r2d8.weather.data.LocationProvider
import dev.r2d8.weather.data.NetworkMonitor
import dev.r2d8.weather.data.PlacesRepository
import dev.r2d8.weather.data.SettingsRepository
import dev.r2d8.weather.data.WeatherRepository
import dev.r2d8.weather.data.local.WeatherDatabase
import dev.r2d8.weather.data.map.MapDataRepository
import dev.r2d8.weather.data.map.OfflineMapsUpdater
import dev.r2d8.weather.data.map.OfflinePacks
import dev.r2d8.weather.data.radar.HomeServerApi
import dev.r2d8.weather.data.radar.RadarRepository
import dev.r2d8.weather.data.radar.RainViewerApi
import dev.r2d8.weather.data.remote.OpenMeteoClient
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
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

    val networkMonitor = NetworkMonitor(context)

    // The home server is on the user's tailnet: when Tailscale is off a connection attempt just
    // hangs, so these time limits are short and the radar falls back to RainViewer quickly.
    private val serverHttp = okHttp.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private val converter = json.asConverterFactory("application/json".toMediaType())

    val radarRepository = RadarRepository(
        rainViewer = Retrofit.Builder()
            .baseUrl("https://api.rainviewer.com/")
            .client(okHttp)
            .addConverterFactory(converter)
            .build()
            .create(RainViewerApi::class.java),
        serverApi = { baseUrl ->
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(serverHttp)
                .addConverterFactory(converter)
                .build()
                .create(HomeServerApi::class.java)
        },
    )

    /** Identifies the app to OpenFreeMap, as its offline-download terms ask. */
    private val userAgent = run {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        "dev.r2d8.weather/${version ?: "dev"} (personal non-commercial weather app; MapLibre Android)"
    }

    private val mapDispatcher = Dispatcher()

    val mapDataRepository = MapDataRepository(
        http = okHttp.newBuilder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        }.build(),
        directory = File(context.filesDir, "map"),
    )

    /**
     * The HTTP client MapLibre uses for everything the map loads (styles, map tiles, radar tiles).
     * It answers pinned style addresses from disk and sets the User-Agent; offline downloads lower
     * its per-host limit to 4 while they run.
     */
    val mapLibreHttp: OkHttpClient = okHttp.newBuilder()
        .dispatcher(mapDispatcher)
        .addInterceptor(mapDataRepository.interceptor)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build()) }
        .build()

    val offlinePacks = OfflinePacks(context, mapDataRepository, mapDispatcher)

    val offlineMapsUpdater = OfflineMapsUpdater(mapDataRepository, offlinePacks, placesRepository.places, networkMonitor.isMetered)
}
