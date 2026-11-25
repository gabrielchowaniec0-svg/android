package com.kinetic.launcher.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

/**
 * What to do when you say no to a permission. Android stops showing its dialog
 * after you refuse twice, so in that case Kinetic opens its own Settings page,
 * where you can still switch it on.
 */
object PermissionHelp {
    fun refused(context: Context, permission: String, forWhat: String) {
        val activity = context.findActivity()
        val blocked = activity != null && !activity.shouldShowRequestPermissionRationale(permission)
        val name = PermissionActivity.nameOf(permission)
        if (blocked) {
            Toast.makeText(context.applicationContext, "$forWhat needs $name. Allow it under Permissions", Toast.LENGTH_LONG).show()
            openSettings(context)
        } else {
            Toast.makeText(context.applicationContext, "$forWhat needs $name", Toast.LENGTH_LONG).show()
        }
    }

    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
