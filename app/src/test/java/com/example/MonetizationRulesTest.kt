package com.example

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.billing.PurchaseVerifier
import com.example.data.SecurityEventStatus
import com.example.data.SecurityPrefs
import com.example.worker.EvidenceLimitNotice
import org.junit.After
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
        resetAllowance()
    }

    @After
    fun tearDown() {
        prefs.isPremium = false
        resetAllowance()
    }

    // SecurityPrefs is a process singleton under Robolectric; start each test with
    // an untouched allowance.
    private fun resetAllowance() {
        context.getSharedPreferences("intruder_security_prefs", Context.MODE_PRIVATE).edit()
            .remove("key_evidence_month_counter")
            .remove("key_evidence_month_index")
            .commit()
    }

    @Test
    fun `free allowance stops at three reserved captures`() {
        repeat(SecurityPrefs.FREE_EVIDENCE_MONTHLY_LIMIT) {
            assertTrue(prefs.tryReserveEvidenceSlot())
        }
        assertFalse("a fourth capture must be refused", prefs.tryReserveEvidenceSlot())
        assertEquals(3 to 3, prefs.getEvidenceUsage())
    }

    @Test
    fun `a capture that produced nothing gives its slot back`() {
        assertTrue(prefs.tryReserveEvidenceSlot())
        prefs.releaseEvidenceSlot()
        assertEquals(0, prefs.getEvidenceUsage().first)

        // Never below zero, even if released twice.
        prefs.releaseEvidenceSlot()
        assertEquals(0, prefs.getEvidenceUsage().first)
    }

    @Test
    fun `premium allowance is the published fifty`() {
        prefs.isPremium = true
        repeat(SecurityPrefs.PREMIUM_EVIDENCE_MONTHLY_LIMIT) {
            assertTrue(prefs.tryReserveEvidenceSlot())
        }
        assertFalse(prefs.tryReserveEvidenceSlot())
    }

    @Test
    fun `extended retention is what premium buys`() {
        prefs.isPremium = false
        assertEquals(7L, prefs.getPhotoRetentionDays())
        prefs.isPremium = true
        assertEquals(30L, prefs.getPhotoRetentionDays())
    }

    @Test
    fun `a spent allowance still reports the attempt exactly once`() {
        val event = prefs.enqueueSecurityEvent()
        val logsBefore = prefs.getLogs().size

        assertTrue(EvidenceLimitNotice.handleExhausted(context, event.id))
        assertFalse("a second caller must not notify again", EvidenceLimitNotice.handleExhausted(context, event.id))

        assertEquals(SecurityEventStatus.FAILED, prefs.getSecurityEvent(event.id)?.status)
        assertEquals("the attempt is logged, not dropped", logsBefore + 1, prefs.getLogs().size)
        assertFalse(prefs.getLogs().first().photoCaptured)
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
