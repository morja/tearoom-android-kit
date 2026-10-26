package one.tearoom.unlock

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Serializes SDK callbacks on the main thread and bounds each request. A canceled call can never
 * publish a late answer. Taken over from Lazy Moves (billing hardening, 16 October 2026).
 */
internal class PlayBillingSession(
    private val context: Application,
    private val owner: OneTimeUnlock,
    private val state: MutableStateFlow<UnlockState>,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    createClient: ((PurchasesUpdatedListener) -> BillingClient)? = null,
) : PurchasesUpdatedListener {
    companion object {
        private const val TAG = "OneTimeUnlock"
        private const val REQUEST_TIMEOUT = 15_000L
        private const val PRICE_MAX_AGE = 5 * 60_000L
        private val RETRY_DELAYS = longArrayOf(2_000L, 4_000L)
    }

    private val config = owner.config
    private val policy = UnlockPolicy(config.productId)
    private val createClient: (PurchasesUpdatedListener) -> BillingClient = createClient ?: { listener ->
        BillingClient.newBuilder(context)
            .setListener(listener)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
    }

    private var client: BillingClient? = null
    private var connectionCall: Call? = null
    private var priceCall: Call? = null
    private var purchasesCall: Call? = null
    private var priceRetry: Runnable? = null
    private var priceRetries = 0
    private var product: ProductDetails? = null
    private var offerToken: String? = null
    private var productLoadedAt = 0L
    private var announceRestore = false
    private val acknowledgements = mutableMapOf<String, Acknowledgement>()

    private inner class Call(onTimeout: () -> Unit) {
        private var active = true
        private val timeout = Runnable { if (finish()) onTimeout() }

        init {
            handler.postDelayed(timeout, REQUEST_TIMEOUT)
        }

        fun finish(): Boolean {
            if (!active) return false
            active = false
            handler.removeCallbacks(timeout)
            return true
        }
    }

    private inner class Acknowledgement {
        var call: Call? = null
        var retry: Runnable? = null
        var retries = 0
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == handler.looper) action() else handler.post { action() }
    }

    private fun cached() = owner.cachedPurchase(context)

    fun refresh() {
        val purchased = cached()
        state.value = state.value.copy(purchased = purchased, unlocked = purchased || config.debugUnlocked(context))
        priceRetry?.let(handler::removeCallbacks)
        priceRetry = null
        priceRetries = 0
        load()
    }

    fun restore() {
        announceRestore = true
        state.value = state.value.copy(message = null)
        refresh()
    }

    private fun load() {
        val current = client
        when {
            current == null || current.connectionState == BillingClient.ConnectionState.CLOSED -> connect()
            connectionCall != null -> Unit
            else -> {
                // API calls trigger the SDK's automatic reconnection after a disconnect.
                queryPurchases(current)
                queryProduct(current)
            }
        }
    }

    private fun connect() {
        discardClient()
        state.value = state.value.copy(loadingPrice = true, priceProblem = null)
        val created = createClient(this)
        client = created
        val call = Call {
            discardClient()
            priceFailed(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)
        }
        connectionCall = call
        created.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) = onMain {
                if (client !== created || !call.finish()) return@onMain
                connectionCall = null
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryPurchases(created)
                    queryProduct(created)
                } else {
                    discardClient()
                    priceFailed(result.responseCode)
                }
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    private fun queryProduct(current: BillingClient) {
        if (priceCall != null) return
        // The shown price stays until the answer replaces it (no flicker on resume); purchase() waits.
        state.value = state.value.copy(loadingPrice = true, priceProblem = null)
        val call = Call {
            // A hung SDK request must not prevent manual retry or reuse a half-open connection.
            discardClient()
            priceFailed(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)
        }
        priceCall = call
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(config.productId).setProductType(BillingClient.ProductType.INAPP).build()
        )).build()
        current.queryProductDetailsAsync(params) { result, details -> onMain {
            if (client !== current || !call.finish()) return@onMain
            priceCall = null
            val candidate = details.productDetailsList.firstOrNull { it.productId == config.productId }
            val offers = candidate?.oneTimePurchaseOfferDetailsList.orEmpty()
            // Older Play responses can expose the legacy offer in both getters without an option ID.
            val offer = offers.firstOrNull { it.isPermanentBuy() }
                ?: candidate?.oneTimePurchaseOfferDetails?.takeIf { it.isPermanentBuy(legacy = true) }
            if (result.responseCode == BillingClient.BillingResponseCode.OK && candidate != null &&
                offer != null && offer.formattedPrice.isNotBlank()) {
                product = candidate
                offerToken = offer.offerToken
                productLoadedAt = SystemClock.elapsedRealtime()
                state.value = state.value.copy(price = offer.formattedPrice, loadingPrice = false, priceProblem = null)
            } else {
                val unavailable = result.responseCode == BillingClient.BillingResponseCode.OK ||
                    result.responseCode == BillingClient.BillingResponseCode.ITEM_UNAVAILABLE
                val missing = details.unfetchedProductList.joinToString { "${it.productId}:${it.statusCode}" }
                Log.w(TAG, "Price query failed: ${result.responseCode}; unfetched=$missing")
                // Retry an empty successful query too, but never invent a price or loop indefinitely.
                priceFailed(result.responseCode, unavailable)
            }
        } }
    }

    private fun ProductDetails.OneTimePurchaseOfferDetails.isPermanentBuy(legacy: Boolean = false) =
        (purchaseOptionId == config.purchaseOptionId || legacy && purchaseOptionId.isNullOrBlank()) &&
            rentalDetails == null && preorderDetails == null

    private fun priceFailed(code: Int, productUnavailable: Boolean = false) {
        forgetProduct()
        if ((retryable(code) || code == BillingClient.BillingResponseCode.OK) && priceRetries < RETRY_DELAYS.size) {
            val retry = Runnable {
                priceRetry = null
                load()
            }
            priceRetry = retry
            state.value = state.value.copy(loadingPrice = true, priceProblem = null)
            handler.postDelayed(retry, RETRY_DELAYS[priceRetries++])
            return
        }
        state.value = state.value.copy(price = null, loadingPrice = false, priceProblem = when {
            code == BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> PriceProblem.BILLING_UNAVAILABLE
            productUnavailable -> PriceProblem.PRODUCT_UNAVAILABLE
            else -> PriceProblem.PLAY_UNAVAILABLE
        })
        if (client == null) finishRestore(false)
        Log.w(TAG, "Price unavailable after ${priceRetries + 1} attempt(s): $code")
    }

    /** Drops the offer used for purchases; the shown price is only removed when loading finally fails. */
    private fun forgetProduct() {
        product = null
        offerToken = null
        productLoadedAt = 0L
    }

    private fun queryPurchases(current: BillingClient) {
        if (purchasesCall != null) return
        val call = Call {
            purchasesCall = null
            finishRestore(false)
        }
        purchasesCall = call
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        current.queryPurchasesAsync(params) { result, purchases -> onMain {
            if (client !== current || !call.finish()) return@onMain
            purchasesCall = null
            val ok = result.responseCode == BillingClient.BillingResponseCode.OK
            val owned = purchases.map { it.toOwned() }
            owner.storePurchase(policy.unlockedAfterQuery(cached(), ok, owned))
            if (ok) {
                state.value = state.value.copy(pending = policy.pending(owned))
                acknowledge(current, owned)
            }
            finishRestore(ok)
        } }
    }

    private fun finishRestore(ok: Boolean) {
        if (!announceRestore) return
        announceRestore = false
        state.value = state.value.copy(message = when {
            !ok -> UnlockMessage.PLAY_UNREACHABLE
            cached() -> UnlockMessage.ACTIVE_HERE
            else -> UnlockMessage.NOT_FOUND
        })
    }

    fun purchase(activity: Activity) {
        if (state.value.busy || state.value.pending || state.value.purchased) return
        val current = client
        val details = product
        if (current == null || !current.isReady || details == null || state.value.loadingPrice ||
            SystemClock.elapsedRealtime() - productLoadedAt >= PRICE_MAX_AGE) {
            state.value = state.value.copy(message = UnlockMessage.PRICE_REFRESHING)
            refresh()
            return
        }
        if (activity.isFinishing || activity.isDestroyed) return
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).apply {
                offerToken?.takeIf { it.isNotBlank() }?.let { setOfferToken(it) }
            }.build()
        )).build()
        state.value = state.value.copy(busy = true, message = null)
        val result = current.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            state.value = state.value.copy(busy = false, message = UnlockMessage.PURCHASE_NOT_STARTED)
            forgetProduct()
            // Refresh stale offers, but only a new user action may start another purchase flow.
            refresh()
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) = onMain {
        state.value = state.value.copy(busy = false)
        val owned = purchases.orEmpty().map { it.toOwned() }
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                // Any query started before this event is an older view of the user's entitlement.
                purchasesCall?.finish()
                purchasesCall = null
                val before = cached()
                val after = policy.unlockedAfterUpdate(before, owned)
                owner.storePurchase(after)
                val pending = policy.pending(owned)
                state.value = state.value.copy(pending = pending, message = when {
                    pending && !after -> UnlockMessage.PENDING
                    after && !before -> UnlockMessage.UNLOCKED
                    else -> null
                })
                client?.let { acknowledge(it, owned) }
                finishRestore(true)
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                purchasesCall?.finish()
                purchasesCall = null
                client?.let { queryPurchases(it) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> state.value = state.value.copy(message = UnlockMessage.PURCHASE_INCOMPLETE)
        }
    }

    private fun acknowledge(current: BillingClient, owned: List<OwnedPurchase>) {
        policy.toAcknowledge(owned).forEach { token ->
            if (token !in acknowledgements) {
                val attempt = Acknowledgement()
                acknowledgements[token] = attempt
                acknowledge(current, token, attempt)
            }
        }
    }

    private fun acknowledge(current: BillingClient, token: String, attempt: Acknowledgement) {
        val call = Call { retryAcknowledgement(current, token, attempt, BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE) }
        attempt.call = call
        current.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()) { result -> onMain {
            if (client !== current || !call.finish()) return@onMain
            attempt.call = null
            if (result.responseCode == BillingClient.BillingResponseCode.OK) acknowledgements.remove(token)
            else retryAcknowledgement(current, token, attempt, result.responseCode)
        } }
    }

    private fun retryAcknowledgement(current: BillingClient, token: String, attempt: Acknowledgement, code: Int) {
        attempt.call = null
        if (retryable(code) && attempt.retries < RETRY_DELAYS.size) {
            val retry = Runnable {
                attempt.retry = null
                if (client === current) acknowledge(current, token, attempt)
            }
            attempt.retry = retry
            handler.postDelayed(retry, RETRY_DELAYS[attempt.retries++])
        } else {
            acknowledgements.remove(token)
            Log.w(TAG, "Purchase acknowledgement failed: $code")
        }
    }

    private fun retryable(code: Int) = code in setOf(
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED, BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.NETWORK_ERROR, BillingClient.BillingResponseCode.ERROR
    )

    private fun discardClient() {
        connectionCall?.finish()
        connectionCall = null
        priceCall?.finish()
        priceCall = null
        purchasesCall?.finish()
        purchasesCall = null
        acknowledgements.values.forEach { attempt ->
            attempt.call?.finish()
            attempt.retry?.let(handler::removeCallbacks)
        }
        acknowledgements.clear()
        val previous = client
        client = null
        previous?.endConnection()
        forgetProduct()
    }

    /** Only the owner disposes the session; closing never clears a saved purchase. */
    fun close() {
        priceRetry?.let(handler::removeCallbacks)
        priceRetry = null
        discardClient()
        state.value = state.value.copy(loadingPrice = false, busy = false)
    }

    private fun Purchase.toOwned(): OwnedPurchase {
        val verified = config.publicKeyBase64?.let { key ->
            PurchaseSignature.verify(key, originalJson, signature).also { if (!it) Log.w(TAG, "Purchase signature does not match") }
        } ?: true
        return OwnedPurchase(products, purchaseState == Purchase.PurchaseState.PURCHASED, isAcknowledged, purchaseToken, verified)
    }
}
