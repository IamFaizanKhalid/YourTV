package com.iamfaizankhalid.YourTV

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Opens the player from the boot receiver.
 */
object AutoStart {
    private const val TAG = "YourTVAutoStart"

    fun launchPlayer(context: Context) {
        val intent = Intent(context, PlayerActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )

        try {
            context.startActivity(intent)
        } catch (error: Exception) {
            // Android can refuse background activity starts without the overlay
            // permission. The settings screen explains how to grant it.
            Log.e(TAG, "Unable to open the player automatically", error)
        }
    }
}
