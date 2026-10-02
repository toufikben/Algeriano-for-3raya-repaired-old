package com.example.billing

import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.example.data.SecurityPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Google Play Billing manager for Phone Fortress Premium lifetime purchase.
 * 
 * Product ID: phone_fortress_premium_lifetime
 * Price: $4.99 USD (localized by Play Store)
 * Type: One-time non-consumable lifetime entitlement
 */
class BillingManager(private val context: Context) {

    companion object {
        private const val TAG = "PhoneFortress::BillingManager"
        const val PREMIUM_PRODUCT_ID = "phone_fortress_premium_lifetime"
    }

    private var billingClient: BillingClient? = null
    private var productDetails: ProductDetails? = null

    // A flow, not a field: the price usually lands after the Premium screen is
    // already open, and a snapshot taken at that moment would leave the unlock
    // button disabled forever.
    private val _localizedPrice = MutableStateFlow<String?>(null)
    val localizedPrice: StateFlow<String?> = _localizedPrice.asStateFlow()

    // Seeded from the last Google-confirmed value: an unknown entitlement must not
    // be reported as "free", or an offline premium user would lose their cache
    // before the first Play response arrives.
    private val _isPremium = MutableStateFlow(readCachedPremium())
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    private val _billingAvailable = MutableStateFlow(true)
    val billingAvailable: StateFlow<Boolean> = _billingAvailable.asStateFlow()

    private val _purchaseError = MutableStateFlow<String?>(null)
    val purchaseError: StateFlow<String?> = _purchaseError.asStateFlow()

    private fun readCachedPremium(): Boolean = try {
        SecurityPrefs.getInstance(context).isPremium
    } catch (e: Exception) {
        false
    }

