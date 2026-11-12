package one.tearoom.review

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.LocalDate
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * @param playPackage the app's id on Google Play, for the listing; null takes the package name. Builds
 *   with an id suffix (`.debug`, `.fast`) pass the Play id, so the row opens the real listing.
 * @param calmFor how long the main screen stays calm before the prompt: about two seconds after start.
 */
data class ReviewConfig(
    val playPackage: String? = null,
    val calmFor: Duration = 2.seconds,
)

/**
 * The one rating prompt of an app (ADR-0006): it counts the days the app is opened, asks Google Play
 * for its review dialog once the rule of [ReviewPolicy] is met and the main screen is calm, and opens
 * the Play listing for the app's permanent "Rate the app" row. Declare one per app:
 *
 * ```kotlin
 * val Review = AppReview(ReviewConfig(playPackage = "one.tearoom.weatherfold"))
 * // MainActivity.onCreate
 * Review.observe(this)
 * // main screen
 * LaunchedEffect(calm) { if (calm) Review.askIfDue(activity) }
 * // settings row
 * onClick = { Review.openListing(context) }
 * ```
 *
 * There is no own pre-question, no routing of unhappy users and no reward (Google's policy).
 */
class AppReview internal constructor(
    val config: ReviewConfig,
    private val today: () -> LocalDate,
    private val now: () -> Long,
    private val launcher: (Context, fake: Boolean) -> ReviewLauncher,
) {
    constructor(config: ReviewConfig = ReviewConfig()) :
        this(config, { LocalDate.now() }, { System.currentTimeMillis() }, PlayReviewLauncher::create)

    /** Counts today as an opening day; the app came to the foreground (launcher, widget, notification). */
    fun opened(context: Context) = ReviewStore.update(context) { ReviewPolicy.opened(it, today().toEpochDay()) }

    /** Counts every start of [activity] (the main activity) as an opening; one day counts once. */
    fun observe(activity: ComponentActivity) {
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = opened(activity)
        })
    }

    /** Whether the next calm moment would prompt. */
    fun isDue(context: Context): Boolean =
        ReviewPolicy.due(ReviewStore.read(context), today().toEpochDay(), versionCode(context))

    /**
     * Prompts once the rule is met, on this and every later resume of [activity] while it runs: waits
     * [ReviewConfig.calmFor] in front, then asks Play. Call it from the main screen whenever it becomes
     * calm; leaving the calm state (an error, a permission request, a purchase, onboarding) or the
     * activity pausing cancels the wait: `LaunchedEffect(calm) { if (calm) Review.askIfDue(activity) }`.
     * Suspends until cancelled.
     */
    suspend fun askIfDue(activity: ComponentActivity): Unit = coroutineScope {
        // A return from the background is the next opening, also when the screen was calm before: one try per
        // resume, cancelled by the pause (a system dialog, another app). Like repeatOnLifecycle, without lifecycle-ktx.
        var attempt: Job? = null
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> attempt = launch { askOnce(activity) }
                Lifecycle.Event.ON_PAUSE -> attempt?.cancel()
                else -> Unit
            }
        }
        // Adding the observer replays ON_RESUME at once when the activity is in front.
        activity.lifecycle.addObserver(observer)
        try {
            awaitCancellation()
        } finally {
            activity.lifecycle.removeObserver(observer)
        }
    }

    /** One try, for the activity in front: true when Play's flow was shown. */
    internal suspend fun askOnce(activity: ComponentActivity): Boolean {
        if (!isDue(activity)) return false
        delay(config.calmFor)
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !isDue(activity)) return false

        val fake = ReviewStore.read(activity).forced
        return suspendCancellableCoroutine { cont ->
            // Play answers after a moment. By then the screen may have left the calm state, the activity paused
            // or a newer try started (this one is then cancelled): only a try still waiting shows the dialog.
            val wanted = {
                cont.isActive && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && isDue(activity)
            }
            launcher(activity, fake).launch(activity, wanted) { shown ->
                // Marked right away, before Play's dialog pauses the activity and cancels the wait. Only when the flow
                // was shown: no Play, no network or a screen no longer calm tries again on the next opening.
                if (shown) ReviewStore.update(activity) { ReviewPolicy.asked(it, versionCode(activity), today().toEpochDay(), now(), fake) }
                if (cont.isActive) cont.resume(shown)
            }
        }
    }

    /**
     * Opens the app's Play listing for the "Rate the app" row: the Play Store app (`market://`), else
     * the web page. False when neither can be opened; the app may say so in its own words.
     */
    fun openListing(context: Context): Boolean {
        val id = config.playPackage ?: context.packageName
        return listOf("market://details?id=$id", "https://play.google.com/store/apps/details?id=$id").any { url ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
    }

    /** What the kit counted, for a test build's debug line. */
    fun status(context: Context): ReviewStatus =
        ReviewStatus(ReviewStore.read(context), today().toEpochDay(), versionCode(context))

    /** Test builds: forgets every opening and the last prompt. */
    fun reset(context: Context) = ReviewDebug.reset(context)

    /** Test builds: the next calm moment prompts through `FakeReviewManager`, regardless of the rule. */
    fun forceNext(context: Context) = ReviewDebug.forceNext(context)

    internal companion object {
        @Suppress("DEPRECATION")
        fun versionCode(context: Context): Long {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        }
    }
}
