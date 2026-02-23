// File: app/src/main/java/com/sleepy/droidheadless/MainActivity.kt
package com.sleepy.droidheadless

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.sleepy.droidheadless.databinding.ActivityMainBinding
import com.sleepy.droidheadless.utils.NotificationHelper

/**
 * Simple control panel activity for the headless browser.
 *
 * Shows:
 * - Current status (running/stopped)
 * - Port number
 * - Live request count and data transferred
 * - Start/Stop button
 * - Connection info for Puppeteer
 * - Settings button
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Service binding
    private var service: HeadlessBrowserService? = null
    private var isBound = false

    // Broadcast receiver for status updates from the service
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == HeadlessBrowserService.ACTION_STATUS_UPDATE) {
                val isRunning = intent.getBooleanExtra(HeadlessBrowserService.EXTRA_IS_RUNNING, false)
                val port = intent.getIntExtra(HeadlessBrowserService.EXTRA_PORT, HeadlessBrowserService.DEFAULT_PORT)
                val requestCount = intent.getIntExtra(HeadlessBrowserService.EXTRA_REQUEST_COUNT, 0)
                val bytes = intent.getLongExtra(HeadlessBrowserService.EXTRA_BYTES, 0L)
                updateUI(isRunning, port, requestCount, bytes)
            }
        }
    }

    // Service connection callbacks
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as HeadlessBrowserService.LocalBinder
            service = localBinder.getService()
            isBound = true
            // Update UI with current state
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
            val port = prefs.getInt("cdp_port", HeadlessBrowserService.DEFAULT_PORT)
            updateUI(service?.isRunning == true, port, 0, 0L)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
            updateUI(false, HeadlessBrowserService.DEFAULT_PORT, 0, 0L)
        }
    }

    // Permission request for notifications (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startHeadlessService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()

        // Try to bind to existing service
        tryBindService()
    }

    private fun setupUI() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val port = prefs.getInt("cdp_port", HeadlessBrowserService.DEFAULT_PORT)

        // Update Puppeteer connection code
        binding.tvPuppeteerCode.text = """puppeteer.connect({
  browserURL: 'http://127.0.0.1:$port'
})"""

        // Toggle button
        binding.btnToggle.setOnClickListener {
            if (service?.isRunning == true) {
                stopHeadlessService()
            } else {
                requestStartService()
            }
        }

        // Settings button
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Initial UI state
        updateUI(false, port, 0, 0L)
    }

    /**
     * Requests to start the service, checking for notification permission first.
     */
    private fun requestStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    startHeadlessService()
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            startHeadlessService()
        }
    }

    /**
     * Starts the headless browser foreground service.
     */
    private fun startHeadlessService() {
        val intent = Intent(this, HeadlessBrowserService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // Bind to the service so we can get status updates
        tryBindService()
    }

    /**
     * Stops the headless browser service.
     */
    private fun stopHeadlessService() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            service = null
        }
        val intent = Intent(this, HeadlessBrowserService::class.java)
        stopService(intent)
        updateUI(false, HeadlessBrowserService.DEFAULT_PORT, 0, 0L)
    }

    /**
     * Attempts to bind to the service if it's running.
     */
    private fun tryBindService() {
        try {
            val intent = Intent(this, HeadlessBrowserService::class.java)
            bindService(intent, serviceConnection, 0) // Don't auto-create
        } catch (e: Exception) {
            // Service might not be running
        }
    }

    /**
     * Updates the UI with current service status.
     */
    private fun updateUI(isRunning: Boolean, port: Int, requestCount: Int, bytes: Long) {
        runOnUiThread {
            if (isRunning) {
                binding.tvStatus.text = getString(R.string.status_running, port)
                binding.tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                binding.tvDetails.text = "CDP server accepting connections"

                binding.tvStats.visibility = View.VISIBLE
                binding.tvStats.text = "Requests: $requestCount · Data: ${NotificationHelper.formatBytes(bytes)}"

                binding.cardConnection.visibility = View.VISIBLE
                binding.btnToggle.text = getString(R.string.stop_service)
            } else {
                binding.tvStatus.text = getString(R.string.status_stopped)
                binding.tvStatus.setTextColor(getColor(android.R.color.darker_gray))
                binding.tvDetails.text = "Tap Start to begin"

                binding.tvStats.visibility = View.GONE
                binding.cardConnection.visibility = View.GONE
                binding.btnToggle.text = getString(R.string.start_service)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Register for status broadcasts
        val filter = IntentFilter(HeadlessBrowserService.ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            try {
                unbindService(serviceConnection)
            } catch (_: Exception) {}
            isBound = false
        }
    }
}
