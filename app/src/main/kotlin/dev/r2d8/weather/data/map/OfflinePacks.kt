package dev.r2d8.weather.data.map

import android.content.Context
import dev.r2d8.weather.domain.MapStyle
import dev.r2d8.weather.domain.SavedPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** How the offline map around one saved place is doing. */
sealed interface PackStatus {
    /** Not downloaded yet; it will be on Wi-Fi. */
    data object Waiting : PackStatus
    data class Downloading(val bytes: Long, val percent: Int?) : PackStatus
    data class Ready(val bytes: Long) : PackStatus
    data class Failed(val message: String) : PackStatus
}

/**
 * Offline map packs around each saved place, stored by MapLibre in its own database next to the
 * normal tile cache. Packs are only downloaded on an unmetered connection while the app is open,
 * one at a time and at most [MAX_PARALLEL] requests at once (OpenFreeMap's offline terms). A pack
 * is replaced when its place moves or the pinned map data version changes, and deleted when its
 * place is removed.
 *
 * MapLibre's offline API calls back on the main thread, so every call to it runs there.
 */
class OfflinePacks(
    context: Context,
    private val mapData: MapDataRepository,
    /** MapLibre's OkHttp dispatcher; its per-host limit is lowered while downloading. */
    private val dispatcher: okhttp3.Dispatcher,
) {
    private val appContext = context.applicationContext
    private val manager by lazy {
        OfflineManager.getInstance(appContext).apply { setOfflineMapboxTileCountLimit(PackPlanner.MAX_TILES) }
    }
    private val pixelRatio = context.resources.displayMetrics.density

    private val _status = MutableStateFlow<Map<Long, PackStatus>>(emptyMap())
    /** Per saved place id. */
    val status: StateFlow<Map<Long, PackStatus>> = _status

    private data class Pack(val region: OfflineRegion, val info: PackInfo?)

    /**
     * Brings the packs in line with [places]: deletes what is no longer needed and, if
     * [unmetered], downloads what is missing. Cancelling (the app goes to the background, or the
     * connection becomes metered) pauses the current download; the next call resumes it.
     */
    suspend fun sync(places: List<SavedPlace>, unmetered: Boolean) = withContext(Dispatchers.Main) {
        // The style must be on disk before MapLibre can read which tiles a pack needs.
        val styleReady = mapData.ensureStyle(PACK_STYLE).isSuccess
        val version = mapData.state.value.version?.name
        val packs = listPacks()
        val byId = places.associateBy { it.id }

        // Packs for removed or moved places are of no use any more.
        val (kept, unused) = packs.partition { pack ->
            val info = pack.info ?: return@partition false
            val place = byId[info.placeId] ?: return@partition false
            info.latitude == place.place.latitude && info.longitude == place.place.longitude
        }
        unused.forEach { delete(it.region) }

        val statuses = mutableMapOf<Long, PackStatus>()
        val todo = mutableListOf<Pair<SavedPlace, Pack?>>()
        for (place in places) {
            val current = kept.firstOrNull { it.info?.placeId == place.id && it.info.version == version }
            val currentStatus = current?.let { getStatus(it.region) }
            if (current != null && currentStatus?.isComplete == true) {
                statuses[place.id] = PackStatus.Ready(currentStatus.completedResourceSize)
                // An older version is only dropped once the new one is complete.
                kept.filter { it.info?.placeId == place.id && it !== current }.forEach { delete(it.region) }
            } else {
                statuses[place.id] = PackStatus.Waiting
                todo += place to current
            }
        }
        _status.value = statuses
        if (!unmetered || !styleReady || version == null) return@withContext

        for ((place, existing) in todo) {
            download(place, existing, version)
            if (_status.value[place.id] is PackStatus.Ready) {
                listPacks()
                    .filter { it.info?.placeId == place.id && it.info.version != version }
                    .forEach { delete(it.region) }
            }
        }
    }

    private suspend fun download(place: SavedPlace, existing: Pack?, version: String) {
        var pack = existing
        var maxZoom = existing?.info?.maxZoom ?: PackPlanner.MAX_ZOOM
        while (true) {
            val plan = PackPlanner.plan(place.place.latitude, place.place.longitude, maxZoom)
            if (plan == null) {
                setStatus(place.id, PackStatus.Failed("Area too large"))
                return
            }
            val region = pack?.region ?: runCatching { create(place, plan, version) }.getOrElse {
                setStatus(place.id, PackStatus.Failed(it.message ?: "Could not start the download"))
                return
            }
            when (val result = run(region, place.id)) {
                is RunResult.Done -> {
                    setStatus(place.id, PackStatus.Ready(result.bytes))
                    return
                }
                RunResult.TooBig -> {
                    // Busy city areas can be heavy: drop the most detailed zoom level and try again.
                    delete(region)
                    pack = null
                    maxZoom = plan.maxZoom - 1
                    if (maxZoom < MIN_USEFUL_ZOOM) {
                        setStatus(place.id, PackStatus.Failed("Too much map data around this place"))
                        return
                    }
                }
            }
        }
    }

    private sealed interface RunResult {
        data class Done(val bytes: Long) : RunResult
        data object TooBig : RunResult
    }

    /** Downloads [region] until it is complete or too big; pauses it if cancelled. */
    private suspend fun run(region: OfflineRegion, placeId: Long): RunResult {
        val normalLimit = dispatcher.maxRequestsPerHost
        dispatcher.maxRequestsPerHost = MAX_PARALLEL
        try {
            return suspendCancellableCoroutine { cont ->
                region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        if (!cont.isActive) return
                        when {
                            status.completedResourceSize > PackPlanner.MAX_BYTES -> {
                                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                cont.resume(RunResult.TooBig)
                            }
                            status.isComplete -> {
                                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                cont.resume(RunResult.Done(status.completedResourceSize))
                            }
                            else -> setStatus(
                                placeId,
                                PackStatus.Downloading(
                                    bytes = status.completedResourceSize,
                                    percent = status.requiredResourceCount.takeIf { it > 0 }
                                        ?.let { (100 * status.completedResourceCount / it).toInt() },
                                ),
                            )
                        }
                    }

                    // Network errors are retried by MapLibre itself; only report them.
                    override fun onError(error: OfflineRegionError) {
                        if (cont.isActive) setStatus(placeId, PackStatus.Failed(error.message))
                    }

                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        if (!cont.isActive) return
                        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        cont.resume(RunResult.TooBig)
                    }
                })
                region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                cont.invokeOnCancellation {
                    // Called on any thread; MapLibre wants the main one.
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    }
                }
            }
        } finally {
            region.setObserver(null)
            dispatcher.maxRequestsPerHost = normalLimit
        }
    }

    private fun setStatus(placeId: Long, status: PackStatus) = _status.update { it + (placeId to status) }

    private suspend fun listPacks(): List<Pack> = suspendCancellableCoroutine { cont ->
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                cont.resume(offlineRegions.orEmpty().map { Pack(it, PackInfo.decode(it.metadata)) })
            }

            override fun onError(error: String) = cont.resumeWithException(IllegalStateException(error))
        })
    }

    private suspend fun create(place: SavedPlace, plan: PackPlan, version: String): OfflineRegion =
        suspendCancellableCoroutine { cont ->
            val bounds = LatLngBounds.from(plan.box.north, plan.box.east, plan.box.south, plan.box.west)
            val definition = OfflineTilePyramidRegionDefinition(
                MapDataRepository.styleUrl(PACK_STYLE),
                bounds,
                plan.minZoom.toDouble(),
                plan.maxZoom.toDouble(),
                pixelRatio,
                // Chinese/Japanese/Korean glyphs are large and not needed in Europe.
                false,
            )
            val info = PackInfo(place.id, place.place.latitude, place.place.longitude, version, plan.maxZoom)
            manager.createOfflineRegion(definition, info.encode(), object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) = cont.resume(offlineRegion)
                override fun onError(error: String) = cont.resumeWithException(IllegalStateException(error))
            })
        }

    private suspend fun getStatus(region: OfflineRegion): OfflineRegionStatus? = suspendCancellableCoroutine { cont ->
        region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) = cont.resume(status)
            override fun onError(error: String?) = cont.resume(null)
        })
    }

    private suspend fun delete(region: OfflineRegion) = suspendCancellableCoroutine { cont ->
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() = cont.resume(Unit)
            override fun onError(error: String) = cont.resume(Unit)
        })
    }


    companion object {
        /** OpenFreeMap allows at most 4 parallel requests for offline downloads. */
        const val MAX_PARALLEL = 4
        private const val MIN_USEFUL_ZOOM = 9
        /**
         * Packs are made with the detailed style. All four styles use the same map tiles, so the
         * other styles work offline too; only their fonts may be missing if never shown online.
         */
        val PACK_STYLE = MapStyle.Detailed
    }
}
