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
        val anyway = intent.getBooleanExtra(EXTRA_ANYWAY, false)
        val kind = intent.getStringExtra(EXTRA_KIND).orEmpty()
        when {
            granted || anyway -> retry(confirmed = anyway)
            // Said no twice (or "Don't allow" for good): Android won't ask again,
            // so the only way back is Kinetic's page in Settings.
            !shouldShowRequestPermissionRationale(kind) -> {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                IslandBus.tell(this, "⚙", "Android blocked ${nameOf(kind)}. Allow it under Permissions, then try again")
            }
            else -> IslandBus.tell(this, "✕", "Not allowed. Kinetic can't do that without ${nameOf(kind)}")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra(EXTRA_KIND) ?: return finish()
        when (kind) {
            DND -> openPage(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            WRITE_SETTINGS -> openPage(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            NOTIFICATIONS -> openPage(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            USAGE -> openPage(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            else -> {
                if (ContextCompat.checkSelfPermission(this, kind) == PackageManager.PERMISSION_GRANTED) {
                    retry(confirmed = intent.getBooleanExtra(EXTRA_ANYWAY, false))
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
    private fun retry(confirmed: Boolean = false) {
        val cmd = intent.getStringExtra(EXTRA_RETRY).orEmpty()
        if (cmd.isBlank()) return
        val app = applicationContext
        val main = android.os.Handler(mainLooper)
        Thread {
            val parsed = KineticCommands.parse(cmd) ?: return@Thread
            val ready = runCatching { KineticCommands.resolve(app, parsed) }.getOrNull() ?: return@Thread
            main.post {
                // Carries on in the island (asks to confirm a text, the next permission, etc.).
                if (confirmed) {
                    if (ready.run(app) && !ready.quiet) IslandBus.say(ready.badge, ready.done ?: ready.title)
                } else if (!IslandFlow.handle(app, cmd, ready)) {
                    if (ready.run(app) && !ready.quiet) IslandBus.say(ready.badge, ready.done ?: ready.title)
                }
            }
        }.start()
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_RETRY = "retry"
        /** You already confirmed (e.g. pressed Send): run it whether or not it's allowed. */
        const val EXTRA_ANYWAY = "anyway"
        const val DND = "kinetic:dnd"
        const val WRITE_SETTINGS = "kinetic:write_settings"
        const val NOTIFICATIONS = "kinetic:notifications"
        const val USAGE = "kinetic:usage"

        /** [kind] is a runtime permission (e.g. READ_CONTACTS) or one of the special kinds above. */
        fun start(context: Context, kind: String, retry: String, runAnyway: Boolean = false) {
            context.startActivity(
                Intent(context, PermissionActivity::class.java)
                    .putExtra(EXTRA_KIND, kind).putExtra(EXTRA_RETRY, retry).putExtra(EXTRA_ANYWAY, runAnyway)
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
            android.Manifest.permission.RECORD_AUDIO -> "the microphone"
            android.Manifest.permission.POST_NOTIFICATIONS -> "notifications"
            android.Manifest.permission.BLUETOOTH_CONNECT -> "nearby devices"
            android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO -> "your photos"
            else -> "that permission"
        }
    }
}
