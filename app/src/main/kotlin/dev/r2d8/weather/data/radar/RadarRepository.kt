package dev.r2d8.weather.data.radar

import dev.r2d8.weather.data.runCatchingCancellable
import dev.r2d8.weather.domain.AppSettings
import dev.r2d8.weather.domain.LegendBand
import dev.r2d8.weather.domain.Place
import dev.r2d8.weather.domain.PointTemperature
import dev.r2d8.weather.domain.RadarFrame
import dev.r2d8.weather.domain.RadarLayerSet
import dev.r2d8.weather.domain.RadarPalette
import dev.r2d8.weather.domain.RadarSourceKind
import dev.r2d8.weather.domain.TemperatureHour
import dev.r2d8.weather.domain.TemperatureLayerSet
import java.time.Instant
import kotlin.math.abs

/** The temperature maps cannot be shown; [message] is written for the user. */
class TemperatureUnavailable(message: String) : Exception(message)

sealed interface ServerTestResult {
    data class Ok(val frames: Int) : ServerTestResult
    data class Problem(val message: String) : ServerTestResult
}

/**
 * Finds the radar frames for a place. RainViewer is the default and works for everyone. The home
 * server is used **only** when the user switched it on and gave an address; it is skipped for
 * places it has no data for, and if it cannot be reached the radar falls back to RainViewer with
 * a notice. When the server answers, its weather-model rain frames (the next hours) follow the
 * radar frames, whichever radar is shown.
 */
