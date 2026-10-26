package one.tearoom.unlock

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.PurchasesUpdatedListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One permanent one-time unlock through Google Play Billing. Create one instance per app (for example
 * in an `object` or the `Application`) and share it; screens read [state], background work reads
 * [isUnlocked]. The Play connection opens on the first [refresh], [restore] or [purchase].
 *
 * Call [refresh] when the app comes to the foreground (Google's guidance: purchases made elsewhere,
 * pending payments that completed, refunds).
 */
class OneTimeUnlock internal constructor(
    application: Application,
    val config: UnlockConfig,
    private val handler: Handler,
    private val createClient: ((PurchasesUpdatedListener) -> BillingClient)?,
) {
    constructor(application: Application, config: UnlockConfig) :
        this(application, config, Handler(Looper.getMainLooper()), null)

    private val app = application
    private val mutableState = MutableStateFlow(
        cachedPurchase(app).let { UnlockState(unlocked = it || config.debugUnlocked(app), purchased = it) },
    )

    val state: StateFlow<UnlockState> = mutableState.asStateFlow()
    private var session: PlayBillingSession? = null

    /** The cached purchase or a test build; works offline and from any thread, without asking Play. */
    fun isUnlocked(context: Context = app): Boolean = cachedPurchase(context) || config.debugUnlocked(context)

    internal fun cachedPurchase(context: Context): Boolean =
        context.getSharedPreferences(config.prefsName, Context.MODE_PRIVATE).getBoolean(config.prefsKey, false)

    internal fun storePurchase(purchased: Boolean) {
        app.getSharedPreferences(config.prefsName, Context.MODE_PRIVATE).edit().putBoolean(config.prefsKey, purchased).apply()
        mutableState.value = mutableState.value.copy(purchased = purchased, unlocked = purchased || config.debugUnlocked(app))
    }

    private fun withSession(action: (PlayBillingSession) -> Unit) = onMain {
        val current = session ?: PlayBillingSession(app, this, mutableState, handler, createClient).also { session = it }
        action(current)
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == handler.looper) action() else handler.post { action() }
    }

    /** Reads the cache, asks Play for purchases and the price. Keeps the shown price while it runs. */
    fun refresh() = withSession { it.refresh() }

    /** Like [refresh], and reports the answer as [UnlockState.message]. */
    fun restore() = withSession { it.restore() }

    /** Opens Google Play's purchase sheet, or asks for another tap if the price is out of date. */
    fun purchase(activity: Activity) = withSession { it.purchase(activity) }

    fun clearMessage() = onMain { mutableState.value = mutableState.value.copy(message = null) }

    /** Ends the Play connection; the cached purchase stays. A later call opens a new one. */
    fun close() = onMain {
        session?.close()
        session = null
    }

    /** For tests at the SDK boundary. */
    internal fun sessionForTest(): PlayBillingSession {
        var current: PlayBillingSession? = null
        withSession { current = it }
        return checkNotNull(current) { "call on the handler's thread" }
    }
}
