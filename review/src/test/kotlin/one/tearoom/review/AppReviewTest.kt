package one.tearoom.review

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.time.Duration

/** The Android side: the store on disk, the opening on ON_START, the prompt only when due and in front, the listing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppReviewTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private var date = LocalDate.of(2026, 11, 11)
    private var accept = true
    private val launches = mutableListOf<Boolean>()
    private lateinit var controller: ActivityController<ComponentActivity>
    private lateinit var activity: ComponentActivity

    private fun review(config: ReviewConfig = ReviewConfig(calmFor = Duration.ZERO)) =
        AppReview(config, { date }, { 1_000L }) { _, fake ->
            ReviewLauncher { _, done -> launches += fake; done(accept) }
        }

    @Before fun prepare() {
        app.getSharedPreferences("one.tearoom.review", Context.MODE_PRIVATE).edit().clear().commit()
        controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        activity = controller.get()
    }

    /** Opens the app on [days] different days, ending today. */
    private fun openOn(review: AppReview, days: Int, from: LocalDate = date) {
        repeat(days) { i -> date = from.plusDays(i.toLong()); review.opened(app) }
    }

    @Test fun `every start of the observed activity counts, one day once`() {
        val review = review()
        review.observe(activity)
        controller.start().resume().pause().stop().restart()
        assertEquals(1, review.status(app).record.openingDays)

        date = date.plusDays(1)
        controller.stop().restart()
        assertEquals(2, review.status(app).record.openingDays)
    }

    @Test fun `the record survives a new holder (a new process)`() {
        openOn(review(), 3)
        assertEquals(3, review().status(app).record.openingDays)
    }

    @Test fun `no prompt before the rule is met`() = runBlocking {
        val review = review()
        controller.start().resume()
        openOn(review, 9, from = date.minusDays(20))
        assertFalse(review.askOnce(activity))
        assertTrue(launches.isEmpty())
    }

    @Test fun `once due and in front, Play is asked once per version`() = runBlocking {
        val review = review()
        controller.start().resume()
        openOn(review, 10)
        assertTrue(review.askOnce(activity))
        assertEquals(listOf(false), launches)
        assertEquals(AppReview.versionCode(app), review.status(app).record.askedVersion)
        assertEquals(1_000L, review.status(app).record.lastPromptAt)

        // The next opening of the same version stays quiet.
        assertFalse(review.askOnce(activity))
        assertEquals(1, launches.size)
    }

    @Test fun `not in front, no prompt and nothing spent`() = runBlocking {
        val review = review()
        controller.start()
        openOn(review, 10)
        assertFalse(review.askOnce(activity))
        assertTrue(launches.isEmpty())
        assertTrue(review.isDue(app))
    }

    @Test fun `Play refusing the request leaves the prompt for the next opening`() = runBlocking {
        val review = review()
        controller.start().resume()
        openOn(review, 10)
        accept = false
        assertFalse(review.askOnce(activity))
        assertNull(review.status(app).record.askedVersion)
        assertTrue(review.isDue(app))
    }

    @Test fun `a forced prompt goes to the fake manager regardless of the rule, reset forgets everything`() = runBlocking {
        val review = review()
        controller.start().resume()
        review.opened(app)
        review.forceNext(app)
        assertTrue(review.isDue(app))
        assertTrue(review.askOnce(activity))
        assertEquals(listOf(true), launches)
        assertTrue(review.status(app).record.lastPromptFake)
        assertFalse(review.isDue(app))

        review.reset(app)
        assertEquals(ReviewRecord(), review.status(app).record)
    }

    @Test fun `askIfDue asks on every resume while the screen stays calm`() {
        val review = review()
        review.forceNext(app)
        val job = CoroutineScope(Dispatchers.Main.immediate).launch { review.askIfDue(activity) }
        controller.start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(true), launches)

        // Back from the background: the next opening tries again, here with a new forced prompt.
        review.forceNext(app)
        controller.pause().stop().restart().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(true, true), launches)

        // Calm no more: nothing after the cancel.
        job.cancel()
        review.forceNext(app)
        controller.pause().stop().restart().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, launches.size)
    }

    @Test fun `the debug receiver forces, resets and answers with the status line`() {
        val receiver = ReviewDebugReceiver()
        receiver.onReceive(app, Intent(ReviewDebugReceiver.FORCE))
        assertTrue(review().status(app).record.forced)
        receiver.onReceive(app, Intent(ReviewDebugReceiver.RESET))
        assertEquals(ReviewRecord(), review().status(app).record)
    }

    @Test fun `the status line names both counts and the state`() {
        val review = review()
        openOn(review, 3)
        val line = review.status(app).describe()
        assertTrue(line, line.startsWith("2 of 7 days · 3 of 10 opening days"))
        assertTrue(line, line.endsWith("not due"))
    }

    @Test fun `the listing opens the Play Store app with the Play id`() {
        assertTrue(review(ReviewConfig(playPackage = "one.tearoom.weatherfold")).openListing(activity))
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("market://details?id=one.tearoom.weatherfold", intent.dataString)
    }

    @Test fun `without the Play Store the listing falls back to the web page`() {
        shadowOf(app).checkActivities(true)
        // No activity resolves market://, so the first start throws; register only the browser.
        val browser = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=x"))
        shadowOf(app.packageManager).addResolveInfoForIntent(browser, android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply { packageName = "browser"; name = "Browser" }
        })
        assertTrue(review(ReviewConfig(playPackage = "x")).openListing(app))
        assertEquals("https://play.google.com/store/apps/details?id=x", shadowOf(app).nextStartedActivity.dataString)
    }

    @Test fun `the listing defaults to the package name`() {
        review().openListing(app)
        assertEquals("market://details?id=${app.packageName}", shadowOf(app).nextStartedActivity.dataString)
    }
}
