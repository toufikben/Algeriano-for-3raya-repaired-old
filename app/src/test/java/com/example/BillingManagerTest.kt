package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.billing.BillingManager
import com.example.data.SecurityPrefs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BillingManagerTest {
    private lateinit var context: Context
    private lateinit var prefs: SecurityPrefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = SecurityPrefs.getInstance(context)
    }

    @After
    fun tearDown() {
        // SecurityPrefs is a process singleton under Robolectric.
        prefs.isPremium = false
    }

    // The entitlement cache is seeded before Play is contacted, so these tests build
    // the manager after writing the cached value it reads at construction.
    @Test
    fun `an unreachable Play keeps the last confirmed entitlement`() {
        prefs.isPremium = true
        val manager = BillingManager(context)

        // No initialize(), so billingClient is null: this is the offline path.
        runBlocking { manager.queryPremiumEntitlement() }

        assertTrue("a failed query must not downgrade a paying user to Free", manager.isPremium.value)
        assertTrue(prefs.isPremium)
    }

    @Test
    fun `no price exists before Play reports one`() {
        val manager = BillingManager(context)

        runBlocking { manager.queryProductDetails() }

        assertNull(
            "the unlock button must not fall back to an invented price",
            manager.localizedPrice.value
        )
    }

    @Test
    fun `a resume with no Play connection changes nothing`() {
        prefs.isPremium = true
        val manager = BillingManager(context)

        // onResume and the paywall both go through this path; with no client it must
        // neither crash nor downgrade the user nor guess a price.
        runBlocking { manager.refreshFromPlay() }

        assertTrue(manager.isPremium.value)
        assertTrue(prefs.isPremium)
        assertNull(manager.localizedPrice.value)
    }
}
