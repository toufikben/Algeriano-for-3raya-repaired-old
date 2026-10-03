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
        // Premium's "extended retention" is this window: 7 days on Free, 30 on Premium.
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(prefs.getPhotoRetentionDays())
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
