package com.kinetic.core.home

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Process
import com.kinetic.core.apps.AppEntry

/**
 * Reads the home screen and app-drawer layout of the phone's previous launcher
 * (One UI Home first, then Pixel Launcher / Launcher3) through the launcher's
 * settings provider, the same source launchers use for "import layout".
 *
 * The provider is guarded by the launcher's READ_SETTINGS permission. Whether a
 * third-party app is allowed to read it is up to the phone maker; if it isn't,
 * [read] returns [Result.Failed] with an explanation instead of throwing.
 *
 * Call off the main thread.
 */
object LauncherImporter {

    sealed interface Result {
        data class Success(
            val source: String,
            val home: List<ImportedHomeItem>,
            val dock: List<String>,
            val drawer: List<ImportedDrawerEntry>,
            val appsImported: Int,
            val widgetsSkipped: Int,
            val unmatched: Int,
        ) : Result

        data class Failed(val reason: String) : Result
    }

    private data class Source(val name: String, val authority: String)

    private val SOURCES = listOf(
        Source("One UI Home", "com.sec.android.app.launcher.settings"),
        Source("Pixel Launcher", "com.google.android.apps.nexuslauncher.settings"),
        Source("Launcher3", "com.android.launcher3.settings"),
    )

    // Launcher3 / One UI Home column constants
    private const val CONTAINER_DESKTOP = -100L
    private const val CONTAINER_HOTSEAT = -101L
    private const val TYPE_APP = 0
    private const val TYPE_SHORTCUT = 1
    private const val TYPE_FOLDER = 2
    private const val TYPE_WIDGET = 4
    private const val TYPE_CUSTOM_WIDGET = 5
    private const val TYPE_DEEP_SHORTCUT = 6

    private class Row(val values: Map<String, String?>) {
        fun str(name: String): String? = values[name]
        fun int(name: String): Int? = values[name]?.toIntOrNull()
        fun long(name: String): Long? = values[name]?.toLongOrNull()
        val id get() = long("_id")
        val type get() = int("itemType") ?: TYPE_APP
        val container get() = long("container") ?: CONTAINER_DESKTOP
        val screen get() = long("screen") ?: 0L
        val cellX get() = int("cellX") ?: 0
        val cellY get() = int("cellY") ?: 0
        /** Order inside a folder / hotseat / drawer. */
        val order: Long
            get() = int("rank")?.toLong()
                ?: int("cell")?.let { screen * 10_000 + it }
                ?: (screen * 10_000 + cellY * 100L + cellX)
    }

    fun read(context: Context, apps: List<AppEntry>): Result {
        val resolver = context.contentResolver
        var blockedBy: String? = null
        for (source in SOURCES) {
            val favorites = try {
                query(resolver, Uri.parse("content://${source.authority}/favorites"))
            } catch (e: SecurityException) {
                blockedBy = source.name
                continue
            } catch (e: Exception) {
                continue
            } ?: continue
            if (favorites.isEmpty()) continue
            val drawerRows = runCatching { query(resolver, Uri.parse("content://${source.authority}/appOrder")) }.getOrNull()
            return build(source.name, favorites, drawerRows.orEmpty(), Matcher(apps))
        }
        return Result.Failed(
            if (blockedBy != null) {
                "$blockedBy is on this phone, but it doesn't let other apps read its layout " +
                    "(its READ_SETTINGS permission is reserved for system apps). " +
                    "Your home screen will need to be arranged by hand in edit mode."
            } else {
                "Couldn't find another launcher's layout to import on this phone."
            }
        )
    }

