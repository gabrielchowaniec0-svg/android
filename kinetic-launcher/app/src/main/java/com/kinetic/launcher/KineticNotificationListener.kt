package com.kinetic.launcher

import android.app.Notification
import android.app.PendingIntent
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

    /** A phone call in progress or ringing (shown in the Dynamic Island). */
    data class CallInfo(
        val key: String,
        val name: String,
        /** When the call connected (System.currentTimeMillis), or null while it's ringing. */
        val startedAt: Long?,
        val incoming: Boolean,
        val packageName: String,
        val contentIntent: PendingIntent?,
        /** The call notification's own buttons (Answer, Decline, Hang up…). */
        val actions: List<Notification.Action>,
    )

    private val _connected = MutableStateFlow(false)
    /** True while Kinetic's notification listener is running (Notification access is on). */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    internal fun setConnected(on: Boolean) {
        _connected.value = on
    }

    private val _call = MutableStateFlow<CallInfo?>(null)
    val call: StateFlow<CallInfo?> = _call.asStateFlow()

    internal fun setCall(call: CallInfo?) {
        _call.value = call
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

    override fun onListenerConnected() {
        NotificationHub.setConnected(true)
        refresh()
    }

    override fun onListenerDisconnected() {
        NotificationHub.set(emptySet())
        NotificationHub.setCall(null)
        NotificationHub.setConnected(false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    private fun refresh() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        // Only things the user can clear: skips "music playing", "charging" and similar.
        NotificationHub.set(active.filter { it.isClearable }.mapTo(HashSet()) { it.packageName })
        NotificationHub.setCall(active.firstOrNull { it.notification.category == Notification.CATEGORY_CALL }?.let(::callInfo))
    }

    private fun callInfo(sbn: StatusBarNotification): NotificationHub.CallInfo {
        val n = sbn.notification
        val extras = n.extras
        val name = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() } ?: "Call"
        val callType = extras.getInt(Notification.EXTRA_CALL_TYPE, 0)
        val timing = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        val incoming = callType == Notification.CallStyle.CALL_TYPE_INCOMING || (callType == 0 && !timing)
        return NotificationHub.CallInfo(
            key = sbn.key,
            name = name,
            startedAt = if (timing && !incoming) n.`when` else null,
            incoming = incoming,
            packageName = sbn.packageName,
            contentIntent = n.contentIntent,
            actions = n.actions?.toList().orEmpty(),
        )
    }
}
