package com.bluewave.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Transport evidence, not proof of home Wi-Fi identity or VPN route/peer authorization. */
data class NetworkState(
    val available: Boolean = false,
    val wifi: Boolean = false,
    val cellular: Boolean = false,
    val ethernet: Boolean = false,
    val vpn: Boolean = false,
) {
    val summary: String
        get() =
            when {
                !available -> "No active network"
                vpn -> "Android VPN detected • home route not verified"
                wifi -> "Wi-Fi • home network not verified"
                ethernet -> "Ethernet • home network not verified"
                cellular -> "Mobile data • remote VPN required"
                else -> "Other network • remote VPN required"
            }
}

object AccessPolicy {
    fun error(remote: Boolean, secure: Boolean, network: NetworkState): String? =
        when {
            !network.available -> "No network. Connect to home Wi-Fi or your authorized home VPN."
            remote && !secure -> "Remote access requires TLS on port 8002."
            remote && !network.vpn ->
                "Remote VPN mode requires an active Android VPN with a route to your home TV."
            !remote && network.vpn ->
                "VPN detected. Select Remote VPN mode, or disconnect VPN for local LAN mode."
            !remote && !network.wifi && !network.ethernet ->
                "Outside home Wi-Fi? Configure a home VPN and select Remote VPN mode."
            else -> null
        }
}

object ReconnectPolicy {
    const val MAX_ATTEMPTS = 3

    fun delayMillis(attempt: Int): Long {
        require(attempt in 1..MAX_ATTEMPTS)
        return 2000L shl (attempt - 1)
    }
}

class NetworkMonitor(context: Context, private val changed: (NetworkState) -> Unit) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                changed(snapshot())
            }

            override fun onLost(network: Network) {
                changed(snapshot())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                changed(snapshot())
            }
        }

    fun snapshot(): NetworkState {
        val capabilities =
            manager.getNetworkCapabilities(manager.activeNetwork) ?: return NetworkState()
        return NetworkState(
            true,
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        )
    }

    fun start() {
        manager.registerDefaultNetworkCallback(callback)
        changed(snapshot())
    }

    fun stop() {
        manager.unregisterNetworkCallback(callback)
    }
}
