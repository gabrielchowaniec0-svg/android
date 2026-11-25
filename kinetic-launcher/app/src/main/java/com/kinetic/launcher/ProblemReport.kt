package com.kinetic.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.FileProvider
import com.kinetic.wallpaper.DiagLog
import kotlin.concurrent.thread

/** "report problem": bundles Kinetic's log into one file and opens the share sheet. */
object ProblemReport {
    fun send(context: Context) {
        val app = context.applicationContext
        Toast.makeText(app, "Making a problem report…", Toast.LENGTH_SHORT).show()
        thread(name = "problem-report") {
            val file = runCatching { DiagLog.buildReport(app, details(app)) }.getOrNull()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (file == null) {
                    Toast.makeText(app, "Couldn't make the report", Toast.LENGTH_LONG).show()
                    return@post
                }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.vaultfiles", file)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "Kinetic problem report")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching {
                    app.startActivity(Intent.createChooser(send, "Send the problem report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }

    /** What's switched on and allowed (helps explain most problems). No personal data. */
    private fun details(c: Context): List<String> {
        fun has(p: String) = if (c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) "yes" else "no"
        val power = c.getSystemService(android.os.PowerManager::class.java)
        val battery = c.getSystemService(android.os.BatteryManager::class.java)
        val home = runCatching {
            c.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName == c.packageName
        }.getOrDefault(false)
        return listOf(
            "Default home: $home",
            "Island service on: ${com.kinetic.launcher.island.DynamicIslandService.isEnabled(c)} (running: ${com.kinetic.launcher.island.DynamicIslandService.isRunning})",
            "Notification access: ${NotificationHub.isEnabled(c)}",
            "Permissions: camera ${has(Manifest.permission.CAMERA)}, location ${has(Manifest.permission.ACCESS_COARSE_LOCATION)}, " +
                "calendar ${has(Manifest.permission.READ_CALENDAR)}, photos ${has(Manifest.permission.READ_MEDIA_IMAGES)}, " +
                "notifications ${has(Manifest.permission.POST_NOTIFICATIONS)}, mic ${has(Manifest.permission.RECORD_AUDIO)}, sms ${has(Manifest.permission.SEND_SMS)}",
            "Power saving: ${power?.isPowerSaveMode}, battery ${battery?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)}%",
            "Focus: ${FocusModes.active.value?.name ?: "none"}",
            "Vault set up: ${com.kinetic.launcher.vault.VaultCrypto.isSetUp(c)}",
        )
    }
}
