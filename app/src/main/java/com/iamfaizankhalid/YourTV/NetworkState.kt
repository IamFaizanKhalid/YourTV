package com.iamfaizankhalid.YourTV

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Connectivity checks shared by the player and the boot receiver.
 */
object NetworkState {
    fun isOnline(context: Context): Boolean {
        val manager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        // Do not require VALIDATED here: some TVs never set it, which made the app
        // report "no internet" while the network was actually fine. Real playback
        // failures and the reachability probe catch the no-internet case instead.
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
