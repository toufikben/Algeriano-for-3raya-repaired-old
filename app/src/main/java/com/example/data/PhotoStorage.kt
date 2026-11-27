package com.example.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Where the capture photos live.
 *
 * This used to be getExternalFilesDir(null)/intruder_photos. That is app-private
 * storage, but on a secondary/external volume it is not guaranteed to be present:
 * when the user moves the app to adoptable storage or the volume is unmounted
 * (USB/OTG scenarios), getExternalFilesDir(null) returns null and the capture
 * wrote nowhere. The photo leg of the alert then failed silently at the exact
 * moment it mattered. filesDir is on internal storage and always exists.
 *
 * Everything that reads or writes that directory goes through here so the two
 * copies cannot drift, and so the one-time move away from the old location
 * happens in a single place.
 */
object PhotoStorage {
    const val DIRECTORY_NAME = "intruder_photos"
    private const val TAG = "PhotoStorage"

    /** The current directory. Created on demand. */
    fun directory(context: Context): File {
        val dir = File(context.filesDir, DIRECTORY_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * The pre-1.2.2 location, or null when this device has no external files
     * directory. Kept so migration and cleanup can still see files written by an
     * older build that has not been upgraded yet.
     */
    fun legacyDirectory(context: Context): File? =
        context.getExternalFilesDir(null)?.resolve(DIRECTORY_NAME)

    /**
     * Moves photos from the old external directory to the internal one and
     * returns the old-path -> new-path map so the caller can rewrite the
     * database references.
     *
     * A move that fails (still-open file, permissions) leaves the source in
     * place; the file is then simply reported with its old path and the caller
     * keeps working, because a photo at the old location is still a photo.
     */
    fun migrateLegacyDirectory(context: Context): Map<String, String> {
        val legacy = legacyDirectory(context) ?: return emptyMap()
        if (!legacy.isDirectory) return emptyMap()
        val target = directory(context)
        val mapping = mutableMapOf<String, String>()

        legacy.listFiles()?.forEach { source ->
            if (!source.isFile) return@forEach
            val destination = File(target, source.name)
            val moved = runCatching {
                if (destination.exists()) {
                    // Same name already present in the new location: the target
                    // wins and the stale duplicate is dropped.
                    source.delete()
                    true
                } else {
                    source.renameTo(destination) ||
                        (source.copyTo(destination, overwrite = false).let { source.delete() })
                }
            }.getOrElse { error ->
                Log.w(TAG, "Unable to migrate ${source.name}: ${error.javaClass.simpleName}")
                false
            }
            if (moved && destination.exists()) {
                mapping[source.absolutePath] = destination.absolutePath
            }
        }

        runCatching {
            if (legacy.listFiles()?.isEmpty() == true) legacy.delete()
        }
        return mapping
    }

    /** True when [path] points into the pre-migration directory. */
    fun isLegacyPath(context: Context, path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val legacy = legacyDirectory(context) ?: return false
        return runCatching {
            File(path).canonicalPath.startsWith(legacy.canonicalPath + File.separator)
        }.getOrDefault(false)
    }

    /** The migrated location of [path], or the original path if it did not move. */
    fun migratedPath(context: Context, path: String): String {
        val legacy = legacyDirectory(context) ?: return path
        val file = File(path)
        if (!runCatching { file.canonicalPath.startsWith(legacy.canonicalPath + File.separator) }
                .getOrDefault(false)) {
            return path
        }
        val candidate = File(directory(context), file.name)
        return if (candidate.exists()) candidate.absolutePath else path
    }
}
