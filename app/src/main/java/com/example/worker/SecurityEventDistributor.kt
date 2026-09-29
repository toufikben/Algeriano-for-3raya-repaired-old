package com.example.worker

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.IntruderLog
import com.example.data.SecurityEvent
import com.example.data.SecurityEventComponentState
import com.example.data.SecurityPrefs
import com.example.data.SecurityEventStatus
import com.example.MainActivity
import com.example.R
import com.example.SecurityApp
import com.example.i18n.localizedForAppLanguage
import com.example.service.CameraForegroundService
import com.example.util.EmailSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Durable dispatcher for security events.
 *
 * It serializes each event by a unique WorkManager name. It does not claim an
 * event: the service claims it immediately before processing, which keeps a
 * worker crash from losing a PENDING event.
 */
object SecurityEventDistributor {
    private const val EVENT_ID_KEY = "security_event_id"
    private const val WORK_PREFIX = "security-event-"
    private const val RECOVERY_WORK_NAME = "security-events-recovery"
    private const val RECOVERY_DELAY_MINUTES = 2L
    private const val MAX_WORK_ATTEMPTS = 3
    private const val RETRY_DELAY_MINUTES = 1L

    fun enqueue(context: Context, eventId: String) {
        val prefs = SecurityPrefs.getInstance(context)
        if (prefs.getPendingSecurityEvents().none { it.id == eventId }) return

        val event = prefs.getPendingSecurityEvents().first { it.id == eventId }
        val retryDelayMinutes = if (event.sendAttempts == 0) {
            0L
        } else {
            RETRY_DELAY_MINUTES shl (event.sendAttempts - 1).coerceIn(0, 2)
        }
        val request = OneTimeWorkRequestBuilder<SecurityEventWorker>()
            .setInputData(workDataOf(EVENT_ID_KEY to eventId))
            .setInitialDelay(retryDelayMinutes, TimeUnit.MINUTES)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                RETRY_DELAY_MINUTES,
                TimeUnit.MINUTES
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_PREFIX + eventId,
            ExistingWorkPolicy.KEEP,
            request
        )
        scheduleRecovery(context)
    }

    fun enqueuePending(context: Context) {
        SecurityPrefs.getInstance(context).getPendingSecurityEvents()
            .forEach { enqueue(context, it.id) }
    }

    /**
     * Rebuilds only reboot-safe work. Camera capture remains deferred until a
     * visible Activity starts it, while already-captured events can continue
     * through the durable send/retry path.
     */
    fun enqueueAfterReboot(context: Context) {
        val prefs = SecurityPrefs.getInstance(context)
        prefs.recoverStaleSecurityEvents()
        prefs.getPendingSecurityEvents()
            .filter { it.status == SecurityEventStatus.SEND_PENDING || it.status == SecurityEventStatus.FAILED_RETRYABLE }
            .forEach { enqueue(context, it.id) }
    }

    /** Dispatch durable events from a visible Activity instead of relying on a
     * background Worker to start a camera foreground service on Android 14+. */
    fun dispatchPendingFromVisibleContext(context: Context) {
        val appContext = context.applicationContext
        val prefs = SecurityPrefs.getInstance(appContext)
        prefs.getPendingSecurityEvents().forEach { event ->
            val countdownEvent = prefs.countdownCapturePending &&
                prefs.countdownEventId == event.id
            val intent = Intent(appContext, CameraForegroundService::class.java).apply {
                action = if (countdownEvent) {
                    CameraForegroundService.ACTION_COUNTDOWN_EXPIRED
                } else {
                    CameraForegroundService.ACTION_CAPTURE_AND_SEND
                }
                putExtra(CameraForegroundService.EXTRA_SECURITY_EVENT_ID, event.id)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(appContext, intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (_: Exception) {
                enqueue(appContext, event.id)
            }
        }
    }

    fun scheduleRecovery(context: Context) {
        val request = OneTimeWorkRequestBuilder<SecurityEventRecoveryWorker>()
            .setInitialDelay(RECOVERY_DELAY_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            RECOVERY_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    internal fun eventId(input: androidx.work.Data): String? = input.getString(EVENT_ID_KEY)

    internal fun workName(eventId: String): String = WORK_PREFIX + eventId

    internal const val MAX_ATTEMPTS = MAX_WORK_ATTEMPTS

    fun notifyDeferredCapture(context: Context, eventId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val openIntent = PendingIntent.getActivity(
            context,
            eventId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val l10n = context.localizedForAppLanguage()
        val notification = NotificationCompat.Builder(context, SecurityApp.CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground_img_1787338860864)
            .setContentTitle(l10n.getString(R.string.notification_deferred_title))
            .setContentText(l10n.getString(R.string.notification_deferred_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(l10n.getString(R.string.notification_deferred_text)))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(DEFERRED_NOTIFICATION_BASE + eventId.hashCode(), notification)
        }
    }

    private const val DEFERRED_NOTIFICATION_BASE = 31_000
}

class SecurityEventWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    private val l10n by lazy { applicationContext.localizedForAppLanguage() }

    override suspend fun doWork(): Result {
        val eventId = SecurityEventDistributor.eventId(inputData) ?: return Result.failure()
        val prefs = SecurityPrefs.getInstance(applicationContext)
        val event = prefs.getPendingSecurityEvent(eventId)
            ?: return Result.success()

        // A camera foreground service must be started from a user-visible flow
        // once the background-start restriction applies (Android 12/S+, where
        // ForegroundServiceStartNotAllowedException was introduced). Keep the
        // durable event pending; the next visible app session will enqueue it
        // again instead of converting this policy block into a failed security
        // event or an endless retry loop.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            event.status != SecurityEventStatus.SEND_PENDING &&
            event.status != SecurityEventStatus.FAILED_RETRYABLE
        ) {
            prefs.deferSecurityEvent(event.id)
            SecurityEventDistributor.notifyDeferredCapture(applicationContext, event.id)
            return Result.success()
        }

        // FIX: the service intentionally skips startForeground() on the
        // ACTION_SEND_PENDING mail-only path, so calling
        // ContextCompat.startForegroundService() for it from this background
        // worker can crash with "Context.startForegroundService() did not
        // then call Service.startForeground()". Perform the mail-only resend
        // in-process instead, mirroring the service's status handling.
        if (event.status == SecurityEventStatus.SEND_PENDING ||
            event.status == SecurityEventStatus.FAILED_RETRYABLE
        ) {
            return sendPendingEventFromWorker(prefs, event)
        }

        return try {
            val intent = Intent(applicationContext, CameraForegroundService::class.java).apply {
                action = CameraForegroundService.ACTION_CAPTURE_AND_SEND
                putExtra(CameraForegroundService.EXTRA_SECURITY_EVENT_ID, event.id)
            }
            ContextCompat.startForegroundService(applicationContext, intent)
            Result.success()
        } catch (_: Exception) {
            // On Android 12+ a background foreground-service start is rejected by
            // policy rather than a transient fault, so defer for a visible session
            // instead of draining retries into an unrecoverable FAILED_FINAL.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                prefs.deferSecurityEvent(event.id)
                SecurityEventDistributor.notifyDeferredCapture(applicationContext, event.id)
                return Result.success()
            }
            if (runAttemptCount + 1 < SecurityEventDistributor.MAX_ATTEMPTS) {
                Result.retry()
            } else {
                prefs.failPendingSecurityEvent(event.id)
                Result.failure()
            }
        }
    }

    /**
     * Minimal mirror of CameraForegroundService's ACTION_SEND_PENDING send flow:
     * normalize status, send the stored photo by email, then apply the same
     * SENT / retry / FAILED_FINAL handling the service applies. Location is
     * reused from the event (never re-fetched), which the service's send-only
     * path already tolerates (null/absent coordinates fall back gracefully).
     */
    private suspend fun sendPendingEventFromWorker(
        prefs: SecurityPrefs,
        event: SecurityEvent
    ): Result = withContext(Dispatchers.IO) {
        try {
            prefs.moveCapturedEventToSendPending(event.id)
            val senderEmail = prefs.email
            val appPassword = prefs.password
            if (senderEmail.isBlank() || appPassword.isBlank()) {
                prefs.updateEmailState(event.id, SecurityEventComponentState.FAILED)
                prefs.completeSecurityEvent(event.id, SecurityEventStatus.FAILED_FINAL)
                return@withContext Result.success()
            }
            prefs.updateEmailState(event.id, SecurityEventComponentState.PENDING)

            val imageFile = event.photoPath?.let(::File)?.takeIf { it.exists() }
            val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(event.timestamp))
            val locationText = if (event.latitude != null && event.longitude != null) {
                l10n.getString(
                    R.string.ui_location_coordinates,
                    event.latitude.toString(),
                    event.longitude.toString()
                )
            } else {
                l10n.getString(R.string.ui_c0ae8d5ba4fa)
            }
            val body = """
                ${l10n.getString(R.string.ui_greeting)}

                ${l10n.getString(R.string.ui_intrusion_body)}

                ${l10n.getString(R.string.ui_time_label, timeStr)}
                ${l10n.getString(R.string.ui_location_label)}
                $locationText

                ${l10n.getString(R.string.ui_photo_attached)}

                ---
                ${l10n.getString(R.string.ui_auto_sent)}
            """.trimIndent()

            val sendResult = EmailSender.sendSecurityAlert(
                context = l10n,
                senderEmail = senderEmail,
                appPassword = appPassword,
                recipientEmail = senderEmail,
                subject = l10n.getString(R.string.ui_alert_subject, timeStr),
                bodyText = body,
                imageFile = imageFile,
                eventId = event.id
            )
            prefs.updateEmailState(
                event.id,
                if (sendResult.isSuccess) SecurityEventComponentState.SUCCEEDED else SecurityEventComponentState.FAILED
            )
            when {
                sendResult.isSuccess -> {
                    prefs.completeSecurityEvent(event.id, SecurityEventStatus.SENT)
                    recordWorkerSendLog(prefs, event, imageFile, true, null)
                    Result.success()
                }
                sendResult.retryable && prefs.recordSendRetry(event.id) -> {
                    prefs.markSendPendingForRetry(event.id)
                    // Re-arms the durable queue; enqueue() also schedules the
                    // recovery worker that will pick the event back up.
                    SecurityEventDistributor.enqueue(applicationContext, event.id)
                    recordWorkerSendLog(prefs, event, imageFile, false, sendResult.errorMessage)
                    Result.success()
                }
                else -> {
                    prefs.completeSecurityEvent(event.id, SecurityEventStatus.FAILED_FINAL)
                    recordWorkerSendLog(prefs, event, imageFile, false, sendResult.errorMessage)
                    Result.success()
                }
            }
        } catch (_: Exception) {
            // Same terminal handling the service applies to an unexpected error
            // while processing a send-pending event.
            prefs.failPendingSecurityEvent(event.id)
            Result.failure()
        }
    }

    /** Keeps the intruder log honest after a background resend: addLog updates
     * the existing entry for the event id in place. */
    private fun recordWorkerSendLog(
        prefs: SecurityPrefs,
        event: SecurityEvent,
        imageFile: File?,
        emailSuccess: Boolean,
        errorMessage: String?
    ) {
        val separator = l10n.getString(R.string.ui_separator)
        val photoStatus = if (imageFile != null) {
            l10n.getString(R.string.ui_dd886e527edc)
        } else {
            l10n.getString(R.string.ui_22a64e14f4b3)
        }
        val locationStatus = if (event.latitude != null && event.longitude != null) {
            l10n.getString(R.string.ui_c0a3068f31ba)
        } else {
            l10n.getString(R.string.ui_243471de5777)
        }
        val emailStatus = if (emailSuccess) {
            l10n.getString(R.string.ui_fd5b9e2ff279)
        } else {
            l10n.getString(R.string.ui_email_failed, errorMessage ?: "")
        }
        val completedEvent = prefs.getSecurityEvent(event.id)
        prefs.addLog(
            IntruderLog(
                id = UUID.randomUUID().toString(),
                eventId = event.id,
                photoCaptured = imageFile != null,
                locationCaptured = event.latitude != null && event.longitude != null,
                timestamp = event.timestamp,
                photoPath = imageFile?.absolutePath,
                latitude = event.latitude,
                longitude = event.longitude,
                address = if (event.latitude != null && event.longitude != null) {
                    "${event.latitude}, ${event.longitude}"
                } else {
                    null
                },
                emailSent = emailSuccess,
                statusMessage = listOf(photoStatus, locationStatus, emailStatus)
                    .joinToString(separator),
                photoState = completedEvent?.photoState
                    ?: if (imageFile != null) SecurityEventComponentState.SUCCEEDED else SecurityEventComponentState.FAILED,
                locationState = completedEvent?.locationState
                    ?: if (event.latitude != null && event.longitude != null) SecurityEventComponentState.SUCCEEDED else SecurityEventComponentState.FAILED,
                emailState = completedEvent?.emailState
                    ?: if (emailSuccess) SecurityEventComponentState.SUCCEEDED else SecurityEventComponentState.FAILED
            )
        )
    }
}

class SecurityEventRecoveryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val prefs = SecurityPrefs.getInstance(applicationContext)
        prefs.recoverStaleSecurityEvents()
        prefs.getPendingSecurityEvents()
            .filter { it.status != com.example.data.SecurityEventStatus.DEFERRED }
            .forEach { SecurityEventDistributor.enqueue(applicationContext, it.id) }
        if (prefs.getPendingSecurityEvents().any { it.status != com.example.data.SecurityEventStatus.DEFERRED }) {
            SecurityEventDistributor.scheduleRecovery(applicationContext)
        }
        return Result.success()
    }
}