    /** BillingManager is the only writer of the cached entitlement. */
    private fun setPremium(entitled: Boolean) {
        _isPremium.value = entitled
        try {
            SecurityPrefs.getInstance(context).isPremium = entitled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache premium state", e)
        }
    }

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                Log.d(TAG, "Purchase updated: ${purchases?.size} purchases")
                purchases?.forEach { purchase ->
                    handlePurchase(purchase)
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.d(TAG, "User canceled purchase")
                _purchaseError.value = "cancelled"
            }
            else -> {
                Log.e(TAG, "Purchase error: ${billingResult.debugMessage}")
                _purchaseError.value = billingResult.debugMessage
            }
        }
    }

    /**
     * Initialize billing client and check premium entitlement.
     * Call this from Application.onCreate() or when needed.
     */
    suspend fun initialize() {
        try {
            billingClient = BillingClient.newBuilder(context)
                .setListener(purchasesUpdatedListener)
                .enablePendingPurchases(
                    PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
                )
                .build()

            connectToBillingService()
            queryPremiumEntitlement()
            queryProductDetails()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize billing", e)
            _billingAvailable.value = false
        }
    }

    private suspend fun connectToBillingService() {
        suspendCoroutine<Unit> { continuation ->
            billingClient?.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    when (billingResult.responseCode) {
                        BillingClient.BillingResponseCode.OK -> {
                            Log.d(TAG, "Billing connected")
                            _billingAvailable.value = true
                            continuation.resume(Unit)
                        }
                        else -> {
                            Log.e(TAG, "Billing setup failed: ${billingResult.debugMessage}")
                            _billingAvailable.value = false
                            continuation.resume(Unit)
                        }
                    }
                }

                override fun onBillingServiceDisconnected() {
                    Log.w(TAG, "Billing disconnected")
                    _billingAvailable.value = false
                }
            }) ?: continuation.resume(Unit)
        }
    }

    /**
     * Query user's premium entitlement from Play Store.
     * This is the PRIMARY source of truth for premium status.
     * Updates SecurityPrefs cache after verification.
     */
    suspend fun queryPremiumEntitlement() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        val outcome = suspendCoroutine<Pair<BillingResult, List<Purchase>?>?> { continuation ->
            val client = billingClient
            if (client == null) {
                continuation.resume(null)
            } else {
                client.queryPurchasesAsync(params) { billingResult, purchases ->
                    continuation.resume(billingResult to purchases)
                }
            }
        }

        if (outcome == null) {
            Log.w(TAG, "Billing client unavailable - keeping cached entitlement")
            return
        }
        val (billingResult, purchases) = outcome
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "Query purchases failed: ${billingResult.debugMessage} - keeping cached entitlement")
            return
        }

        // A PURCHASED-but-unacknowledged record is not yet an entitlement: Google
        // refunds and revokes anything left unacknowledged after three days, so it
        // must be acknowledged here rather than left to the update listener, which
        // never fires when the process died between purchase and callback.
        var entitled = false
        purchases?.forEach { purchase ->
            if (!purchase.products.contains(PREMIUM_PRODUCT_ID) ||
                purchase.purchaseState != Purchase.PurchaseState.PURCHASED
            ) {
                return@forEach
            }
            entitled = purchase.isAcknowledged || acknowledge(purchase.purchaseToken)
        }

        setPremium(entitled)
        Log.d(TAG, "Premium entitlement verified from Google Play: $entitled")
    }

    private suspend fun acknowledge(purchaseToken: String): Boolean =
        suspendCoroutine { continuation ->
            val client = billingClient
            if (client == null) {
                continuation.resume(false)
                return@suspendCoroutine
            }
            val ackParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchaseToken)
                .build()
            client.acknowledgePurchase(ackParams) { result ->
                val acknowledged = result.responseCode == BillingClient.BillingResponseCode.OK
                if (acknowledged) {
                    Log.d(TAG, "Purchase acknowledged")
                } else {
                    Log.e(TAG, "Acknowledge failed: ${result.debugMessage}")
                }
                continuation.resume(acknowledged)
            }
        }

    /**
     * Query product details from Play Store to get localized price.
     */
    suspend fun queryProductDetails() {
        try {
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(PREMIUM_PRODUCT_ID)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
                    )
                )
                .build()

            billingClient?.queryProductDetailsAsync(params) { billingResult, result ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    // Billing 9 hands back a result wrapper, not the list: products Play
                    // could not find are reported separately as unfetched.
                    productDetails = result.productDetailsList?.firstOrNull()
                    _localizedPrice.value = productDetails?.oneTimePurchaseOfferDetails?.formattedPrice
                    Log.d(TAG, "Product found: ${_localizedPrice.value}")
                } else {
                    Log.e(TAG, "Query product failed: ${billingResult.debugMessage}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying product", e)
        }
    }

    /**
     * Launch purchase flow for premium lifetime upgrade.
     * Must be called from an Activity context.
     */
    suspend fun launchPurchase(activity: androidx.activity.ComponentActivity) {
        val details = productDetails
        val offerToken = details?.oneTimePurchaseOfferDetails?.offerToken
        if (details == null || offerToken == null) {
            Log.e(TAG, "Product not loaded yet")
            _purchaseError.value = "Product not available"
            return
        }

        try {
            val flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(details)
                            .setOfferToken(offerToken)
                            .build()
                    )
                )
                .build()

            // Billing 9 removed BillingFlowParams.intentSender: launchBillingFlow starts
            // the Play screen itself and reports only whether that request went out.
            val result = billingClient?.launchBillingFlow(activity, flowParams)
            if (result != null && result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.e(TAG, "launchBillingFlow: ${result.debugMessage}")
                _purchaseError.value = "Cannot start purchase"
            }
            Log.d(TAG, "Purchase flow launched")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch purchase", e)
            _purchaseError.value = "Cannot start purchase"
        }
    }

    /**
     * Restore previous purchases (for reinstall/device change).
     * Calls queryPremiumEntitlement internally.
     */
    suspend fun restorePurchases() {
        Log.d(TAG, "Restoring purchases...")
        queryPremiumEntitlement()
    }

    /**
     * Handle a purchase and acknowledge it.
     * Called straight from the Billing callback, so it must stay non-suspending.
     * Premium is granted only once acknowledgement has succeeded.
     */
    private fun handlePurchase(purchase: Purchase) {
        if (!purchase.products.contains(PREMIUM_PRODUCT_ID)) return

        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                if (purchase.isAcknowledged) {
                    Log.d(TAG, "Purchase already acknowledged - granting premium")
                    setPremium(true)
                } else {
                    val ackParams = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()

                    billingClient?.acknowledgePurchase(ackParams) { billingResult ->
                        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                            Log.d(TAG, "Purchase acknowledged - granting premium")
                            setPremium(true)
                        } else {
                            // An unacknowledged purchase is refunded and revoked by
                            // Google after three days, so it is not an entitlement yet.
                            Log.e(TAG, "Acknowledge failed: ${billingResult.debugMessage}")
                        }
                    }
                }
            }
            Purchase.PurchaseState.PENDING -> {
                // enablePendingPurchases() is on: the receipt only becomes PURCHASED
                // later, and queryPremiumEntitlement picks it up on the next start.
                Log.d(TAG, "Purchase pending - not granting premium")
            }
            Purchase.PurchaseState.UNSPECIFIED_STATE -> {
                Log.w(TAG, "Purchase in unspecified state - not granting premium")
            }
        }
    }

    /**
     * Clear error state.
     */
    fun clearError() {
        _purchaseError.value = null
    }

    /**
     * End billing connection.
     */
    fun destroy() {
        try {
            billingClient?.endConnection()
            billingClient = null
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying billing", e)
        }
    }
}
