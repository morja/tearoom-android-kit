package one.tearoom.review

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the kit counted, for the debug line of a test build. [describe] is English on purpose: it is
 * developer text in debug settings and adb output, never shown in a release (AGENTS.md rule 1 is
 * about user-facing text).
 */
data class ReviewStatus(val record: ReviewRecord, val today: Long, val versionCode: Long) {
    val due: Boolean get() = ReviewPolicy.due(record, today, versionCode)

    fun describe(): String {
        val days = record.firstDay?.let { today - it } ?: 0
        val asked = record.askedVersion?.toString() ?: "—"
        val last = record.lastPromptAt?.let {
            SimpleDateFormat("d MMM HH:mm", Locale.ENGLISH).format(Date(it)) + if (record.lastPromptFake) " (fake)" else ""
        } ?: "—"
        val state = when {
            record.forced -> "forced, next calm moment"
            due -> "due"
            else -> "not due"
        }
        return "$days of ${ReviewPolicy.MIN_DAYS} days · ${record.openingDays} of ${ReviewPolicy.MIN_OPENING_DAYS} opening days · " +
            "asked for version $asked (now $versionCode) · last prompt $last · $state"
    }
}

/** Reset and force for test builds; also reached through [ReviewDebugReceiver]. */
internal object ReviewDebug {
    fun reset(context: Context) = ReviewStore.write(context, ReviewRecord())

    fun forceNext(context: Context) = ReviewStore.update(context) { it.copy(forced = true) }
}

/**
 * The adb commands of a test build, for an app without a debug screen. Declared only in the app's
 * `src/debug/AndroidManifest.xml` (exported, explicit component), never in a release:
 *
 * ```
 * adb shell am broadcast -n <app id>/one.tearoom.review.ReviewDebugReceiver -a one.tearoom.review.FORCE
 * ```
 *
 * Actions `one.tearoom.review.RESET`, `.FORCE` and `.STATUS`; each answers with the status line as
 * the broadcast's result data, which `am broadcast` prints.
 */
class ReviewDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            RESET -> ReviewDebug.reset(context)
            FORCE -> ReviewDebug.forceNext(context)
        }
        if (isOrderedBroadcast) resultData = AppReview().status(context).describe()
    }

    companion object {
        const val RESET = "one.tearoom.review.RESET"
        const val FORCE = "one.tearoom.review.FORCE"
        const val STATUS = "one.tearoom.review.STATUS"
    }
}
