package one.tearoom.permissions

import android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.CAMERA
import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The state machine against a fake device: every row of the skill's matrix that a flow can see. */
class PermissionFlowTest {
    private val texts = PermissionTexts(reason = 1, denied = 2, blocked = 3, allow = 4, openSettings = 5, notNow = 6, partial = 7)
    private val backgroundTexts = texts.copy(reason = 11, denied = 12, blocked = 13)
    private val device = FakeDevice()
    private val results = mutableListOf<PermissionState>()

    private fun flow(permission: AppPermission, initialAsk: PermissionAsk? = null) =
        PermissionFlow(permission, device, initialAsk) { results += it }

    private val camera = AppPermission(PermissionKind.Camera, texts)
    private val cameraOnScreen = AppPermission(PermissionKind.Camera, texts, explain = Explain.SCREEN)
    private val location = AppPermission(PermissionKind.Location(), texts, explain = Explain.SCREEN)
    private val widgets = AppPermission(PermissionKind.BackgroundLocation(), backgroundTexts, explain = Explain.SCREEN, first = location)

    @Test fun `fresh install, the reason first, then the dialog, then the feature`() {
        val flow = flow(camera)
        assertEquals(PermissionStatus.NOT_ASKED, flow.state.value.status)

        flow.start()
        assertEquals(PermissionAsk.REASON, flow.state.value.ask)
        assertNull(device.launched)

        flow.proceed()
        assertEquals(listOf(CAMERA), device.launched)
        assertTrue(flow.state.value.busy)
        assertNull(flow.state.value.ask)

        device.grant(CAMERA)
        device.answer(flow)
        assertEquals(PermissionStatus.GRANTED, flow.state.value.status)
        assertFalse(flow.state.value.busy)
        assertEquals(1, results.size)
        assertTrue(results.single().usable)
    }

    @Test fun `the screen says why, the dialog at once`() {
        val flow = flow(cameraOnScreen)
        flow.start()
        assertEquals(listOf(CAMERA), device.launched)
    }

    @Test fun `already granted, the feature runs at once, nothing is asked`() {
        device.grant(CAMERA)
        val flow = flow(camera)
        flow.start()
        assertNull(device.launched)
        assertEquals(1, results.size)
        assertTrue(results.single().usable)
    }

    @Test fun `only this time, granted now, asked again on the next start`() {
        device.grant(CAMERA)
        flow(camera).start()
        assertTrue(results.single().usable)

        // The next process: the grant is gone, Android would show its dialog again.
        device.revokeAll()
        val next = flow(cameraOnScreen)
        assertEquals(PermissionStatus.NOT_ASKED, next.state.value.status)
        next.start()
        assertEquals(listOf(CAMERA), device.launched)
    }

    @Test fun `deny once, the text names the permission, the control asks again with the reason`() {
        val flow = flow(camera)
        flow.start()
        flow.proceed()
        device.rationale += CAMERA
        device.answer(flow, after = 3000)

        assertEquals(PermissionStatus.DENIED, flow.state.value.status)
        assertNull(flow.state.value.ask)
        assertEquals(2, PermissionStatus.DENIED.textRes(flow.texts))
        assertFalse(results.single().usable)

        flow.start()
        assertEquals(PermissionAsk.REASON, flow.state.value.ask)
    }

    @Test fun `deny twice, no dialog any more, the settings path instead of a dead button`() {
        val flow = flow(cameraOnScreen)
        flow.start()
        device.rationale += CAMERA
        device.answer(flow, after = 3000)
        assertEquals(PermissionStatus.DENIED, flow.state.value.status)

        flow.start()
        device.rationale -= CAMERA
        device.answer(flow, after = 3000)
        assertEquals(PermissionStatus.BLOCKED, flow.state.value.status)
        assertEquals(PermissionAsk.SETTINGS, flow.state.value.ask)
        assertTrue(device.marked(PermissionKind.Camera))
        assertEquals(2, results.size)

        // The next process remembers: the settings path, no launch.
        device.launched = null
        val next = flow(cameraOnScreen)
        assertEquals(PermissionStatus.BLOCKED, next.state.value.status)
        next.start()
        assertNull(device.launched)
        assertEquals(PermissionAsk.SETTINGS, next.state.value.ask)
    }

    @Test fun `blocked in the settings, the silent answer shows in its speed`() {
        val flow = flow(cameraOnScreen)
        flow.start()
        device.answer(flow, after = 300)
        assertEquals(PermissionStatus.BLOCKED, flow.state.value.status)
        assertEquals(PermissionAsk.SETTINGS, flow.state.value.ask)
    }

    @Test fun `a dialog dismissed slowly without an answer is not blocked`() {
        val flow = flow(cameraOnScreen)
        flow.start()
        device.answer(flow, after = 2500)
        assertEquals(PermissionStatus.NOT_ASKED, flow.state.value.status)
        assertNull(flow.state.value.ask)
        assertFalse(device.marked(PermissionKind.Camera))
    }