class RadarRepository(
    private val rainViewer: RainViewerApi,
    /**
     * Whether this app's traffic goes through a VPN (Tailscale) right now; null if unknown. Only
     * used to explain why the home server could not be reached.
     */
    private val vpnActive: () -> Boolean? = { null },
    /** Builds a client for a normalised server base URL (with short time limits). */
    private val serverApi: (baseUrl: String) -> HomeServerApi,
) {
    suspend fun load(place: Place, settings: AppSettings): Result<RadarLayerSet> {
        var notice: String? = null
        var look: ServerLook? = null
        if (settings.homeServerEnabled && settings.homeServerAddress.isNotBlank()) {
            when (val attempt = tryServer(place, settings)) {
                is ServerAttempt.Used -> return Result.success(attempt.layers)
                is ServerAttempt.Skipped -> {
                    notice = attempt.reason
                    look = attempt.look
                }
            }
        }
        return runCatchingCancellable { loadRainViewer(notice, look) }
    }

    suspend fun testServer(address: String): ServerTestResult {
        ServerAddress.problem(address)?.let { return ServerTestResult.Problem(it) }
        val base = checkNotNull(ServerAddress.normalize(address))
        return try {
            val health = serverApi(base).health()
            ServerTestResult.Ok(health.frames)
        } catch (e: retrofit2.HttpException) {
            // The server answers 503 from /health while it is starting or when its frames are stale.
            ServerTestResult.Problem("Reached the server, but it is not ready (HTTP ${e.code()}).")
        } catch (e: java.io.IOException) {
            ServerTestResult.Problem("${unreachableReason()} (${e.javaClass.simpleName})")
        } catch (e: kotlinx.serialization.SerializationException) {
            ServerTestResult.Problem("That address answered, but it does not look like the radar server.")
        }
    }

    /** Why the server did not answer, as far as the phone can tell. */
    private fun unreachableReason(): String = when (vpnActive()) {
        false -> "Tailscale is off on this phone: turn it on to use your home server."
        true -> "The home server is not answering. Tailscale is on, so the server may be down or restarting."
        null -> "Home server not reachable (is Tailscale on?)."
    }

    /**
     * The home server's temperature maps. Fails with a [TemperatureUnavailable] whose message can
     * be shown as is. Never contacts anything unless the home server is switched on.
     */
    suspend fun loadTemperature(settings: AppSettings): Result<TemperatureLayerSet> {
        val api = serverFor(settings) ?: return Result.failure(TemperatureUnavailable(NEEDS_SERVER))
        val base = checkNotNull(ServerAddress.normalize(settings.homeServerAddress))
        val answer = try {
            api.temperature()
        } catch (e: retrofit2.HttpException) {
            val message = if (e.code() == 404) {
                "This home server has no temperature maps: update it to the newest version."
            } else {
                "The home server could not send the temperature maps (HTTP ${e.code()})."
            }
            return Result.failure(TemperatureUnavailable(message))
        } catch (e: java.io.IOException) {
            return Result.failure(TemperatureUnavailable(unreachableReason()))
        } catch (e: kotlinx.serialization.SerializationException) {
            return Result.failure(TemperatureUnavailable("The home server's answer could not be read."))
        }
        if (answer.hours.isEmpty()) {
            return Result.failure(
                TemperatureUnavailable("The home server is still collecting temperature maps. Try again in a few minutes."),
            )
        }
        val hours = answer.hours.sortedBy { it.id }.map {
            TemperatureHour(
                time = Instant.ofEpochSecond(it.id),
                id = it.id,
                tileUrl = "${base}temperature/${it.version ?: it.run}/${it.id}/${answer.palette}/{z}/{x}/{y}.png",
            )
        }
        val legend = answer.bands.mapNotNull { band ->
            val rgb = band.color.removePrefix("#").toIntOrNull(16) ?: return@mapNotNull null
            LegendBand(band.from, (0xFF shl 24) or rgb)
        }
        return Result.success(
            TemperatureLayerSet(
                hours = hours,
                nowIndex = hours.indices.minBy { abs(hours[it].id - answer.now) },
                maxZoom = answer.maxZoom,
                tileSize = answer.tileSize,
                legend = legend,
            ),
        )
    }

    /** The temperature at a tapped point for one hour (a request of a few hundred bytes). */
    suspend fun temperatureAt(settings: AppSettings, latitude: Double, longitude: Double, hourId: Long): Result<PointTemperature> {
        val api = serverFor(settings) ?: return Result.failure(TemperatureUnavailable(NEEDS_SERVER))
        return runCatchingCancellable {
            val point = api.temperaturePoint(latitude, longitude, hourId)
            PointTemperature(point.temperature, point.groundMetres)
        }
    }

    private fun serverFor(settings: AppSettings): HomeServerApi? {
        if (!settings.homeServerEnabled || ServerAddress.problem(settings.homeServerAddress) != null) return null
        return serverApi(checkNotNull(ServerAddress.normalize(settings.homeServerAddress)))
    }

    private sealed interface ServerAttempt {
        data class Used(val layers: RadarLayerSet) : ServerAttempt
        /** [look] is set when the server answered, so its model frames can still be shown. */
        data class Skipped(val reason: String, val look: ServerLook? = null) : ServerAttempt
    }

    /** How the server's tiles are drawn, and its model rain frames. */
    private data class ServerLook(
        val base: String,
        val api: HomeServerApi,
        /** Palette name for tile URLs, with the snow suffix when the server can show snow. */
        val paletteId: String,
        val legend: List<LegendBand>?,
        val snowLegend: List<LegendBand>,
        val mixedLegend: List<LegendBand>,
    )

    private suspend fun lookOf(api: HomeServerApi, base: String, palette: RadarPalette): ServerLook {
        val palettes = runCatchingCancellable { api.palettes() }.getOrNull()
        val suffix = palettes?.snowSuffix.orEmpty()
        return ServerLook(
            base = base,
            api = api,
            paletteId = palette.id + suffix,
            legend = palettes?.let { legendFor(it, palette) },
            snowLegend = if (suffix.isEmpty()) emptyList() else bandsOf(palettes?.snow.orEmpty()),
            mixedLegend = if (suffix.isEmpty()) emptyList() else bandsOf(palettes?.mixed.orEmpty()),
        )
    }

    /** The server's model rain frames after [after]; none if it has none or does not answer. */
    private suspend fun modelFrames(look: ServerLook, after: Instant): List<RadarFrame> {
        val model = runCatchingCancellable { look.api.model() }.getOrNull() ?: return emptyList()
        return model.hours.sortedBy { it.id }.filter { it.id > after.epochSecond }.map {
            RadarFrame(
                time = Instant.ofEpochSecond(it.id),
                tileUrl = "${look.base}model/${it.version}/${it.id}/${look.paletteId}/{z}/{x}/{y}.png",
                isModel = true,
                maxZoom = model.maxZoom,
                attribution = MODEL_ATTRIBUTION,
            )
        }
    }

    private suspend fun tryServer(place: Place, settings: AppSettings): ServerAttempt {
        val problem = ServerAddress.problem(settings.homeServerAddress)
        if (problem != null) return ServerAttempt.Skipped("Home server not used: $problem")
        val base = checkNotNull(ServerAddress.normalize(settings.homeServerAddress))
        val api = serverApi(base)
        val unreachable = ServerAttempt.Skipped("${unreachableReason()} Showing RainViewer.")

        val covered = runCatchingCancellable { api.coverage(place.latitude, place.longitude) }
            .getOrElse { return unreachable }
        val look = lookOf(api, base, settings.radarPalette)
        if (!covered.covered) {
            return ServerAttempt.Skipped("The home server has no radar for this place. Showing RainViewer.", look)
        }
        val frames = runCatchingCancellable { api.frames() }.getOrElse { return unreachable }
        val ids = pickFrames(frames.frames.map { it.id })
        if (ids.isEmpty()) return ServerAttempt.Skipped("The home server has no frames yet. Showing RainViewer.", look)

        val palette = look.paletteId
        // The short forecast only follows if it was made from the newest frame we show.
        val forecastBase = frames.forecastBase
        val forecast = if (forecastBase != null && forecastBase == ids.last()) {
            frames.forecast.sortedBy { it.leadMinutes }.map {
                RadarFrame(
                    time = Instant.ofEpochSecond(it.id),
                    tileUrl = "${base}forecast/$forecastBase/${it.leadMinutes}/$palette/{z}/{x}/{y}.png",
                    forecastMinutes = it.leadMinutes,
                )
            }
        } else {
            emptyList()
        }
        val observed = ids.map { id ->
            RadarFrame(Instant.ofEpochSecond(id), "${base}tiles/$id/$palette/{z}/{x}/{y}.png")
        }
        val model = modelFrames(look, (forecast.lastOrNull() ?: observed.last()).time)
        return ServerAttempt.Used(
            RadarLayerSet(
                source = RadarSourceKind.HomeServer,
                frames = observed + forecast + model,
                maxZoom = frames.maxZoom,
                tileSize = frames.tileSize,
                attribution = SERVER_ATTRIBUTION,
                legend = look.legend,
                notice = null,
                historyEstimateMb = SERVER_HISTORY_MB + if (model.isEmpty()) 0 else MODEL_FRAMES_MB,
                snowLegend = look.snowLegend,
                mixedLegend = look.mixedLegend,
            ),
        )
    }

    private suspend fun loadRainViewer(notice: String?, look: ServerLook?): RadarLayerSet {
        val maps = rainViewer.maps()
        val frames = maps.radar.past.sortedBy { it.time }.map {
            RadarFrame(
                time = Instant.ofEpochSecond(it.time),
                // 256 px tiles, colour scheme 2 (universal blue), smoothed, snow shown in its own colours.
                tileUrl = "${maps.host}${it.path}/256/{z}/{x}/{y}/2/1_1.png",
            )
        }
        require(frames.isNotEmpty()) { "RainViewer returned no frames" }
        // The home server answered but has no radar here: its model frames still follow.
        val model = look?.let { modelFrames(it, frames.last().time) }.orEmpty()
        return RadarLayerSet(
            source = RadarSourceKind.RainViewer,
            frames = frames + model,
            maxZoom = RAINVIEWER_MAX_ZOOM,
            tileSize = 256,
            attribution = RAINVIEWER_ATTRIBUTION,
            legend = if (model.isEmpty()) null else look?.legend,
            notice = notice,
            historyEstimateMb = RAINVIEWER_HISTORY_MB + if (model.isEmpty()) 0 else MODEL_FRAMES_MB,
            snowLegend = if (model.isEmpty()) emptyList() else look?.snowLegend.orEmpty(),
            mixedLegend = if (model.isEmpty()) emptyList() else look?.mixedLegend.orEmpty(),
        )
    }

    private fun legendFor(palettes: ServerPalettes, palette: RadarPalette): List<LegendBand>? =
        palettes.palettes.firstOrNull { it.name == palette.id }?.bands?.let(::bandsOf)

    private fun bandsOf(bands: List<ServerBand>): List<LegendBand> = bands.mapNotNull { band ->
        val rgb = band.color.removePrefix("#").toIntOrNull(16) ?: return@mapNotNull null
        LegendBand(band.from, (band.alpha.coerceIn(0, 255) shl 24) or rgb)
    }

    companion object {
        const val NEEDS_SERVER = "Temperature maps come from your home server: switch it on in Settings."

        /** RainViewer's free tiles stop at zoom 7 (higher zooms return a placeholder picture). */
        const val RAINVIEWER_MAX_ZOOM = 7
        const val RAINVIEWER_ATTRIBUTION = "Radar: RainViewer.com"
        /**
         * Rough mobile data cost of the 13 frames of one regional view, shown to the user before
         * history is loaded on a metered connection. Measured 2026-10-24: the server's frames cost
         * 0.4 MB, RainViewer's about 1.1 MB (its tiles are 20 to 35 KB each); rounded up.
         */
        const val RAINVIEWER_HISTORY_MB = 2
        const val SERVER_HISTORY_MB = 1
        const val SERVER_ATTRIBUTION = "Radar: Radar-DPC, Dipartimento della Protezione Civile (CC BY-SA 4.0)"
        const val MODEL_ATTRIBUTION = "Model rain: DWD ICON-D2/ICON-EU (CC BY 4.0)"
        /** Rough extra cost of the 12 model frames of one regional view (see docs/plan.md). */
        const val MODEL_FRAMES_MB = 1

        /** Radar history shown by default: the last 2 hours, one frame every 10 minutes. */
        const val SPAN_SECONDS = 2 * 60 * 60
        const val STEP_SECONDS = 10 * 60

        /**
         * From the server's 5-minute frames, the newest one and every 10 minutes back over 2 hours
         * (half the data of using them all). A missing frame is replaced by a neighbour within
         * 5 minutes, otherwise skipped. Oldest first.
         */
        internal fun pickFrames(ids: List<Long>): List<Long> {
            val newest = ids.maxOrNull() ?: return emptyList()
            return (SPAN_SECONDS / STEP_SECONDS downTo 0).mapNotNull { k ->
                val target = newest - k * STEP_SECONDS
                ids.minByOrNull { abs(it - target) }?.takeIf { abs(it - target) <= STEP_SECONDS / 2 }
            }.distinct()
        }
    }
}
