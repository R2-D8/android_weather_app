package dev.r2d8.weather.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.r2d8.weather.domain.AppSettings
import dev.r2d8.weather.domain.ColorPalette
import dev.r2d8.weather.domain.DetailCardType
import dev.r2d8.weather.domain.ForecastSection
import dev.r2d8.weather.domain.Place
import dev.r2d8.weather.domain.PlaceSelection
import dev.r2d8.weather.domain.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * User preferences in a Preferences DataStore (a small key-value file that is read and written
 * asynchronously and safely). Switches are stored as the set of things turned **off**, so a card
 * or section added in a later version shows up by default.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }

    suspend fun setThemeMode(mode: ThemeMode) = dataStore.edit { it[THEME_MODE] = mode.name }

    suspend fun setPalette(palette: ColorPalette) = dataStore.edit { it[PALETTE] = palette.name }

    suspend fun setSectionVisible(section: ForecastSection, visible: Boolean) =
        dataStore.edit { it[HIDDEN_SECTIONS] = it[HIDDEN_SECTIONS].orEmpty().toggled(section.name, hidden = !visible) }

    suspend fun setCardVisible(card: DetailCardType, visible: Boolean) =
        dataStore.edit { it[HIDDEN_CARDS] = it[HIDDEN_CARDS].orEmpty().toggled(card.name, hidden = !visible) }

    suspend fun select(selection: PlaceSelection) = dataStore.edit {
        it[SELECTION] = when (selection) {
            PlaceSelection.Gps -> SELECTION_GPS
            is PlaceSelection.Saved -> "$SELECTION_SAVED_PREFIX${selection.id}"
        }
    }

    suspend fun setLastGpsPlace(place: Place) = dataStore.edit {
        it[GPS_NAME] = place.name
        it[GPS_LATITUDE] = place.latitude
        it[GPS_LONGITUDE] = place.longitude
    }

    private fun Set<String>.toggled(name: String, hidden: Boolean) = if (hidden) this + name else this - name

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        enumValues<E>().firstOrNull { it.name == this } ?: default

    private fun Preferences.toSettings(): AppSettings {
        val hiddenSections = this[HIDDEN_SECTIONS].orEmpty()
        val hiddenCards = this[HIDDEN_CARDS].orEmpty()
        val gpsLat = this[GPS_LATITUDE]
        val gpsLon = this[GPS_LONGITUDE]
        return AppSettings(
            themeMode = this[THEME_MODE].toEnum(ThemeMode.System),
            palette = this[PALETTE].toEnum(ColorPalette.Dynamic),
            visibleSections = ForecastSection.entries.filter { it.name !in hiddenSections }.toSet(),
            visibleCards = DetailCardType.entries.filter { it.name !in hiddenCards }.toSet(),
            selection = this[SELECTION].toSelection(),
            lastGpsPlace = if (gpsLat != null && gpsLon != null) {
                Place(this[GPS_NAME] ?: DEFAULT_GPS_NAME, gpsLat, gpsLon)
            } else {
                null
            },
        )
    }

    private fun String?.toSelection(): PlaceSelection {
        if (this == null || !startsWith(SELECTION_SAVED_PREFIX)) return PlaceSelection.Gps
        val id = removePrefix(SELECTION_SAVED_PREFIX).toLongOrNull() ?: return PlaceSelection.Gps
        return PlaceSelection.Saved(id)
    }

    private companion object {
        const val SELECTION_GPS = "gps"
        const val SELECTION_SAVED_PREFIX = "saved:"
        const val DEFAULT_GPS_NAME = "Current location"

        val THEME_MODE = stringPreferencesKey("theme_mode")
        val PALETTE = stringPreferencesKey("palette")
        val HIDDEN_SECTIONS = stringSetPreferencesKey("hidden_sections")
        val HIDDEN_CARDS = stringSetPreferencesKey("hidden_cards")
        val SELECTION = stringPreferencesKey("selection")
        val GPS_NAME = stringPreferencesKey("gps_name")
        val GPS_LATITUDE = doublePreferencesKey("gps_latitude")
        val GPS_LONGITUDE = doublePreferencesKey("gps_longitude")
    }
}
