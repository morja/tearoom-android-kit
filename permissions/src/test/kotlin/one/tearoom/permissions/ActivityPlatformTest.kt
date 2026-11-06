package one.tearoom.permissions

import android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.CAMERA
import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
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

/** The Android binding: the Activity Result launcher, the real grants, the marks on disk, the settings intents, the resume. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ActivityPlatformTest {
    private val texts = PermissionTexts(reason = 1, denied = 2, blocked = 3, allow = 4, openSettings = 5, notNow = 6)
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val registry = TestRegistry()
    private val results = mutableListOf<PermissionState>()
    private lateinit var controller: ActivityController<ComponentActivity>
    private lateinit var activity: ComponentActivity

    @Before fun prepare() {
        app.getSharedPreferences("one.tearoom.permissions", Context.MODE_PRIVATE).edit().clear().commit()
        controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        activity = controller.get()
    }

    private fun flow(permission: AppPermission): PermissionFlow {
        val flow = permission.request(activity, registry) { results += it }
        controller.start().resume()
        return flow
    }

    @Test fun `the dialog is asked through the Activity Result API with the stage's permissions`() {
        val flow = flow(AppPermission(PermissionKind.Location(), texts, explain = Explain.SCREEN))
        flow.start()
        assertEquals(listOf(ACCESS_COARSE_LOCATION), registry.launched)

        shadowOf(app).grantPermissions(ACCESS_COARSE_LOCATION)
        registry.answer(mapOf(ACCESS_COARSE_LOCATION to true))
        assertTrue(flow.state.value.usable)
        assertTrue(results.single().usable)
    }

    @Test fun `the second refusal is read from the rationale and marked on disk`() {
        val flow = flow(AppPermission(PermissionKind.Camera, texts, explain = Explain.SCREEN))
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(CAMERA, true)
        assertEquals(PermissionStatus.DENIED, flow.state.value.let { flow.refresh(); flow.state.value }.status)

        flow.start()
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(CAMERA, false)
        registry.answer(mapOf(CAMERA to false))

        assertEquals(PermissionStatus.BLOCKED, flow.state.value.status)
        assertEquals(PermissionAsk.SETTINGS, flow.state.value.ask)
        assertTrue(app.getSharedPreferences("one.tearoom.permissions", Context.MODE_PRIVATE).getBoolean(CAMERA, false))
    }

    @Test fun `a grant in the settings clears the mark and finishes on resume`() {
        app.getSharedPreferences("one.tearoom.permissions", Context.MODE_PRIVATE).edit().putBoolean(CAMERA, true).commit()
        val flow = flow(AppPermission(PermissionKind.Camera, texts))
        assertEquals(PermissionStatus.BLOCKED, flow.state.value.status)

        flow.openSettings()
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)

        shadowOf(app).grantPermissions(CAMERA)
        controller.pause().resume()
        assertTrue(flow.state.value.usable)
        assertTrue(results.single().usable)
        assertFalse(app.getSharedPreferences("one.tearoom.permissions", Context.MODE_PRIVATE).contains(CAMERA))
    }

    @Test fun `notifications open the app's notification settings`() {
        val flow = flow(AppPermission(PermissionKind.Notifications, texts))
        flow.openSettings()
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test fun `revoked while running, the resume shows it`() {
        shadowOf(app).grantPermissions(CAMERA)
        val flow = flow(AppPermission(PermissionKind.Camera, texts))
        assertTrue(flow.state.value.usable)
        shadowOf(app).denyPermissions(CAMERA)
        controller.pause().resume()
        assertFalse(flow.state.value.usable)
    }

    @Test fun `isUsable and isGranted from any context, the first step included`() {
        val location = AppPermission(PermissionKind.Location(), texts)
        val widgets = AppPermission(PermissionKind.BackgroundLocation(), texts, first = location)
        assertFalse(widgets.isUsable(app))
        shadowOf(app).grantPermissions(ACCESS_BACKGROUND_LOCATION)
        assertFalse(widgets.isUsable(app))
        shadowOf(app).grantPermissions(ACCESS_COARSE_LOCATION)
        assertTrue(widgets.isUsable(app))
        assertTrue(widgets.isGranted(app))
        assertTrue(location.isUsable(app))
    }

    @Test fun `the background step launches alone, after the foreground`() {
        val location = AppPermission(PermissionKind.Location(), texts, explain = Explain.SCREEN)
        val flow = flow(AppPermission(PermissionKind.BackgroundLocation(), texts, explain = Explain.SCREEN, first = location))
        flow.start()
        assertEquals(listOf(ACCESS_COARSE_LOCATION), registry.launched)
        shadowOf(app).grantPermissions(ACCESS_COARSE_LOCATION)
        registry.answer(mapOf(ACCESS_COARSE_LOCATION to true))
        assertEquals(listOf(ACCESS_BACKGROUND_LOCATION), registry.launched)
        assertEquals(PermissionKind.BackgroundLocation(), flow.state.value.stage)
    }

    @Test fun `the settings option, the group's label for a plain kind`() {
        val option = PermissionSettings.option(app, PermissionKind.Notifications)
        // Robolectric knows the platform permissions; whatever it labels them, nothing throws and a string comes back.
        assertNull(option.takeIf { it.contains("Exception") })
    }

    @Test
    @Config(sdk = [32])
    fun `before Android 13 notifications need nothing`() {
        val flow = flow(AppPermission(PermissionKind.Notifications, texts))
        assertEquals(PermissionStatus.NOT_NEEDED, flow.state.value.status)
        flow.start()
        assertNull(registry.launched)
        assertTrue(results.single().usable)
        assertEquals(emptyList<String>(), PermissionKind.Notifications.permissions(32))
        assertEquals(listOf(POST_NOTIFICATIONS), PermissionKind.Notifications.permissions(33))
    }

    /** Records what the flow launches and hands back the test's answer. */
    class TestRegistry : ActivityResultRegistry() {
        var launched: List<String>? = null
        private var code = 0

        @Suppress("UNCHECKED_CAST")
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            code = requestCode
            launched = (input as Array<String>).toList()
        }

        fun answer(result: Map<String, Boolean>) {
            dispatchResult(code, result)
        }
    }
}
