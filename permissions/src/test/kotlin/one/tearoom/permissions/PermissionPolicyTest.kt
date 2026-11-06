package one.tearoom.permissions

import android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
import one.tearoom.permissions.PermissionPolicy.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {
    private val coarse = PermissionKind.Location()
    private val precise = PermissionKind.Location(Precision.PRECISE)
    private val required = PermissionKind.Location(Precision.PRECISE_REQUIRED)
    private val background = PermissionKind.BackgroundLocation()

    @Test fun `status from what the system says`() {
        assertEquals(PermissionStatus.NOT_NEEDED, PermissionPolicy.status(PermissionKind.Notifications, emptySet(), emptySet(), false, 32))
        assertEquals(PermissionStatus.GRANTED, PermissionPolicy.status(coarse, setOf(ACCESS_COARSE_LOCATION), emptySet(), false, 34))
        assertEquals(PermissionStatus.PARTIAL, PermissionPolicy.status(precise, setOf(ACCESS_COARSE_LOCATION), emptySet(), false, 34))
        assertEquals(PermissionStatus.DENIED, PermissionPolicy.status(coarse, emptySet(), setOf(ACCESS_COARSE_LOCATION), false, 34))
        assertEquals(PermissionStatus.BLOCKED, PermissionPolicy.status(coarse, emptySet(), emptySet(), true, 34))
        assertEquals(PermissionStatus.NOT_ASKED, PermissionPolicy.status(coarse, emptySet(), emptySet(), false, 34))
    }

    @Test fun `a partial grant that is enough counts before a rationale or a mark, one that is not enough after`() {
        assertEquals(PermissionStatus.PARTIAL, PermissionPolicy.status(precise, setOf(ACCESS_COARSE_LOCATION), setOf(ACCESS_FINE_LOCATION), true, 34))
        assertEquals(PermissionStatus.PARTIAL, PermissionPolicy.status(required, setOf(ACCESS_COARSE_LOCATION), emptySet(), false, 34))
        assertEquals(PermissionStatus.DENIED, PermissionPolicy.status(required, setOf(ACCESS_COARSE_LOCATION), setOf(ACCESS_FINE_LOCATION), false, 34))
        assertEquals(PermissionStatus.BLOCKED, PermissionPolicy.status(required, setOf(ACCESS_COARSE_LOCATION), emptySet(), true, 34))
        assertEquals(PermissionStatus.PARTIAL, PermissionPolicy.status(PermissionKind.Media(), setOf(READ_MEDIA_VISUAL_USER_SELECTED), emptySet(), false, 34))
        assertEquals(PermissionStatus.GRANTED, PermissionPolicy.status(PermissionKind.Media(), setOf(READ_MEDIA_VISUAL_USER_SELECTED, READ_MEDIA_IMAGES), emptySet(), false, 34))
    }

    @Test fun `usable allows partial grants only where the kind says so`() {
        assertTrue(PermissionPolicy.usable(precise, setOf(ACCESS_COARSE_LOCATION), 34))
        assertFalse(PermissionPolicy.usable(required, setOf(ACCESS_COARSE_LOCATION), 34))
        assertTrue(PermissionPolicy.usable(required, setOf(ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION), 34))
        assertTrue(PermissionPolicy.usable(PermissionKind.Notifications, emptySet(), 32))
        assertFalse(PermissionPolicy.usable(PermissionKind.Notifications, emptySet(), 33))
    }

    @Test fun `the background location is asked for only after the foreground location`() {
        assertEquals(coarse, PermissionPolicy.stage(background, emptySet(), 34))
        assertEquals(background, PermissionPolicy.stage(background, setOf(ACCESS_COARSE_LOCATION), 34))
        // Precise required: approximate alone keeps the flow at the first step.
        val requiredBackground = PermissionKind.BackgroundLocation(Precision.PRECISE_REQUIRED)
        assertEquals(required, PermissionPolicy.stage(requiredBackground, setOf(ACCESS_COARSE_LOCATION), 34))
        assertEquals(requiredBackground, PermissionPolicy.stage(requiredBackground, setOf(ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION), 34))
        // Android 9: the foreground location covers the background, nothing more to ask.
        assertEquals(PermissionStatus.NOT_NEEDED, PermissionPolicy.status(background, setOf(ACCESS_COARSE_LOCATION), emptySet(), false, 28))
        assertEquals(PermissionStatus.NOT_ASKED, PermissionPolicy.status(background, setOf(ACCESS_COARSE_LOCATION), emptySet(), false, 29))
        assertEquals(PermissionStatus.GRANTED, PermissionPolicy.status(background, setOf(ACCESS_COARSE_LOCATION, ACCESS_BACKGROUND_LOCATION), emptySet(), false, 34))
    }

    @Test fun `an answer without a dialog, the second refusal, or a silent one`() {
        // The second "Don't allow": Android explained before, not after.
        assertTrue(PermissionPolicy.answeredWithoutDialog(usableNow = false, rationaleBefore = true, rationaleAfter = false, elapsedMillis = 3000))
        // A later process, blocked before: nothing explained, the answer comes at once.
        assertTrue(PermissionPolicy.answeredWithoutDialog(usableNow = false, rationaleBefore = false, rationaleAfter = false, elapsedMillis = 400))
        // The first refusal: Android explains from now on.
        assertFalse(PermissionPolicy.answeredWithoutDialog(usableNow = false, rationaleBefore = false, rationaleAfter = true, elapsedMillis = 400))
        // A dialog dismissed slowly without an answer: not blocked, Android asks again.
        assertFalse(PermissionPolicy.answeredWithoutDialog(usableNow = false, rationaleBefore = false, rationaleAfter = false, elapsedMillis = 2500))
        // A grant is never blocked, however fast.
        assertFalse(PermissionPolicy.answeredWithoutDialog(usableNow = true, rationaleBefore = true, rationaleAfter = false, elapsedMillis = 10))
    }

    @Test fun `what a tap on the feature does`() {
        assertEquals(Move.DONE, PermissionPolicy.onStart(PermissionStatus.GRANTED, true, Explain.SHEET))
        assertEquals(Move.DONE, PermissionPolicy.onStart(PermissionStatus.NOT_NEEDED, true, Explain.SCREEN))
        assertEquals(Move.EXPLAIN, PermissionPolicy.onStart(PermissionStatus.NOT_ASKED, true, Explain.SHEET))
        assertEquals(Move.LAUNCH, PermissionPolicy.onStart(PermissionStatus.NOT_ASKED, true, Explain.SCREEN))
        assertEquals(Move.EXPLAIN, PermissionPolicy.onStart(PermissionStatus.DENIED, true, Explain.SHEET))
        assertEquals(Move.LAUNCH, PermissionPolicy.onStart(PermissionStatus.DENIED, true, Explain.SCREEN))
        assertEquals(Move.SETTINGS, PermissionPolicy.onStart(PermissionStatus.BLOCKED, true, Explain.SHEET))
        assertEquals(Move.SETTINGS, PermissionPolicy.onStart(PermissionStatus.BLOCKED, true, Explain.SCREEN))
        // Approximate where precise is enough: done, never asked for in a loop; where it is required, once more.
        assertEquals(Move.DONE, PermissionPolicy.onStart(PermissionStatus.PARTIAL, true, Explain.SHEET))
        assertEquals(Move.LAUNCH, PermissionPolicy.onStart(PermissionStatus.PARTIAL, false, Explain.SHEET))
    }
}
