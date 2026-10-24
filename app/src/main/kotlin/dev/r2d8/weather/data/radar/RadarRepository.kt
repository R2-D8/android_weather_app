package dev.r2d8.weather.data.radar

import dev.r2d8.weather.data.runCatchingCancellable
import dev.r2d8.weather.domain.AppSettings
import dev.r2d8.weather.domain.LegendBand
import dev.r2d8.weather.domain.Place
import dev.r2d8.weather.domain.RadarFrame
import dev.r2d8.weather.domain.RadarLayerSet
import dev.r2d8.weather.domain.RadarPalette
import dev.r2d8.weather.domain.RadarSourceKind
import java.time.Instant
import kotlin.math.abs

sealed interface ServerTestResult {
    data class Ok(val frames: Int) : ServerTestResult
    data class Problem(val message: String) : ServerTestResult
}

/**
 * Finds the radar frames for a place. RainViewer is the default and works for everyone. The home
 * server is used **only** when the user switched it on and gave an address; it is skipped for
 * places it has no data for, and if it cannot be reached the radar falls back to RainViewer with
 * a notice.
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
        if (settings.homeServerEnabled && settings.homeServerAddress.isNotBlank()) {
            when (val attempt = tryServer(place, settings)) {
                is ServerAttempt.Used -> return Result.success(attempt.layers)
                is ServerAttempt.Skipped -> notice = attempt.reason
            }
        }
        return runCatchingCancellable { loadRainViewer(notice) }
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

    private sealed interface ServerAttempt {
        data class Used(val layers: RadarLayerSet) : ServerAttempt
        data class Skipped(val reason: String) : ServerAttempt
    }

    private suspend fun tryServer(place: Place, settings: AppSettings): ServerAttempt {
        val problem = ServerAddress.problem(settings.homeServerAddress)
        if (problem != null) return ServerAttempt.Skipped("Home server not used: $problem")
        val base = checkNotNull(ServerAddress.normalize(settings.homeServerAddress))
        val api = serverApi(base)
        val unreachable = ServerAttempt.Skipped("${unreachableReason()} Showing RainViewer.")

        val covered = runCatchingCancellable { api.coverage(place.latitude, place.longitude) }
            .getOrElse { return unreachable }
        if (!covered.covered) {
            return ServerAttempt.Skipped("The home server has no radar for this place. Showing RainViewer.")
        }
        val frames = runCatchingCancellable { api.frames() }.getOrElse { return unreachable }
        val ids = pickFrames(frames.frames.map { it.id })
        if (ids.isEmpty()) return ServerAttempt.Skipped("The home server has no frames yet. Showing RainViewer.")

        val palette = settings.radarPalette
        val legend = runCatchingCancellable { api.palettes() }.getOrNull()?.let { legendFor(it, palette) }
        // The short forecast only follows if it was made from the newest frame we show.
        val forecastBase = frames.forecastBase
        val forecast = if (forecastBase != null && forecastBase == ids.last()) {
            frames.forecast.sortedBy { it.leadMinutes }.map {
                RadarFrame(
                    time = Instant.ofEpochSecond(it.id),
                    tileUrl = "${base}forecast/$forecastBase/${it.leadMinutes}/${palette.id}/{z}/{x}/{y}.png",
                    forecastMinutes = it.leadMinutes,
                )
            }
        } else {
            emptyList()
        }
        return ServerAttempt.Used(
            RadarLayerSet(
                source = RadarSourceKind.HomeServer,
                frames = ids.map { id ->
                    RadarFrame(Instant.ofEpochSecond(id), "${base}tiles/$id/${palette.id}/{z}/{x}/{y}.png")
                } + forecast,
                maxZoom = frames.maxZoom,
                tileSize = frames.tileSize,
                attribution = SERVER_ATTRIBUTION,
                legend = legend,
                notice = null,
                historyEstimateMb = SERVER_HISTORY_MB,
            ),
        )
    }

    private suspend fun loadRainViewer(notice: String?): RadarLayerSet {
        val maps = rainViewer.maps()
        val frames = maps.radar.past.sortedBy { it.time }.map {
            RadarFrame(
                time = Instant.ofEpochSecond(it.time),
                // 256 px tiles, colour scheme 2 (universal blue), smoothed, snow shown in its own colours.
                tileUrl = "${maps.host}${it.path}/256/{z}/{x}/{y}/2/1_1.png",
            )
        }
        require(frames.isNotEmpty()) { "RainViewer returned no frames" }
        return RadarLayerSet(
            source = RadarSourceKind.RainViewer,
            frames = frames,
            maxZoom = RAINVIEWER_MAX_ZOOM,
            tileSize = 256,
            attribution = RAINVIEWER_ATTRIBUTION,
            legend = null,
            notice = notice,
            historyEstimateMb = RAINVIEWER_HISTORY_MB,
        )
    }

    private fun legendFor(palettes: ServerPalettes, palette: RadarPalette): List<LegendBand>? =
        palettes.palettes.firstOrNull { it.name == palette.id }?.bands?.mapNotNull { band ->
            val rgb = band.color.removePrefix("#").toIntOrNull(16) ?: return@mapNotNull null
            LegendBand(band.from, (band.alpha.coerceIn(0, 255) shl 24) or rgb)
        }

    companion object {
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
