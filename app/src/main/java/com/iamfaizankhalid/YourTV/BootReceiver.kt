package com.iamfaizankhalid.YourTV

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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

                AutoStart.launchPlayer(context)
            }
        }
    }

    private companion object {
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        const val ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
    }
}
