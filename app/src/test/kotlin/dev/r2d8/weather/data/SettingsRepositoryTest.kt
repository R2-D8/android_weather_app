package dev.r2d8.weather.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.r2d8.weather.domain.ColorPalette
import dev.r2d8.weather.domain.DetailCardType
import dev.r2d8.weather.domain.ForecastSection
import dev.r2d8.weather.domain.Place
import dev.r2d8.weather.domain.PlaceSelection
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
}
