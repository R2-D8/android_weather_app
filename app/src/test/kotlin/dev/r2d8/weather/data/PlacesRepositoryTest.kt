package dev.r2d8.weather.data

import dev.r2d8.weather.data.local.SavedPlaceDao
import dev.r2d8.weather.data.local.SavedPlaceEntity
import dev.r2d8.weather.data.remote.OpenMeteoClient
import dev.r2d8.weather.data.remote.OpenMeteoUrls
import dev.r2d8.weather.domain.Place
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeSavedPlaceDao : SavedPlaceDao {
    private val rows = MutableStateFlow<List<SavedPlaceEntity>>(emptyList())
    private var nextId = 1L
    private fun sorted(list: List<SavedPlaceEntity>) = list.sortedWith(compareBy({ it.position }, { it.id }))

    override fun observeAll(): Flow<List<SavedPlaceEntity>> = rows.map(::sorted)
    override suspend fun getAll() = sorted(rows.value)
    override suspend fun maxPosition() = rows.value.maxOfOrNull { it.position } ?: -1
    override suspend fun insert(entity: SavedPlaceEntity): Long {
        val id = nextId++
        rows.value = rows.value + entity.copy(id = id)
        return id
    }
    override suspend fun delete(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }
    override suspend fun setPosition(id: Long, position: Int) {
        rows.value = rows.value.map { if (it.id == id) it.copy(position = position) else it }
    }
}

class PlacesRepositoryTest {
    private val server = MockWebServer()
    private val dao = FakeSavedPlaceDao()
    private lateinit var repository: PlacesRepository

    @Before
    fun setUp() {
        server.start()
        val base = server.url("/").toString()
        val client = OpenMeteoClient(OkHttpClient(), Json { ignoreUnknownKeys = true }, OpenMeteoUrls(base, base, base))
        repository = PlacesRepository(dao, client.geocoding)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun result(name: String, lat: Double, lon: Double) = PlaceSearchResult(Place(name, lat, lon), "Region")

    @Test
    fun `reorder moves one step and clamps at the ends`() {
        val ids = listOf(1L, 2L, 3L)
        assertEquals(listOf(2L, 1L, 3L), PlacesRepository.reorder(ids, 2, -1))
        assertEquals(listOf(1L, 3L, 2L), PlacesRepository.reorder(ids, 2, 1))
        assertEquals(ids, PlacesRepository.reorder(ids, 1, -1))
        assertEquals(ids, PlacesRepository.reorder(ids, 3, 1))
        assertEquals(ids, PlacesRepository.reorder(ids, 99, 1))
    }

    @Test
    fun `places are saved in order and can be moved and deleted`() = runTest {
        val rome = repository.add(result("Rome", 41.89, 12.48))
        val milan = repository.add(result("Milan", 45.46, 9.19))
        val turin = repository.add(result("Turin", 45.07, 7.69))
        assertEquals(listOf("Rome", "Milan", "Turin"), repository.places.first().map { it.place.name })

        repository.move(turin, -2)
        assertEquals(listOf("Turin", "Rome", "Milan"), repository.places.first().map { it.place.name })

        repository.remove(rome)
        assertEquals(listOf("Turin", "Milan"), repository.places.first().map { it.place.name })
        assertTrue(milan > 0)
    }

    @Test
    fun `adding the same place twice keeps one entry`() = runTest {
        val first = repository.add(result("Rome", 41.89, 12.48))
        val again = repository.add(result("Roma", 41.8905, 12.4805))

        assertEquals(first, again)
        assertEquals(1, repository.places.first().size)
    }

    @Test
    fun `search maps results with region and skips short queries`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"results":[
                    {"id":1,"name":"Bologna","latitude":44.49,"longitude":11.34,"country":"Italy","admin1":"Emilia-Romagna"},
                    {"id":2,"name":"Bologna","latitude":1.0,"longitude":2.0,"country":"Philippines"},
                    {"id":3,"name":"Bologna","latitude":44.50,"longitude":11.30,"country":"Italy","admin1":"Emilia-Romagna"}
                ]}""",
            ),
        )

        val results = repository.search("Bolo").getOrThrow()

        // The third row has the same name and region as the first, so it is dropped.
        assertEquals(listOf("Emilia-Romagna, Italy", "Philippines"), results.map { it.region })
        assertEquals(Place("Bologna", 44.49, 11.34), results.first().place)
        assertTrue(server.takeRequest().path!!.contains("name=Bolo"))

        assertEquals(emptyList<PlaceSearchResult>(), repository.search(" a ").getOrThrow())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `italian matches are listed first and the rest keep the service order`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"results":[
                    {"id":1,"name":"Rome","latitude":34.0,"longitude":-85.0,"country":"United States","country_code":"US","admin1":"Georgia"},
                    {"id":2,"name":"Rome","latitude":41.89,"longitude":12.48,"country":"Italy","country_code":"IT","admin1":"Lazio"},
                    {"id":3,"name":"Rome","latitude":43.2,"longitude":-75.4,"country":"United States","country_code":"US","admin1":"New York"},
                    {"id":4,"name":"Rome","latitude":40.1,"longitude":18.1,"country":"Italy","country_code":"IT","admin1":"Puglia"}
                ]}""",
            ),
        )

        val results = repository.search("Rome").getOrThrow()

        assertEquals(listOf("Lazio", "Puglia", "Georgia", "New York"), results.map { it.region?.substringBefore(",") })
        assertTrue(server.takeRequest().path!!.contains("count=20"))
    }

    @Test
    fun `search with no match gives an empty list`() = runTest {
        server.enqueue(MockResponse().setBody("""{"generationtime_ms":0.5}"""))

        assertEquals(emptyList<PlaceSearchResult>(), repository.search("Nowhereville").getOrThrow())
    }

    @Test
    fun `search failure is reported as a failed result`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertTrue(repository.search("Rome").isFailure)
    }
}
