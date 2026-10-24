package dev.r2d8.weather

import android.app.Application
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager

/** Created once when the app process starts, before any activity. Registered in the manifest. */
class WeatherApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // MapLibre must be initialised once before the first MapView is created.
        MapLibre.getInstance(this)
        container = AppContainer(this)
        HttpRequestUtil.setOkHttpClient(container.mapLibreHttp)
        // Map tiles seen while browsing are kept up to this size (offline packs are extra and not
        // counted). MapLibre's default is 50 MB, which a few map styles fill quickly.
        OfflineManager.getInstance(this).setMaximumAmbientCacheSize(
            AMBIENT_CACHE_BYTES,
            object : OfflineManager.FileSourceCallback {
                override fun onSuccess() = Unit
                override fun onError(message: String) = Unit
            },
        )
    }

    private companion object {
        const val AMBIENT_CACHE_BYTES = 250L * 1024 * 1024
    }
}