    private fun build(source: String, favorites: List<Row>, drawerRows: List<Row>, matcher: Matcher): Result.Success {
        var unmatched = 0
        var widgets = 0
        val imported = HashSet<String>()

        fun keyOf(row: Row): String? = matcher.keyFor(row.str("intent")).also { key ->
            if (key == null) unmatched++ else imported += key
        }

        // Folder contents, keyed by folder row id.
        fun folderContents(rows: List<Row>): Map<Long, List<String>> =
            rows.filter { it.container > 0 && (it.type == TYPE_APP || it.type == TYPE_SHORTCUT || it.type == TYPE_DEEP_SHORTCUT) }
                .groupBy { it.container }
                .mapValues { (_, children) -> children.sortedBy { it.order }.mapNotNull { keyOf(it) }.distinct() }

        // ---- home screen ----
        val homeFolders = folderContents(favorites)
        val desktop = favorites.filter { it.container == CONTAINER_DESKTOP }
        val screenIndex = desktop.map { it.screen }.distinct().sorted().withIndex().associate { (i, s) -> s to i }
        val home = mutableListOf<ImportedHomeItem>()
        desktop.forEach { row ->
            val page = screenIndex[row.screen] ?: 0
            when (row.type) {
                TYPE_APP, TYPE_SHORTCUT, TYPE_DEEP_SHORTCUT ->
                    keyOf(row)?.let { home += ImportedHomeItem.App(page, row.cellX, row.cellY, it) }
                TYPE_FOLDER -> home += ImportedHomeItem.Folder(
                    page, row.cellX, row.cellY,
                    name = row.str("title")?.takeIf { it.isNotBlank() } ?: "Folder",
                    appKeys = row.id?.let { homeFolders[it] }.orEmpty(),
                )
                TYPE_WIDGET, TYPE_CUSTOM_WIDGET -> widgets++
            }
        }
        val dock = favorites
            .filter { it.container == CONTAINER_HOTSEAT && it.type != TYPE_FOLDER }
            .sortedBy { it.screen * 100 + it.cellX }
            .mapNotNull { keyOf(it) }

        // ---- app drawer (One UI Home keeps its custom order in "appOrder") ----
        val drawer = mutableListOf<ImportedDrawerEntry>()
        if (drawerRows.isNotEmpty()) {
            val drawerFolders = folderContents(drawerRows)
            val folderIds = drawerRows.filter { it.type == TYPE_FOLDER }.mapNotNull { it.id }.toSet()
            drawerRows
                .filter { it.container !in folderIds && !(it.container > 0) }
                .sortedBy { it.order }
                .forEach { row ->
                    when (row.type) {
                        TYPE_FOLDER -> drawer += ImportedDrawerEntry.Folder(
                            name = row.str("title")?.takeIf { it.isNotBlank() } ?: "Folder",
                            appKeys = row.id?.let { drawerFolders[it] }.orEmpty(),
                        )
                        TYPE_APP, TYPE_SHORTCUT, TYPE_DEEP_SHORTCUT ->
                            keyOf(row)?.let { drawer += ImportedDrawerEntry.App(it) }
                    }
                }
        }

        return Result.Success(
            source = source,
            home = home,
            dock = dock,
            drawer = drawer,
            appsImported = imported.size,
            widgetsSkipped = widgets,
            unmatched = unmatched,
        )
    }

    /** Reads every row as strings (blobs such as icons are skipped). Null if the provider isn't there. */
    private fun query(resolver: ContentResolver, uri: Uri): List<Row>? {
        val cursor: Cursor = resolver.query(uri, null, null, null, null) ?: return null
        return cursor.use { c ->
            val names = c.columnNames
            val rows = ArrayList<Row>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) {
                val values = HashMap<String, String?>(names.size)
                for (i in names.indices) {
                    if (c.getType(i) == Cursor.FIELD_TYPE_BLOB) continue
                    values[names[i]] = c.getString(i)
                }
                rows += Row(values)
            }
            rows
        }
    }

    /** Maps a launcher's stored intent to one of our [AppEntry] keys. */
    private class Matcher(apps: List<AppEntry>) {
        private val me = Process.myUserHandle()
        private val byComponent = HashMap<String, String>()
        private val byPackage = HashMap<String, String>()

        init {
            // Personal-profile apps win over work-profile copies.
            apps.sortedBy { if (it.user == me) 0 else 1 }.forEach { app ->
                byComponent.putIfAbsent(app.component.flattenToShortString(), app.key)
                byComponent.putIfAbsent(app.component.flattenToString(), app.key)
                byPackage.putIfAbsent(app.packageName, app.key)
            }
        }

        fun keyFor(intentUri: String?): String? {
            if (intentUri.isNullOrBlank()) return null
            val intent = runCatching { Intent.parseUri(intentUri, 0) }.getOrNull() ?: return null
            val component = intent.component
            if (component != null) {
                return byComponent[component.flattenToShortString()]
                    ?: byComponent[component.flattenToString()]
                    ?: byPackage[component.packageName]
            }
            return intent.`package`?.let { byPackage[it] }
        }
    }
}