    @Test fun `from the settings, a grant finishes, a refusal leaves the line on the screen`() {
        device.mark(PermissionKind.Camera)
        val flow = flow(camera)
        flow.start()
        assertEquals(PermissionAsk.SETTINGS, flow.state.value.ask)

        flow.openSettings()
        assertEquals(PermissionKind.Camera, device.settingsOpenedFor)
        assertTrue(flow.state.value.busy)
        flow.refresh()
        assertEquals(PermissionStatus.BLOCKED, flow.state.value.status)
        assertNull(flow.state.value.ask)
        assertFalse(flow.state.value.busy)
        assertFalse(results.single().usable)

        flow.openSettings()
        device.grant(CAMERA)
        flow.refresh()
        assertTrue(flow.state.value.usable)
        assertTrue(results.last().usable)
        assertFalse(device.marked(PermissionKind.Camera))
    }

    @Test fun `revoked while running, the next resume shows the real state`() {
        device.grant(CAMERA)
        val flow = flow(camera)
        assertTrue(flow.state.value.usable)

        device.revokeAll()
        flow.refresh()
        assertFalse(flow.state.value.usable)
        assertEquals(PermissionStatus.NOT_ASKED, flow.state.value.status)
        assertTrue(results.isEmpty())
    }

    @Test fun `background location, the foreground step first, the background from the same tap`() {
        val flow = flow(widgets)
        assertEquals(PermissionKind.Location(), flow.state.value.stage)
        assertEquals(1, flow.texts.reason)

        flow.start()
        assertEquals(listOf(ACCESS_COARSE_LOCATION), device.launched)
        device.grant(ACCESS_COARSE_LOCATION)
        device.answer(flow, after = 3000)

        // No tap in between: the background request follows at once.
        assertEquals(listOf(ACCESS_BACKGROUND_LOCATION), device.launched)
        assertEquals(PermissionKind.BackgroundLocation(), flow.state.value.stage)
        assertEquals(11, flow.texts.reason)
        assertTrue(results.isEmpty())

        device.grant(ACCESS_BACKGROUND_LOCATION)
        device.answer(flow, after = 5000)
        assertTrue(flow.state.value.usable)
        assertTrue(results.single().usable)
    }

    @Test fun `background location with the reason sheet, the disclosure between the steps`() {
        val sheet = AppPermission(PermissionKind.BackgroundLocation(), backgroundTexts, first = AppPermission(PermissionKind.Location(), texts))
        val flow = flow(sheet)
        flow.start()
        assertEquals(PermissionAsk.REASON, flow.state.value.ask)
        flow.proceed()
        device.grant(ACCESS_COARSE_LOCATION)
        device.answer(flow, after = 3000)

        assertEquals(PermissionAsk.REASON, flow.state.value.ask)
        assertEquals(PermissionKind.BackgroundLocation(), flow.state.value.stage)
        assertEquals(listOf(ACCESS_COARSE_LOCATION), device.launched)
        flow.proceed()
        assertEquals(listOf(ACCESS_BACKGROUND_LOCATION), device.launched)
    }

    @Test fun `background location refused for good, the settings path, and the foreground stays usable`() {
        device.grant(ACCESS_COARSE_LOCATION)
        val flow = flow(widgets)
        assertEquals(PermissionKind.BackgroundLocation(), flow.state.value.stage)
        flow.start()
        assertEquals(listOf(ACCESS_BACKGROUND_LOCATION), device.launched)
        device.answer(flow, after = 200)

        assertEquals(PermissionStatus.BLOCKED, flow.state.value.status)
        assertEquals(PermissionAsk.SETTINGS, flow.state.value.ask)
        assertFalse(results.single().usable)
        assertTrue(location.isUsableOn(device))
    }

    @Test fun `the foreground refused, the background is never asked for`() {
        val flow = flow(widgets)
        flow.start()
        device.rationale += ACCESS_COARSE_LOCATION
        device.answer(flow, after = 3000)
        assertEquals(PermissionKind.Location(), flow.state.value.stage)
        assertEquals(PermissionStatus.DENIED, flow.state.value.status)
        assertEquals(listOf(ACCESS_COARSE_LOCATION), device.launched)
        assertFalse(results.single().usable)
    }

    @Test fun `notifications before Android 13, nothing to ask, the feature runs`() {
        device.sdk = 32
        val flow = flow(AppPermission(PermissionKind.Notifications, texts))
        assertEquals(PermissionStatus.NOT_NEEDED, flow.state.value.status)
        flow.start()
        assertNull(device.launched)
        assertTrue(results.single().usable)

        device.sdk = 33
        val later = flow(AppPermission(PermissionKind.Notifications, texts))
        later.start()
        assertEquals(PermissionAsk.REASON, later.state.value.ask)
        later.proceed()
        assertEquals(listOf(POST_NOTIFICATIONS), device.launched)
    }

