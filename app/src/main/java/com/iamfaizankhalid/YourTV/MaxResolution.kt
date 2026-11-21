package com.iamfaizankhalid.YourTV

import android.content.Context
import kotlin.math.roundToInt

/**
 * Options for the "Player resolution" setting.
 *
 * YouTube picks its quality from the size of the player viewport. For a fixed
 * resolution we render the player at that size and scale it up to the screen,
 * so YouTube treats it as a smaller display. AUTO and TV leave the player
 * full-screen.
 */
object MaxResolution {
    const val AUTO = 0
    const val TV = -1

    private val STANDARD_HEIGHTS = intArrayOf(2160, 1440, 1080, 720, 480, 360, 240, 144)

    fun get(context: Context): Int {
        val stored = AppSettings.maxResolution(context)
        return if (stored == AUTO || stored == TV || stored in options(context)) stored else AUTO
    }

    fun set(context: Context, value: Int) {
        AppSettings.setMaxResolution(context, value)
    }

    /** Auto first, then the TV's own resolution, then every standard size below it. */
    fun options(context: Context): List<Int> {
        val tvHeight = tvHeight(context)
        val options = mutableListOf(AUTO, TV)
        STANDARD_HEIGHTS.forEach { height ->
            if (height < tvHeight) options.add(height)
        }
        return options
    }

    fun label(context: Context, value: Int): String = when (value) {
        AUTO -> context.getString(R.string.max_resolution_auto)
        TV -> "${tvHeight(context)}p"
        else -> "${value}p"
    }

    /** CSS viewport size, or null when the player should stay full-screen. */
    fun viewportSize(context: Context, value: Int): Pair<Int, Int>? {
        if (value == AUTO || value == TV) return null
        if (value !in STANDARD_HEIGHTS) return null
        val width = (value * 16f / 9f).roundToInt()
        return width to value
    }

    fun next(context: Context, current: Int, delta: Int): Int {
        val options = options(context)
        val index = options.indexOf(current).let { if (it < 0) 0 else it }
        return options[(index + delta).mod(options.size)]
    }

    private fun tvHeight(context: Context): Int =
        context.resources.displayMetrics.heightPixels.coerceAtLeast(144)
}
