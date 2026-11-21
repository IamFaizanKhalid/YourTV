package com.iamfaizankhalid.YourTV

import android.content.Context

/** Options for the "Bandwidth limit" setting. Values are kbps, 0 means unlimited. */
object BandwidthLimit {
    const val NONE = 0

    private val OPTIONS = intArrayOf(0, 250, 500, 750, 1000, 1500, 2000, 3000, 4000, 6000, 8000, 10000)

    fun get(context: Context): Int =
        AppSettings.bandwidthLimitKbps(context).takeIf { it in OPTIONS } ?: NONE

    fun set(context: Context, value: Int) {
        AppSettings.setBandwidthLimitKbps(context, value)
    }

    fun label(context: Context, value: Int): String = when {
        value == NONE -> context.getString(R.string.bandwidth_limit_none)
        value < 1000 -> "$value kbps"
        value % 1000 == 0 -> "${value / 1000} Mbps"
        else -> "${value / 1000f} Mbps"
    }

    fun next(context: Context, current: Int, delta: Int): Int {
        val index = OPTIONS.indexOf(current).let { if (it < 0) 0 else it }
        return OPTIONS[(index + delta).mod(OPTIONS.size)]
    }
}
