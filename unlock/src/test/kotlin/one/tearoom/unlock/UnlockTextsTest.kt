package one.tearoom.unlock

import org.junit.Assert.assertEquals
import org.junit.Test

class UnlockTextsTest {
    // Ids in declaration order, so each code must land on "its" field.
    private val texts = UnlockTexts(
        unlocked = 1, pending = 2, activeHere = 3, notFound = 4, playUnreachable = 5, priceRefreshing = 6,
        purchaseNotStarted = 7, purchaseIncomplete = 8,
        billingUnavailable = 9, productUnavailable = 10, playUnavailable = 11,
        buy = 12, buyWithoutPrice = 13, loadingPrice = 14, retry = 15, restore = 16,
    )

    @Test fun `every message has its own text`() {
        val ids = UnlockMessage.entries.map { it.textRes(texts) }
        assertEquals((1..8).toList(), ids)
    }

    @Test fun `every price problem has its own text`() {
        val ids = PriceProblem.entries.map { it.textRes(texts) }
        assertEquals((9..11).toList(), ids)
    }
}
