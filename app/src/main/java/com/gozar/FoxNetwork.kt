package com.gozar

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object FoxNetwork {

    fun mtu(context: Context): Int {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return FoxStack.MTU
        val network = manager.activeNetwork ?: return FoxStack.MTU
        val capabilities = manager.getNetworkCapabilities(network) ?: return FoxStack.MTU
        return if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) 1280 else FoxStack.MTU
    }
}
