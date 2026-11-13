package one.tearoom.permissions

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
        else -> false
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
