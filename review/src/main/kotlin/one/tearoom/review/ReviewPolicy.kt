package one.tearoom.review

/**
 * What the kit remembers about one app's openings, in its own preferences file. Days are local
 * calendar days as epoch days (`LocalDate.toEpochDay`), so time zones and clock changes count by the
 * calendar on the phone, not by 24-hour spans.
 *
 * @param firstDay the day of the first counted opening; null before it.
 * @param days the different days the app came to the foreground since the first opening, or since the
 *   last prompt once there was one; kept until the threshold of [ReviewPolicy.openingDaysNeeded]. A day
 *   seen again (a second opening, a clock set back to a day already counted) does not count twice.
 * @param askedDay the day of the last real prompt; null before the first. Starts the longer wait.
 * @param prompts how many real prompts went to Play; decides how long the next one waits.
 * @param askedVersion the versionCode the prompt was last asked for; at most once per version.
 * @param lastPromptAt when the prompt was last asked (epoch millis), for the debug line.
 * @param lastPromptFake whether that prompt went to `FakeReviewManager` (a forced prompt of a test build).
 * @param forced a test build asked for the next calm moment to prompt regardless of the rule.
 */
data class ReviewRecord(
    val firstDay: Long? = null,
    val days: Set<Long> = emptySet(),
    val askedDay: Long? = null,
    val prompts: Int = 0,
    val askedVersion: Long? = null,
    val lastPromptAt: Long? = null,
    val lastPromptFake: Boolean = false,
    val forced: Boolean = false,
) {
    /** How many different days the app came to the foreground in the current round, up to its threshold. */
    val openingDays: Int get() = days.size
}

/**
 * The one rule of every app (user decisions, 11 and 12 November 2026). The first prompt: at least
 * [MIN_DAYS] days since the first opening and the app opened on at least [MIN_OPENING_DAYS] different
 * days (ten different days already span nine; [MIN_DAYS] stays as the floor if that number changes).
 * The second: at least [SECOND_DAYS] days since the first prompt and [AGAIN_OPENING_DAYS] opening days
 * since then. Every later one: at least [LATER_DAYS] days and [AGAIN_OPENING_DAYS] opening days since
 * the last. Always at most once per versionCode. The app never learns whether someone rated, so the
 * waits are long enough for those who did. Pure; [AppReview] reads the clock and the store.
 */
object ReviewPolicy {
    const val MIN_DAYS = 7
    const val MIN_OPENING_DAYS = 10
    const val SECOND_DAYS = 90
    const val LATER_DAYS = 365
    const val AGAIN_OPENING_DAYS = 30

    /** The days to wait in the current round: since the first opening, or since the last prompt. */
    fun daysNeeded(record: ReviewRecord): Int = when (record.prompts) {
        0 -> MIN_DAYS
        1 -> SECOND_DAYS
        else -> LATER_DAYS
    }

    /** The opening days to count in the current round. */
    fun openingDaysNeeded(record: ReviewRecord): Int = if (record.prompts == 0) MIN_OPENING_DAYS else AGAIN_OPENING_DAYS

    /** The days since the start of the current round on [today]: the first opening or the last prompt. */
    fun daysWaited(record: ReviewRecord, today: Long): Long =
        (record.askedDay ?: record.firstDay)?.let { today - it } ?: 0

    /** The app came to the foreground on [today]; counts each day once. */
    fun opened(record: ReviewRecord, today: Long): ReviewRecord = when {
        record.firstDay == null -> record.copy(firstDay = today, days = setOf(today))
        // Enough days counted: the set stops growing, the rule only asks for the threshold.
        today in record.days || record.openingDays >= openingDaysNeeded(record) -> record
        else -> record.copy(days = record.days + today)
    }

    /** The rule is met for [versionCode] on [today], or a test build forced the next prompt. */
    fun due(record: ReviewRecord, today: Long, versionCode: Long): Boolean {
        if (record.forced) return true
        if (record.firstDay == null) return false
        return daysWaited(record, today) >= daysNeeded(record) &&
            record.openingDays >= openingDaysNeeded(record) &&
            record.askedVersion != versionCode
    }

    /**
     * The prompt went to Play for [versionCode] on [today]. A real prompt starts the longer wait and a
     * new count; a forced prompt of a test build is spent and leaves the count as it was.
     */
    fun asked(record: ReviewRecord, versionCode: Long, today: Long, at: Long, fake: Boolean): ReviewRecord {
        val spent = record.copy(askedVersion = versionCode, lastPromptAt = at, lastPromptFake = fake, forced = false)
        return if (fake) spent else spent.copy(askedDay = today, prompts = record.prompts + 1, days = emptySet())
    }
}
