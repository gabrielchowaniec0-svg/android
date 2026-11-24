package com.kinetic.core.apps

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Process
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * One launchable activity installed on the phone.
 *
 * [key] uniquely identifies it across user profiles (e.g. a work profile copy
 * of the same app): "<package>/<activity>#<user serial>".
 */
data class AppEntry(
    val key: String,
    val packageName: String,
    val component: ComponentName,
    val user: UserHandle,
    val label: String,
    val icon: ImageBitmap,
    /** ApplicationInfo.category (e.g. CATEGORY_GAME), or -1 if the app didn't declare one. */
    val category: Int = -1,
)

/**
 * Loads the real installed apps (with their real, system-themed icons) through
 * [LauncherApps] — the same API stock launchers use — and keeps the list fresh
 * when apps are installed, updated or removed.
 */
class AppRepository(context: Context) {

    private val appContext = context.applicationContext
    private val launcherApps = appContext.getSystemService(LauncherApps::class.java)
    private val userManager = appContext.getSystemService(UserManager::class.java)
    private val densityDpi = appContext.resources.displayMetrics.densityDpi
    private val iconPx = (64 * appContext.resources.displayMetrics.density).roundToInt()
    private val renderer = IconRenderer(iconPx)
    private val me = Process.myUserHandle()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val iconCache = ConcurrentHashMap<String, ImageBitmap>()
    private var refreshJob: Job? = null
    private var started = false

    /** null until the first load finishes; then the full, alphabetical app list. */
    private val _apps = MutableStateFlow<List<AppEntry>?>(null)
    val apps: StateFlow<List<AppEntry>?> = _apps.asStateFlow()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            changed(*packageNames)
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            changed(*packageNames)
    }

    fun start() {
        if (started) return
        started = true
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    /** Call once from onDestroy; the repository can't be restarted afterwards. */
    fun stop() {
        if (!started) return
        started = false
        launcherApps.unregisterCallback(callback)
        scope.cancel()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val list = runCatching { load() }.getOrNull() ?: return@launch
            _apps.value = list
        }
    }

    fun find(key: String): AppEntry? = _apps.value?.firstOrNull { it.key == key }

    /**
     * Launches the app the same way the stock launcher does. [sourceBounds] (screen
     * pixels) and [options] (e.g. a scale-up animation) make it open from the icon.
     */
    fun launch(entry: AppEntry, sourceBounds: Rect? = null, options: Bundle? = null): Boolean = try {
        launcherApps.startMainActivity(entry.component, entry.user, sourceBounds, options)
        true
    } catch (e: Exception) {
        false
    }

    /** One app shortcut from the long-press menu (e.g. "New message", or Chowaniec's "Group Chat"). */
    data class Shortcut(val info: ShortcutInfo, val label: String, val icon: ImageBitmap?)

    /**
     * The app's shortcuts, like One UI's long-press menu. Needs Kinetic to be the
     * default Home app; returns an empty list otherwise.
     */
    fun shortcuts(entry: AppEntry, max: Int = 5): List<Shortcut> {
        if (!runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)) return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(entry.packageName)
            .setActivity(entry.component)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC)
        val found = runCatching { launcherApps.getShortcuts(query, entry.user) }.getOrNull().orEmpty()
        val shortcutPx = (40 * appContext.resources.displayMetrics.density).roundToInt()
        val shortcutRenderer = IconRenderer(shortcutPx)
        return found
            .filter { it.isEnabled }
            .sortedWith(compareBy<ShortcutInfo>({ if (it.isDeclaredInManifest) 0 else 1 }, { it.rank }))
            .take(max)
            .map { info ->
                val icon = runCatching {
                    launcherApps.getShortcutIconDrawable(info, densityDpi)?.let { shortcutRenderer.render(it).asImageBitmap() }
                }.getOrNull()
                Shortcut(info, (info.shortLabel ?: info.longLabel ?: "").toString(), icon)
            }
    }

    fun startShortcut(shortcut: Shortcut, sourceBounds: Rect? = null, options: Bundle? = null): Boolean = try {
        launcherApps.startShortcut(shortcut.info, sourceBounds, options)
        true
    } catch (e: Exception) {
        false
    }

    /** Opens the system "App info" page for the app. */
    fun openAppInfo(entry: AppEntry): Boolean = try {
        launcherApps.startAppDetailsActivity(entry.component, entry.user, null, null)
        true
    } catch (e: Exception) {
        false
    }

    private fun changed(vararg packageNames: String) {
        packageNames.forEach { pkg -> iconCache.keys.removeIf { it.startsWith("$pkg/") } }
        refresh()
    }

    private fun load(): List<AppEntry> {
        val self = appContext.packageName
        val out = ArrayList<AppEntry>()
        for (user in launcherApps.profiles) {
            val serial = userManager.getSerialNumberForUser(user)
            for (info in launcherApps.getActivityList(null, user)) {
                val component = info.componentName
                // Kinetic itself isn't listed, but its "system apps" (Settings, Vault…) are.
                if (component.packageName == self && !component.className.contains(".shortcut.")) continue
                val key = "${component.flattenToShortString()}#$serial"
                val icon = iconCache.getOrPut(key) { renderIcon(info) }
                out += AppEntry(
                    key = key,
                    packageName = component.packageName,
                    component = component,
                    user = user,
                    label = info.label?.toString() ?: component.packageName,
                    icon = icon,
                    category = info.applicationInfo?.category ?: -1,
                )
            }
        }
        return out.sortedBy { it.label.lowercase() }
    }

    /**
     * Every icon drawn into the same squircle at the same size (see [IconRenderer]),
     * plus the work-profile briefcase where relevant. Rendered once and cached.
     */
    private fun renderIcon(info: LauncherActivityInfo): ImageBitmap {
        // Fast path: the finished icon saved on disk last time (re-made when the app updates).
        val diskKey = diskKey(info)
        val file = java.io.File(diskDir, "$diskKey.png")
        if (file.exists()) {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.let { return it.asImageBitmap() }
        }
        val bmp = renderUncached(info)
        runCatching {
            diskDir.mkdirs()
            file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bmp.asImageBitmap()
    }

    /** Where finished icons are kept between launches (cleared automatically when stale). */
    private val diskDir = java.io.File(appContext.cacheDir, "icons-v$ICON_CACHE_VERSION-$iconPx")

    private fun diskKey(info: LauncherActivityInfo): String {
        val stamp = runCatching { java.io.File(info.applicationInfo.sourceDir).lastModified() }.getOrDefault(0L)
        val raw = "${info.componentName.flattenToShortString()}#${userManager.getSerialNumberForUser(info.user)}#$stamp"
        return Integer.toHexString(raw.hashCode()) + raw.length
    }

    private fun renderUncached(info: LauncherActivityInfo): android.graphics.Bitmap {
        val shaped = renderer.render(info.getIcon(densityDpi))
        if (info.user == me) return shaped
        val badged = appContext.packageManager.getUserBadgedIcon(BitmapDrawable(appContext.resources, shaped), info.user)
        return badged.toBitmap(iconPx, iconPx)
    }

    private companion object {
        /** Bump when the icon look changes, so old cached icons are re-made. */
        const val ICON_CACHE_VERSION = 1
    }
}