    @Test fun `approximate instead of precise, usable, said once, never asked for in a loop`() {
        device.grant(ACCESS_COARSE_LOCATION)
        val flow = flow(AppPermission(PermissionKind.Location(Precision.PRECISE), texts))
        assertEquals(PermissionStatus.PARTIAL, flow.state.value.status)
        assertTrue(flow.state.value.usable)
        assertEquals(7, PermissionStatus.PARTIAL.textRes(flow.texts))

        flow.start()
        assertNull(device.launched)
        assertTrue(results.single().usable)
    }

    @Test fun `precise required, approximate is asked for once more, a silent answer blocks`() {
        device.grant(ACCESS_COARSE_LOCATION)
        val flow = flow(AppPermission(PermissionKind.Location(Precision.PRECISE_REQUIRED), texts))
        assertFalse(flow.state.value.usable)
        flow.start()
        assertEquals(listOf(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION), device.launched)
        device.answer(flow, after = 200)
        assertEquals(PermissionStatus.BLOCKED, flow.state.value.status)
        assertEquals(PermissionAsk.SETTINGS, flow.state.value.ask)
    }

    @Test fun `selected photos on Android 14 are partial and usable`() {
        device.grant(READ_MEDIA_VISUAL_USER_SELECTED)
        val flow = flow(AppPermission(PermissionKind.Media(), texts))
        assertEquals(PermissionStatus.PARTIAL, flow.state.value.status)
        assertTrue(flow.state.value.usable)
        device.grant(READ_MEDIA_IMAGES)
        flow.refresh()
        assertEquals(PermissionStatus.GRANTED, flow.state.value.status)
    }

    @Test fun `a stale mark goes once the system asks again or granted`() {
        device.mark(PermissionKind.Camera)
        device.rationale += CAMERA
        val flow = flow(camera)
        assertEquals(PermissionStatus.DENIED, flow.state.value.status)
        assertFalse(device.marked(PermissionKind.Camera))
    }

    @Test fun `an answer after the screen was rebuilt still finishes`() {
        val flow = flow(cameraOnScreen)
        flow.start()
        val rebuilt = flow(cameraOnScreen)
        device.grant(CAMERA)
        rebuilt.onAnswer(mapOf(CAMERA to true))
        assertTrue(rebuilt.state.value.usable)
        assertTrue(results.single().usable)
    }

    @Test fun `a second tap while the dialog stands does nothing, dismiss closes the reason`() {
        val flow = flow(camera)
        flow.start()
        flow.dismiss()
        assertNull(flow.state.value.ask)
        flow.start()
        flow.proceed()
        device.launched = null
        flow.start()
        flow.proceed()
        assertNull(device.launched)
        // A resume while the dialog stands keeps waiting for the answer.
        flow.refresh()
        assertTrue(flow.state.value.busy)
    }

    @Test fun `the reason survives a rebuild through the initial ask`() {
        val flow = flow(camera, initialAsk = PermissionAsk.REASON)
        assertEquals(PermissionAsk.REASON, flow.state.value.ask)
        device.grant(CAMERA)
        flow.refresh()
        assertNull(flow.state.value.ask)
    }

    private fun AppPermission.isUsableOn(platform: PermissionPlatform): Boolean {
        val granted = granted(platform)
        return PermissionPolicy.stage(kind, granted, platform.sdk) == kind && PermissionPolicy.usable(kind, granted, platform.sdk)
    }

    /** A device whose grants, rationales, clock and marks the test sets. */
    class FakeDevice : PermissionPlatform {
        override var sdk = 34
        private val granted = mutableSetOf<String>()
        val rationale = mutableSetOf<String>()
        private val marks = mutableSetOf<String>()
        private var clock = 10_000L
        var launched: List<String>? = null
        var settingsOpenedFor: PermissionKind? = null

        fun grant(vararg permissions: String) = granted.addAll(permissions)
        fun revokeAll() = granted.clear()
        fun mark(stage: PermissionKind) = marks.add(stage.permissions(sdk).first())
        fun marked(stage: PermissionKind) = stage.permissions(sdk).firstOrNull() in marks

        /** The system answers the open request after [after] milliseconds with the grants as they stand. */
        fun answer(flow: PermissionFlow, after: Long = 3000) {
            clock += after
            flow.onAnswer(launched.orEmpty().associateWith { it in granted })
        }

        override fun isGranted(permission: String) = permission in granted
        override fun showsRationale(permission: String) = permission in rationale
        override fun now() = clock
        override fun isMarkedBlocked(stage: PermissionKind) = stage.permissions(sdk).firstOrNull() in marks
        override fun markBlocked(stage: PermissionKind, blocked: Boolean) {
            val key = stage.permissions(sdk).firstOrNull() ?: return
            if (blocked) marks += key else marks -= key
        }
        override fun launch(permissions: List<String>) { launched = permissions }
        override fun openSettings(stage: PermissionKind) { settingsOpenedFor = stage }
        override fun settingsOption(stage: PermissionKind) = "Allow all the time"
    }
}
