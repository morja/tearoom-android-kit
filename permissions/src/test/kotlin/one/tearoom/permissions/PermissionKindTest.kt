package one.tearoom.permissions

import android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.Manifest.permission.NEARBY_WIFI_DEVICES
import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.READ_MEDIA_AUDIO
import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VIDEO
import android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Android version branches of every kind, on the JVM. */
class PermissionKindTest {
    @Test fun `location asks for coarse alone or fine with coarse`() {
        assertEquals(listOf(ACCESS_COARSE_LOCATION), PermissionKind.Location().permissions(34))
        assertEquals(listOf(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION), PermissionKind.Location(Precision.PRECISE).permissions(34))
        assertEquals(listOf(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION), PermissionKind.Location(Precision.PRECISE_REQUIRED).permissions(29))
    }

    @Test fun `approximate instead of precise is partial, usable unless required`() {
        val coarseOnly = setOf(ACCESS_COARSE_LOCATION)
        assertFalse(PermissionKind.Location().partial(coarseOnly, 34))
        assertTrue(PermissionKind.Location(Precision.PRECISE).partial(coarseOnly, 34))
        assertTrue(PermissionKind.Location(Precision.PRECISE).partialUsable)
        assertTrue(PermissionKind.Location(Precision.PRECISE_REQUIRED).partial(coarseOnly, 34))
        assertFalse(PermissionKind.Location(Precision.PRECISE_REQUIRED).partialUsable)
    }

    @Test fun `background location is a second step from Android 10, none before`() {
        assertEquals(listOf(ACCESS_BACKGROUND_LOCATION), PermissionKind.BackgroundLocation().permissions(29))
        assertEquals(emptyList<String>(), PermissionKind.BackgroundLocation().permissions(28))
        assertEquals(PermissionKind.Location(Precision.PRECISE), PermissionKind.BackgroundLocation(Precision.PRECISE).first(34))
    }

    @Test fun `notifications are a runtime permission from Android 13`() {
        assertEquals(emptyList<String>(), PermissionKind.Notifications.permissions(32))
        assertEquals(listOf(POST_NOTIFICATIONS), PermissionKind.Notifications.permissions(33))
    }

    @Test fun `media follows the storage, the media and the partial-access eras`() {
        assertEquals(listOf(READ_EXTERNAL_STORAGE), PermissionKind.Media().permissions(32))
        assertEquals(listOf(READ_MEDIA_IMAGES), PermissionKind.Media().permissions(33))
        assertEquals(listOf(READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED), PermissionKind.Media().permissions(34))
        assertEquals(
            listOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_AUDIO, READ_MEDIA_VISUAL_USER_SELECTED),
            PermissionKind.Media(images = true, video = true, audio = true).permissions(34),
        )
        assertEquals(listOf(READ_MEDIA_AUDIO), PermissionKind.Media(images = false, audio = true).permissions(34))
    }

    @Test fun `selected photos on Android 14 are partial and usable`() {
        val selected = setOf(READ_MEDIA_VISUAL_USER_SELECTED)
        assertTrue(PermissionKind.Media().partial(selected, 34))
        assertFalse(PermissionKind.Media().partial(selected, 33))
        assertFalse(PermissionKind.Media().partial(selected + READ_MEDIA_IMAGES, 34))
        assertTrue(PermissionKind.Media().partialUsable)
    }

    @Test fun `nearby devices need bluetooth permissions from Android 12, the fine location before`() {
        assertEquals(listOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT), PermissionKind.NearbyDevices().permissions(31))
        assertEquals(listOf(ACCESS_FINE_LOCATION), PermissionKind.NearbyDevices().permissions(30))
        assertEquals(listOf(BLUETOOTH_CONNECT), PermissionKind.NearbyDevices(scan = false).permissions(34))
        assertEquals(emptyList<String>(), PermissionKind.NearbyDevices(scan = false).permissions(30))
    }

    @Test fun `wifi discovery has its own permission from Android 13`() {
        assertEquals(listOf(NEARBY_WIFI_DEVICES), PermissionKind.NearbyDevices(scan = false, connect = false, wifi = true).permissions(33))
        assertEquals(listOf(ACCESS_FINE_LOCATION), PermissionKind.NearbyDevices(scan = false, connect = false, wifi = true).permissions(31))
        // Below 12 scan and wifi share the fine location, asked once.
        assertEquals(listOf(ACCESS_FINE_LOCATION), PermissionKind.NearbyDevices(wifi = true).permissions(30))
    }

    @Test fun `camera and microphone are one permission each`() {
        assertEquals(listOf(android.Manifest.permission.CAMERA), PermissionKind.Camera.permissions(26))
        assertEquals(listOf(android.Manifest.permission.RECORD_AUDIO), PermissionKind.Microphone.permissions(36))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `media must ask for something`() {
        PermissionKind.Media(images = false)
    }
}
