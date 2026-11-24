package com.kinetic.launcher.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.kinetic.launcher.island.IslandBus

/**
 * Invisible screen behind the island's "Allow" button: asks Android for the permission
 * a command needs, then runs that command right away if you said yes. Special
 * permissions (Do Not Disturb, Modify system settings, notification access) open their
 * settings page instead, since Android only lets you switch those on there.
 */
class PermissionActivity : ComponentActivity() {

    private val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) retry() else IslandBus.say("✕", "Not allowed")
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra(EXTRA_KIND) ?: return finish()
        when (kind) {
            DND -> openPage(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            WRITE_SETTINGS -> openPage(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            NOTIFICATIONS -> openPage(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            else -> {
                if (ContextCompat.checkSelfPermission(this, kind) == PackageManager.PERMISSION_GRANTED) {
                    retry()
                    finish()
                } else {
                    ask.launch(kind)
                }
            }
        }
    }

    private fun openPage(i: Intent) {
        runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        IslandBus.say("⚙", "Switch Kinetic on, then try again")
        finish()
    }

    /** Runs the command that was waiting for this permission. */
    private fun retry() {
        val cmd = intent.getStringExtra(EXTRA_RETRY).orEmpty()
        if (cmd.isBlank()) return
        val app = applicationContext
        val main = android.os.Handler(mainLooper)
        Thread {
            val parsed = KineticCommands.parse(cmd) ?: return@Thread
            val ready = runCatching { KineticCommands.resolve(app, parsed) }.getOrNull() ?: return@Thread
            main.post {
                if (ready.answer) {
                    IslandBus.say(ready.badge, ready.done ?: (ready.title + if (ready.subtitle.isNotBlank()) " · ${ready.subtitle}" else ""))
                } else if (ready.run(app)) {
                    if (!ready.quiet) IslandBus.say(ready.badge, ready.done ?: ready.title)
                }
            }
        }.start()
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_RETRY = "retry"
        const val DND = "kinetic:dnd"
        const val WRITE_SETTINGS = "kinetic:write_settings"
        const val NOTIFICATIONS = "kinetic:notifications"

        /** [kind] is a runtime permission (e.g. READ_CONTACTS) or one of the special kinds above. */
        fun start(context: Context, kind: String, retry: String) {
            context.startActivity(
                Intent(context, PermissionActivity::class.java)
                    .putExtra(EXTRA_KIND, kind).putExtra(EXTRA_RETRY, retry)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }

        /** A friendly name for what's being asked. */
        fun nameOf(permission: String): String = when (permission) {
            android.Manifest.permission.READ_CONTACTS -> "your contacts"
            android.Manifest.permission.CALL_PHONE -> "phone calls"
            android.Manifest.permission.SEND_SMS -> "sending texts"
            android.Manifest.permission.READ_CALENDAR -> "your calendar"
            android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION -> "your location"
            android.Manifest.permission.CAMERA -> "the camera"
            else -> "permission"
        }
    }
}
