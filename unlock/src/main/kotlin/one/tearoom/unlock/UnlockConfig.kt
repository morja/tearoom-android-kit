package one.tearoom.unlock

import android.content.Context

/**
 * What one app sells: a single permanent one-time product.
 *
 * @param productId the one-time product in the Play Console, e.g. `pro`.
 * @param prefsName shared preferences file that caches the unlock, so it works offline.
 * @param prefsKey boolean key in [prefsName].
 * @param purchaseOptionId the permanent buy option of the product. Legacy offers without an option id
 *   are accepted too; rental and preorder options never are.
 * @param publicKeyBase64 the app's Play licence key (Play Console, Monetisation setup). When set, a
 *   purchase only counts if its signature matches; when null the check is off (ADR-0002).
 * @param debugUnlocked test builds that count as unlocked without a purchase. Never affects the
 *   stored purchase or the purchase flow.
 */
data class UnlockConfig(
    val productId: String,
    val prefsName: String,
    val prefsKey: String = "unlocked",
    val purchaseOptionId: String = "buy",
    val publicKeyBase64: String? = null,
    val debugUnlocked: (Context) -> Boolean = { false },
)
