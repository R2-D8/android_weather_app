package dev.r2d8.weather.data.map

import dev.r2d8.weather.domain.SavedPlace
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Keeps the offline maps up to date while the app is open; there is no background work. On Wi-Fi
 * it moves to newer map data about monthly and downloads packs for saved places that lack one; on
 * mobile data it only tidies up (removed places) and checks once a day that the pinned map data
 * still exists. A change of connection or of the saved places cancels the current step and starts
 * again, which resumes an unfinished download where it stopped.
 */
class OfflineMapsUpdater(
    private val mapData: MapDataRepository,
    private val packs: OfflinePacks,
    private val places: Flow<List<SavedPlace>>,
    private val metered: Flow<Boolean>,
) {
    /** Runs until cancelled; call it while the app is on screen. */
    suspend fun runWhileOpen() {
        mapData.load()
        combine(places, metered) { p, m -> p to m }
            .distinctUntilChanged()
            .collectLatest { (saved, isMetered) ->
                // Let the screen the user opened load first; also absorbs quick connection flaps.
                delay(START_DELAY_MS)
                mapData.refreshIfDue(unmetered = !isMetered)
                packs.sync(saved, unmetered = !isMetered)
            }
    }

    private companion object {
        const val START_DELAY_MS = 3_000L
    }
}
