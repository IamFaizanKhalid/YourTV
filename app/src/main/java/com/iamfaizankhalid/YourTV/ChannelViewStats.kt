package com.iamfaizankhalid.YourTV

import android.content.Context
import java.time.LocalDate
import java.time.temporal.WeekFields

class ChannelViewStats(context: Context) {
    private companion object {
        const val PREFS_NAME = "channel_view_stats"
        const val KEY_WEEK = "current_week"
        const val WEEKLY_PREFIX = "weekly_"
        const val TOTAL_PREFIX = "total_"
        val LOCK = Any()
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun recordView(channel: Channel) {
        synchronized(LOCK) {
            ensureCurrentWeek()
            val weeklyKey = weeklyKey(channel)
            val totalKey = totalKey(channel)
            prefs.edit()
                .putInt(weeklyKey, prefs.getInt(weeklyKey, 0) + 1)
                .putInt(totalKey, prefs.getInt(totalKey, 0) + 1)
                .apply()
        }
    }

    fun weeklyViews(channel: Channel): Int = synchronized(LOCK) {
        ensureCurrentWeek()
        prefs.getInt(weeklyKey(channel), 0)
    }

    fun totalViews(channel: Channel): Int = synchronized(LOCK) {
        prefs.getInt(totalKey(channel), 0)
    }

    /**
     * Ranks channels by views in the current week first, then by all-time views.
     * Channels with identical counts keep their original order.
     */
    fun rankedChannels(channels: List<Channel>): List<Channel> = synchronized(LOCK) {
        ensureCurrentWeek()
        channels.sortedWith(
            compareByDescending<Channel> { prefs.getInt(weeklyKey(it), 0) }
                .thenByDescending { prefs.getInt(totalKey(it), 0) }
                .thenBy { channels.indexOf(it) }
        )
    }

    private fun ensureCurrentWeek() {
        val currentWeek = currentWeekId()
        if (prefs.getString(KEY_WEEK, null) == currentWeek) return

        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(WEEKLY_PREFIX) }
            .forEach(editor::remove)
        editor.putString(KEY_WEEK, currentWeek).apply()
    }

    private fun currentWeekId(): String {
        val today = LocalDate.now()
        val weekFields = WeekFields.ISO
        return "${today.get(weekFields.weekBasedYear())}-W${today.get(weekFields.weekOfWeekBasedYear())}"
    }

    private fun weeklyKey(channel: Channel): String = "$WEEKLY_PREFIX${channel.youtubeUrl}"

    private fun totalKey(channel: Channel): String = "$TOTAL_PREFIX${channel.youtubeUrl}"
}
