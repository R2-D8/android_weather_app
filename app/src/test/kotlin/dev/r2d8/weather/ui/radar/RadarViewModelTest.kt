package dev.r2d8.weather.ui.radar

import dev.r2d8.weather.data.radar.FakeRainViewer
import dev.r2d8.weather.data.radar.FakeServer
import dev.r2d8.weather.data.radar.RadarRepository
import dev.r2d8.weather.domain.AppSettings
import dev.r2d8.weather.domain.Basemap
import dev.r2d8.weather.domain.Place
import dev.r2d8.weather.domain.RadarSourceKind
import dev.r2d8.weather.domain.SavedPlace
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RadarViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val rome = Place("Rome", 41.89, 12.48)
    private val settings = MutableStateFlow(AppSettings(lastGpsPlace = rome))
    private val places = MutableStateFlow(emptyList<SavedPlace>())
    private val metered = MutableStateFlow(false)
    private val rainViewer = FakeRainViewer()
    private val server = FakeServer()
    private val savedBasemaps = mutableListOf<Basemap>()

    private fun TestScope.viewModel(): RadarViewModel {
        val vm = RadarViewModel(
            radar = RadarRepository(rainViewer) { server },
            settings = settings,
            places = places,
            metered = metered,
            saveBasemap = { savedBasemaps += it },
            frameMillis = 600,
            holdMillis = 1500,
        )
        // uiState only runs while someone collects it, like the screen does.
        backgroundScope.launch { vm.uiState.collect {} }
        // Whatever a test does, playback must not outlive it: its endless loop would keep the
        // test clock busy forever and hide the real failure.
        backgroundScope.launch {
            try {
                awaitCancellation()
            } finally {
                vm.pause()
            }
        }
        runCurrent()
        return vm
    }

    private fun RadarViewModel.state() = uiState.value

    @Test
    fun `on wifi all frames load and the newest is shown`() = runTest(dispatcher, timeout = 15.seconds) {
        val vm = viewModel()
        val s = vm.state()
        assertEquals(RadarSourceKind.RainViewer, s.layers!!.source)
        assertEquals(13, s.frames.size)
        assertEquals(12, s.index)
        assertFalse(s.isLoading)
        assertFalse(s.historyPending)
        assertEquals(rome, s.place)
    }

    @Test
    fun `on mobile data with the setting off only the newest frame is loaded`() = runTest(dispatcher, timeout = 15.seconds) {
        metered.value = true
        val vm = viewModel()
        val s = vm.state()
        assertEquals(1, s.frames.size)
        assertFalse(s.historyAllowed)
        assertFalse(s.historyPending)
        assertFalse(s.canPlay)
    }

    @Test
    fun `on mobile data with the setting on the history waits for Play`() = runTest(dispatcher, timeout = 15.seconds) {
        metered.value = true
        settings.value = settings.value.copy(radarHistoryOnMobileData = true)
        val vm = viewModel()
        assertEquals(1, vm.state().frames.size)
        assertTrue(vm.state().historyAllowed)
        assertTrue(vm.state().historyPending)

        vm.togglePlay()
        runCurrent()
        assertEquals("Play asks for the history", 13, vm.state().frames.size)
        assertFalse("not playing before the frames have loaded", vm.state().playing)

        vm.onMapIdle() // the map reports its tiles are in
        runCurrent()
        assertTrue(vm.state().playing)
        vm.pause()
    }

    @Test
    fun `load once on mobile data allows the history for this visit and plays`() = runTest(dispatcher, timeout = 15.seconds) {
        metered.value = true
        val vm = viewModel()
        vm.loadHistoryOnce()
        runCurrent()
        assertEquals(13, vm.state().frames.size)
        vm.onMapIdle()
        runCurrent()
        assertTrue(vm.state().playing)
        vm.pause()
    }

    @Test
    fun `playing steps through the frames, pauses on the newest and starts over`() = runTest(dispatcher, timeout = 15.seconds) {
        val vm = viewModel()
        vm.onMapIdle()
        vm.select(10)
        runCurrent()
        vm.togglePlay()
        runCurrent()
        assertTrue(vm.state().playing)

        advanceTimeBy(601)
        assertEquals(11, vm.state().index)
        advanceTimeBy(600)
        assertEquals(12, vm.state().index)
        advanceTimeBy(1_400)
        assertEquals("holds on the newest picture", 12, vm.state().index)
        advanceTimeBy(200)
        assertEquals("then starts over", 0, vm.state().index)
        vm.pause()
    }

    @Test
    fun `dragging the slider stops playing and shows that frame`() = runTest(dispatcher, timeout = 15.seconds) {
        val vm = viewModel()
        vm.onMapIdle()
        runCurrent()
        vm.togglePlay()
        runCurrent()
        assertTrue(vm.state().playing)

        vm.select(3)
        runCurrent()

        assertFalse(vm.state().playing)
        assertEquals(3, vm.state().index)
    }

    @Test
    fun `play does nothing until the map has loaded the frames`() = runTest(dispatcher, timeout = 15.seconds) {
        val vm = viewModel()
        vm.onFramesChanged() // the map just got a new set of frames
        runCurrent()
        assertFalse(vm.state().canPlay)
        vm.togglePlay()
        advanceTimeBy(5_000)
        assertFalse(vm.state().playing)
        vm.pause()
    }

    @Test
    fun `the basemap is saved when toggled`() = runTest(dispatcher, timeout = 15.seconds) {
        val vm = viewModel()
        vm.toggleBasemap()
        runCurrent()
        assertEquals(listOf(Basemap.Satellite), savedBasemaps)
        settings.value = settings.value.copy(basemap = Basemap.Satellite)
        runCurrent()
        vm.toggleBasemap()
        runCurrent()
        assertEquals(listOf(Basemap.Satellite, Basemap.Normal), savedBasemaps)
    }

    @Test
    fun `without a known place there is nothing to load`() = runTest(dispatcher, timeout = 15.seconds) {
        settings.value = AppSettings()
        val vm = viewModel()
        val s = vm.state()
        assertNull(s.place)
        assertFalse(s.isLoading)
        assertFalse(s.failed)
        assertEquals(0, rainViewer.calls)
    }

    @Test
    fun `a failed load shows an error and retry loads again`() = runTest(dispatcher, timeout = 15.seconds) {
        rainViewer.fail = true
        val vm = viewModel()
        assertTrue(vm.state().failed)

        rainViewer.fail = false
        vm.retry()
        runCurrent()

        assertFalse(vm.state().failed)
        assertEquals(13, vm.state().frames.size)
    }

    @Test
    fun `changing the place loads that place's radar`() = runTest(dispatcher, timeout = 15.seconds) {
        val vm = viewModel()
        val before = rainViewer.calls
        settings.value = settings.value.copy(lastGpsPlace = Place("Turku", 60.45, 22.27))
        runCurrent()
        assertEquals(before + 1, rainViewer.calls)
        assertEquals("Turku", vm.state().place!!.name)
    }

    @Test
    fun `unrelated settings do not reload the radar`() = runTest(dispatcher, timeout = 15.seconds) {
        val vm = viewModel()
        vm.state()
        val before = rainViewer.calls
        settings.value = settings.value.copy(radarHistoryOnMobileData = true, basemap = Basemap.Satellite)
        runCurrent()
        assertEquals(before, rainViewer.calls)
    }

    @Test
    fun `turning the home server on switches the source`() = runTest(dispatcher, timeout = 15.seconds) {
        val vm = viewModel()
        assertEquals(0, server.calls)
        settings.value = settings.value.copy(homeServerEnabled = true, homeServerAddress = "100.95.20.125:8080")
        runCurrent()
        assertEquals(RadarSourceKind.HomeServer, vm.state().layers!!.source)

        server.failure = IOException("down")
        vm.retry()
        runCurrent()
        assertEquals(RadarSourceKind.RainViewer, vm.state().layers!!.source)
        assertTrue(vm.state().layers!!.notice!!.contains("not reachable"))
    }

    @Test
    fun `with a forecast the radar opens on now and plays through the forecast`() = runTest(dispatcher, timeout = 15.seconds) {
        server.forecastBase = 1_792_791_900L
        settings.value = settings.value.copy(homeServerEnabled = true, homeServerAddress = "100.95.20.125:8080")
        val vm = viewModel()
        runCurrent()
        val s = vm.state()
        assertEquals(16, s.frames.size)
        assertEquals(12, s.nowIndex)
        assertEquals("opens on now, not on the last forecast", 12, s.index)
        assertFalse(s.frames[s.index].isForecast)

        vm.onMapIdle()
        vm.togglePlay()
        runCurrent()
        advanceTimeBy(601)
        assertTrue(vm.state().frames[vm.state().index].isForecast)
        vm.pause()
    }
}
