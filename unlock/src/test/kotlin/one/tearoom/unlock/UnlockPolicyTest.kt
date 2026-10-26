package one.tearoom.unlock

import org.junit.Assert.*
import org.junit.Test

class UnlockPolicyTest {
    private val policy = UnlockPolicy("plus_unlock")
    private val plus = OwnedPurchase(listOf("plus_unlock"), purchased = true, acknowledged = true, token = "a")
    private val pending = plus.copy(purchased = false, acknowledged = false, token = "p")
    private val other = plus.copy(products = listOf("something_else"), token = "o")
    private val unverified = plus.copy(acknowledged = false, token = "u", verified = false)

    @Test fun `being offline never removes a cached unlock`() {
        assertTrue(policy.unlockedAfterQuery(cached = true, queryOk = false, purchases = emptyList()))
        assertFalse(policy.unlockedAfterQuery(cached = false, queryOk = false, purchases = listOf(plus)))
    }

    @Test fun `a successful query restores purchases and removes refunded ones`() {
        assertTrue(policy.unlockedAfterQuery(cached = false, queryOk = true, purchases = listOf(plus)))
        assertFalse(policy.unlockedAfterQuery(cached = true, queryOk = true, purchases = emptyList()))
        assertFalse(policy.unlockedAfterQuery(cached = true, queryOk = true, purchases = listOf(other)))
    }

    @Test fun `pending payments do not unlock until confirmed`() {
        assertFalse(policy.unlockedAfterUpdate(cached = false, purchases = listOf(pending)))
        assertTrue(policy.pending(listOf(pending)))
        assertFalse(policy.unlockedAfterQuery(cached = false, queryOk = true, purchases = listOf(pending)))
        assertTrue(policy.unlockedAfterUpdate(cached = false, purchases = listOf(plus)))
    }

    @Test fun `only confirmed unacknowledged purchases of the product are acknowledged`() {
        val fresh = plus.copy(acknowledged = false, token = "f")
        assertEquals(listOf("f"), policy.toAcknowledge(listOf(plus, pending, fresh, other.copy(acknowledged = false), unverified)))
    }

    @Test fun `a purchase with a wrong signature neither unlocks nor keeps an unlock`() {
        assertFalse(policy.unlockedAfterUpdate(cached = false, purchases = listOf(unverified)))
        assertFalse(policy.unlockedAfterQuery(cached = true, queryOk = true, purchases = listOf(unverified)))
    }
}
