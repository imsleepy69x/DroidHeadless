// File: app/src/main/java/com/sleepy/droidheadless/HeadlessBrowserService.kt
package com.sleepy.droidheadless

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.preference.PreferenceManager
import com.sleepy.droidheadless.browser.NetworkInterceptor
import com.sleepy.droidheadless.browser.WebViewManager
import com.sleepy.droidheadless.cdp.CDPHandler
import com.sleepy.droidheadless.cdp.CDPServer
import com.sleepy.droidheadless.utils.NotificationHelper
import kotlinx.coroutines.*

/**
 * Foreground service that runs the headless browser and CDP server.
 *
 * Lifecycle:
 * 1. Service starts → creates notification channel → shows foreground notification
 * 2. Initializes: NetworkInterceptor → WebViewManager → CDPHandler → CDPServer
 * 3. Creates one default page (about:blank)
 * 4. Starts HTTP + WebSocket servers on configured port
 * 5. Periodically updates notification with live traffic stats
 * 6. On stop: shuts down servers, destroys WebViews, removes notification
 *
 * Uses START_STICKY so Android restarts the service if it's killed.
 */
class HeadlessBrowserService : Service() {

    companion object {
        private const val TAG = "HeadlessBrowserService"
        const val DEFAULT_PORT = 9222

        // Broadcast action for status updates to the activity
        const val ACTION_STATUS_UPDATE = "com.sleepy.droidheadless.STATUS_UPDATE"
        const val EXTRA_IS_RUNNING = "is_running"
        const val EXTRA_PORT = "port"
        const val EXTRA_REQUEST_COUNT = "request_count"
        const val EXTRA_BYTES = "bytes"
    }

    // Core components
    private var networkInterceptor: NetworkInterceptor? = null
    private var webViewManager: WebViewManager? = null
    private var cdpHandler: CDPHandler? = null
    private var cdpServer: CDPServer? = null

    // Configured port (from SharedPreferences)
    private var port = DEFAULT_PORT

    // Notification update job
    private var notificationJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Is the service fully started?
    var isRunning = false
        private set

    // Binder for activity to communicate with service
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): HeadlessBrowserService = this@HeadlessBrowserService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service start command received")

        if (isRunning) {
            Log.i(TAG, "Service already running, ignoring start command")
            return START_STICKY
        }

        // Read port from preferences
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        port = prefs.getInt("cdp_port", DEFAULT_PORT)

        // Create notification channel and start as foreground service
        NotificationHelper.createChannel(this)
        val notification = NotificationHelper.buildNotification(this, port)
        startForeground(NotificationHelper.NOTIFICATION_ID, notification)

        // Initialize everything
        initializeBrowser()

        return START_STICKY // Restart if killed
    }

    /**
     * Initializes all browser components and starts the CDP server.
     */
    private fun initializeBrowser() {
        serviceScope.launch {
            try {
                Log.i(TAG, "Initializing headless browser...")

                // 1. Create network interceptor
                val interceptor = NetworkInterceptor()
                networkInterceptor = interceptor

                // 2. Create WebView manager (must be on main thread)
                val manager = WebViewManager(this@HeadlessBrowserService, interceptor)
                webViewManager = manager

                // 3. Create CDP handler (routes messages between clients and browser)
                val handler = CDPHandler(manager, interceptor)
                cdpHandler = handler

                // 4. Create the default page (about:blank)
                val defaultPageId = manager.createPage("about:blank").await()
                Log.i(TAG, "Default page created: $defaultPageId")

                // 5. Start the CDP server (HTTP + WebSocket)
                val server = CDPServer(port, manager, handler)
                cdpServer = server

                // Start on IO thread since it involves network binding
                withContext(Dispatchers.IO) {
                    server.start()
                }

                isRunning = true
                Log.i(TAG, "Headless browser fully initialized on port $port")

                // 6. Start periodic notification updates
                startNotificationUpdates()

                // 7. Broadcast status to activity
                broadcastStatus()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize headless browser", e)
                stopSelf()
            }
        }
    }

    /**
     * Periodically updates the foreground notification with live traffic stats.
     * Updates every 2 seconds to avoid excessive notification overhead.
     */
    private fun startNotificationUpdates() {
        notificationJob = serviceScope.launch {
            while (isActive) {
                try {
                    val count = networkInterceptor?.requestCount?.get() ?: 0
                    val bytes = networkInterceptor?.bytesTransferred?.get() ?: 0L

                    NotificationHelper.updateNotification(
                        this@HeadlessBrowserService,
                        port,
                        count,
                        bytes
                    )

                    // Also broadcast to activity
                    broadcastStatus()

                } catch (e: Exception) {
                    Log.w(TAG, "Error updating notification", e)
                }

                delay(2_000) // Update every 2 seconds
            }
        }
    }

    /**
     * Broadcasts current status to the activity.
     */
    private fun broadcastStatus() {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_IS_RUNNING, isRunning)
            putExtra(EXTRA_PORT, port)
            putExtra(EXTRA_REQUEST_COUNT, networkInterceptor?.requestCount?.get() ?: 0)
            putExtra(EXTRA_BYTES, networkInterceptor?.bytesTransferred?.get() ?: 0L)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroying...")

        isRunning = false
        notificationJob?.cancel()

        // Shutdown in reverse order of initialization
        try {
            cdpServer?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping CDP server", e)
        }

        try {
            cdpHandler?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying CDP handler", e)
        }

        try {
            webViewManager?.destroyAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying WebView manager", e)
        }

        try {
            networkInterceptor?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying network interceptor", e)
        }

        serviceScope.cancel()

        // Broadcast stopped status
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_IS_RUNNING, false)
            setPackage(packageName)
        }
        sendBroadcast(intent)

        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }
}
