package one.tearoom.unlock

import androidx.annotation.StringRes

/**
 * The app's string resources for everything the unlock flow says. One value per app, in every language
 * the app ships; the library has no text of its own (AGENTS.md rule 1).
 *
 * The first eleven are the texts for the library's codes ([UnlockMessage], [PriceProblem]); the rest
 * label the purchase surface ([buy] takes the price as `%s`). [notFound] should say that the purchase
 * may belong to another Google account.
 */
data class UnlockTexts(
    // UnlockMessage
    @StringRes val unlocked: Int,
    @StringRes val pending: Int,
    @StringRes val activeHere: Int,
    @StringRes val notFound: Int,
    @StringRes val playUnreachable: Int,
    @StringRes val priceRefreshing: Int,
    @StringRes val purchaseNotStarted: Int,
    @StringRes val purchaseIncomplete: Int,
    // PriceProblem
    @StringRes val billingUnavailable: Int,
    @StringRes val productUnavailable: Int,
    @StringRes val playUnavailable: Int,
    // Buttons of the purchase surface
    /** The buy button with the price, e.g. "Get Pro for %s". */
    @StringRes val buy: Int,
    /** The buy button while there is no price; the reason shows next to it. */
    @StringRes val buyWithoutPrice: Int,
    /** The buy button while the price loads for the first time. */
    @StringRes val loadingPrice: Int,
    /** Asks Play for the price again after a [PriceProblem]. */
    @StringRes val retry: Int,
    /** Asks Play for an earlier purchase. */
    @StringRes val restore: Int,
)

/** The app's text for this code. */
@StringRes
fun UnlockMessage.textRes(texts: UnlockTexts): Int = when (this) {
    UnlockMessage.UNLOCKED -> texts.unlocked
    UnlockMessage.PENDING -> texts.pending
    UnlockMessage.ACTIVE_HERE -> texts.activeHere
    UnlockMessage.NOT_FOUND -> texts.notFound
    UnlockMessage.PLAY_UNREACHABLE -> texts.playUnreachable
    UnlockMessage.PRICE_REFRESHING -> texts.priceRefreshing
    UnlockMessage.PURCHASE_NOT_STARTED -> texts.purchaseNotStarted
    UnlockMessage.PURCHASE_INCOMPLETE -> texts.purchaseIncomplete
}

/** The app's text for this code. */
@StringRes
fun PriceProblem.textRes(texts: UnlockTexts): Int = when (this) {
    PriceProblem.BILLING_UNAVAILABLE -> texts.billingUnavailable
    PriceProblem.PRODUCT_UNAVAILABLE -> texts.productUnavailable
    PriceProblem.PLAY_UNAVAILABLE -> texts.playUnavailable
}
