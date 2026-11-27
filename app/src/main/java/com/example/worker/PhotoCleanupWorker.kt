package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.PhotoStorage
import com.example.data.SecurityPrefs
import java.util.concurrent.TimeUnit

/** Removes old private photos and clears references only after a successful delete. */
class PhotoCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val directory = PhotoStorage.directory(applicationContext)
        if (!directory.isDirectory) return Result.success()
        val prefs = SecurityPrefs.getInstance(applicationContext)
        // Free expires a photo after a week; Premium keeps it until the user deletes
        // it and reports Long.MAX_VALUE as the sentinel for that. The arithmetic
        // would not do what it looks like: TimeUnit.DAYS.toMillis saturates at
        // Long.MAX_VALUE, and subtracting that from the current time wraps to a
        // large negative cutoff, so every file would compare as "newer than the
        // cutoff" and nothing would be deleted. That happens to be the intended
        // outcome, but reaching it through signed overflow is not something to
        // leave load-bearing - state it.
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
        private const val TAG = "PhotoCleanupWorker"
    }
}
