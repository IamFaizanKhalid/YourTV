package com.iamfaizankhalid.YourTV

import android.content.Context

/**
 * User preferences for the app. Auto-start is on by default so the TV opens
 * straight into the player when it boots or wakes from sleep.
 */
object AppSettings {
    private const val PREFS = "app_settings"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_BANDWIDTH_LIMIT = "bandwidth_limit_kbps"

    /** Bandwidth limit in kbps, 0 for unlimited. */
    fun bandwidthLimitKbps(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_BANDWIDTH_LIMIT, 0)

    fun setBandwidthLimitKbps(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BANDWIDTH_LIMIT, value)
            .apply()
    }

    fun isAutoStartEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_START, true)

    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_START, enabled)
            .apply()
    }
}
