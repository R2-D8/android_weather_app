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

@Serializable
data class ServerPalettes(val palettes: List<ServerPalette> = emptyList())

@Serializable
data class ServerPalette(val name: String, val bands: List<ServerBand> = emptyList())

@Serializable
data class ServerBand(val from: Double, val color: String, val alpha: Int)

interface HomeServerApi {
    @GET("frames")
    suspend fun frames(): ServerFrames

    @GET("coverage")
    suspend fun coverage(@Query("lat") latitude: Double, @Query("lon") longitude: Double): ServerCoverage

    @GET("palettes")
    suspend fun palettes(): ServerPalettes

    @GET("health")
    suspend fun health(): ServerHealth
}
