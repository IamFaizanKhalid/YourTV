package com.iamfaizankhalid.YourTV

import android.content.Context

/**
 * User preferences for the app. Auto-start is on by default so the TV opens
 * straight into the player when it boots or wakes from sleep.
 */
object AppSettings {
    private const val PREFS = "app_settings"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_MAX_RESOLUTION = "max_resolution"
    private const val KEY_BANDWIDTH_LIMIT = "bandwidth_limit_mbps"

    /** Bandwidth limit in Mbps, 0 for unlimited. */
    fun bandwidthLimitMbps(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_BANDWIDTH_LIMIT, 0)

    fun setBandwidthLimitMbps(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BANDWIDTH_LIMIT, value)
            .apply()
    }

    /** Player resolution, 0 for auto and -1 for the TV's resolution. */
    fun maxResolution(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MAX_RESOLUTION, 0)

    fun setMaxResolution(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MAX_RESOLUTION, value)
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
