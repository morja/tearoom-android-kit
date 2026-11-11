package one.tearoom.review

import android.content.Context

/**
 * The record in the kit's own preferences file, the same for every app (AGENTS.md rule 2: no app
 * values). Test builds reset or force it through [AppReview] or [ReviewDebugReceiver].
 */
internal object ReviewStore {
    private const val FILE = "one.tearoom.review"
    private const val FIRST_DAY = "first_day"
    private const val LAST_DAY = "last_day"
    private const val OPENING_DAYS = "opening_days"
    private const val ASKED_VERSION = "asked_version"
    private const val LAST_PROMPT_AT = "last_prompt_at"
    private const val LAST_PROMPT_FAKE = "last_prompt_fake"
    private const val FORCED = "forced"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(context: Context): ReviewRecord {
        val p = prefs(context)
        fun long(key: String) = if (p.contains(key)) p.getLong(key, 0) else null
        return ReviewRecord(
            firstDay = long(FIRST_DAY),
            lastDay = long(LAST_DAY),
            openingDays = p.getInt(OPENING_DAYS, 0),
            askedVersion = long(ASKED_VERSION),
            lastPromptAt = long(LAST_PROMPT_AT),
            lastPromptFake = p.getBoolean(LAST_PROMPT_FAKE, false),
            forced = p.getBoolean(FORCED, false),
        )
    }

    fun write(context: Context, record: ReviewRecord) {
        prefs(context).edit().apply {
            clear()
            record.firstDay?.let { putLong(FIRST_DAY, it) }
            record.lastDay?.let { putLong(LAST_DAY, it) }
            putInt(OPENING_DAYS, record.openingDays)
            record.askedVersion?.let { putLong(ASKED_VERSION, it) }
            record.lastPromptAt?.let { putLong(LAST_PROMPT_AT, it) }
            putBoolean(LAST_PROMPT_FAKE, record.lastPromptFake)
            putBoolean(FORCED, record.forced)
        }.commit()
    }

    fun update(context: Context, change: (ReviewRecord) -> ReviewRecord) = write(context, change(read(context)))
}
