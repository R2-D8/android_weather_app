package dev.r2d8.weather

import android.app.Application

/** Created once when the app process starts, before any activity. Registered in the manifest. */
class WeatherApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
