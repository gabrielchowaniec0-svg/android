package com.kinetic.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Turns the Focus off when a "focus 25 min" session ends. */
class FocusEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FocusModes.init(context)
        com.kinetic.launcher.island.FocusLive.finished(context)
        FocusModes.turnOff(context)
    }

    companion object {
        fun schedule(context: Context, seconds: Int) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                context, 7, Intent(context, FocusEndReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + seconds * 1000L, pi)
        }
    }
}
