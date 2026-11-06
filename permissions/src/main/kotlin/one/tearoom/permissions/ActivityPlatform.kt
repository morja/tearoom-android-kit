package one.tearoom.permissions

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * The device behind a [PermissionFlow]: the activity's grants and rationales, the kit's marks in
 * their own preferences file, and Android's settings. [launcher] opens the system dialog; the
 * binding that owns the Activity Result launcher passes it in.
 */
class ActivityPlatform(private val activity: Activity, private val launcher: (List<String>) -> Unit) : PermissionPlatform {
    override val sdk: Int get() = Build.VERSION.SDK_INT

    override fun isGranted(permission: String) = activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun showsRationale(permission: String) = activity.shouldShowRequestPermissionRationale(permission)

    private val marks get() = activity.getSharedPreferences(MARKS, Context.MODE_PRIVATE)

    override fun isMarkedBlocked(stage: PermissionKind) = key(stage)?.let { marks.getBoolean(it, false) } ?: false

    override fun markBlocked(stage: PermissionKind, blocked: Boolean) {
        val key = key(stage) ?: return
        marks.edit().apply { if (blocked) putBoolean(key, true) else remove(key) }.apply()
    }

    override fun launch(permissions: List<String>) = launcher(permissions)

    override fun openSettings(stage: PermissionKind) = PermissionSettings.open(activity, stage)

    override fun settingsOption(stage: PermissionKind) = PermissionSettings.option(activity, stage)

    private fun key(stage: PermissionKind) = stage.permissions(sdk).firstOrNull()

    private companion object {
        /** The kit's own file, so an app's preferences never carry a permission flag (skill android-permissions). */
        const val MARKS = "one.tearoom.permissions"
    }
}

/** Android's settings for a permission, and the name of the option to pick there. */
object PermissionSettings {
    /**
     * The page where [stage] is changed: the app's notification settings for notifications (the toggle
     * is right there); App info for everything else, since the permission pages themselves are not
     * public. The app's text names the rest of the way.
     */
    fun intent(context: Context, stage: PermissionKind): Intent = when (stage) {
        PermissionKind.Notifications -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    }

    /** Opens [intent]; from an activity on top of it, so back returns to the screen. */
    fun open(context: Context, stage: PermissionKind) {
        val intent = intent(context, stage)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * The option the settings path names for [stage]: for the background location Android's own name
     * of "Allow all the time" in the language of the settings; for the other kinds the label of the
     * permission's group ("Location", "Notifications"). Empty when Android has none.
     */
    fun option(context: Context, stage: PermissionKind): String = when (stage) {
        is PermissionKind.BackgroundLocation -> backgroundOptionLabel(context)
        else -> groupLabel(context, stage)
    }

    /**
     * `backgroundPermissionOptionLabel` follows the app's language, the settings page the system's;
     * where the two differ the label is read from the permission controller with the system locales
     * (taken over from Weatherfold, 5 November 2026).
     */
    private fun backgroundOptionLabel(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        val label = runCatching { context.packageManager.backgroundPermissionOptionLabel.toString() }.getOrDefault("")
        val system = systemLocales(context)
        if (context.resources.configuration.locales == system) return label
        return runCatching {
            val controller = context.packageManager.resolveActivity(Intent(REQUEST_PERMISSIONS), 0)!!.activityInfo.packageName
            val resources = context.createPackageContext(controller, 0)
                .createConfigurationContext(Configuration().apply { setLocales(system) }).resources
            resources.getString(resources.getIdentifier("app_permission_button_allow_always", "string", controller))
        }.getOrDefault(label)
    }

    /**
     * The label of the permission group, as Android's settings list it ("Location", "Notifications").
     * `PermissionInfo.group` reports UNDEFINED for platform permissions since Android 10, so the group
     * is named here per kind; the label comes from the framework in the app's language.
     */
    private fun groupLabel(context: Context, stage: PermissionKind): String {
        val sdk = Build.VERSION.SDK_INT
        val group = when (stage) {
            is PermissionKind.Location, is PermissionKind.BackgroundLocation -> "LOCATION"
            PermissionKind.Notifications -> "NOTIFICATIONS"
            PermissionKind.Camera -> "CAMERA"
            PermissionKind.Microphone -> "MICROPHONE"
            PermissionKind.PhysicalActivity -> "ACTIVITY_RECOGNITION"
            is PermissionKind.NearbyDevices -> if (sdk >= 31) "NEARBY_DEVICES" else "LOCATION"
            is PermissionKind.Media -> when {
                sdk < 33 -> "STORAGE"
                stage.images || stage.video -> "READ_MEDIA_VISUAL"
                else -> "READ_MEDIA_AURAL"
            }
        }
        val pm = context.packageManager
        return runCatching { pm.getPermissionGroupInfo("android.permission-group.$group", 0).loadLabel(pm).toString() }.getOrDefault("")
    }

    private fun systemLocales(context: Context): LocaleList {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return Resources.getSystem().configuration.locales
        return context.getSystemService(LocaleManager::class.java)?.systemLocales ?: Resources.getSystem().configuration.locales
    }

    private const val REQUEST_PERMISSIONS = "android.content.pm.action.REQUEST_PERMISSIONS"
}

/**
 * The flow for an activity, with the Activity Result launcher registered in [registry] (register
 * before the activity starts), [PermissionFlow.refresh] on every resume and [PermissionFlow.paused]
 * on every pause.
 */
internal fun AppPermission.activityFlow(
    activity: ComponentActivity,
    registry: ActivityResultRegistry,
    onResult: (PermissionState) -> Unit,
): PermissionFlow {
    var flow: PermissionFlow? = null
    val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions(), registry) { answer ->
        flow?.onAnswer(answer)
    }
    val created = PermissionFlow(this, ActivityPlatform(activity) { launcher.launch(it.toTypedArray()) }, onResult = onResult)
    flow = created
    activity.lifecycle.addObserver(
        LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> created.refresh()
                Lifecycle.Event.ON_PAUSE -> created.paused()
                else -> Unit
            }
        },
    )
    return created
}

/** The flow for an activity with another result registry, for tests. */
fun AppPermission.request(activity: ComponentActivity, registry: ActivityResultRegistry, onResult: (PermissionState) -> Unit = {}): PermissionFlow =
    activityFlow(activity, registry, onResult)
