package com.kinetic.core.widgets

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import kotlin.math.ceil

/**
 * Hosts real Android home-screen widgets (the same mechanism stock launchers
 * use): lists installed widget providers, handles the bind/configure steps,
 * and creates/caches the live widget views.
 */
class WidgetHostManager(context: Context) {

    private val appContext = context.applicationContext
    private val manager: AppWidgetManager = AppWidgetManager.getInstance(appContext)
    private val host = AppWidgetHost(appContext, HOST_ID)
    private val launcherApps = appContext.getSystemService(LauncherApps::class.java)
    private val densityDpi = appContext.resources.displayMetrics.densityDpi
    private val views = HashMap<Int, AppWidgetHostView>()

    /** Call from onStart so widgets receive updates while the launcher is visible. */
    fun startListening() {
        runCatching { host.startListening() }
    }

    fun stopListening() {
        runCatching { host.stopListening() }
    }

    /** Every widget provider on the phone, including work-profile ones. */
    fun providers(): List<AppWidgetProviderInfo> =
        launcherApps.profiles.flatMap { manager.getInstalledProvidersForProfile(it) }

    fun allocateId(): Int = host.allocateAppWidgetId()

    fun deleteId(appWidgetId: Int) {
        views.remove(appWidgetId)
        runCatching { host.deleteAppWidgetId(appWidgetId) }
    }

    /** Binds silently if the user has already allowed this launcher to add widgets. */
    fun bindIfAllowed(appWidgetId: Int, info: AppWidgetProviderInfo): Boolean = runCatching {
        manager.bindAppWidgetIdIfAllowed(appWidgetId, info.profile, info.provider, null)
    }.getOrDefault(false)

    /** System dialog asking the user to allow this launcher to create widgets. */
    fun bindPermissionIntent(appWidgetId: Int, info: AppWidgetProviderInfo): Intent =
        Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
        }

    fun needsConfiguration(info: AppWidgetProviderInfo): Boolean =
        info.configure != null &&
            (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) == 0

    /** Opens the widget's own setup screen; the result arrives in Activity.onActivityResult. */
    fun startConfiguration(activity: Activity, appWidgetId: Int, requestCode: Int): Boolean = try {
        host.startAppWidgetConfigureActivityForResult(activity, appWidgetId, 0, requestCode, null)
        true
    } catch (e: Exception) {
        false
    }

    fun infoFor(appWidgetId: Int): AppWidgetProviderInfo? = manager.getAppWidgetInfo(appWidgetId)

    /** The live widget view, created once per id and reused (it's moved between parents as needed). */
    fun viewFor(appWidgetId: Int): AppWidgetHostView? {
        val cached = views[appWidgetId]
        if (cached != null) return cached
        val info = infoFor(appWidgetId) ?: return null
        return host.createView(appContext, appWidgetId, info).also { views[appWidgetId] = it }
    }

    fun label(info: AppWidgetProviderInfo): String =
        runCatching { info.loadLabel(appContext.packageManager) }.getOrNull() ?: info.provider.className

    fun appName(info: AppWidgetProviderInfo): String = runCatching {
        val pm = appContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
    }.getOrDefault(info.provider.packageName)

    fun preview(info: AppWidgetProviderInfo): Drawable? =
        runCatching { info.loadPreviewImage(appContext, densityDpi) ?: info.loadIcon(appContext, densityDpi) }.getOrNull()

    /** A sensible starting size in grid cells (the user can resize in edit mode). */
    fun defaultSpan(info: AppWidgetProviderInfo, columns: Int, rows: Int): Pair<Int, Int> {
        if (info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            return info.targetCellWidth.coerceIn(1, columns) to info.targetCellHeight.coerceIn(1, rows)
        }
        val density = appContext.resources.displayMetrics.density
        val spanX = ceil(info.minWidth / density / CELL_WIDTH_DP).toInt().coerceIn(1, columns)
        val spanY = ceil(info.minHeight / density / CELL_HEIGHT_DP).toInt().coerceIn(1, rows)
        return spanX to spanY
    }

    private companion object {
        const val HOST_ID = 0x4B4E54 // "KNT"
        const val CELL_WIDTH_DP = 95f
        const val CELL_HEIGHT_DP = 110f
    }
}
