package com.iamfaizankhalid.YourTV

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.tvprovider.media.tv.TvContractCompat

/**
 * Creates the default Android TV home screen channel when the system first
 * initializes the app's programs.
 */
class HomeScreenChannelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TvContractCompat.ACTION_INITIALIZE_PROGRAMS) return

        val pendingResult = goAsync()
        Thread {
            try {
                HomeScreenChannels(context).sync()
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
