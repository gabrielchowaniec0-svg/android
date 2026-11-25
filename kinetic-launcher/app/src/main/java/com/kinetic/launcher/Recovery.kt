package com.kinetic.launcher

import android.app.NotificationManager
import android.content.Context
import com.kinetic.launcher.ui.Homework
import com.kinetic.launcher.ui.SnoozeEndReceiver

/**
 * Puts timed things back after the phone restarts or Kinetic updates. Android
 * forgets every alarm on a restart, so without this a Focus session or a
 * notification snooze that was running would never end, and homework
 * reminders would never ring.
 */
object Recovery {
    private fun prefs(c: Context) = c.getSharedPreferences("kinetic_pen", Context.MODE_PRIVATE)

    fun run(context: Context) {
        runCatching { focus(context) }
        runCatching { snooze(context) }
        runCatching { Homework.rescheduleAll(context) }
    }

    private fun focus(context: Context) {
        val end = prefs(context).getLong("focus_end", 0L)
        if (end == 0L) return
        val left = end - System.currentTimeMillis()
        if (left <= 0) {
            // It ended while the phone was off: finish it quietly.
            FocusModes.init(context)
            FocusModes.turnOff(context)
            com.kinetic.launcher.island.FocusLive.clear(context)
        } else {
            FocusEndReceiver.schedule(context, ((left + 999) / 1000).toInt())
        }
    }

    private fun snooze(context: Context) {
        val until = prefs(context).getLong(SnoozeEndReceiver.KEY, 0L)
        if (until == 0L) return
        val left = until - System.currentTimeMillis()
        if (left <= 0) {
            prefs(context).edit().remove(SnoozeEndReceiver.KEY).apply()
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        } else {
            SnoozeEndReceiver.scheduleAt(context, until)
        }
    }
}
