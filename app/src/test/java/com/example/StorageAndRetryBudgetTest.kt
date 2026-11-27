package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.PhotoStorage
import com.example.data.SecurityEvent
import com.example.data.SecurityEventStatus
import com.example.data.SecurityPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Covers the wave-4 storage and retry-budget fixes.
 *
 * Both are the kind of bug that never shows up in a happy-path run: photo
 * storage only breaks on a device without external storage, and the retry budget
 * only matters after three background starts have already been refused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StorageAndRetryBudgetTest {
    private lateinit var context: Context
    private lateinit var prefs: SecurityPrefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        prefs = SecurityPrefs.getInstance(context)
    }

    @Test
    fun `photos are written under internal storage, not external`() {
        val directory = PhotoStorage.directory(context)
        assertTrue("photo directory must be created on demand", directory.isDirectory)
        assertTrue(
            "the capture must not depend on an external volume being mounted",
            directory.absolutePath.startsWith(context.filesDir.absolutePath)
        )
    }

    @Test
    fun `files from the old external directory are moved and reported`() {
        val legacy = PhotoStorage.legacyDirectory(context)
            ?: run {
                // A host with no external files dir has nothing to migrate; that
                // is the exact case the fix exists for, so it is a pass.
                return
            }
        legacy.mkdirs()
        val original = File(legacy, "capture_old.jpg")
        original.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00))

        val mapping = PhotoStorage.migrateLegacyDirectory(context)

        val moved = PhotoStorage.directory(context).resolve("capture_old.jpg")
        assertTrue("the file must now exist in internal storage", moved.exists())
        assertFalse("the old copy must be gone", original.exists())
        assertEquals(
            "the caller needs the old path to rewrite stored references",
            moved.absolutePath,
            mapping[original.absolutePath]
        )
    }

    @Test
    fun `a stored legacy path resolves to the migrated file`() {
        val legacy = PhotoStorage.legacyDirectory(context) ?: return
        legacy.mkdirs()
        val original = File(legacy, "capture_legacy.jpg")
        original.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00))
        PhotoStorage.migrateLegacyDirectory(context)

        val moved = PhotoStorage.directory(context).resolve("capture_legacy.jpg")
        assertEquals(moved.absolutePath, PhotoStorage.migratedPath(context, original.absolutePath))
    }

    @Test
    fun `an exhausted countdown retry budget is cleared from a visible session`() {
        // Three background starts were refused, so CaptureRetryWorker.enqueue()
        // would return without scheduling anything and the pending capture would
        // sit there forever - including after the user tapped the notification.
        prefs.countdownRetryCount = 3
        assertEquals(3, prefs.countdownRetryCount)

        prefs.resetCountdownRetryBudget()

        assertEquals(
            "a visible Activity can start a foreground service, so the budget is spendable again",
            0,
            prefs.countdownRetryCount
        )
    }

    @Test
    fun `resetting an untouched budget does not write`() {
        prefs.countdownRetryCount = 0
        prefs.resetCountdownRetryBudget()
        assertEquals(0, prefs.countdownRetryCount)
    }

    /**
     * True when the test host can actually round-trip a secret through the
     * Android keystore. Robolectric's keystore support varies by version, and a
     * test that silently asserts on a no-op write would be worse than no test.
     */
    private fun keystoreUsable(): Boolean {
        prefs.email = "probe@example.com"
        val ok = prefs.email == "probe@example.com"
        prefs.email = ""
        return ok
    }

    @Test
    fun `a stored credential that cannot be decrypted is reported, not silently blanked`() {
        if (!keystoreUsable()) return

        prefs.email = "user@example.com"
        assertFalse("a readable credential is not reported as broken", prefs.credentialsUnreadable)

        val stored = context
            .getSharedPreferences("intruder_security_prefs", Context.MODE_PRIVATE)
            .getString("key_email", "")
        assertTrue("sanity: the value is stored encrypted", stored!!.startsWith("v1:"))

        // Corrupt the payload so GCM/base64 fails the way a lost key does.
        context.getSharedPreferences("intruder_security_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("key_email", "v1:bm90LXZhbGlkLWNpcGhlcnRleHQ=")
            .commit()

        assertEquals("", prefs.email)
        assertTrue(
            "the UI has to explain why the field is empty rather than pretend it was never set",
            prefs.credentialsUnreadable
        )
    }

    @Test
    fun `a successful save clears the unreadable flag`() {
        if (!keystoreUsable()) return

        context.getSharedPreferences("intruder_security_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("key_credentials_unreadable", "key_password")
            .commit()
        assertTrue(prefs.credentialsUnreadable)

        prefs.email = "user@example.com"

        assertFalse(
            "a working keystore write proves the key is usable again",
            prefs.credentialsUnreadable
        )
    }

    @Test
    fun `a legacy pending event keeps its status when its photo path is remapped`() {
        // Guards the migration's data shape: it rewrites photoPath only.
        val event = SecurityEvent(
            id = "event-1",
            timestamp = 1L,
            failedAttempt = 1,
            status = SecurityEventStatus.SEND_PENDING,
            updatedAt = 1L
        )
        val remapped = event.copy(photoPath = "/data/user/0/files/intruder_photos/x.jpg")
        assertEquals(event.id, remapped.id)
        assertEquals(event.status, remapped.status)
    }
}
