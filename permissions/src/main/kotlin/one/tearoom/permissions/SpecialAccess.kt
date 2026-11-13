package one.tearoom.permissions

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * The special app accesses of [PermissionKind.settingsOnly] kinds: whether Android has one switched
 * on, and the page of its settings where the user switches it (ADR-0007). Read from the system on
 * every call; nothing is cached.
 */
internal object SpecialAccess {
    /** Android has the access of [stage] switched on for this app; false for every other kind. */
    fun isOn(context: Context, stage: PermissionKind): Boolean = when (stage) {
        is PermissionKind.NotificationListener -> listenerOn(context, component(context, stage))
        PermissionKind.ExactAlarm -> exactAlarmOn(context)
        else -> false
    }

    /**
     * The "Alarms & reminders" page of the app (Android 12 and later). Below 12 there is no such access;
     * the flow never opens a page for it then, and App info stands in.
     */
    fun exactAlarmIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        }

    /** Android 12 and later ask AlarmManager; before that every app may schedule exact alarms. */
    private fun exactAlarmOn(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return runCatching { context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }.getOrDefault(false)
    }

    /**
     * The notification-access page of the app's own listener (Android 11 and later), else the list
     * of all notification listeners, where the app's name is the switch.
     */
    fun listenerIntent(context: Context, stage: PermissionKind.NotificationListener): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context, stage).flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }

    fun component(context: Context, stage: PermissionKind.NotificationListener) = ComponentName(context.packageName, stage.service)

    private fun listenerOn(context: Context, component: ComponentName): Boolean {
        // Android 8.1 asks NotificationManager for the app's own component; 8.0 only has the secure setting.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return runCatching { context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component) }.getOrDefault(false)
        }
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }
}
