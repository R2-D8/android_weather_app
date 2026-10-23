package dev.r2d8.weather.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.r2d8.weather.WeatherApplication
import dev.r2d8.weather.data.SettingsRepository
import dev.r2d8.weather.domain.AppSettings
import dev.r2d8.weather.domain.ColorPalette
import dev.r2d8.weather.domain.DetailCardType
import dev.r2d8.weather.domain.ForecastSection
import dev.r2d8.weather.domain.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { repository.setThemeMode(mode) }

    fun setPalette(palette: ColorPalette) = viewModelScope.launch { repository.setPalette(palette) }

    fun setSectionVisible(section: ForecastSection, visible: Boolean) =
        viewModelScope.launch { repository.setSectionVisible(section, visible) }

    fun setCardVisible(card: DetailCardType, visible: Boolean) =
        viewModelScope.launch { repository.setCardVisible(card, visible) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as WeatherApplication).container
                SettingsViewModel(container.settingsRepository)
            }
        }
    }
}
