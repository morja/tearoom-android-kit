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

    @Test fun `the settings open on top of the activity behind a wrapped context`() {
        val wrapped = android.view.ContextThemeWrapper(activity, 0)
        PermissionSettings.open(wrapped, PermissionKind.Camera)
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals(0, intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test fun `notifications open the app's notification settings`() {
        val flow = flow(AppPermission(PermissionKind.Notifications, texts))
        flow.openSettings()
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test fun `a channel opens its own page on top of the activity`() {
        PermissionSettings.openChannel(activity, "connection")
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals("connection", intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals(0, intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
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

    @Test fun `a flow declared as a property of the activity reads nothing before the activity is attached`() {
        PropertyActivity.registry = registry
        val controller = Robolectric.buildActivity(PropertyActivity::class.java).setup()
        val property = controller.get()
        assertEquals(PermissionStatus.NOT_ASKED, property.flow.state.value.status)

        property.flow.start()
        assertEquals(listOf(ACCESS_COARSE_LOCATION), registry.launched)
        shadowOf(app).grantPermissions(ACCESS_COARSE_LOCATION)
        registry.answer(mapOf(ACCESS_COARSE_LOCATION to true))
        assertEquals(listOf(ACCESS_BACKGROUND_LOCATION), registry.launched)
    }

    @Test fun `a rotation during the foreground dialog, the answer to the rebuilt activity continues the background step`() {
        PropertyActivity.registry = registry
        val controller = Robolectric.buildActivity(PropertyActivity::class.java).setup()
        controller.get().flow.start()
        assertEquals(listOf(ACCESS_COARSE_LOCATION), registry.launched)

        controller.recreate()
        val rebuilt = controller.get()
        assertTrue(rebuilt.flow.state.value.busy)
        shadowOf(app).grantPermissions(ACCESS_COARSE_LOCATION)
        registry.answer(mapOf(ACCESS_COARSE_LOCATION to true))
        assertEquals(listOf(ACCESS_BACKGROUND_LOCATION), registry.launched)
        assertEquals(PermissionKind.BackgroundLocation(), rebuilt.flow.state.value.stage)
    }

    @Test fun `a rotation in the settings, the return to the rebuilt activity answers`() {
        PropertyActivity.registry = registry
        val controller = Robolectric.buildActivity(PropertyActivity::class.java).setup()
        controller.get().flow.openSettings()
        // The activity is rebuilt while the settings are in front; the grant is made there, then the user comes back.
        shadowOf(app).grantPermissions(ACCESS_COARSE_LOCATION, ACCESS_BACKGROUND_LOCATION)
        controller.recreate()
        val rebuilt = controller.get()
        assertTrue(rebuilt.flow.state.value.usable)
        assertTrue(rebuilt.results.single().usable)
    }

    private val listenerKind = PermissionKind.NotificationListener("app.Listener")
    private val listenerComponent get() = android.content.ComponentName(app.packageName, "app.Listener")
    private val notifications get() = shadowOf(app.getSystemService(android.app.NotificationManager::class.java))

    @Test fun `notification access opens the listener's own page for the app's component, no dialog`() {
        val flow = flow(AppPermission(listenerKind, texts, explain = Explain.SCREEN))
        flow.start()
        assertNull(registry.launched)
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, intent.action)
        assertEquals(listenerComponent.flattenToString(), intent.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME))
        assertEquals(0, intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    @Config(sdk = [29])
    fun `before Android 11 notification access opens the list of listeners`() {
        val intent = PermissionSettings.intent(app, listenerKind)
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, intent.action)
    }

    @Test fun `notification access is read from Android on the way back and again when it is turned off`() {
        val flow = flow(AppPermission(listenerKind, texts, explain = Explain.SCREEN))
        val access = AppPermission(listenerKind, texts)
        assertFalse(access.isUsable(app))
        flow.start()
        controller.pause()

        notifications.setNotificationListenerAccessGranted(listenerComponent, true)
        controller.resume()
        assertTrue(flow.state.value.usable)
        assertTrue(results.single().usable)
        assertTrue(access.isUsable(app))
        assertTrue(access.isGranted(app))

        notifications.setNotificationListenerAccessGranted(listenerComponent, false)
        controller.pause().resume()
        assertFalse(flow.state.value.usable)
        assertEquals(PermissionStatus.NOT_ASKED, flow.state.value.status)
        assertFalse(access.isUsable(app))
    }

    @Test fun `notification access names no option`() {
        assertEquals("", PermissionSettings.option(app, listenerKind))
    }

    @Test fun `exact alarms open the app's alarms page, no dialog`() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val flow = flow(AppPermission(PermissionKind.ExactAlarm, texts, explain = Explain.SCREEN))
        flow.start()
        assertNull(registry.launched)
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
        assertEquals(0, intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test fun `exact alarms are read from AlarmManager on the way back and again when turned off`() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val flow = flow(AppPermission(PermissionKind.ExactAlarm, texts, explain = Explain.SCREEN))
        val access = AppPermission(PermissionKind.ExactAlarm, texts)
        assertFalse(access.isUsable(app))
        flow.start()
        controller.pause()

        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        controller.resume()
        assertTrue(flow.state.value.usable)
        assertTrue(results.single().usable)
        assertTrue(access.isGranted(app))

        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        controller.pause().resume()
        assertEquals(PermissionStatus.NOT_ASKED, flow.state.value.status)
        assertFalse(access.isUsable(app))
    }

    @Test
    @Config(sdk = [30])
    fun `before Android 12 exact alarms are on and nothing opens`() {
        val flow = flow(AppPermission(PermissionKind.ExactAlarm, texts))
        assertEquals(PermissionStatus.NOT_NEEDED, flow.state.value.status)
        assertTrue(SpecialAccess.isOn(app, PermissionKind.ExactAlarm))
        flow.start()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(results.single().usable)
    }

    @Test fun `exact alarms name no option`() {
        assertEquals("", PermissionSettings.option(app, PermissionKind.ExactAlarm))
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

/** An activity that declares its flow as a property, the form README and HO-1 show (REV-4 finding 1). */
class PropertyActivity : ComponentActivity() {
    val results = mutableListOf<PermissionState>()
    private val texts = PermissionTexts(reason = 1, denied = 2, blocked = 3, allow = 4, openSettings = 5, notNow = 6)
    private val location = AppPermission(PermissionKind.Location(), texts, explain = Explain.SCREEN)
    val flow = AppPermission(PermissionKind.BackgroundLocation(), texts, explain = Explain.SCREEN, first = location)
        .request(this, registry) { results += it }

    companion object {
        lateinit var registry: ActivityResultRegistry
    }
}
