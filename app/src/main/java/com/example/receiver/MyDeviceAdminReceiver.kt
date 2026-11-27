package com.example.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.SecurityPrefs
import com.example.service.CameraForegroundService
import com.example.worker.SecurityEventDistributor

class MyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)

        // The cheap part stays on the broadcast thread: these are SharedPreferences
        // reads and writes, and dropping the callback early is the common case.
        val prefs = SecurityPrefs.getInstance(context)
        if (!prefs.isTrackingEnabled) {
            Log.d("DeviceAdminReceiver", "Password attempt ignored because protection is disabled")
            return
        }

        val failedAttempts = prefs.registerFailedUnlockAttempt()
        if (failedAttempts < prefs.failedThreshold) {
            Log.d("DeviceAdminReceiver", "Failed unlock recorded ($failedAttempts/${prefs.failedThreshold})")
            return
        }

        // enqueueSecurityEvent() writes to Room through SecurityStore.io(), which is
        // runBlocking(Dispatchers.IO). Doing that here blocked the broadcast main
        // thread for the duration of a disk write on the one path that must stay
        // responsive - a failed unlock, where the device is mid-lockscreen and the
        // ANR budget is already thin. goAsync keeps the receiver alive for the
        // background work, the same way onDisabled already does.
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        Thread {
            try {
                // Every callback becomes an independent event. The active foreground
                // service serializes concurrent captures and the durable queue handles
                // events that cannot be processed immediately.
                val event = prefs.enqueueSecurityEvent()
                val captureIntent = Intent(appContext, CameraForegroundService::class.java).apply {
                    action = CameraForegroundService.ACTION_CAPTURE_AND_SEND
                    putExtra(CameraForegroundService.EXTRA_SECURITY_EVENT_ID, event.id)
                }
                try {
                    // A plain startService() can throw IllegalStateException here on
                    // API 26+: the app is virtually never in the foreground at the
                    // moment someone else is failing to unlock the device. Route
                    // through the compat helper so this, the primary trigger for the
                    // whole app, uses the same background-start-safe path as every
                    // other dispatch site (SecurityEventDistributor, SecurityEventWorker).
                    ContextCompat.startForegroundService(appContext, captureIntent)
                    Log.d("DeviceAdminReceiver", "Failed-unlock capture dispatched for event ${event.id}")
                } catch (e: Exception) {
                    Log.e("DeviceAdminReceiver", "Direct capture dispatch failed; queued for recovery", e)
                    SecurityEventDistributor.enqueue(appContext, event.id)
                }
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        val prefs = SecurityPrefs.getInstance(context)
        // Reset the next unlock session without cancelling an event already
        // created for the preceding failed attempt; capture/email may still
        // be processing asynchronously.
        prefs.resetFailedUnlockAttempts(cancelPendingEvents = false)
        Log.d("DeviceAdminReceiver", "Password succeeded; consecutive failed attempts reset")
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d("DeviceAdminReceiver", "Device Admin Enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d("DeviceAdminReceiver", "Device Admin Disabled")
        // resetFailedUnlockAttempts/cancel do synchronous Room work (SecurityPrefs
        // uses runBlocking); run them off the broadcast main thread via goAsync.
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        Thread {
            try {
                // If disabled, turn off tracking to avoid inconsistent UI
                val prefs = SecurityPrefs.getInstance(appContext)
                prefs.isTrackingEnabled = false
                prefs.resetFailedUnlockAttempts()
                com.example.receiver.CountdownScheduler.cancel(appContext)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
