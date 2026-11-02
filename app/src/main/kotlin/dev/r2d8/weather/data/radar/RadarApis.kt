package dev.r2d8.weather.data.radar

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

// --- RainViewer (https://www.rainviewer.com/api.html): the built-in radar, works for everyone ---

@Serializable
data class RainViewerMaps(val host: String, val radar: RainViewerRadar)

@Serializable
data class RainViewerRadar(val past: List<RainViewerFrame> = emptyList())

/** [time] is epoch seconds; [path] is appended to the host to form tile URLs. */
@Serializable
data class RainViewerFrame(val time: Long, val path: String)

interface RainViewerApi {
    @GET("public/weather-maps.json")
    suspend fun maps(): RainViewerMaps
}

// --- The user's own radar server (the weather_radar_backend project); optional, off by default ---

@Serializable
data class ServerFrames(
    val frames: List<ServerFrame>,
    @SerialName("step_seconds") val stepSeconds: Int = 300,
    @SerialName("max_zoom") val maxZoom: Int = 8,
    @SerialName("tile_size") val tileSize: Int = 256,
    /** The observed frame the short forecast was made from, and the forecast frames (both optional). */
    @SerialName("forecast_base") val forecastBase: Long? = null,
    val forecast: List<ServerForecastFrame> = emptyList(),
)

@Serializable
data class ServerForecastFrame(@SerialName("lead_minutes") val leadMinutes: Int, val id: Long)

/** [id] is the frame time in epoch seconds. */
@Serializable
data class ServerFrame(val id: Long)

@Serializable
data class ServerCoverage(val covered: Boolean)

@Serializable
data class ServerHealth(val status: String, val frames: Int = 0)

/**
 * The server's rain palettes. When [snowSuffix] is set (the server knows the model snow line),
 * appending it to a palette name gives tiles with snow and mixed precipitation in the [snow] and
 * [mixed] colours.
 */
@Serializable
data class ServerPalettes(
    val palettes: List<ServerPalette> = emptyList(),
    @SerialName("snow_suffix") val snowSuffix: String? = null,
    val mixed: List<ServerBand> = emptyList(),
    val snow: List<ServerBand> = emptyList(),
)

@Serializable
data class ServerPalette(val name: String, val bands: List<ServerBand> = emptyList())

@Serializable
data class ServerBand(val from: Double, val color: String, val alpha: Int)

/** Hourly temperature maps (`GET /temperature`); [now] is the current hour, epoch seconds. */
@Serializable
data class ServerTemperature(
    val hours: List<ServerTemperatureHour> = emptyList(),
    val now: Long = 0,
    @SerialName("max_zoom") val maxZoom: Int = 8,
    @SerialName("tile_size") val tileSize: Int = 256,
    val palette: String = "temp2c",
    val bands: List<ServerTemperatureBand> = emptyList(),
)

/**
 * [id] is the hour, [run] the ICON-EU run it comes from (both epoch seconds). [version] names all
 * model runs used (ICON-EU and ICON-D2) and goes into the tile URL; older servers only send [run].
 */
@Serializable
data class ServerTemperatureHour(val id: Long, val run: Long, val version: String? = null)

/** Model rain frames (`GET /model`): each is the rain in the hour ending at its [ServerModelHour.id]. */
@Serializable
data class ServerModel(
    val hours: List<ServerModelHour> = emptyList(),
    @SerialName("max_zoom") val maxZoom: Int = 8,
    @SerialName("tile_size") val tileSize: Int = 256,
)

/** [id] is the end of the hour (epoch seconds); [version] names the model runs, for the tile URL. */
@Serializable
data class ServerModelHour(val id: Long, val version: String)

@Serializable
data class ServerTemperatureBand(val from: Double, val color: String)

@Serializable
data class ServerTemperaturePoint(val temperature: Double? = null, @SerialName("ground_m") val groundMetres: Int = 0)

interface HomeServerApi {
    @GET("frames")
    suspend fun frames(): ServerFrames

    @GET("coverage")
    suspend fun coverage(@Query("lat") latitude: Double, @Query("lon") longitude: Double): ServerCoverage

    @GET("palettes")
    suspend fun palettes(): ServerPalettes

    @GET("health")
    suspend fun health(): ServerHealth

    @GET("temperature")
    suspend fun temperature(): ServerTemperature

    @GET("model")
    suspend fun model(): ServerModel

    @GET("temperature/point")
    suspend fun temperaturePoint(
        @Query("lat") latitude: Double,
        @Query("lon") longitude: Double,
        @Query("valid") hourId: Long,
    ): ServerTemperaturePoint
}
