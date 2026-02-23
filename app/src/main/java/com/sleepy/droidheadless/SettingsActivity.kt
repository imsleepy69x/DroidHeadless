// File: app/src/main/java/com/sleepy/droidheadless/SettingsActivity.kt
package com.sleepy.droidheadless

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.sleepy.droidheadless.databinding.ActivitySettingsBinding

/**
 * Settings screen for configuring port and auto-start behavior.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup toolbar
        binding.toolbar.setNavigationOnClickListener { finish() }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        // Load current settings
        val currentPort = prefs.getInt("cdp_port", HeadlessBrowserService.DEFAULT_PORT)
        val autoStart = prefs.getBoolean("auto_start", false)

        binding.etPort.setText(currentPort.toString())
        binding.switchAutoStart.isChecked = autoStart

        // Save button
        binding.btnSave.setOnClickListener {
            saveSettings()
        }
    }

    private fun saveSettings() {
        val portText = binding.etPort.text.toString()
        val port = portText.toIntOrNull()

        if (port == null || port < 1024 || port > 65534) {
            Toast.makeText(this, "Invalid port. Must be between 1024 and 65534.", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit().apply {
            putInt("cdp_port", port)
            putBoolean("auto_start", binding.switchAutoStart.isChecked)
            apply()
        }

        Toast.makeText(this, "Settings saved. Restart service to apply port change.", Toast.LENGTH_LONG).show()
        finish()
    }
}
