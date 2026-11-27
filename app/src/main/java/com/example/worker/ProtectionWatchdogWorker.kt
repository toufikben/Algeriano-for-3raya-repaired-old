package com.example.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.SecurityPrefs
import com.example.receiver.BootReceiver
import java.util.concurrent.TimeUnit

/**
 * Periodic health check for the protection service.
 *
 * The foreground service can be killed by the system under memory pressure or by
 * an OEM battery manager (Xiaomi, Huawei, Samsung, Oppo and others do this
 * aggressively). Nothing in the app noticed: `isTrackingEnabled` stayed true, so
 * the status card kept showing "protection active" while no service was alive to
 * capture anything.
 *
 * This worker cannot start the service itself (Android 12+ forbids a background
 * camera foreground-service start, the same restriction that makes the boot
 * notification necessary). What it can do is stop the silence: when protection
 * is enabled but the service is not running, it re-posts the existing
 * "open the app to resume protection" prompt so the user knows to act.
 */
class ProtectionWatchdogWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = SecurityPrefs.getInstance(applicationContext)

        // Nothing to watch while the user has protection switched off.
        if (!prefs.isTrackingEnabled) {
            SecurityEventDistributor.cancelProtectionWatchdog(applicationContext)
            return Result.success()
        }

        if (!com.example.service.CameraForegroundService.isRunning) {
            BootReceiver.postResumeNotificationInternal(applicationContext)
        } else {
            // The service is healthy again (the user resumed it, or the system
            // restarted it). Drop the prompt so it does not outlive its purpose.
            BootReceiver.cancelResumeNotification(applicationContext)
        }

        return Result.success()
    }

    companion object {
        /** Unique work name; also used by [SecurityEventDistributor] to cancel it. */
        const val WORK_NAME = "protection-watchdog-periodic"
        private const val INTERVAL_MINUTES = 30L
        private const val INITIAL_DELAY_MINUTES = 5L

        /** Starts the watchdog. Safe to call repeatedly; KEEP keeps one instance. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ProtectionWatchdogWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            ).setInitialDelay(INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
