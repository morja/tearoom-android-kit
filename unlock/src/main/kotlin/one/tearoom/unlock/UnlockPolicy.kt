package one.tearoom.unlock

/** What the rules need to know about a Play purchase, independent of the Billing Library. */
data class OwnedPurchase(
    val products: List<String>,
    val purchased: Boolean,
    val acknowledged: Boolean,
    val token: String,
    /** False when a configured Play public key does not match the purchase's signature. */
    val verified: Boolean = true,
)

/**
 * Pure rules for one permanent one-time unlock. Verification stays on the device (ADR-0002).
 */
class UnlockPolicy(private val productId: String) {
    private fun OwnedPurchase.isProduct() = productId in products
    private fun OwnedPurchase.counts() = isProduct() && purchased && verified

    /**
     * A failed query keeps the cached unlock, so being offline never removes it.
     * A successful answer without the purchase removes it, which covers refunds and revocations.
     */
    fun unlockedAfterQuery(cached: Boolean, queryOk: Boolean, purchases: List<OwnedPurchase>): Boolean =
        if (!queryOk) cached else purchases.any { it.counts() }

    /** Pending payments do not unlock; a later purchase update does. */
    fun unlockedAfterUpdate(cached: Boolean, purchases: List<OwnedPurchase>): Boolean =
        cached || purchases.any { it.counts() }

    fun pending(purchases: List<OwnedPurchase>): Boolean = purchases.any { it.isProduct() && !it.purchased }

    /** Play refunds purchases that stay unacknowledged for three days; unverified ones are left to that. */
    fun toAcknowledge(purchases: List<OwnedPurchase>): List<String> =
        purchases.filter { it.counts() && !it.acknowledged }.map { it.token }
}
