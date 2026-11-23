package com.kinetic.launcher

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which apps currently have notifications waiting (drives the spinning rings on icons). */
object NotificationHub {
    private val _packages = MutableStateFlow<Set<String>>(emptySet())
    val packages: StateFlow<Set<String>> = _packages.asStateFlow()

    internal fun set(packages: Set<String>) {
        _packages.value = packages
    }

    /** True once the user has allowed Kinetic to see notifications. */
    fun isEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Opens the system page where the user turns on Kinetic's notification access. */
    fun openSettings(context: Context) {
        val component = ComponentName(context, KineticNotificationListener::class.java)
        val detail = android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(detail)
        } catch (e: Exception) {
            context.startActivity(
                android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/**
 * Lets Kinetic see *which apps* have notifications (not their contents) so it can
 * show a ring around those icons. Only runs if the user grants Notification access.
 */
class KineticNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() = refresh()

    override fun onListenerDisconnected() = NotificationHub.set(emptySet())

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    private fun refresh() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        // Only things the user can clear: skips "music playing", "charging" and similar.
        NotificationHub.set(active.filter { it.isClearable }.mapTo(HashSet()) { it.packageName })
    }
}
