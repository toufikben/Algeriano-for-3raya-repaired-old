package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.SecurityPrefs
import java.util.concurrent.TimeUnit

/** Removes old private photos and clears references only after a successful delete. */
class PhotoCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val directory = applicationContext.getExternalFilesDir(null)?.resolve(PHOTO_DIRECTORY)
            ?: return Result.success()
        if (!directory.isDirectory) return Result.success()
        val prefs = SecurityPrefs.getInstance(applicationContext)
        // Free expires a photo after a week; Premium keeps it until the user deletes
        // it, and reports Long.MAX_VALUE as the sentinel for that. Converting the
        // sentinel with TimeUnit.DAYS.toMillis would overflow into a negative
        // number, which turns the cutoff into a future timestamp and deletes
        // everything - so the unbounded case returns before any arithmetic.
        val retentionDays = prefs.getPhotoRetentionDays()
        if (retentionDays == Long.MAX_VALUE) return Result.success()
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays)
        var failures = 0
        directory.listFiles()?.forEach { file ->
            if (!file.isFile || file.lastModified() >= cutoff) return@forEach
            if (runCatching { file.delete() }.getOrDefault(false)) {
                prefs.clearPhotoReference(file)
            } else {
                failures++
                Log.w(TAG, "Unable to delete expired photo: ${file.name}")
            }
        }
        return if (failures == 0) Result.success() else Result.retry()
    }

    companion object {
        const val WORK_NAME = "weekly_photo_cleanup"
        private const val PHOTO_DIRECTORY = "intruder_photos"
        private const val TAG = "PhotoCleanupWorker"
    }
}
