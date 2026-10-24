package dev.r2d8.weather.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.r2d8.weather.domain.Basemap
import dev.r2d8.weather.domain.ColorPalette
import dev.r2d8.weather.domain.DayDetail
import dev.r2d8.weather.domain.DetailCardType
import dev.r2d8.weather.domain.ForecastSection
import dev.r2d8.weather.domain.MapStyle
import dev.r2d8.weather.domain.Place
import dev.r2d8.weather.domain.PlaceSelection
import dev.r2d8.weather.domain.RadarPalette
import dev.r2d8.weather.domain.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.newStore(name: String = "settings") =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.newFile("$name.preferences_pb") }

    @Test
    fun `defaults show everything, follow the system and use the phone location`() = runTest {
        val settings = SettingsRepository(newStore()).settings.first()

        assertEquals(ThemeMode.System, settings.themeMode)
        assertEquals(ColorPalette.Dynamic, settings.palette)
        assertEquals(ForecastSection.entries.toSet(), settings.visibleSections)
        assertEquals(DetailCardType.entries.toSet(), settings.visibleCards)
        assertEquals(DayDetail.entries.toSet(), settings.visibleDayDetails)
        assertEquals(PlaceSelection.Gps, settings.selection)
        assertNull(settings.lastGpsPlace)
    }

    @Test
    fun `theme and palette are stored`() = runTest {
        val repository = SettingsRepository(newStore())

        repository.setThemeMode(ThemeMode.Dark)
        repository.setPalette(ColorPalette.Orange)

        val settings = repository.settings.first()
        assertEquals(ThemeMode.Dark, settings.themeMode)
        assertEquals(ColorPalette.Orange, settings.palette)
    }

    @Test
    fun `cards and sections can be switched off and on again`() = runTest {
        val repository = SettingsRepository(newStore())

        repository.setCardVisible(DetailCardType.Pollen, false)
        repository.setCardVisible(DetailCardType.Wind, false)
        repository.setSectionVisible(ForecastSection.HourlyStrip, false)
        val off = repository.settings.first()
        assertFalse(DetailCardType.Pollen in off.visibleCards)
        assertFalse(DetailCardType.Wind in off.visibleCards)
        assertTrue(DetailCardType.Pressure in off.visibleCards)
        assertFalse(ForecastSection.HourlyStrip in off.visibleSections)
        assertTrue(ForecastSection.DailyForecast in off.visibleSections)

        repository.setCardVisible(DetailCardType.Pollen, true)
        assertTrue(DetailCardType.Pollen in repository.settings.first().visibleCards)
        assertFalse(DetailCardType.Wind in repository.settings.first().visibleCards)
    }

    @Test
    fun `day parts and hourly rows of an expanded day can be switched separately`() = runTest {
        val repository = SettingsRepository(newStore())

        repository.setDayDetailVisible(DayDetail.Hourly, false)
        assertEquals(setOf(DayDetail.DayParts), repository.settings.first().visibleDayDetails)

        repository.setDayDetailVisible(DayDetail.DayParts, false)
        assertTrue(repository.settings.first().visibleDayDetails.isEmpty())

        repository.setDayDetailVisible(DayDetail.Hourly, true)
        assertEquals(setOf(DayDetail.Hourly), repository.settings.first().visibleDayDetails)
    }

    @Test
    fun `selection and last phone location round trip`() = runTest {
        val repository = SettingsRepository(newStore())

        repository.select(PlaceSelection.Saved(42))
        repository.setLastGpsPlace(Place("Turin", 45.07, 7.69))

        val settings = repository.settings.first()
        assertEquals(PlaceSelection.Saved(42), settings.selection)
        assertEquals(Place("Turin", 45.07, 7.69), settings.lastGpsPlace)

        repository.select(PlaceSelection.Gps)
        assertEquals(PlaceSelection.Gps, repository.settings.first().selection)
    }

    @Test
    fun `unknown stored values fall back to defaults instead of crashing`() = runTest {
        val store = newStore()
        store.edit {
            it[stringPreferencesKey("theme_mode")] = "Solarized"
            it[stringPreferencesKey("palette")] = "Chartreuse"
            it[stringPreferencesKey("selection")] = "saved:not-a-number"
        }

        val settings = SettingsRepository(store).settings.first()

        assertEquals(ThemeMode.System, settings.themeMode)
        assertEquals(ColorPalette.Dynamic, settings.palette)
        assertEquals(PlaceSelection.Gps, settings.selection)
    }

    @Test
    fun `the home server is off and has no address by default`() = runTest {
        val settings = SettingsRepository(newStore()).settings.first()

        assertFalse(settings.homeServerEnabled)
        assertEquals("", settings.homeServerAddress)
        assertFalse(settings.radarHistoryOnMobileData)
        assertEquals(RadarPalette.Classic, settings.radarPalette)
        assertEquals(Basemap.Normal, settings.basemap)
    }

    @Test
    fun `radar settings are stored`() = runTest {
        val repository = SettingsRepository(newStore())

        repository.setHomeServerEnabled(true)
        repository.setHomeServerAddress("100.1.2.3:8080")
        repository.setRadarHistoryOnMobileData(true)
        repository.setRadarPalette(RadarPalette.Blues)
        repository.setBasemap(Basemap.Satellite)

        val settings = repository.settings.first()
        assertTrue(settings.homeServerEnabled)
        assertEquals("100.1.2.3:8080", settings.homeServerAddress)
        assertTrue(settings.radarHistoryOnMobileData)
        assertEquals(RadarPalette.Blues, settings.radarPalette)
        assertEquals(Basemap.Satellite, settings.basemap)
    }

    @Test
    fun `unknown stored radar values fall back to defaults`() = runTest {
        val store = newStore()
        store.edit {
            it[stringPreferencesKey("radar_palette")] = "Neon"
            it[stringPreferencesKey("basemap")] = "Terrain"
        }
        val settings = SettingsRepository(store).settings.first()
        assertEquals(RadarPalette.Classic, settings.radarPalette)
        assertEquals(Basemap.Normal, settings.basemap)
    }

    @Test
    fun `the map style defaults to detailed and is stored`() = runTest {
        val repository = SettingsRepository(newStore())
        assertEquals(MapStyle.Detailed, repository.settings.first().mapStyle)

        repository.setMapStyle(MapStyle.Dark)

        assertEquals(MapStyle.Dark, repository.settings.first().mapStyle)
    }

    @Test
    fun `an unknown stored map style falls back to detailed`() = runTest {
        val store = newStore()
        store.edit { it[stringPreferencesKey("map_style")] = "Topographic" }
        assertEquals(MapStyle.Detailed, SettingsRepository(store).settings.first().mapStyle)
    }
}
