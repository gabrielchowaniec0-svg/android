package com.kinetic.launcher.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Android or a Samsung update sometimes switches accessibility services off by
 * itself. Then the island, Kinetic's status bar and the volume slider vanish
 * without a word, which is worse if Samsung's own icons are hidden. This notices
 * and says so (once each time it happens).
 */
object IslandWatch {
    private const val ID = 4401
    private fun prefs(c: Context) = com.kinetic.launcher.KineticApp.pen(c)

    /** The island is running: remember that it was wanted, and clear any notice. */
    fun on(c: Context) {
        prefs(c).edit().putBoolean("island_wanted", true).putBoolean("island_off_told", false).apply()
        runCatching { c.getSystemService(NotificationManager::class.java).cancel(ID) }
    }

    /** Called when Kinetic opens and after a restart or update. */
    fun check(c: Context) {
        val p = prefs(c)
        if (!p.getBoolean("island_wanted", false) || p.getBoolean("island_off_told", false)) return
        if (DynamicIslandService.isEnabled(c)) return
        p.edit().putBoolean("island_off_told", true).apply()
        val nm = c.getSystemService(NotificationManager::class.java)
        val title = "The Dynamic Island was switched off"
        val text = "Android turned it off (often after an update), so the island, status bar and volume slider are gone. Tap to switch it back on."
        if (!nm.areNotificationsEnabled()) {
            Toast.makeText(c.applicationContext, "$title. Switch it back on in Kinetic settings ▸ Setup", Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            nm.createNotificationChannel(NotificationChannel("kinetic_setup", "Setup problems", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                c, ID,
                Intent(c, IslandSettingsTrampoline::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            nm.notify(
                ID,
                Notification.Builder(c, "kinetic_setup").setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle(title).setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open).setAutoCancel(true).build(),
            )
        }
    }
}

/** Opens the island's switch from the notice (a notification can't call openSettings directly). */
class IslandSettingsTrampoline : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicIslandService.openSettings(this)
        finish()
    }
}
