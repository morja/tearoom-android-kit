package one.tearoom.unlock

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.*
import io.mockk.*
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Duration
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class OneTimeUnlockTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var billing: BillingClient
    private lateinit var state: StateFlow<UnlockState>
    private lateinit var session: PlayBillingSession
    private lateinit var nextClient: BillingClient
    private lateinit var unlock: OneTimeUnlock

    private companion object {
        const val PRODUCT = "plus_unlock"
    }

    @Before fun prepare() {
        context.getSharedPreferences("plus", 0).edit().clear().commit()
        billing = client()
        nextClient = billing
        start(UnlockConfig(productId = PRODUCT, prefsName = "plus"))
    }

    private fun start(config: UnlockConfig) {
        if (::session.isInitialized) session.close()
        unlock = OneTimeUnlock(context, config, Handler(Looper.getMainLooper())) { nextClient }
        state = unlock.state
        session = unlock.sessionForTest()
    }

    @After fun cleanup() {
        session.close()
    }

    @Test fun `refresh calls APIs after disconnection to trigger automatic reconnection`() {
        session.refresh()
        every { billing.isReady } returns false
        every { billing.connectionState } returns BillingClient.ConnectionState.DISCONNECTED

        session.refresh()

        verify(exactly = 2) { billing.queryPurchasesAsync(any(), any()) }
        verify(exactly = 2) { billing.queryProductDetailsAsync(any(), any()) }
        verify(exactly = 1) { billing.startConnection(any()) }
        assertEquals("€2.99", state.value.price)
    }

    @Test fun `empty product response retries twice then gives an actionable error`() {
        respondWithProducts()

        session.refresh()
        advance(60)

        verify(exactly = 3) { billing.queryProductDetailsAsync(any(), any()) }
        assertNull(state.value.price)
        assertFalse(state.value.loadingPrice)
        assertEquals(PriceProblem.PRODUCT_UNAVAILABLE, state.value.priceProblem)
    }

    @Test fun `unanswered price queries release loading and permit manual retry`() {
        every { billing.queryProductDetailsAsync(any(), any()) } just Runs

        session.refresh()
        advance(60)

        assertFalse(state.value.loadingPrice)
        assertEquals(PriceProblem.PLAY_UNAVAILABLE, state.value.priceProblem)
        verify(exactly = 3) { billing.queryProductDetailsAsync(any(), any()) }

        respondWithProducts(priceProduct())
        session.refresh()
        assertEquals("€2.99", state.value.price)
        assertNull(state.value.priceProblem)
    }

    @Test fun `transient network failure recovers automatically`() {
        var requests = 0
        every { billing.queryProductDetailsAsync(any(), any()) } answers {
            requests++
            secondArg<ProductDetailsResponseListener>().onProductDetailsResponse(
                result(if (requests == 1) BillingClient.BillingResponseCode.NETWORK_ERROR else 0),
                products(*if (requests == 1) emptyArray() else arrayOf(priceProduct()))
            )
        }

        session.refresh()
        assertTrue(state.value.loadingPrice)
        advance(2)

        assertEquals(2, requests)
        assertEquals("€2.99", state.value.price)
        assertFalse(state.value.loadingPrice)
    }

    @Test fun `billing unavailable does not retry indefinitely`() {
        every { billing.queryProductDetailsAsync(any(), any()) } answers {
            secondArg<ProductDetailsResponseListener>().onProductDetailsResponse(
                result(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE), products())
        }

        session.refresh()
        advance(60)

        verify(exactly = 1) { billing.queryProductDetailsAsync(any(), any()) }
        assertFalse(state.value.loadingPrice)
        assertEquals(PriceProblem.BILLING_UNAVAILABLE, state.value.priceProblem)
    }

    @Test fun `a replaced clients late response cannot remove the current price`() {
        val old = slot<ProductDetailsResponseListener>()
        every { billing.queryProductDetailsAsync(any(), capture(old)) } just Runs
        session.refresh()
        every { billing.connectionState } returns BillingClient.ConnectionState.CLOSED
        nextClient = client()

        session.refresh()
        old.captured.onProductDetailsResponse(result(), products())

        assertEquals("€2.99", state.value.price)
        assertFalse(state.value.loadingPrice)
    }

    @Test fun `late response after request timeout is ignored`() {
        val old = slot<ProductDetailsResponseListener>()
        every { billing.queryProductDetailsAsync(any(), capture(old)) } just Runs
        session.refresh()
        val lateResponse = old.captured
        advance(15)
        nextClient = client()
        advance(2)
        lateResponse.onProductDetailsResponse(result(), products())

        assertEquals("€2.99", state.value.price)
        assertFalse(state.value.loadingPrice)
    }

    @Test fun `connection timeout is bounded and late setup cannot overwrite new state`() {
        val setup = slot<BillingClientStateListener>()
        every { billing.startConnection(capture(setup)) } just Runs
        session.refresh()
        val lateSetup = setup.captured
        advance(60)

        assertFalse(state.value.loadingPrice)
        verify(exactly = 3) { billing.startConnection(any()) }
        nextClient = client()
        session.refresh()
        lateSetup.onBillingSetupFinished(result())

        assertEquals("€2.99", state.value.price)
        verify(exactly = 0) { billing.queryProductDetailsAsync(any(), any()) }
    }

    @Test fun `ownership query started before purchase cannot revoke the new purchase`() {
        val response = slot<PurchasesResponseListener>()
        every { billing.queryPurchasesAsync(any(), capture(response)) } just Runs
        session.refresh()
        session.onPurchasesUpdated(result(), mutableListOf(purchase()))
        assertTrue(state.value.purchased)

        response.captured.onQueryPurchasesResponse(result(), emptyList())

        assertTrue(state.value.purchased)
        assertTrue(unlock.cachedPurchase(context))
    }

    @Test fun `failed ownership and price queries preserve a cached purchase`() {
        context.getSharedPreferences("plus", 0).edit().putBoolean("unlocked", true).commit()
        every { billing.queryPurchasesAsync(any(), any()) } answers {
            secondArg<PurchasesResponseListener>().onQueryPurchasesResponse(
                result(BillingClient.BillingResponseCode.NETWORK_ERROR), emptyList())
        }
        every { billing.queryProductDetailsAsync(any(), any()) } just Runs

        session.restore()
        advance(60)

        assertTrue(state.value.purchased)
        assertEquals(UnlockMessage.PLAY_UNREACHABLE, state.value.message)
    }

    @Test fun `successful empty ownership query still revokes a refunded entitlement`() {
        context.getSharedPreferences("plus", 0).edit().putBoolean("unlocked", true).commit()

        session.refresh()

        assertFalse(state.value.purchased)
        assertFalse(unlock.cachedPurchase(context))
    }

    @Test fun `price failures do not erase a successful restore message`() {
        every { billing.queryPurchasesAsync(any(), any()) } answers {
            secondArg<PurchasesResponseListener>().onQueryPurchasesResponse(result(), listOf(purchase()))
        }
        respondWithProducts()

        session.restore()
        advance(6)

        assertTrue(state.value.purchased)
        assertEquals(UnlockMessage.ACTIVE_HERE, state.value.message)
        assertEquals(PriceProblem.PRODUCT_UNAVAILABLE, state.value.priceProblem)
    }

    @Test fun `rental fallback cannot be offered as permanent Plus`() {
        respondWithProducts(invalidProduct(rental = true))

        session.refresh()
        advance(6)

        assertNull(state.value.price)
        assertEquals(PriceProblem.PRODUCT_UNAVAILABLE, state.value.priceProblem)
    }

    @Test fun `preorder cannot be offered as permanent Plus`() {
        respondWithProducts(invalidProduct(rental = false))

        session.refresh()
        advance(6)

        assertNull(state.value.price)
        assertEquals(PriceProblem.PRODUCT_UNAVAILABLE, state.value.priceProblem)
    }

    @Test fun `legacy permanent product without option ID still displays its price`() {
        val legacy = priceProduct(legacy = true)
        assertFalse(legacy.oneTimePurchaseOfferDetailsList.isNullOrEmpty())
        respondWithProducts(legacy)

        session.refresh()

        assertEquals("€2.99", state.value.price)
        assertFalse(state.value.loadingPrice)
    }

    @Test fun `SDK purchase flow launches once even with repeated taps`() {
        every { billing.launchBillingFlow(any(), any()) } returns result()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        session.refresh()

        session.purchase(activity)
        session.purchase(activity)

        verify(exactly = 1) { billing.launchBillingFlow(activity, any()) }
        assertTrue(state.value.busy)
        session.onPurchasesUpdated(result(BillingClient.BillingResponseCode.USER_CANCELED), null)
        assertFalse(state.value.busy)
    }

    @Test fun `stale product details refresh without automatically opening a purchase`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        session.refresh()
        advance(300)

        session.purchase(activity)

        verify(exactly = 0) { billing.launchBillingFlow(any(), any()) }
        verify(exactly = 2) { billing.queryProductDetailsAsync(any(), any()) }
        assertEquals(UnlockMessage.PRICE_REFRESHING, state.value.message)
    }

    @Test fun `failed purchase launch refreshes price but requires another explicit tap`() {
        every { billing.launchBillingFlow(any(), any()) } returns result(BillingClient.BillingResponseCode.ITEM_UNAVAILABLE)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        session.refresh()

        session.purchase(activity)
        advance(60)

        verify(exactly = 1) { billing.launchBillingFlow(any(), any()) }
        verify(exactly = 2) { billing.queryProductDetailsAsync(any(), any()) }
        assertFalse(state.value.busy)
        assertEquals(UnlockMessage.PURCHASE_NOT_STARTED, state.value.message)
    }

    @Test fun `acknowledgement retries temporary failure and deduplicates overlapping events`() {
        var attempts = 0
        every { billing.acknowledgePurchase(any(), any()) } answers {
            attempts++
            secondArg<AcknowledgePurchaseResponseListener>().onAcknowledgePurchaseResponse(
                result(if (attempts == 1) BillingClient.BillingResponseCode.NETWORK_ERROR else 0))
        }
        session.refresh()
        val owned = purchase(acknowledged = false)

        session.onPurchasesUpdated(result(), mutableListOf(owned))
        session.onPurchasesUpdated(result(), mutableListOf(owned))
        assertEquals(1, attempts)
        advance(2)

        assertEquals(2, attempts)
        assertTrue(state.value.purchased)
    }

    @Test fun `pending purchase never unlocks or starts a second flow`() {
        session.refresh()
        val pending = purchase()
        every { pending.purchaseState } returns Purchase.PurchaseState.PENDING
        session.onPurchasesUpdated(result(), mutableListOf(pending))

        session.purchase(Robolectric.buildActivity(Activity::class.java).setup().get())

        assertFalse(state.value.purchased)
        assertTrue(state.value.pending)
        verify(exactly = 0) { billing.launchBillingFlow(any(), any()) }
        verify(exactly = 0) { billing.acknowledgePurchase(any(), any()) }
    }

    @Test fun `ownership timeout reports failure and a later restore can recover`() {
        every { billing.queryPurchasesAsync(any(), any()) } just Runs
        session.restore()
        advance(15)
        assertEquals(UnlockMessage.PLAY_UNREACHABLE, state.value.message)

        every { billing.queryPurchasesAsync(any(), any()) } answers {
            secondArg<PurchasesResponseListener>().onQueryPurchasesResponse(result(), listOf(purchase()))
        }
        session.restore()

        assertTrue(state.value.purchased)
        assertEquals(UnlockMessage.ACTIVE_HERE, state.value.message)
    }

    @Test fun `already owned refreshes ownership instead of starting another payment`() {
        session.refresh()
        every { billing.queryPurchasesAsync(any(), any()) } answers {
            secondArg<PurchasesResponseListener>().onQueryPurchasesResponse(result(), listOf(purchase()))
        }

        session.onPurchasesUpdated(result(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED), null)

        assertTrue(state.value.purchased)
        verify(exactly = 2) { billing.queryPurchasesAsync(any(), any()) }
        verify(exactly = 0) { billing.launchBillingFlow(any(), any()) }
    }

    @Test fun `unanswered acknowledgements retry within a fixed budget`() {
        every { billing.acknowledgePurchase(any(), any()) } just Runs
        session.refresh()

        session.onPurchasesUpdated(result(), mutableListOf(purchase(acknowledged = false)))
        advance(60)

        assertTrue(state.value.purchased)
        verify(exactly = 3) { billing.acknowledgePurchase(any(), any()) }
    }

    @Test fun `manual retry cancels a scheduled automatic retry`() {
        respondWithProducts()
        session.refresh()
        respondWithProducts(priceProduct())

        session.refresh()
        advance(60)

        assertEquals("€2.99", state.value.price)
        verify(exactly = 2) { billing.queryProductDetailsAsync(any(), any()) }
    }

    @Test fun `callbacks from a worker thread are serialized on the main thread`() {
        val response = slot<ProductDetailsResponseListener>()
        every { billing.queryProductDetailsAsync(any(), capture(response)) } just Runs
        session.refresh()

        Thread { response.captured.onProductDetailsResponse(result(), products(priceProduct())) }.apply {
            start()
            join()
        }
        assertNull(state.value.price)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("€2.99", state.value.price)
    }

    // ── Additions in the shared library ──

    @Test fun `refresh keeps the shown price until the new answer arrives`() {
        session.refresh()
        assertEquals("€2.99", state.value.price)
        val response = slot<ProductDetailsResponseListener>()
        every { billing.queryProductDetailsAsync(any(), capture(response)) } just Runs

        session.refresh()

        assertTrue(state.value.loadingPrice)
        assertEquals("€2.99", state.value.price)
        response.captured.onProductDetailsResponse(result(), products(priceProduct(price = "€3.99")))
        assertEquals("€3.99", state.value.price)
        assertFalse(state.value.loadingPrice)
    }

    @Test fun `a buy tap while the price reloads asks for another tap`() {
        every { billing.launchBillingFlow(any(), any()) } returns result()
        session.refresh()
        every { billing.queryProductDetailsAsync(any(), any()) } just Runs
        session.refresh()

        session.purchase(Robolectric.buildActivity(Activity::class.java).setup().get())

        verify(exactly = 0) { billing.launchBillingFlow(any(), any()) }
        assertEquals(UnlockMessage.PRICE_REFRESHING, state.value.message)
        assertEquals("€2.99", state.value.price)
    }

    @Test fun `the shown price goes once loading finally fails`() {
        session.refresh()
        respondWithProducts()

        session.refresh()
        assertEquals("€2.99", state.value.price)
        advance(6)

        assertNull(state.value.price)
        assertEquals(PriceProblem.PRODUCT_UNAVAILABLE, state.value.priceProblem)
    }

    @Test fun `restore without a purchase reports not found`() {
        session.restore()

        assertFalse(state.value.purchased)
        assertEquals(UnlockMessage.NOT_FOUND, state.value.message)
        unlock.clearMessage()
        assertNull(state.value.message)
    }

    @Test fun `a completed purchase reports unlocked once`() {
        session.refresh()

        session.onPurchasesUpdated(result(), mutableListOf(purchase()))
        assertEquals(UnlockMessage.UNLOCKED, state.value.message)
        unlock.clearMessage()
        session.onPurchasesUpdated(result(), mutableListOf(purchase()))

        assertNull(state.value.message)
        assertTrue(unlock.isUnlocked())
    }

    @Test fun `a pending payment reports pending`() {
        session.refresh()
        val pending = purchase()
        every { pending.purchaseState } returns Purchase.PurchaseState.PENDING

        session.onPurchasesUpdated(result(), mutableListOf(pending))

        assertEquals(UnlockMessage.PENDING, state.value.message)
        assertFalse(state.value.canBuy)
    }

    @Test fun `debug builds count as unlocked without touching the stored purchase`() {
        start(UnlockConfig(productId = PRODUCT, prefsName = "plus", debugUnlocked = { true }))

        assertTrue(state.value.unlocked)
        assertFalse(state.value.purchased)
        assertTrue(unlock.isUnlocked())
        session.refresh()

        assertTrue(state.value.unlocked)
        assertFalse(unlock.cachedPurchase(context))
        assertTrue(state.value.canBuy)
    }

    @Test fun `the configured prefs key holds the purchase`() {
        context.getSharedPreferences("pro", 0).edit().clear().putBoolean("owned_v2", true).commit()
        every { billing.queryPurchasesAsync(any(), any()) } answers {
            secondArg<PurchasesResponseListener>().onQueryPurchasesResponse(
                result(BillingClient.BillingResponseCode.NETWORK_ERROR), emptyList())
        }
        start(UnlockConfig(productId = PRODUCT, prefsName = "pro", prefsKey = "owned_v2"))

        assertTrue(state.value.unlocked)
        session.refresh()
        assertTrue(unlock.isUnlocked(context))
    }

    @Test fun `another purchase option id is only accepted when configured`() {
        respondWithProducts(priceProduct(optionId = "pro-buy"))
        session.refresh()
        advance(6)
        assertNull(state.value.price)

        start(UnlockConfig(productId = PRODUCT, prefsName = "plus", purchaseOptionId = "pro-buy"))
        session.refresh()
        assertEquals("€2.99", state.value.price)
    }

    @Test fun `with a public key only correctly signed purchases unlock and are acknowledged`() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
        start(UnlockConfig(productId = PRODUCT, prefsName = "plus", publicKeyBase64 = publicKey))
        session.refresh()
        val json = """{"productId":"$PRODUCT","purchaseToken":"test-purchase"}"""
        val forged = purchase(acknowledged = false).also {
            every { it.originalJson } returns json
            every { it.signature } returns sign(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair(), json)
        }

        session.onPurchasesUpdated(result(), mutableListOf(forged))
        assertFalse(state.value.purchased)
        verify(exactly = 0) { billing.acknowledgePurchase(any(), any()) }

        val genuine = purchase(acknowledged = false).also {
            every { it.originalJson } returns json
            every { it.signature } returns sign(keys, json)
        }
        session.onPurchasesUpdated(result(), mutableListOf(genuine))
        assertTrue(state.value.purchased)
        verify(exactly = 1) { billing.acknowledgePurchase(any(), any()) }
    }

    @Test fun `public API runs on the main thread from any thread`() {
        Thread { unlock.restore() }.apply { start(); join() }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(UnlockMessage.NOT_FOUND, state.value.message)
        assertEquals("€2.99", state.value.price)
    }

    private fun sign(keys: KeyPair, data: String): String = Signature.getInstance("SHA1withRSA").run {
        initSign(keys.private)
        update(data.toByteArray())
        Base64.getEncoder().encodeToString(sign())
    }

    private fun client(): BillingClient = mockk<BillingClient>(relaxed = true).also { client ->
        every { client.isReady } returns true
        every { client.connectionState } returns BillingClient.ConnectionState.CONNECTED
        every { client.startConnection(any()) } answers {
            firstArg<BillingClientStateListener>().onBillingSetupFinished(result())
        }
        every { client.queryPurchasesAsync(any(), any()) } answers {
            secondArg<PurchasesResponseListener>().onQueryPurchasesResponse(result(), emptyList())
        }
        every { client.queryProductDetailsAsync(any(), any()) } answers {
            secondArg<ProductDetailsResponseListener>().onProductDetailsResponse(result(), products(priceProduct()))
        }
    }

    private fun respondWithProducts(vararg products: ProductDetails) {
        every { billing.queryProductDetailsAsync(any(), any()) } answers {
            secondArg<ProductDetailsResponseListener>().onProductDetailsResponse(result(), products(*products))
        }
    }

    private fun result(code: Int = BillingClient.BillingResponseCode.OK) =
        BillingResult.newBuilder().setResponseCode(code).build()

    private fun products(vararg products: ProductDetails) = mockk<QueryProductDetailsResult> {
        every { productDetailsList } returns products.toList()
        every { unfetchedProductList } returns emptyList()
    }

    private fun purchase(acknowledged: Boolean = true) = mockk<Purchase> {
        every { products } returns listOf(PRODUCT)
        every { purchaseState } returns Purchase.PurchaseState.PURCHASED
        every { isAcknowledged } returns acknowledged
        every { purchaseToken } returns "test-purchase"
    }

    private fun invalidProduct(rental: Boolean): ProductDetails {
        val offer = mockk<ProductDetails.OneTimePurchaseOfferDetails>(relaxed = true) {
            every { purchaseOptionId } returns if (rental) "rent" else "buy"
            every { formattedPrice } returns "€0.99"
            every { rentalDetails } returns if (rental) mockk(relaxed = true) else null
            every { preorderDetails } returns if (rental) null else mockk(relaxed = true)
        }
        return mockk {
            every { productId } returns PRODUCT
            every { oneTimePurchaseOfferDetailsList } returns listOf(offer)
            every { oneTimePurchaseOfferDetails } returns offer
        }
    }

    private fun priceProduct(legacy: Boolean = false, price: String = "€2.99", optionId: String = "buy"): ProductDetails {
        // Use the real SDK parser so building a purchase flow exercises the actual product format.
        val json = """{"productId":"$PRODUCT","type":"inapp","title":"Lazy Moves Plus",
            "name":"Lazy Moves Plus","description":"Permanent unlock",
            "oneTimePurchaseOfferDetailsList":[{"formattedPrice":"$price","priceAmountMicros":2990000,
            "priceCurrencyCode":"EUR","offerIdToken":"test-offer","purchaseOptionId":"$optionId"}]}"""
        val fixture = if (legacy) json
            .replace("oneTimePurchaseOfferDetailsList", "oneTimePurchaseOfferDetails")
            .replace("[{", "{").replace("}]", "}")
            .replace(",\"purchaseOptionId\":\"$optionId\"", "") else json
        return ProductDetails::class.java.getDeclaredConstructor(String::class.java)
            .apply { isAccessible = true }.newInstance(fixture)
    }

    private fun advance(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
}
