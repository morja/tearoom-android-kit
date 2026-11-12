package one.tearoom.review

/**
 * What the kit remembers about one app's openings, in its own preferences file. Days are local
 * calendar days as epoch days (`LocalDate.toEpochDay`), so time zones and clock changes count by the
 * calendar on the phone, not by 24-hour spans.
 *
 * @param firstDay the day of the first counted opening; null before it.
 * @param days the different days the app came to the foreground, kept until there are
 *   [ReviewPolicy.MIN_OPENING_DAYS] of them; a day seen again (a second opening, a clock set back to a
 *   day already counted) does not count twice.
 * @param askedVersion the versionCode the prompt was last asked for; at most once per version.
 * @param lastPromptAt when the prompt was last asked (epoch millis), for the debug line.
 * @param lastPromptFake whether that prompt went to `FakeReviewManager` (a forced prompt of a test build).
 * @param forced a test build asked for the next calm moment to prompt regardless of the rule.
 */
data class ReviewRecord(
    val firstDay: Long? = null,
    val days: Set<Long> = emptySet(),
    val askedVersion: Long? = null,
    val lastPromptAt: Long? = null,
    val lastPromptFake: Boolean = false,
    val forced: Boolean = false,
) {
    /** How many different days the app came to the foreground, up to [ReviewPolicy.MIN_OPENING_DAYS]. */
    val openingDays: Int get() = days.size
}

/**
 * The one rule of every app (user decision, 11 November 2026): at least [MIN_DAYS] days since the
 * first opening and the app opened on at least [MIN_OPENING_DAYS] different days, at most once per
 * versionCode. Pure; [AppReview] reads the clock and the store.
 */
object ReviewPolicy {
    const val MIN_DAYS = 7
    const val MIN_OPENING_DAYS = 10

    /** The app came to the foreground on [today]; counts each day once. */
    fun opened(record: ReviewRecord, today: Long): ReviewRecord = when {
        record.firstDay == null -> record.copy(firstDay = today, days = setOf(today))
        // Enough days counted: the set stops growing, the rule only asks for the threshold.
        today in record.days || record.openingDays >= MIN_OPENING_DAYS -> record
        else -> record.copy(days = record.days + today)
    }

    /** The rule is met for [versionCode] on [today], or a test build forced the next prompt. */
    fun due(record: ReviewRecord, today: Long, versionCode: Long): Boolean {
        if (record.forced) return true
        val first = record.firstDay ?: return false
        return today - first >= MIN_DAYS &&
            record.openingDays >= MIN_OPENING_DAYS &&
            record.askedVersion != versionCode
    }

    /** The prompt went to Play for [versionCode]; a forced prompt is spent. */
    fun asked(record: ReviewRecord, versionCode: Long, at: Long, fake: Boolean): ReviewRecord =
        record.copy(askedVersion = versionCode, lastPromptAt = at, lastPromptFake = fake, forced = false)
}
