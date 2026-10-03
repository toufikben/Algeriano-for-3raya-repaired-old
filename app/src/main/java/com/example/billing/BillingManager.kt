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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Outcomes the paywall reports to the user. A cancelled purchase is not one of them. */
enum class PurchaseEvent { PURCHASED, PENDING, FAILED, RESTORED, NOTHING_TO_RESTORE }

/**
 * Google Play Billing manager for Phone Fortress Premium lifetime purchase.
 *
 * Product ID: phone_fortress_premium_lifetime
 * Price: whatever Play reports for the user's market and currency
 * Type: One-time non-consumable lifetime entitlement
 *
 * One instance per process: the purchase listener has to outlive any screen, because
 * Play delivers a purchase that completes after the paywall (or its ViewModel) is gone.
 */
class BillingManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PhoneFortress::BillingManager"
        const val PREMIUM_PRODUCT_ID = "phone_fortress_premium_lifetime"

        @Volatile
        private var INSTANCE: BillingManager? = null

        fun getInstance(context: Context): BillingManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: BillingManager(context.applicationContext).also { INSTANCE = it }
            }

        /** Unit tests build a manager without a Play client (the offline path). */
        internal fun createForTest(context: Context): BillingManager = BillingManager(context)
    }

    private var billingClient: BillingClient? = null
    private var productDetails: ProductDetails? = null
    private val connectMutex = Mutex()
    private val billingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    private val _purchaseEvents = MutableSharedFlow<PurchaseEvent>(extraBufferCapacity = 4)
    val purchaseEvents: SharedFlow<PurchaseEvent> = _purchaseEvents.asSharedFlow()

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
                billingScope.launch {
                    purchases?.forEach { purchase -> handleNewPurchase(purchase) }
                }
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                // Bought earlier (another device, a reinstall): read it back from Play.
                billingScope.launch {
                    queryPremiumEntitlement()
                    if (_isPremium.value) _purchaseEvents.tryEmit(PurchaseEvent.RESTORED)
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.d(TAG, "User canceled purchase")
            }
            else -> {
                Log.e(TAG, "Purchase error ${billingResult.responseCode}: ${billingResult.debugMessage}")
                _purchaseEvents.tryEmit(PurchaseEvent.FAILED)
            }
        }
    }

    /**
     * Creates the Play client once per process and reads the entitlement and price.
     * Safe to call from every screen that needs Play; only the first call connects.
     */
    suspend fun initialize() {
        try {
            if (billingClient == null) {
                synchronized(this) {
                    if (billingClient == null) {
                        billingClient = BillingClient.newBuilder(context)
                            .setListener(purchasesUpdatedListener)
                            .enablePendingPurchases(
                                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
                            )
                            // Billing 8+: a call made while Play's service is down
                            // reconnects by itself, replacing a hand-written retry loop.
                            .enableAutoServiceReconnection()
                            .build()
                    }
                }
            }
            refreshFromPlay()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize billing", e)
        }
    }

    /**
     * Makes sure the first setup ran. After that, automatic service reconnection
     * handles a Play process that died; this only re-runs setup when the first one
     * never succeeded (Play missing, updating, or signed out at app start).
     */
    private suspend fun ensureConnected(): Boolean = connectMutex.withLock {
        val client = billingClient ?: return@withLock false
        if (client.isReady) return@withLock true
        suspendCoroutine { continuation ->
            var resumed = false
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    val ok = billingResult.responseCode == BillingClient.BillingResponseCode.OK
                    if (!ok) Log.e(TAG, "Billing setup failed: ${billingResult.debugMessage}")
                    // The listener may be called again on a later reconnect; the
                    // continuation can only be resumed once.
                    if (!resumed) {
                        resumed = true
                        continuation.resume(ok)
                    }
                }

                override fun onBillingServiceDisconnected() {
                    // Automatic service reconnection is enabled.
                    Log.w(TAG, "Billing disconnected")
                }
            })
        }
    }

    /**
     * Ask Play for the current state: on resume, when the paywall opens, on restore and
     * on the unlock tap. Without a connection the cached entitlement stays untouched.
     */
    suspend fun refreshFromPlay() {
        if (!ensureConnected()) return
        queryPremiumEntitlement()
        queryProductDetails()
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

        // A verified PURCHASED record is the entitlement. Acknowledgement is a separate
        // duty: Google refunds anything left unacknowledged for three days, so it is
        // retried here on every query, but a failed acknowledge (no network) must not
        // take Premium away from a user who has just paid.
        var entitled = false
        purchases?.forEach { purchase ->
            if (!purchase.products.contains(PREMIUM_PRODUCT_ID) ||
                purchase.purchaseState != Purchase.PurchaseState.PURCHASED ||
                !PurchaseVerifier.isSignatureValid(purchase.originalJson, purchase.signature)
            ) {
                return@forEach
            }
            entitled = true
            if (!purchase.isAcknowledged) acknowledge(purchase.purchaseToken)
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
     * Query product details from Play Store to get localized price. Suspends until
     * Play answers, so a caller can use the result right after it returns.
     */
    suspend fun queryProductDetails() {
        val client = billingClient ?: return
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

            suspendCoroutine<Unit> { continuation ->
                client.queryProductDetailsAsync(params) { billingResult, result ->
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        // Billing 9 hands back a result wrapper, not the list: products Play
                        // could not find are reported separately as unfetched.
                        productDetails = result.productDetailsList?.firstOrNull()
                        _localizedPrice.value = productDetails?.oneTimePurchaseOfferDetails?.formattedPrice
                        Log.d(TAG, "Product found: ${_localizedPrice.value}")
                    } else {
                        Log.e(TAG, "Query product failed: ${billingResult.debugMessage}")
                    }
                    continuation.resume(Unit)
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
        if (productDetails == null) {
            // The tap means the user wants the product now, so ask Play once more
            // instead of leaving a stale "premium unavailable" screen.
            refreshFromPlay()
        }
        val details = productDetails
        val offerToken = details?.oneTimePurchaseOfferDetails?.offerToken
        if (details == null || offerToken == null) {
            Log.e(TAG, "Product not loaded yet")
            _purchaseEvents.tryEmit(PurchaseEvent.FAILED)
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
            if (result == null || result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.e(TAG, "launchBillingFlow: ${result?.debugMessage}")
                if (result?.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                    restorePurchases()
                } else {
                    _purchaseEvents.tryEmit(PurchaseEvent.FAILED)
                }
                return
            }
            Log.d(TAG, "Purchase flow launched")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch purchase", e)
            _purchaseEvents.tryEmit(PurchaseEvent.FAILED)
        }
    }

    /**
     * Restore previous purchases (for reinstall/device change) and tell the user
     * whether anything was found.
     */
    suspend fun restorePurchases() {
        Log.d(TAG, "Restoring purchases...")
        if (!ensureConnected()) {
            _purchaseEvents.tryEmit(PurchaseEvent.FAILED)
            return
        }
        queryPremiumEntitlement()
        _purchaseEvents.tryEmit(
            if (_isPremium.value) PurchaseEvent.RESTORED else PurchaseEvent.NOTHING_TO_RESTORE
        )
    }

    /**
     * A purchase Play just delivered. Premium is granted once the purchase is verified
     * as PURCHASED; acknowledgement follows and is retried by the next query if it fails.
     */
    private suspend fun handleNewPurchase(purchase: Purchase) {
        if (!purchase.products.contains(PREMIUM_PRODUCT_ID)) return

        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                if (!PurchaseVerifier.isSignatureValid(purchase.originalJson, purchase.signature)) {
                    Log.e(TAG, "Purchase signature did not verify - not granting premium")
                    _purchaseEvents.tryEmit(PurchaseEvent.FAILED)
                    return
                }
                setPremium(true)
                _purchaseEvents.tryEmit(PurchaseEvent.PURCHASED)
                if (!purchase.isAcknowledged) acknowledge(purchase.purchaseToken)
            }
            Purchase.PurchaseState.PENDING -> {
                // enablePendingPurchases() is on: the listener fires again when the
                // payment completes, and queryPremiumEntitlement picks it up on resume.
                Log.d(TAG, "Purchase pending - not granting premium")
                _purchaseEvents.tryEmit(PurchaseEvent.PENDING)
            }
            else -> {
                Log.w(TAG, "Purchase in unspecified state - not granting premium")
            }
        }
    }
}
