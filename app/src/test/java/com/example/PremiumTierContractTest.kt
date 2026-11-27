package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.SecurityPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the one thing the paywall must never get wrong again: the numbers it
 * prints have to be the numbers the enforcement paths apply.
 *
 * The previous paywall hard-coded "3" and "5" and ticked "Photo evidence" in the
 * Free column while `tryReserveEvidenceSlot` refused the fourth capture. Users
 * were sold a feature the app then withheld, which is a Play misrepresentation
 * risk, not just a copy bug. PremiumScreen now reads these constants directly,
 * so the invariant is: whatever the constants say, both tiers stay coherent.
 *
 * After the Plan A repricing the only things left to sell are retention and the
 * auto-restarting countdown, so this file pins those two.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PremiumTierContractTest {

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
    fun `premium allowance is never smaller than the free one`() {
        assertTrue(
            "a paid tier with a tighter cap than the free tier is a refund waiting to happen",
            SecurityPrefs.PREMIUM_EVIDENCE_MONTHLY_LIMIT >= SecurityPrefs.FREE_EVIDENCE_MONTHLY_LIMIT
        )
    }

    @Test
    fun `premium retention is never shorter than the free one`() {
        assertTrue(
            SecurityPrefs.PREMIUM_PHOTO_RETENTION_DAYS >= SecurityPrefs.FREE_PHOTO_RETENTION_DAYS
        )
    }

    @Test
    fun `premium history is never shorter than the free one`() {
        assertTrue(
            SecurityPrefs.PREMIUM_HISTORY_LIMIT >= SecurityPrefs.FREE_HISTORY_LIMIT
        )
    }

    @Test
    fun `the limit the paywall prints is the limit the enforcement path applies`() {
        // PremiumScreen renders these constants in the retention row and the
        // capture rows, so these assertions are what make that rendering truthful.
        prefs.isPremium = false
        assertEquals(
            SecurityPrefs.FREE_EVIDENCE_MONTHLY_LIMIT,
            prefs.getEvidenceMonthlyLimit()
        )
        assertEquals(
            SecurityPrefs.FREE_HISTORY_LIMIT,
            prefs.getHistoryLimit()
        )
        assertEquals(
            SecurityPrefs.FREE_PHOTO_RETENTION_DAYS,
            prefs.getPhotoRetentionDays()
        )

        prefs.isPremium = true
        assertEquals(
            SecurityPrefs.PREMIUM_EVIDENCE_MONTHLY_LIMIT,
            prefs.getEvidenceMonthlyLimit()
        )
        assertEquals(
            SecurityPrefs.PREMIUM_HISTORY_LIMIT,
            prefs.getHistoryLimit()
        )
        assertEquals(
            SecurityPrefs.PREMIUM_PHOTO_RETENTION_DAYS,
            prefs.getPhotoRetentionDays()
        )
    }

    @Test
    fun `no tier caps capture, so the paywall cannot claim it does`() {
        // Both tiers report Int.MAX_VALUE. The paywall's capture rows are ticks on
        // both columns, which is only honest while this holds.
        prefs.isPremium = false
        assertEquals(Int.MAX_VALUE, prefs.getEvidenceMonthlyLimit())
        assertTrue(prefs.canCreateEvidenceEvent())
        assertTrue(prefs.tryReserveEvidenceSlot())

        prefs.isPremium = true
        assertEquals(Int.MAX_VALUE, prefs.getEvidenceMonthlyLimit())
        assertTrue(prefs.canCreateEvidenceEvent())
        assertTrue(prefs.tryReserveEvidenceSlot())
    }

    @Test
    fun `the usage meter is unreachable because both tiers are uncapped`() {
        // PremiumScreen hides the progress card when the limit is Int.MAX_VALUE.
        // If a tier ever regained a finite cap, the card would come back - so this
        // test documents which state we are in rather than guessing.
        prefs.isPremium = false
        val (used, limit) = prefs.getEvidenceUsage()
        assertTrue("used count must never be negative", used >= 0)
        assertEquals(Int.MAX_VALUE, limit)
    }

    @Test
    fun `premium is the only tier that keeps photos forever`() {
        prefs.isPremium = false
        assertFalse(prefs.keepsPhotosForever())
        prefs.isPremium = true
        assertTrue(prefs.keepsPhotosForever())
    }
}
