package one.tearoom.permissions

/** Where one kind of permission stands, read from the system every time, never cached across starts. */
enum class PermissionStatus {
    /** Everything the kind asks for is granted. "Only this time" counts as granted until the app is left. */
    GRANTED,

    /** Less than asked for, but something: the approximate location, the selected photos. */
    PARTIAL,

    /** This Android version has no such runtime permission; the feature just works. */
    NOT_NEEDED,

    /** Never asked on this install, or Android reset it: a request shows the system dialog. */
    NOT_ASKED,

    /** Refused once. Android would show its dialog again; the app's reason comes first. */
    DENIED,

    /**
     * Refused twice, or "Don't allow" in the settings: Android answers a request without showing
     * anything, and only the settings page can change it.
     */
    BLOCKED,
}

/** What a surface shows right now, between the tap on the feature and the system's answer. */
enum class PermissionAsk {
    /** The app's reason in one sentence, before the system dialog; "Continue" opens the dialog. */
    REASON,

    /** The permission is blocked: the settings path, and a button that opens the settings. */
    SETTINGS,
}

/**
 * Everything a permission surface shows.
 *
 * @param kind the permission as the app declared it.
 * @param stage the kind that is being asked for now: for the background location first the
 *   foreground location, then the background; otherwise [kind] itself.
 * @param status where [stage] stands.
 * @param ask what the surface shows now; null for nothing.
 * @param busy the system dialog or the settings page is open; a tap on the feature waits.
 * @param confirmed Android confirmed [status] to this process. False only for [PermissionStatus.BLOCKED]
 *   that rests on the mark of an earlier process, which may be stale (a reinstall with a backup,
 *   Android's reset of an unused app): a surface then offers to ask once more, and the flow tries
 *   Android before it names the settings path.
 * @param partial part of the kind is granted (the approximate location, the selected photos), whatever
 *   [status] says about the rest: "approximate allowed, precise refused" is [PermissionStatus.DENIED]
 *   with this set, a blocked precise location with approximate granted is [PermissionStatus.BLOCKED].
 */
data class PermissionState(
    val kind: PermissionKind,
    val stage: PermissionKind,
    val status: PermissionStatus,
    val ask: PermissionAsk? = null,
    val busy: Boolean = false,
    val confirmed: Boolean = true,
    val partial: Boolean = false,
) {
    /** The feature may run: the whole kind is granted, not needed here, or partially granted where that is enough. */
    val usable: Boolean
        get() = stage == kind && when (status) {
            PermissionStatus.GRANTED, PermissionStatus.NOT_NEEDED -> true
            PermissionStatus.PARTIAL -> stage.partialUsable
            else -> false
        }
}
