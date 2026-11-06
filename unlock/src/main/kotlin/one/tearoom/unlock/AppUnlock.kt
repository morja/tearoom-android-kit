package one.tearoom.unlock

import android.app.Activity
import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/**
 * The one unlock of an app: what it sells ([config]), what it says ([texts]) and the single
 * [OneTimeUnlock] behind both. Declare one per app as a top-level value and use it everywhere:
 *
 * ```kotlin
 * val Pro = AppUnlock(UnlockConfig(productId = "pro", prefsName = "pro"), UnlockTexts(...))
 * ```
 *
 * The Play connection binds to the application context on the first call, so widgets, workers and
 * screens may all ask with whatever context they have. Screens collect [state] (or show the kit's
 * `UnlockSheet`), background work reads [isUnlocked], which never asks Play. Call [refresh] on every
 * resume, [buy] from the buy button, [restore] from a restore action; [messages] gives each piece of
 * feedback once, for an app that shows it outside the purchase surface.
 */
class AppUnlock internal constructor(
    val config: UnlockConfig,
    val texts: UnlockTexts,
    private val create: (Application) -> OneTimeUnlock,
) {
    constructor(config: UnlockConfig, texts: UnlockTexts) : this(config, texts, { OneTimeUnlock(it, config) })

    @Volatile private var instance: OneTimeUnlock? = null

    private fun unlock(context: Context): OneTimeUnlock = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext as Application).also { instance = it }
    }

    /** Everything a purchase surface shows. */
    fun state(context: Context): StateFlow<UnlockState> = unlock(context).state

    /**
     * Each new purchase or restore feedback, once; the same code twice in a row counts twice once the
     * first was cleared. The surface that shows it calls [clearMessage].
     */
    fun messages(context: Context): Flow<UnlockMessage> =
        state(context).map { it.message }.distinctUntilChanged().filterNotNull()

    /** The cached purchase or a test build ([UnlockConfig.debugUnlocked]); offline and from any thread. */
    fun isUnlocked(context: Context): Boolean = unlock(context).isUnlocked(context)

    /** Asks Play for purchases and the price; on every resume. Keeps the shown price while it runs. */
    fun refresh(context: Context) = unlock(context).refresh()

    /** Like [refresh], and reports the answer as a message. */
    fun restore(context: Context) = unlock(context).restore()

    /** Opens Google Play's purchase sheet. */
    fun buy(activity: Activity) = unlock(activity).purchase(activity)

    fun clearMessage(context: Context) = unlock(context).clearMessage()
}
