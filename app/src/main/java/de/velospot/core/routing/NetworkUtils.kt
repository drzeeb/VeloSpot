package de.velospot.core.routing

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Returns `true` if the device is currently connected to a Wi-Fi network.
 * Does NOT require any special permission beyond ACCESS_NETWORK_STATE.
 */
fun isWifiConnected(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps    = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}

/**
 * Returns `true` if the device currently has a network connection with internet
 * capability (Wi-Fi, cellular or Ethernet). Used to fail fast before a large
 * download instead of attempting it with no connectivity at all.
 * Requires only ACCESS_NETWORK_STATE.
 */
fun isInternetAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps    = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

