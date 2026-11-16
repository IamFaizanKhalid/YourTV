package com.iamfaizankhalid.YourTV

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.iamfaizankhalid.YourTV.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding.autoStartRow.setOnClickListener { toggleAutoStart() }
        binding.autoStartSwitch.isChecked = AppSettings.isAutoStartEnabled(this)

        binding.permissionButton.setOnClickListener { openOverlaySettings() }

        binding.autoStartRow.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        binding.autoStartSwitch.isChecked = AppSettings.isAutoStartEnabled(this)
        updatePermissionRow()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun toggleAutoStart() {
        val enabled = !AppSettings.isAutoStartEnabled(this)
        AppSettings.setAutoStartEnabled(this, enabled)
        binding.autoStartSwitch.isChecked = enabled

        updatePermissionRow()
    }

    private fun updatePermissionRow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            binding.permissionRow.visibility = View.GONE
            return
        }

        binding.permissionRow.visibility = View.VISIBLE
        val allowed = Settings.canDrawOverlays(this)
        binding.permissionStatus.text = getString(
            if (allowed) R.string.auto_start_permission_allowed
            else R.string.auto_start_permission_missing
        )
        binding.permissionButton.visibility = if (allowed) View.GONE else View.VISIBLE
    }

    private fun openOverlaySettings() {
        val overlayIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )

        try {
            startActivity(overlayIntent)
        } catch (error: Exception) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }
}
