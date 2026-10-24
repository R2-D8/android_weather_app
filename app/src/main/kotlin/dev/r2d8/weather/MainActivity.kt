package dev.r2d8.weather

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.r2d8.weather.domain.ThemeMode
import dev.r2d8.weather.ui.about.AboutScreen
import dev.r2d8.weather.ui.places.PlacesRoute
import dev.r2d8.weather.ui.radar.RadarRoute
import dev.r2d8.weather.ui.settings.SettingsRoute
import dev.r2d8.weather.ui.theme.WeatherTheme
import dev.r2d8.weather.ui.weather.WeatherRoute
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val ROUTE_WEATHER = "weather"
private const val ROUTE_PLACES = "places"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_ABOUT = "about"
private const val ROUTE_RADAR = "radar"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as WeatherApplication).container
        val settingsRepository = container.settingsRepository
        // Offline maps are only updated while the app is on screen ("started"); leaving the app
        // cancels this and pauses a download, opening it again resumes.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { container.offlineMapsUpdater.runWhileOpen() }
        }
        setContent {
            // Only theme-related settings are read here; the screens read the rest themselves.
            val appearanceFlow = remember { settingsRepository.settings.map { it.themeMode to it.palette } }
            val appearance by appearanceFlow.collectAsStateWithLifecycle(initialValue = null)
            // Wait for the stored theme so the first frame does not flash the wrong colours.
            val (mode, palette) = appearance ?: return@setContent
            val darkTheme = when (mode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // Status and navigation bar icons must match the app's theme, which can differ from the system's.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                )
                onDispose {}
            }
            WeatherTheme(darkTheme = darkTheme, palette = palette) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavHost(navController, startDestination = ROUTE_WEATHER) {
                        composable(ROUTE_WEATHER) {
                            WeatherRoute(
                                onOpenPlaces = { navController.navigate(ROUTE_PLACES) },
                                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                                onOpenRadar = { navController.navigate(ROUTE_RADAR) },
                            )
                        }
                        composable(ROUTE_RADAR) {
                            RadarRoute(onBack = { navController.popBackStack() })
                        }
                        composable(ROUTE_PLACES) {
                            PlacesRoute(
                                onBack = { navController.popBackStack() },
                                onDone = { navController.popBackStack() },
                            )
                        }
                        composable(ROUTE_SETTINGS) {
                            SettingsRoute(
                                onBack = { navController.popBackStack() },
                                onOpenAbout = { navController.navigate(ROUTE_ABOUT) },
                            )
                        }
                        composable(ROUTE_ABOUT) {
                            AboutScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
