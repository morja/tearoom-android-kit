package one.tearoom.unlock

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryProductDetailsResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** The holder every app uses: one instance, bound lazily, feedback once per message. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AppUnlockTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val texts = UnlockTexts(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
    private lateinit var billing: BillingClient
    private var created = 0
    private var job: Job? = null

    @Before fun prepare() {
        context.getSharedPreferences("pro", 0).edit().clear().commit()
        billing = client()
    }

    @After fun cleanup() {
        job?.cancel()
    }

    private fun holder(config: UnlockConfig = UnlockConfig(productId = "pro", prefsName = "pro")) =
        AppUnlock(config, texts) { app ->
            created++
            OneTimeUnlock(app, config, Handler(Looper.getMainLooper())) { billing }
        }

    @Test fun `one OneTimeUnlock for the whole app, created on first use`() {
        val pro = holder()
        assertEquals(0, created)

        val state = pro.state(context)
        pro.refresh(context)
        idle()

        assertSame(state, pro.state(Robolectric.buildActivity(Activity::class.java).get()))
        assertEquals(1, created)
        assertEquals("€2.99", state.value.price)
    }

    @Test fun `the app's debug unlock counts without a purchase`() {
        val pro = holder(UnlockConfig(productId = "pro", prefsName = "pro", debugUnlocked = { true }))

        assertTrue(pro.isUnlocked(context))
        assertTrue(pro.state(context).value.unlocked)
        assertFalse(pro.state(context).value.purchased)
    }

    @Test fun `a changed debug unlock is seen on the next refresh, even when Play does not answer`() {
        var simulated = true
        every { billing.queryPurchasesAsync(any(), any()) } answers {
            secondArg<PurchasesResponseListener>().onQueryPurchasesResponse(
                BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED).build(), emptyList())
        }
        val pro = holder(UnlockConfig(productId = "pro", prefsName = "pro", debugUnlocked = { simulated }))
        assertTrue(pro.state(context).value.unlocked)

        simulated = false
        pro.refresh(context)
        idle()

        assertFalse(pro.state(context).value.unlocked)
        assertFalse(pro.isUnlocked(context))
    }

    @Test fun `messages gives each feedback once, repeats included once cleared`() {
        val pro = holder()
        val seen = mutableListOf<UnlockMessage>()
        job = CoroutineScope(Dispatchers.Unconfined).launch { pro.messages(context).collect { seen += it } }

        pro.restore(context)
        idle()
        pro.refresh(context)
        idle()
        assertEquals(listOf(UnlockMessage.NOT_FOUND), seen)

        pro.clearMessage(context)
        idle()
        pro.restore(context)
        idle()
        assertEquals(listOf(UnlockMessage.NOT_FOUND, UnlockMessage.NOT_FOUND), seen)
    }

    @Test fun `buy opens Play's purchase sheet for the activity`() {
        val pro = holder()
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        pro.refresh(context)
        idle()

        pro.buy(activity)
        idle()

        verify(exactly = 1) { billing.launchBillingFlow(activity, any()) }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun client(): BillingClient = mockk<BillingClient>(relaxed = true).also { client ->
        every { client.isReady } returns true
        every { client.connectionState } returns BillingClient.ConnectionState.CONNECTED
        every { client.startConnection(any()) } answers {
            firstArg<BillingClientStateListener>().onBillingSetupFinished(ok())
        }
        every { client.queryPurchasesAsync(any(), any()) } answers {
            secondArg<PurchasesResponseListener>().onQueryPurchasesResponse(ok(), emptyList())
        }
        every { client.queryProductDetailsAsync(any(), any()) } answers {
            secondArg<ProductDetailsResponseListener>().onProductDetailsResponse(ok(), products())
        }
        every { client.launchBillingFlow(any(), any()) } returns ok()
    }

    private fun ok() = BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build()

    private fun products() = mockk<QueryProductDetailsResult> {
        every { productDetailsList } returns listOf(priceProduct())
        every { unfetchedProductList } returns emptyList()
    }

    // The real SDK parser, as in OneTimeUnlockTest, so the purchase flow sees a genuine product.
    private fun priceProduct() = com.android.billingclient.api.ProductDetails::class.java
        .getDeclaredConstructor(String::class.java).apply { isAccessible = true }
        .newInstance(
            """{"productId":"pro","type":"inapp","title":"Pro","name":"Pro","description":"Permanent unlock",
            "oneTimePurchaseOfferDetailsList":[{"formattedPrice":"€2.99","priceAmountMicros":2990000,
            "priceCurrencyCode":"EUR","offerIdToken":"test-offer","purchaseOptionId":"buy"}]}""",
        )
}
