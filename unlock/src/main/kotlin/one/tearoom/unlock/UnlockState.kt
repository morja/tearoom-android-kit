package one.tearoom.unlock

/** Why no price can be shown. Apps map each code to their own text. */
enum class PriceProblem {
    /** Play Billing is not available: no Play Store, or the Play account cannot pay here. */
    BILLING_UNAVAILABLE,

    /** Play answered, but the product is not offered to this account or installation. */
    PRODUCT_UNAVAILABLE,

    /** Google Play could not be reached (offline, Play services busy or timed out). */
    PLAY_UNAVAILABLE,
}

/** Feedback for one purchase or restore. Apps map each code to their own text and clear it once shown. */
enum class UnlockMessage {
    /** A purchase just completed and unlocked the app. */
    UNLOCKED,

    /** The payment is pending (e.g. cash at a shop); the app unlocks once it is paid. */
    PENDING,

    /** Restore: Google Play confirms the purchase for this account. */
    ACTIVE_HERE,

    /**
     * Restore: Google Play knows no purchase for this account. The app's text should say that the
     * purchase may have been made with another Google account (Play Store, switch account).
     */
    NOT_FOUND,

    /** Restore: Google Play could not be asked; the cached unlock stays. */
    PLAY_UNREACHABLE,

    /** The price was out of date and is being loaded again; the user taps again to buy. */
    PRICE_REFRESHING,

    /** Google Play did not open its purchase sheet. */
    PURCHASE_NOT_STARTED,

    /** The purchase sheet ended with an error other than a cancel. */
    PURCHASE_INCOMPLETE,
}

/** Everything a purchase screen shows. */
data class UnlockState(
    /** Unlocked for the app: a cached purchase, or a test build ([UnlockConfig.debugUnlocked]). */
    val unlocked: Boolean = false,
    /** A purchase from Google Play is cached; false in a test build without one. */
    val purchased: Boolean = false,
    /** Localised price of the permanent offer; kept while a refresh runs. */
    val price: String? = null,
    val loadingPrice: Boolean = false,
    /** A payment is pending; buying again is blocked. */
    val pending: Boolean = false,
    /** Google Play's purchase sheet is open. */
    val busy: Boolean = false,
    val priceProblem: PriceProblem? = null,
    /** Purchase and restore feedback, independent of a concurrent price request. */
    val message: UnlockMessage? = null,
) {
    /** Whether a buy button should be enabled. A tap while the price reloads asks for another tap. */
    val canBuy: Boolean get() = price != null && !busy && !pending && !purchased
}
