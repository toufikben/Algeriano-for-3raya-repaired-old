package com.example.receiver

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.SecurityApp
import com.example.data.SecurityPrefs
import com.example.service.CameraForegroundService
import com.example.worker.CaptureRetryWorker
import com.example.i18n.localizedForAppLanguage

class CountdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // Room/SharedPreferences work here is synchronous (SecurityPrefs uses
        // runBlocking); hop off the broadcast main thread to avoid a timeout.
        val appContext = context.applicationContext
        val action = intent?.action
        val pendingResult = goAsync()
        Thread {
            try {
                val prefs = SecurityPrefs.getInstance(appContext)
                when (action) {
                    CountdownScheduler.ACTION_WARNING -> {
                        showWarning(appContext, prefs)
                    }
                    CountdownScheduler.ACTION_RESET -> {
                        // FIX: use the tamper-resistant expiry check (min of epoch
                        // and elapsedRealtime deadlines) instead of raw clock compare.
                        if (prefs.countdownEnabled && !prefs.isCountdownExpired()) {
                            // FIX: complete orphan countdown event before restarting so
                            // the old PENDING event does not leak as a generic capture.
                            val orphanId = prefs.countdownEventId
                            if (!orphanId.isBlank()) {
                                prefs.completeSecurityEvent(orphanId, com.example.data.SecurityEventStatus.CANCELLED)
                            }
                            CountdownScheduler.start(appContext, prefs.countdownDurationMillis)
                        }
                    }
                    CountdownScheduler.ACTION_EXPIRED -> {
                        if (prefs.countdownEnabled && (prefs.countdownCapturePending || prefs.isCountdownExpired())) {
                            prefs.countdownCapturePending = true
                            if (prefs.countdownEventId.isBlank()) {
                                prefs.countdownEventId = prefs.enqueueSecurityEvent().id
                            }
                            // FIX: service is event-driven (not persistent), so go
                            // straight to startForegroundService. The old
                            // startService-first path always threw on API 26+.
                            try {
                                val serviceIntent = Intent(appContext, CameraForegroundService::class.java).apply {
                                    this.action = CameraForegroundService.ACTION_COUNTDOWN_EXPIRED
                                    putExtra(CameraForegroundService.EXTRA_SECURITY_EVENT_ID, prefs.countdownEventId)
                                }
                                ContextCompat.startForegroundService(appContext, serviceIntent)
                            } catch (e: Exception) {
                                CaptureRetryWorker.enqueue(appContext)
                                // FIX(countdown): background start denied — notify user
                                // to open the app so visible dispatch can complete it.
                                CountdownScheduler.postTapToComplete(appContext)
                            }
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun showWarning(context: Context, prefs: SecurityPrefs) {
        if (!prefs.countdownEnabled || prefs.isCountdownExpired()) return

        val resetIntent = PendingIntent.getBroadcast(
            context,
            CountdownScheduler.RESET_REQUEST_CODE,
            Intent(context, CountdownReceiver::class.java).setAction(CountdownScheduler.ACTION_RESET),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            context,
            CountdownScheduler.OPEN_REQUEST_CODE,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val l10n = context.localizedForAppLanguage()
        val notification = NotificationCompat.Builder(context, SecurityApp.CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground_img_1787338860864)
            .setContentTitle(l10n.getString(com.example.R.string.ui_158bb9c625fb))
            .setContentText(l10n.getString(com.example.R.string.ui_0b183fd63f65))
            .setStyle(NotificationCompat.BigTextStyle().bigText(l10n.getString(com.example.R.string.ui_226510ac7ba6)))
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_launcher_foreground_img_1787338860864, l10n.getString(com.example.R.string.ui_771471017b22), resetIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(CountdownScheduler.WARNING_NOTIFICATION_ID, notification)
    }
}

object CountdownScheduler {
    const val ACTION_WARNING = "com.example.action.COUNTDOWN_WARNING"
    const val ACTION_RESET = "com.example.action.COUNTDOWN_RESET"
    const val ACTION_EXPIRED = "com.example.action.COUNTDOWN_EXPIRED"
    const val RESET_REQUEST_CODE = 4102
    const val OPEN_REQUEST_CODE = 4103
    const val WARNING_NOTIFICATION_ID = 4104
    private const val WARNING_REQUEST_CODE = 4100
    private const val EXPIRED_REQUEST_CODE = 4101
    private const val WARNING_LEAD_MILLIS = 10 * 60 * 1000L

    fun start(context: Context, durationMillis: Long) {
        val prefs = SecurityPrefs.getInstance(context)
        val safeDuration = durationMillis.coerceAtLeast(60_000L)
        prefs.countdownDurationMillis = safeDuration
        prefs.countdownEndTime = System.currentTimeMillis() + safeDuration
        prefs.countdownCapturePending = false
        prefs.countdownRetryCount = 0
        prefs.countdownEventId = ""
        prefs.countdownEnabled = true
        CaptureRetryWorker.cancel(context)
        context.getSystemService(NotificationManager::class.java)?.cancel(WARNING_NOTIFICATION_ID)
        scheduleAlarms(context, prefs.countdownEndTime)
        runCatching {
            val armIntent = Intent(context, CameraForegroundService::class.java).apply {
                action = CameraForegroundService.ACTION_ARM_COUNTDOWN
            }
            ContextCompat.startForegroundService(context, armIntent)
        }
        // The exact alarm remains the recovery path if Android rejects the
        // visible-context foreground-service start.
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager?.cancel(pendingIntent(context, ACTION_WARNING, WARNING_REQUEST_CODE))
        alarmManager?.cancel(pendingIntent(context, ACTION_EXPIRED, EXPIRED_REQUEST_CODE))
        context.getSystemService(NotificationManager::class.java)?.cancel(WARNING_NOTIFICATION_ID)
        CaptureRetryWorker.cancel(context)
        SecurityPrefs.getInstance(context).clearCountdown()
        context.stopService(Intent(context, CameraForegroundService::class.java))
    }

    fun rescheduleFromPrefs(context: Context) {
        val prefs = SecurityPrefs.getInstance(context)
        if (!prefs.countdownEnabled) return
        if (prefs.countdownCapturePending || prefs.isCountdownExpired()) {
            prefs.countdownCapturePending = true
            if (prefs.countdownEventId.isBlank()) {
                prefs.countdownEventId = prefs.enqueueSecurityEvent().id
            }
            CaptureRetryWorker.enqueue(context)
        } else {
            scheduleAlarms(context, prefs.countdownEndTime)
        }
    }

    fun schedulePendingRetry(context: Context) {
        CaptureRetryWorker.enqueue(context)
    }

    private fun scheduleAlarms(context: Context, endTime: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val duration = endTime - now
        // FIX: skip warning when duration is shorter than lead time + slack,
        // otherwise 1-min test timer fires warning after 1s (confusing).
        if (duration > WARNING_LEAD_MILLIS + 60_000L) {
            val warningAt = (endTime - WARNING_LEAD_MILLIS).coerceAtLeast(now + 1_000L)
            runCatching {
                scheduleAlarm(alarmManager, warningAt, pendingIntent(context, ACTION_WARNING, WARNING_REQUEST_CODE))
            }
        }
        runCatching {
            scheduleAlarm(alarmManager, endTime.coerceAtLeast(now + 1_000L), pendingIntent(context, ACTION_EXPIRED, EXPIRED_REQUEST_CODE))
        }
    }

    private fun scheduleAlarm(
        alarmManager: AlarmManager,
        triggerAt: Long,
        pendingIntent: PendingIntent
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                // FIX(countdown): on Android 12+ inexact scheduling is almost
                // always missing Exact-Alarm permission, which drifts long
                // timers. User must grant it in Settings.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (e: SecurityException) {
            // Exact scheduling was refused mid-flight; fall back to inexact.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun pendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, CountdownReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * FIX(countdown): when a background FGS start is denied, the capture
     * stays pending until the app becomes visible. This notification gives
     * the user a one-tap path to open the app and let
     * dispatchPendingFromVisibleContext finish it.
     */
    fun postTapToComplete(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val openIntent = PendingIntent.getActivity(
            context,
            OPEN_REQUEST_CODE,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val l10n = context.localizedForAppLanguage()
        val notification = NotificationCompat.Builder(context, SecurityApp.CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground_img_1787338860864)
            .setContentTitle(l10n.getString(R.string.ui_98c4ee03d7e4))
            .setContentText(l10n.getString(R.string.ui_0b183fd63f65))
            .setStyle(NotificationCompat.BigTextStyle().bigText(l10n.getString(R.string.ui_226510ac7ba6)))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        androidx.core.app.NotificationManagerCompat.from(context)
            .notify(TAP_TO_COMPLETE_NOTIFICATION_ID, notification)
    }

    private const val TAP_TO_COMPLETE_NOTIFICATION_ID = 4105
}
