package one.tearoom.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPolicyTest {
    private val day0 = 20_000L

    /** Opened once on each of [days] (offsets from day0). */
    private fun openedOn(vararg days: Long) = days.fold(ReviewRecord()) { r, d -> ReviewPolicy.opened(r, day0 + d) }

    @Test fun `the first opening starts the count`() {
        assertEquals(ReviewRecord(firstDay = day0, days = setOf(day0)), openedOn(0))
    }

    @Test fun `several openings on one day count once`() {
        assertEquals(1, openedOn(0, 0, 0).openingDays)
        assertEquals(2, openedOn(0, 0, 1, 1).openingDays)
    }

    @Test fun `a clock set back still counts a new day`() {
        assertEquals(3, openedOn(5, 3, 4).openingDays)
    }

    @Test fun `a day seen again does not count twice`() {
        // Eight different dates, ten openings on changing days (time zone, clock): not enough.
        val revisited = openedOn(0, 1, 2, 3, 4, 5, 6, 7, 6, 7)
        assertEquals(8, revisited.openingDays)
        assertFalse(ReviewPolicy.due(revisited, day0 + 7, versionCode = 1))
    }

    @Test fun `the count stops growing at the threshold`() {
        assertEquals(ReviewPolicy.MIN_OPENING_DAYS, openedOn(*LongArray(30) { it.toLong() }).openingDays)
    }

    @Test fun `due after seven days and ten opening days, not before`() {
        val tenDaysInARow = openedOn(*LongArray(10) { it.toLong() })
        assertTrue(ReviewPolicy.due(tenDaysInARow, day0 + 9, versionCode = 5))

        // Ten opening days within seven days are not enough (impossible by the calendar, but the rule says so).
        val sevenDays = openedOn(*LongArray(7) { it.toLong() })
        assertFalse(ReviewPolicy.due(sevenDays, day0 + 6, versionCode = 5))

        // Nine opening days spread over a month: the second condition is missing.
        val nine = openedOn(*LongArray(9) { it * 3L })
        assertFalse(ReviewPolicy.due(nine, day0 + 30, versionCode = 5))
        assertTrue(ReviewPolicy.due(ReviewPolicy.opened(nine, day0 + 31), day0 + 31, versionCode = 5))
    }

    @Test fun `exactly seven days since the first opening is enough`() {
        val record = ReviewRecord(firstDay = day0, days = (0L..9L).map { day0 + it }.toSet())
        assertFalse(ReviewPolicy.due(record, day0 + 6, versionCode = 1))
        assertTrue(ReviewPolicy.due(record, day0 + 7, versionCode = 1))
    }

    @Test fun `never due before the first opening`() {
        assertFalse(ReviewPolicy.due(ReviewRecord(days = (1L..20L).toSet()), day0, versionCode = 1))
    }

    @Test fun `at most once per version, again after an update`() {
        val met = ReviewRecord(firstDay = day0, days = (0L..9L).map { day0 + it * 2 }.toSet())
        val asked = ReviewPolicy.asked(met, versionCode = 13, at = 1L, fake = false)
        assertFalse(ReviewPolicy.due(asked, day0 + 21, versionCode = 13))
        assertTrue(ReviewPolicy.due(asked, day0 + 21, versionCode = 14))
    }

    @Test fun `a forced prompt is due regardless of the rule and spent once asked`() {
        val forced = ReviewRecord(forced = true)
        assertTrue(ReviewPolicy.due(forced, day0, versionCode = 1))
        val asked = ReviewPolicy.asked(forced, versionCode = 1, at = 42L, fake = true)
        assertFalse(asked.forced)
        assertTrue(asked.lastPromptFake)
        assertEquals(42L, asked.lastPromptAt)
        assertFalse(ReviewPolicy.due(asked, day0, versionCode = 1))
    }
}
