package dev.r2d8.weather.data

import dev.r2d8.weather.data.local.SavedPlaceDao
import dev.r2d8.weather.data.local.SavedPlaceEntity
import dev.r2d8.weather.data.remote.GeocodingApi
import dev.r2d8.weather.domain.Place
import dev.r2d8.weather.domain.SavedPlace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.abs

/** A city found by name search, not yet saved. [countryCode] is the ISO code, e.g. "IT". */
data class PlaceSearchResult(val place: Place, val region: String?, val countryCode: String? = null)

/** The user's saved places (order included) and the city search used to add them. */
class PlacesRepository(
    private val dao: SavedPlaceDao,
    private val geocodingApi: GeocodingApi,
) {
    val places: Flow<List<SavedPlace>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun search(query: String): Result<List<PlaceSearchResult>> {
        val name = query.trim()
        if (name.length < MIN_QUERY_LENGTH) return Result.success(emptyList())
        return runCatchingCancellable {
            geocodingApi.search(name, count = SEARCH_RESULT_COUNT).results.map {
                PlaceSearchResult(
                    place = Place(it.name, it.latitude, it.longitude),
                    region = listOfNotNull(it.admin1, it.country).distinct().joinToString(", ").ifEmpty { null },
                    countryCode = it.countryCode,
                )
            }
                // The app is aimed at Italy: those matches come first. The sort is stable, so the service's
                // own order (biggest places first) is kept within each group.
                .sortedBy { if (it.countryCode in PREFERRED_COUNTRIES) 0 else 1 }
                // The service lists some towns twice (e.g. municipality and village); they look identical here.
                .distinctBy { it.place.name to it.region }
        }
    }

    /** Saves [result] at the end of the list. A place less than ~1 km from a saved one is not added twice. */
    suspend fun add(result: PlaceSearchResult): Long {
        val existing = dao.getAll().firstOrNull {
            abs(it.latitude - result.place.latitude) < DUPLICATE_DISTANCE_DEGREES &&
                abs(it.longitude - result.place.longitude) < DUPLICATE_DISTANCE_DEGREES
        }
        if (existing != null) return existing.id
        return dao.insert(
            SavedPlaceEntity(
                name = result.place.name,
                region = result.region,
                latitude = result.place.latitude,
                longitude = result.place.longitude,
                position = dao.maxPosition() + 1,
            ),
        )
    }

    suspend fun remove(id: Long) = dao.delete(id)

    /** Moves a place [delta] steps up (negative) or down (positive) the list; stops at the ends. */
    suspend fun move(id: Long, delta: Int) {
        val ids = dao.getAll().map { it.id }
        val reordered = reorder(ids, id, delta)
        if (reordered == ids) return
        reordered.forEachIndexed { position, placeId -> dao.setPosition(placeId, position) }
    }

    private fun SavedPlaceEntity.toDomain() = SavedPlace(id, Place(name, latitude, longitude), region)

    companion object {
        const val MIN_QUERY_LENGTH = 2

        /** More than the default 10, so Italian matches are not cut off before they can be moved up. */
        private const val SEARCH_RESULT_COUNT = 20
        private val PREFERRED_COUNTRIES = setOf("IT")
        private const val DUPLICATE_DISTANCE_DEGREES = 0.01

        /** Pure reordering: returns [ids] with [id] moved by [delta], clamped to the list bounds. */
        fun reorder(ids: List<Long>, id: Long, delta: Int): List<Long> {
            val from = ids.indexOf(id)
            if (from < 0) return ids
            val to = (from + delta).coerceIn(0, ids.lastIndex)
            if (to == from) return ids
            return ids.toMutableList().apply { add(to, removeAt(from)) }
        }
    }
}
