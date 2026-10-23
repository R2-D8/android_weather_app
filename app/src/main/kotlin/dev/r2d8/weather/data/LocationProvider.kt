package dev.r2d8.weather.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import dev.r2d8.weather.domain.Place
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** Why the phone's location could not be found. */
enum class LocationFailure { PermissionDenied, Unavailable }

class LocationException(val reason: LocationFailure) : Exception(reason.name)

/**
 * The phone's current place using the platform [LocationManager] (no Google Play Services) and
 * the platform [Geocoder] for a readable name. Approximate (network) location is enough for weather.
 */
class LocationProvider(private val context: Context) {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    fun hasPermission(): Boolean =
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    suspend fun currentPlace(): Result<Place> {
        if (!hasPermission()) return Result.failure(LocationException(LocationFailure.PermissionDenied))
        val location = findLocation() ?: return Result.failure(LocationException(LocationFailure.Unavailable))
        return Result.success(Place(nameFor(location), location.latitude, location.longitude))
    }

    @SuppressLint("MissingPermission") // hasPermission() was checked by the caller
    private suspend fun findLocation(): Location? = try {
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
        // A fresh fix when one arrives in time, otherwise whatever the system last knew.
        providers.firstNotNullOfOrNull { withTimeoutOrNull(FIX_TIMEOUT_MS) { currentFix(it) } }
            ?: providers.firstNotNullOfOrNull { locationManager.getLastKnownLocation(it) }
    } catch (_: SecurityException) {
        null // permission revoked while running, or GPS asked with approximate access only
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentFix(provider: String): Location? = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        locationManager.getCurrentLocation(provider, signal, mainExecutor) { continuation.resume(it) }
    }

    /** City or town name from the system geocoder (needs a backend on the phone), else a generic label. */
    private suspend fun nameFor(location: Location): String {
        if (!Geocoder.isPresent()) return GENERIC_NAME
        return withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                Geocoder(context, Locale.ENGLISH).getFromLocation(location.latitude, location.longitude, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<android.location.Address>) {
                        val address = addresses.firstOrNull()
                        continuation.resume(address?.locality ?: address?.subAdminArea ?: address?.adminArea ?: GENERIC_NAME)
                    }

                    override fun onError(errorMessage: String?) = continuation.resume(GENERIC_NAME)
                })
            }
        } ?: GENERIC_NAME
    }

    private companion object {
        const val GENERIC_NAME = "Current location"
        const val FIX_TIMEOUT_MS = 8_000L
        const val GEOCODE_TIMEOUT_MS = 4_000L
    }
}
