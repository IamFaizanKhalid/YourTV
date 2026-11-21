package com.iamfaizankhalid.YourTV

import android.content.Context

/** Options for the "Bandwidth limit" setting. 0 means unlimited. */
object BandwidthLimit {
    const val NONE = 0

    private val OPTIONS = intArrayOf(0, 1, 2, 3, 5, 10, 20)

    fun get(context: Context): Int =
        AppSettings.bandwidthLimitMbps(context).takeIf { it in OPTIONS } ?: NONE

    fun set(context: Context, value: Int) {
        AppSettings.setBandwidthLimitMbps(context, value)
    }

    fun label(context: Context, value: Int): String =
        if (value == NONE) {
            context.getString(R.string.bandwidth_limit_none)
        } else {
            "$value Mbps"
        }

    fun next(context: Context, current: Int, delta: Int): Int {
        val index = OPTIONS.indexOf(current).let { if (it < 0) 0 else it }
        return OPTIONS[(index + delta).mod(OPTIONS.size)]
    }
}
