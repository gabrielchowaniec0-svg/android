package com.kinetic.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import com.kinetic.core.apps.AppEntry

/**
 * First-run home screen: your default phone, messages, browser, camera and
 * gallery apps (whatever you have set as defaults), placed along the bottom row.
 */
fun defaultHomeKeys(context: Context, apps: List<AppEntry>): List<String> {
    val pm = context.packageManager
    val intents = listOf(
        Intent(Intent.ACTION_DIAL),
        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
        Intent(MediaStore.ACTION_IMAGE_CAPTURE),
        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY),
    )
    val packages = intents.mapNotNull { intent ->
        runCatching { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()
    }.filter { it != "android" }.distinct() // "android" = the "choose an app" dialog, no default set

    val keys = packages.mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg }?.key }
    return keys.ifEmpty { apps.take(4).map { it.key } }
}
