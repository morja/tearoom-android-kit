package one.tearoom.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionTextsTest {
    // Ids in declaration order, so each status must land on "its" field.
    private val texts = PermissionTexts(reason = 1, denied = 2, blocked = 3, allow = 4, openSettings = 5, notNow = 6, partial = 7)

    @Test fun `every status that needs a word has its own text`() {
        assertEquals(1, PermissionStatus.NOT_ASKED.textRes(texts))
        assertEquals(2, PermissionStatus.DENIED.textRes(texts))
        assertEquals(3, PermissionStatus.BLOCKED.textRes(texts))
        assertEquals(7, PermissionStatus.PARTIAL.textRes(texts))
        assertNull(PermissionStatus.GRANTED.textRes(texts))
        assertNull(PermissionStatus.NOT_NEEDED.textRes(texts))
    }

    @Test fun `the texts of the step, the first permission's for its kind`() {
        val foreground = AppPermission(PermissionKind.Location(), texts)
        val background = AppPermission(PermissionKind.BackgroundLocation(), texts.copy(reason = 11), first = foreground)
        assertEquals(1, background.textsFor(PermissionKind.Location()).reason)
        assertEquals(11, background.textsFor(PermissionKind.BackgroundLocation()).reason)
        assertEquals(1, foreground.textsFor(PermissionKind.Location()).reason)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the background location needs its first step`() {
        AppPermission(PermissionKind.BackgroundLocation(), texts)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the first step must match the precision`() {
        AppPermission(PermissionKind.BackgroundLocation(Precision.PRECISE), texts, first = AppPermission(PermissionKind.Location(), texts))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `other kinds take no first step`() {
        AppPermission(PermissionKind.Camera, texts, first = AppPermission(PermissionKind.Location(), texts))
    }
}
