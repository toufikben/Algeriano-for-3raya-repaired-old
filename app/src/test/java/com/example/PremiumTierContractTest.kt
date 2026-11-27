package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.SecurityPrefs
import org.junit.Assert.assertEquals
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
        // SecurityPrefs is a process singleton under Robolectric; a leftover
        // count from another test class would skew the limit assertions.
        repeat(prefs.getEvidenceUsage().first) { prefs.releaseEvidenceSlot() }
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
        // PremiumScreen renders SecurityPrefs.FREE_EVIDENCE_MONTHLY_LIMIT in the
        // Free column and PREMIUM_EVIDENCE_MONTHLY_LIMIT in the Premium column.
        // These two assertions are what make that rendering truthful.
        prefs.isPremium = false
        assertEquals(
            SecurityPrefs.FREE_EVIDENCE_MONTHLY_LIMIT,
            prefs.getEvidenceMonthlyLimit()
        )
        assertEquals(
            SecurityPrefs.FREE_HISTORY_LIMIT,
            prefs.getHistoryLimit()
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
    fun `an uncapped tier still reports a usable pair for the usage bar`() {
        // PremiumScreen hides the progress card when the limit is Int.MAX_VALUE,
        // because a bar against that number reads as permanently empty. The pair
        // must still be returned intact for the callers that do show it.
        prefs.isPremium = true
        val (used, limit) = prefs.getEvidenceUsage()
        assertTrue("used count must never be negative", used >= 0)
        assertEquals(Int.MAX_VALUE, limit)
    }
}
