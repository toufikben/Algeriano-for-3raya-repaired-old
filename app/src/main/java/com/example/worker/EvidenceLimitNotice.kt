package com.example.worker

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.MainActivity
import com.example.R
import com.example.SecurityApp
import com.example.data.IntruderLog
import com.example.data.SecurityEventComponentState
import com.example.data.SecurityPrefs
import com.example.i18n.localizedForAppLanguage
import com.example.receiver.CountdownScheduler
import com.example.util.EmailSender
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * What happens to a failed unlock once the monthly evidence allowance is spent.
 *
 * The camera and location stay off (that is the tier), but the owner still learns
 * that someone tried to unlock the phone: a log row, a notification and a
 * text-only email. Dropping the event silently would hide a real intrusion.
 */
object EvidenceLimitNotice {
    private const val TAG = "EvidenceLimitNotice"
    private const val NOTIFICATION_ID = 4200
    private const val WORK_PREFIX = "evidence-limit-mail-"
    internal const val KEY_TIMESTAMP = "timestamp"

    /**
     * Fails [eventId] and tells the owner. Returns false when another caller already
     * handled the same event, so the notice is never sent twice.
     */
    fun handleExhausted(context: Context, eventId: String): Boolean {
        val appContext = context.applicationContext
        val prefs = SecurityPrefs.getInstance(appContext)
        val event = prefs.getSecurityEvent(eventId) ?: return false
        if (!prefs.failPendingSecurityEvent(eventId)) return false
        Log.w(TAG, "Monthly evidence limit reached; event $eventId recorded without capture")

        val l10n = appContext.localizedForAppLanguage()
        val limit = prefs.getEvidenceMonthlyLimit()
        prefs.addLog(
            IntruderLog(
                id = UUID.randomUUID().toString(),
                eventId = eventId,
                photoCaptured = false,
                locationCaptured = false,
                timestamp = event.timestamp,
                photoPath = null,
                latitude = null,
                longitude = null,
                address = null,
                emailSent = false,
                statusMessage = l10n.getString(R.string.evidence_limit_log, limit),
                photoState = SecurityEventComponentState.NOT_REQUESTED,
                locationState = SecurityEventComponentState.NOT_REQUESTED,
                emailState = SecurityEventComponentState.NOT_REQUESTED
            )
        )

        finishCountdownCycle(appContext, prefs, eventId)
        postNotification(appContext)

        if (prefs.email.isNotBlank() && prefs.password.isNotBlank()) {
            val request = OneTimeWorkRequestBuilder<EvidenceLimitMailWorker>()
                .setInputData(workDataOf(KEY_TIMESTAMP to event.timestamp))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            runCatching {
                WorkManager.getInstance(appContext)
                    .enqueueUniqueWork(WORK_PREFIX + eventId, ExistingWorkPolicy.KEEP, request)
            }.onFailure { Log.e(TAG, "Unable to queue the limit email", it) }
        }
        return true
    }

    /**
     * A countdown whose capture was refused must not stay armed and retry forever.
     * Premium auto-restart keeps its cycle; every other countdown ends here, the same
     * way a completed capture ends it.
     */
    private fun finishCountdownCycle(context: Context, prefs: SecurityPrefs, eventId: String) {
        if (prefs.countdownEventId != eventId) return
        val restart = prefs.countdownAutoRestart && prefs.isPremium && prefs.isTrackingEnabled
        if (restart) {
            CountdownScheduler.start(context, prefs.countdownDurationMillis)
        } else {
            CountdownScheduler.cancel(context)
        }
    }

    private fun postNotification(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val l10n = context.localizedForAppLanguage()
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                // The moment the allowance runs out is when the upgrade is relevant.
                putExtra(MainActivity.EXTRA_OPEN_PREMIUM, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = l10n.getString(R.string.evidence_limit_notification_text)
        val notification = NotificationCompat.Builder(context, SecurityApp.CHANNEL_ID_ALERTS)
            .setContentTitle(l10n.getString(R.string.evidence_limit_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setSmallIcon(R.drawable.ic_launcher_foreground_img_1787338860864)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.e(TAG, "Unable to post the limit notification", it) }
    }
}

/** Sends the text-only "failed unlock, allowance spent" email once a network is available. */
class EvidenceLimitMailWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = SecurityPrefs.getInstance(applicationContext)
        val email = prefs.email
        val password = prefs.password
        if (email.isBlank() || password.isBlank()) return Result.success()

        val l10n = applicationContext.localizedForAppLanguage()
        val timestamp = inputData.getLong(EvidenceLimitNotice.KEY_TIMESTAMP, System.currentTimeMillis())
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
        val result = EmailSender.sendSecurityAlert(
            context = l10n,
            senderEmail = email,
            appPassword = password,
            recipientEmail = email,
            subject = l10n.getString(R.string.evidence_limit_email_subject, timeStr),
            bodyText = l10n.getString(R.string.evidence_limit_email_body, timeStr, prefs.getEvidenceMonthlyLimit())
        )
        return when {
            result.isSuccess -> Result.success()
            result.retryable && runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            else -> Result.failure()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
