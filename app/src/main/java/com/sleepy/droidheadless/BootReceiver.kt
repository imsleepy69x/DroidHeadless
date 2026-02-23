// File: app/src/main/java/com/sleepy/droidheadless/BootReceiver.kt
package com.sleepy.droidheadless

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager

/**
 * Receives BOOT_COMPLETED broadcasts to auto-start the headless browser service.
 *
 * The user can enable/disable auto-start from Settings.
 * When enabled, the service starts automatically after the device boots up.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        Log.i(TAG, "Boot completed - checking auto-start preference")

        // Check if auto-start is enabled in preferences
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val autoStart = prefs.getBoolean("auto_start", false)

        if (!autoStart) {
            Log.i(TAG, "Auto-start is disabled, not starting service")
            return
        }

        Log.i(TAG, "Auto-start is enabled, starting headless browser service")

        val serviceIntent = Intent(context, HeadlessBrowserService::class.java)

        // Use startForegroundService for Android 8.0+ (which is our minSdk)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
