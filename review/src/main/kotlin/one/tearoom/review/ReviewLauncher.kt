package one.tearoom.review

import android.app.Activity
import android.content.Context
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.review.testing.FakeReviewManager

/**
 * The one call to Google Play, behind an interface so the rule is tested without Play. [launch]
 * requests the flow and, when Play answers, shows it only if [wanted] still holds (the request takes a
 * moment; the user may have left the screen or the app meanwhile). It reports whether the flow was
 * shown; Play never tells whether the dialog appeared or a rating was given, and throttles on its own.
 */
fun interface ReviewLauncher {
    fun launch(activity: Activity, wanted: () -> Boolean, done: (shown: Boolean) -> Unit)
}

/**
 * Requests a review flow and launches it; `requestReviewFlow` failing (no Play, offline) or the screen
 * no longer wanting it when Play answers is not shown.
 */
internal class PlayReviewLauncher(private val manager: ReviewManager) : ReviewLauncher {
    override fun launch(activity: Activity, wanted: () -> Boolean, done: (shown: Boolean) -> Unit) {
        // Task listeners run on the main thread, like the lifecycle that decides [wanted].
        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (!request.isSuccessful || !wanted()) {
                done(false)
                return@addOnCompleteListener
            }
            done(true)
            // The result of the launch says nothing about the dialog; it only ends the flow.
            manager.launchReviewFlow(activity, request.result)
        }
    }

    companion object {
        /** Google's manager, or its fake for a forced prompt of a test build (shows nothing). */
        fun create(context: Context, fake: Boolean): ReviewLauncher = PlayReviewLauncher(
            if (fake) FakeReviewManager(context.applicationContext) else ReviewManagerFactory.create(context.applicationContext),
        )
    }
}
