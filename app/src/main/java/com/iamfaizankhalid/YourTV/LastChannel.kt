package com.iamfaizankhalid.YourTV

import android.content.Context

/**
 * Remembers the channel the player was showing, so launching the app resumes it
 * and the grid can switch the existing player instance.
 */
object LastChannel {
    private const val PREFS = "player_state"
    private const val KEY_INDEX = "last_channel_index"

    fun get(context: Context): Int {
        val index = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_INDEX, 0)
        return index.coerceIn(0, Channels.all.lastIndex)
    }

    fun set(context: Context, index: Int) {
        if (index !in Channels.all.indices) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_INDEX, index)
            .apply()
    }
}