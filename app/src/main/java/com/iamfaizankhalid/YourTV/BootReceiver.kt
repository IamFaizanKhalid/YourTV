package com.iamfaizankhalid.YourTV

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Opens the player when the TV finishes booting. Some TV vendors send their own
 * quick-boot action instead of BOOT_COMPLETED, so those are handled too.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            ACTION_QUICKBOOT_POWERON,
            ACTION_HTC_QUICKBOOT_POWERON -> {
                if (!AppSettings.isAutoStartEnabled(context)) return

                launchWhenOnline(context)
            }
        }
    }

    /**
     * The TV and its Wi-Fi come up at the same time. Wait briefly so the app does
     * not start while the network is still connecting, then open it anyway.
     */
    private fun launchWhenOnline(context: Context) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        val handler = Handler(Looper.getMainLooper())
        var attempts = 0

        val check = object : Runnable {
            override fun run() {
                attempts++
                if (NetworkState.isOnline(appContext) || attempts >= 6) {
                    AutoStart.launchPlayer(appContext)
                    pendingResult.finish()
                } else {
                    handler.postDelayed(this, 1000L)
                }
            }
        }

        handler.postDelayed(check, 1000L)
    }

    private companion object {
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        const val ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
    }
}
