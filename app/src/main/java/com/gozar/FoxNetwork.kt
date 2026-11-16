package com.gozar

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object FoxNetwork {

    fun mtu(context: Context): Int {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return 1400
        val network = manager.activeNetwork ?: return 1400
        val capabilities = manager.getNetworkCapabilities(network) ?: return 1400
        return if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) 1280 else 1400
    }
}
