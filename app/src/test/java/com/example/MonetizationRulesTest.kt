package com.example

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.billing.PurchaseVerifier
import com.example.data.SecurityPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.Signature

/**
 * Pins what Free and Premium actually sell after the Plan A repricing.
 *
 * The old contract charged capture by the month: three free captures, then no
 * photo and no location until the calendar turned over. That is what this test
 * used to assert. Capture is now free and uncapped on both tiers, and Premium
 * sells retention plus the auto-restarting countdown, so the assertions follow
 * the money: nothing may be refused for a capture, and the two tiers must stay
 * ordered (a paid tier narrower than the free one is a refund waiting to happen).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MonetizationRulesTest {
    private lateinit var context: Context
    private lateinit var prefs: SecurityPrefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        prefs = SecurityPrefs.getInstance(context)
        prefs.isPremium = false
    }

    @Test
    fun `free capture is never refused, however many attempts arrive`() {
        // Well past any number the old monthly cap allowed.
        repeat(500) {
            assertTrue("free capture must never be refused", prefs.tryReserveEvidenceSlot())
        }
        assertTrue(prefs.canCreateEvidenceEvent())
        assertEquals(Int.MAX_VALUE, prefs.getEvidenceMonthlyLimit())
    }

    @Test
    fun `premium capture is never refused either`() {
        prefs.isPremium = true
        repeat(500) {
            assertTrue(prefs.tryReserveEvidenceSlot())
        }
        assertTrue(prefs.canCreateEvidenceEvent())
        assertEquals(Int.MAX_VALUE, prefs.getEvidenceMonthlyLimit())
    }

    @Test
    fun `reserving and releasing a slot has no effect on the reported usage`() {
        // Both are kept as no-ops so the capture path needs no tier branch. A
        // no-op that started reporting a count again would put a usage meter back
        // on the paywall against a limit that does not exist.
        assertTrue(prefs.tryReserveEvidenceSlot())
        prefs.releaseEvidenceSlot()
        prefs.releaseEvidenceSlot()
        assertEquals(0, prefs.getEvidenceUsage().first)
    }

    @Test
    fun `retention is what premium buys, and premium is never shorter`() {
        prefs.isPremium = false
        assertEquals(SecurityPrefs.FREE_PHOTO_RETENTION_DAYS, prefs.getPhotoRetentionDays())
        assertFalse(prefs.keepsPhotosForever())

        prefs.isPremium = true
        assertEquals(SecurityPrefs.PREMIUM_PHOTO_RETENTION_DAYS, prefs.getPhotoRetentionDays())
        assertTrue(prefs.keepsPhotosForever())
        assertTrue(
            "premium retention must never be shorter than free",
            SecurityPrefs.PREMIUM_PHOTO_RETENTION_DAYS >= SecurityPrefs.FREE_PHOTO_RETENTION_DAYS
        )
    }

    @Test
    fun `an uncapped retention sentinel is never converted to a delete-everything cutoff`() {
        // PhotoCleanupWorker returns early on Long.MAX_VALUE because
        // TimeUnit.DAYS.toMillis(Long.MAX_VALUE) overflows to a negative number,
        // which would turn the cutoff into a future timestamp and expire every
        // photo on the device. This asserts the sentinel is still what the tier
        // reports, so that early return stays reachable.
        prefs.isPremium = true
        assertEquals(Long.MAX_VALUE, prefs.getPhotoRetentionDays())
        assertTrue(java.util.concurrent.TimeUnit.DAYS.toMillis(Long.MAX_VALUE) < 0)
    }

    @Test
    fun `purchase signature check accepts Play-signed data and rejects tampering`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val publicKey = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        val json = """{"productId":"phone_fortress_premium_lifetime","purchaseState":0}"""
        val signature = Signature.getInstance("SHA1withRSA").run {
            initSign(keyPair.private)
            update(json.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(sign(), Base64.NO_WRAP)
        }

        assertTrue(PurchaseVerifier.isSignatureValid(json, signature, publicKey))
        assertFalse(PurchaseVerifier.isSignatureValid(json.replace("0}", "1}"), signature, publicKey))
        assertFalse(PurchaseVerifier.isSignatureValid(json, "", publicKey))
    }
}
