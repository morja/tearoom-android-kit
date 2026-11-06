package one.tearoom.permissions

import android.Manifest

/**
 * One kind of runtime permission, as the apps use them: a feature asks for a kind, never for a
 * manifest permission by name. The kind knows which manifest permissions it means on each Android
 * version ([permissions]), which kind must be granted before it ([first]: the foreground location
 * before the background location) and when a grant is only part of what was asked ([partial]: the
 * approximate location, the selected photos). Only constants from `android.Manifest`, so the version
 * branches run in a plain JVM test.
 */
sealed class PermissionKind {
    /** The manifest permissions this kind asks for on [sdk]; empty when that Android version has no such runtime permission. */
    abstract fun permissions(sdk: Int): List<String>

    /** The kind that must be usable before this one is asked for; null for most kinds. */
    open fun first(sdk: Int): PermissionKind? = null

    /** Granted with only some of [permissions]: less than asked for, but something. */
    open fun partial(granted: Set<String>, sdk: Int): Boolean = false

    /** Whether a [partial] grant is enough for the feature. */
    open val partialUsable: Boolean get() = true

    /** The device location while the app is in use. */
    data class Location(val precision: Precision = Precision.APPROXIMATE) : PermissionKind() {
        override fun permissions(sdk: Int) =
            if (precision == Precision.APPROXIMATE) listOf(COARSE) else listOf(FINE, COARSE)

        /** The user chose approximate although precise was asked for. */
        override fun partial(granted: Set<String>, sdk: Int) =
            precision != Precision.APPROXIMATE && COARSE in granted && FINE !in granted

        override val partialUsable: Boolean get() = precision != Precision.PRECISE_REQUIRED
    }

    /**
     * "Allow all the time", the second step after [Location] (Android 10 and later; before that the
     * foreground location covers the background). Android never offers it in the first dialog, so the
     * flow asks for the foreground location first and for this one only once that is granted.
     */
    data class BackgroundLocation(val precision: Precision = Precision.APPROXIMATE) : PermissionKind() {
        override fun permissions(sdk: Int) = if (sdk >= 29) listOf(BACKGROUND) else emptyList()
        override fun first(sdk: Int): PermissionKind = Location(precision)
    }

    /** Posting notifications, a runtime permission since Android 13; before that nothing is asked. */
    data object Notifications : PermissionKind() {
        override fun permissions(sdk: Int) = if (sdk >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    }

    /**
     * Reading the user's photos, videos or audio from the media store. Prefer the photo picker and the
     * document picker, which need no permission; this kind is for an app that must read the library
     * itself. On Android 14 the user may grant selected photos only ([partial]).
     */
    data class Media(val images: Boolean = true, val video: Boolean = false, val audio: Boolean = false) : PermissionKind() {
        init {
            require(images || video || audio) { "Media asks for at least one of images, video, audio" }
        }

        override fun permissions(sdk: Int): List<String> = when {
            sdk >= 34 -> visual() + listOfNotNull(AUDIO.takeIf { audio }, USER_SELECTED.takeIf { images || video })
            sdk >= 33 -> visual() + listOfNotNull(AUDIO.takeIf { audio })
            else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        private fun visual() = listOfNotNull(IMAGES.takeIf { images }, VIDEO.takeIf { video })

        /**
         * "Select photos": Android 14 grants the selection and keeps the full library back. Only the visual
         * permissions can be partial; audio asked for alongside must be granted in full (REV-4 finding 7).
         */
        override fun partial(granted: Set<String>, sdk: Int) =
            sdk >= 34 && USER_SELECTED in granted && visual().any { it !in granted } && (!audio || AUDIO in granted)
    }

    /**
     * Bluetooth and Wi-Fi discovery and connection (Android 12 and later; before that Android ties
     * scanning to the fine location). Wi-Fi discovery on Android 12 still needs the location, fine
     * and coarse together. The app declares `neverForLocation` on `BLUETOOTH_SCAN` in its
     * manifest where it derives no location from the scan.
     */
    data class NearbyDevices(val scan: Boolean = true, val connect: Boolean = true, val wifi: Boolean = false) : PermissionKind() {
        init {
            require(scan || connect || wifi) { "NearbyDevices asks for at least one of scan, connect, wifi" }
        }

        override fun permissions(sdk: Int): List<String> = buildList {
            if (sdk >= 31) {
                if (scan) add(SCAN)
                if (connect) add(CONNECT)
            } else if (scan) {
                add(FINE)
            }
            if (wifi) {
                when {
                    sdk >= 33 -> add(NEARBY_WIFI)
                    // Android 12 ignores a request for the fine location without the coarse one (REV-4 finding 6).
                    sdk >= 31 -> addAll(listOf(FINE, COARSE))
                    FINE !in this -> add(FINE)
                }
            }
        }
    }

    /** The camera: ask on first use, handle a refusal, that is all. */
    data object Camera : PermissionKind() {
        override fun permissions(sdk: Int) = listOf(Manifest.permission.CAMERA)
    }

    /** The microphone: ask on first use, handle a refusal, that is all. */
    data object Microphone : PermissionKind() {
        override fun permissions(sdk: Int) = listOf(Manifest.permission.RECORD_AUDIO)
    }

    /** Step counting and activity recognition (a runtime permission since Android 10; before that nothing is asked). Lazy Moves' departures. */
    data object PhysicalActivity : PermissionKind() {
        override fun permissions(sdk: Int) = if (sdk >= 29) listOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyList()
    }

    private companion object {
        const val COARSE = Manifest.permission.ACCESS_COARSE_LOCATION
        const val FINE = Manifest.permission.ACCESS_FINE_LOCATION
        const val BACKGROUND = Manifest.permission.ACCESS_BACKGROUND_LOCATION
        const val IMAGES = Manifest.permission.READ_MEDIA_IMAGES
        const val VIDEO = Manifest.permission.READ_MEDIA_VIDEO
        const val AUDIO = Manifest.permission.READ_MEDIA_AUDIO
        const val USER_SELECTED = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        const val SCAN = Manifest.permission.BLUETOOTH_SCAN
        const val CONNECT = Manifest.permission.BLUETOOTH_CONNECT
        const val NEARBY_WIFI = Manifest.permission.NEARBY_WIFI_DEVICES
    }
}

/** How exact the location must be. Fine and coarse are asked together; the user may still choose approximate. */
enum class Precision {
    /** Coarse only, about city level; precise is never asked for. */
    APPROXIMATE,

    /** Precise asked for, approximate accepted: the app works with it and says what precise would improve. */
    PRECISE,

    /** Precise asked for and needed: approximate counts as missing, with the app's text why. */
    PRECISE_REQUIRED,
}
