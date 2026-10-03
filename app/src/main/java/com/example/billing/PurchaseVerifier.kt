package com.example.billing

import android.util.Base64
import android.util.Log
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * On-device check that a purchase record was signed by Google Play for this app.
 *
 * Google's recommended verification is server-side (Play Developer API,
 * Purchases.products:get). This app has no backend, so the signature check is the
 * layer available here: it rejects purchase JSON forged by a patched billing
 * service, which a plain "purchaseState == PURCHASED" check accepts.
 */
object PurchaseVerifier {
    private const val TAG = "PhoneFortress::PurchaseVerifier"
    private const val KEY_ALGORITHM = "RSA"
    private const val SIGNATURE_ALGORITHM = "SHA1withRSA"

    /**
     * Play Console → Monetize with Play → Monetization setup → Licensing:
     * "Base64-encoded RSA public key" for com.toufikbendjeddah.phonefortress.
     * It is a public key, not a secret. Empty means "not configured yet".
     */
    const val PLAY_LICENSE_PUBLIC_KEY =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwcGfC0MovLSLGIfbe+zPKsl0kHNOyV0ioqfrmTF/LZE6ZftkCoL3nvq17sGv9rfAGHaWV7OB9s7TKCUKvf1862Dx11CGeG87L+pDmkFTN6kR/ypdg9PYsODi1GDnpHWazowUUhcn6b07KhtMZIaudjOWRw8m+oft/iLgMtRQjGLUS7NTo1qWsc74zrV19q7VB8IZUyQmjudTjEgMfzA6SBs9pQ1uQowTr0Yt2nwrwolsYZho2oQPD3BIVDPlgKnrrWixJciI5GvyyX8EST8SC2w3CEYvM9+bR0HrCUm2S/u4GP4AQxhdHQtbHaNn24Mlj5JkYQ9DpnRG3pUKt0QhgwIDAQAB"

    fun isSignatureValid(
        signedData: String,
        signature: String,
        base64PublicKey: String = PLAY_LICENSE_PUBLIC_KEY
    ): Boolean {
        if (base64PublicKey.isBlank()) {
            Log.w(TAG, "Play license key not configured - purchase signature not checked")
            return true
        }
        if (signedData.isBlank() || signature.isBlank()) return false
        return try {
            val key = decodePublicKey(base64PublicKey)
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(key)
            verifier.update(signedData.toByteArray(Charsets.UTF_8))
            verifier.verify(Base64.decode(signature, Base64.DEFAULT))
        } catch (e: Exception) {
            Log.e(TAG, "Purchase signature check failed", e)
            false
        }
    }

    private fun decodePublicKey(base64PublicKey: String): PublicKey {
        val decoded = Base64.decode(base64PublicKey, Base64.DEFAULT)
        return KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(decoded))
    }
}
