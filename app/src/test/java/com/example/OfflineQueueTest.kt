package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.SecurityEventComponentState
import com.example.data.SecurityEventStatus
import com.example.data.SecurityPrefs
import com.example.worker.SecurityEventDistributor
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Evidence captured during an internet outage must still be mailed when the connection
 * returns. The lease sweep used to finalize such events after two passes, so a night
 * offline left the queue "processing" forever while new events went out normally.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineQueueTest {
    private lateinit var context: Context
    private lateinit var prefs: SecurityPrefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        prefs = SecurityPrefs.getInstance(context)
        prefs.resetFailedUnlockAttempts()
    }

    private fun capturedWaitingForNetwork(): String {
        val photo = File.createTempFile("capture", ".jpg", context.cacheDir).apply { writeBytes(byteArrayOf(1)) }
        val event = prefs.enqueueSecurityEvent()
        assertTrue(prefs.claimSecurityEvent(event.id))
        prefs.recordCaptureResult(event.id, photo.absolutePath, 36.7, 3.0, null)
        assertTrue(prefs.moveCapturedEventToSendPending(event.id))
        prefs.updateEmailState(event.id, SecurityEventComponentState.PENDING)
        return event.id
    }

    @Test
    fun `a night offline never finalizes evidence that is waiting to be sent`() {
        val id = capturedWaitingForNetwork()
        var now = System.currentTimeMillis()
        // Many sweeps over an eight-hour outage.
        repeat(40) {
            now += 12 * 60 * 1000L
            prefs.recoverStaleSecurityEvents(now)
        }
        assertEquals(SecurityEventStatus.SEND_PENDING, prefs.getSecurityEvent(id)?.status)
        assertTrue(prefs.getPendingSecurityEvents().any { it.id == id })
    }

    @Test
    fun `an event the old sweep finalized while offline is sent again once`() {
        val id = capturedWaitingForNetwork()
        // What the old sweep did: finalize while the mail was still only pending.
        prefs.completeSecurityEvent(id, SecurityEventStatus.FAILED_FINAL)
        prefs.updateEmailState(id, SecurityEventComponentState.PENDING)

        prefs.reviveEventsFinalizedWhileOffline(evenIfDone = true)

        assertEquals(SecurityEventStatus.SEND_PENDING, prefs.getSecurityEvent(id)?.status)
    }

    @Test
    fun `opening the app queues waiting evidence for the send work, not the camera`() {
        val id = capturedWaitingForNetwork()

        SecurityEventDistributor.dispatchPendingFromVisibleContext(context)

        val work = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SecurityEventDistributor.workName(id)).get()
        assertEquals(1, work.size)
    }

    @Test
    fun `a send the server refused is not revived`() {
        val id = capturedWaitingForNetwork()
        prefs.updateEmailState(id, SecurityEventComponentState.FAILED)
        prefs.completeSecurityEvent(id, SecurityEventStatus.FAILED_FINAL)

        prefs.reviveEventsFinalizedWhileOffline(evenIfDone = true)

        assertEquals(SecurityEventStatus.FAILED_FINAL, prefs.getSecurityEvent(id)?.status)
    }
}
