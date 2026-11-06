package one.tearoom.permissions

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity

/** Where the app's reason is said before the system dialog. */
enum class Explain {
    /** The kit asks for its reason sheet before the first dialog and after a refusal (`PermissionAsk.REASON`). */
    SHEET,

    /** The screen that starts the feature carries the reason; the dialog opens at once. Only the settings path is asked for. */
    SCREEN,
}

/**
 * One permission of an app: what it asks for ([kind]), what it says ([texts]) and how ([explain]).
 * Declare one per kind as a top-level value and use it everywhere:
 *
 * ```kotlin
 * val DeviceLocation = AppPermission(PermissionKind.Location(), PermissionTexts(...))
 * val WidgetLocation = AppPermission(PermissionKind.BackgroundLocation(), PermissionTexts(...), first = DeviceLocation)
 * ```
 *
 * Screens take a [PermissionFlow] from [request] (or the kit's `rememberPermissionFlow`) and call
 * `start()` when the user starts the feature; widgets, workers and services read [isUsable], which
 * asks the system and never a cache (skill android-permissions: nothing is cached across starts).
 *
 * @param first the permission that must be usable before this one: the foreground location for the
 *   background location, with its own texts for that step. Required for `BackgroundLocation`, not
 *   allowed otherwise.
 */
class AppPermission(
    val kind: PermissionKind,
    val texts: PermissionTexts,
    val explain: Explain = Explain.SHEET,
    val first: AppPermission? = null,
) {
    init {
        val needed = kind.first(Build.VERSION_CODES.Q)
        require((needed == null) == (first == null)) {
            if (needed == null) "${kind::class.simpleName} has no first step; drop `first`" else "${kind::class.simpleName} needs `first`, the ${needed::class.simpleName} it builds on"
        }
        require(first == null || first.kind == needed) { "`first` must be $needed, not ${first?.kind}" }
    }

    /** The texts of the step being asked for: [first]'s for its kind, else this permission's. */
    fun textsFor(stage: PermissionKind): PermissionTexts = if (first != null && stage == first.kind) first.texts else texts

    /** The feature may run now: granted, not a runtime permission here, or partially granted where that is enough. From any thread. */
    fun isUsable(context: Context): Boolean {
        val sdk = Build.VERSION.SDK_INT
        val granted = granted(context, sdk)
        return PermissionPolicy.stage(kind, granted, sdk) == kind && PermissionPolicy.usable(kind, granted, sdk)
    }

    /** Everything granted in full, the prerequisite included: the precise location, all photos. From any thread. */
    fun isGranted(context: Context): Boolean {
        val sdk = Build.VERSION.SDK_INT
        val granted = granted(context, sdk)
        return steps(sdk).all { step -> step.permissions(sdk).all { it in granted } }
    }

    /** The flow for an activity: register it in `onCreate` or as a property, before the activity starts. */
    fun request(activity: ComponentActivity, onResult: (PermissionState) -> Unit = {}): PermissionFlow =
        activityFlow(activity, activity.activityResultRegistry, onResult)

    internal fun steps(sdk: Int): List<PermissionKind> = listOfNotNull(kind.first(sdk), kind)

    internal fun granted(context: Context, sdk: Int): Set<String> =
        steps(sdk).flatMap { it.permissions(sdk) }
            .filter { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            .toSet()
}
