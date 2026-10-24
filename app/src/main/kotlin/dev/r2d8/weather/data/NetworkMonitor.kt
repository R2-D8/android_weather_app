package dev.r2d8.weather.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Tells whether the phone is on a metered connection: mobile data, or Wi-Fi marked as metered
 * (a phone hotspot, for example). Android decides this and takes a VPN such as Tailscale into
 * account, so the answer is about the real underlying connection.
 */
class NetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /**
     * True if this app's traffic currently goes through a VPN such as Tailscale. Android hands
     * each app the network it should use, and while a VPN covers the app that is the VPN, so
     * this is false when Tailscale is off (or set to leave this app out).
     */
    fun isVpnActive(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
    }

    /** Emits the current state first, then every change. */
    val isMetered: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(connectivity.isActiveNetworkMetered)
            }

            override fun onLost(network: Network) {
                trySend(connectivity.isActiveNetworkMetered)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: android.net.NetworkCapabilities) {
                trySend(connectivity.isActiveNetworkMetered)
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        trySend(connectivity.isActiveNetworkMetered)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
