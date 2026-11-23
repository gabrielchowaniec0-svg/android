package com.kinetic.core.home

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Something placed on a home page. Cell coordinates may be half-cells (e.g. 1.5). */
sealed interface HomeItem {
    val id: String
    val cellX: Float
    val cellY: Float
    val spanX: Int
    val spanY: Int
    fun movedTo(x: Float, y: Float): HomeItem

    data class App(
        override val id: String,
        val appKey: String,
        override val cellX: Float,
        override val cellY: Float,
    ) : HomeItem {
        override val spanX: Int get() = 1
        override val spanY: Int get() = 1
        override fun movedTo(x: Float, y: Float) = copy(cellX = x, cellY = y)
    }

    /** A folder. 1 × 1 is a normal folder; 2 × 2 is a One UI–style large folder. */
    data class Folder(
        override val id: String,
        val name: String,
        val appKeys: List<String>,
        override val cellX: Float,
        override val cellY: Float,
        override val spanX: Int = 1,
        override val spanY: Int = 1,
    ) : HomeItem {
        val isLarge: Boolean get() = spanX > 1 || spanY > 1
        override fun movedTo(x: Float, y: Float) = copy(cellX = x, cellY = y)
    }

    data class Widget(
        override val id: String,
        val appWidgetId: Int,
        override val cellX: Float,
        override val cellY: Float,
        override val spanX: Int,
        override val spanY: Int,
    ) : HomeItem {
        override fun movedTo(x: Float, y: Float) = copy(cellX = x, cellY = y)
    }
}

data class HomePage(val id: String, val items: List<HomeItem>)

/** A folder in the all-apps drawer. [color] is an ARGB tint for its tile (0 = default). */
data class DrawerFolder(val id: String, val name: String, val appKeys: List<String>, val color: Int = 0)

/**
 * [drawerOrder] is the custom app-drawer order: "app:<key>", "folder:<id>", or
 * [PAGE_BREAK] to start a new drawer page early (One UI allows part-filled pages).
 * Empty means alphabetical.
 */
data class HomeLayout(
    val pages: List<HomePage>,
    val drawerFolders: List<DrawerFolder>,
    val drawerOrder: List<String> = emptyList(),
) {
    companion object {
        const val PAGE_BREAK = "|"
    }
}

/**
 * The user's home screen layout: which apps/folders/widgets sit where on which
 * page, plus folders in the app drawer. Saved to SharedPreferences as JSON on
 * every change. Call from the main thread.
 */
class HomeLayoutStore(context: Context, val columns: Int, val rows: Int) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True until a layout has been saved once (first run). */
    var isFresh: Boolean = !prefs.contains(KEY_LAYOUT)
        private set

    private val _layout = MutableStateFlow(normalize(load()))
    val layout: StateFlow<HomeLayout> = _layout.asStateFlow()

    // ---- placement queries -------------------------------------------------

    fun canPlace(page: Int, itemId: String, x: Float, y: Float): Boolean {
        val items = _layout.value.pages.getOrNull(page)?.items ?: return false
        val item = items.firstOrNull { it.id == itemId } ?: return false
        return fits(items, itemId, x, y, item.spanX, item.spanY)
    }

    // ---- home items ----------------------------------------------------------

    /** Adds an app to [page] (or the next page with room, or a new page). False if it's already on [page]. */
    fun addApp(page: Int, appKey: String): Boolean = update { l ->
        val target = l.pages.getOrNull(page)
        if (target != null && target.items.any { it is HomeItem.App && it.appKey == appKey }) l
        else l.placeFrom(page, 1, 1) { x, y -> HomeItem.App(newId(), appKey, x, y) }
    }

    /** Places a widget and returns the index of the page it landed on. */
    fun addWidget(page: Int, appWidgetId: Int, spanX: Int, spanY: Int): Int {
        val sx = spanX.coerceIn(1, columns)
        val sy = spanY.coerceIn(1, rows)
        val id = newId()
        update { l -> l.placeFrom(page, sx, sy) { x, y -> HomeItem.Widget(id, appWidgetId, x, y, sx, sy) } }
        return _layout.value.pages.indexOfFirst { p -> p.items.any { it.id == id } }
    }

    fun moveItem(page: Int, itemId: String, x: Float, y: Float): Boolean = update { l ->
        val p = l.pages.getOrNull(page) ?: return@update l
        val item = p.items.firstOrNull { it.id == itemId } ?: return@update l
        if (!fits(p.items, itemId, x, y, item.spanX, item.spanY)) l
        else l.replacePage(page, p.copy(items = p.items.map { if (it.id == itemId) it.movedTo(x, y) else it }))
    }

    /** Removes an item and returns it (so the caller can free a widget id). */
    fun removeItem(page: Int, itemId: String): HomeItem? {
        var removed: HomeItem? = null
        update { l ->
            val p = l.pages.getOrNull(page) ?: return@update l
            removed = p.items.firstOrNull { it.id == itemId } ?: return@update l
            l.replacePage(page, p.copy(items = p.items.filter { it.id != itemId }))
        }
        return removed
    }

    /** Moves an item to another page (index == page count creates a new page). False if no room. */
    fun moveToPage(from: Int, itemId: String, to: Int): Boolean = update { l ->
        if (to == from) return@update l
        val src = l.pages.getOrNull(from) ?: return@update l
        val item = src.items.firstOrNull { it.id == itemId } ?: return@update l
        var pages = l.pages
        if (to == pages.size) pages = pages + HomePage(newId(), emptyList())
        val dst = pages.getOrNull(to) ?: return@update l
        val spot = findFree(dst.items, item.spanX, item.spanY) ?: return@update l
        pages = pages.mapIndexed { i, p ->
            when (i) {
                from -> p.copy(items = p.items.filter { it.id != itemId })
                to -> p.copy(items = p.items + item.movedTo(spot.first, spot.second))
                else -> p
            }
        }
        l.copy(pages = pages)
    }

    /** Grows/shrinks a widget by [dx]/[dy] cells. False if there's no room. */
    fun resizeWidget(page: Int, itemId: String, dx: Int, dy: Int): Boolean = update { l ->
        val p = l.pages.getOrNull(page) ?: return@update l
        val w = p.items.firstOrNull { it.id == itemId } as? HomeItem.Widget ?: return@update l
        val sx = (w.spanX + dx).coerceIn(1, columns)
        val sy = (w.spanY + dy).coerceIn(1, rows)
        if (sx == w.spanX && sy == w.spanY) return@update l
        val x = minOf(w.cellX, (columns - sx).toFloat())
        val y = minOf(w.cellY, (rows - sy).toFloat())
        if (!fits(p.items, w.id, x, y, sx, sy)) l
        else l.replacePage(page, p.copy(items = p.items.map {
            if (it.id == w.id) w.copy(cellX = x, cellY = y, spanX = sx, spanY = sy) else it
        }))
    }

    // ---- home folders ----------------------------------------------------------

    /** Turns an app on the home screen into a folder containing it. */
    fun createFolder(page: Int, appItemId: String, name: String): Boolean = update { l ->
        val p = l.pages.getOrNull(page) ?: return@update l
        val app = p.items.firstOrNull { it.id == appItemId } as? HomeItem.App ?: return@update l
        l.replacePage(page, p.copy(items = p.items.map {
            if (it.id == appItemId) HomeItem.Folder(newId(), name, listOf(app.appKey), app.cellX, app.cellY) else it
        }))
    }

    /** Moves a home-screen app into a folder on the same page. */
    fun addToFolder(page: Int, appItemId: String, folderId: String): Boolean = update { l ->
        val p = l.pages.getOrNull(page) ?: return@update l
        val app = p.items.firstOrNull { it.id == appItemId } as? HomeItem.App ?: return@update l
        val folder = p.items.firstOrNull { it.id == folderId } as? HomeItem.Folder ?: return@update l
        l.replacePage(page, p.copy(items = p.items
            .filter { it.id != appItemId }
            .map { if (it.id == folderId) folder.copy(appKeys = (folder.appKeys + app.appKey).distinct()) else it }))
    }

    /**
     * Takes an app out of a home folder. With [placeOnHome] it goes back onto the
     * page (false if there's no room); otherwise it's just removed. An emptied
     * folder disappears.
     */
    fun removeFromFolder(page: Int, folderId: String, appKey: String, placeOnHome: Boolean): Boolean = update { l ->
        val p = l.pages.getOrNull(page) ?: return@update l
        val folder = p.items.firstOrNull { it.id == folderId } as? HomeItem.Folder ?: return@update l
        val remaining = folder.appKeys - appKey
        val items = if (remaining.isEmpty()) p.items.filter { it.id != folderId }
        else p.items.map { if (it.id == folderId) folder.copy(appKeys = remaining) else it }
        if (!placeOnHome) return@update l.replacePage(page, p.copy(items = items))
        val spot = if (remaining.isEmpty()) folder.cellX to folder.cellY
        else findFree(items, 1, 1) ?: return@update l
        l.replacePage(page, p.copy(items = items + HomeItem.App(newId(), appKey, spot.first, spot.second)))
    }

    /** Renames a folder, whether it's on the home screen or in the drawer. */
    fun renameFolder(folderId: String, name: String): Boolean = update { l ->
        l.copy(
            pages = l.pages.map { p ->
                p.copy(items = p.items.map { if (it is HomeItem.Folder && it.id == folderId) it.copy(name = name) else it })
            },
            drawerFolders = l.drawerFolders.map { if (it.id == folderId) it.copy(name = name) else it },
        )
    }

    // ---- pages ---------------------------------------------------------------

    fun addPage(): Int {
        update { it.copy(pages = it.pages + HomePage(newId(), emptyList())) }
        return _layout.value.pages.lastIndex
    }

    /** Deletes a page only if it's empty and not the last one. */
    fun removePage(index: Int): Boolean = update { l ->
        val p = l.pages.getOrNull(index)
        if (p == null || p.items.isNotEmpty() || l.pages.size <= 1) l
        else l.copy(pages = l.pages.filterIndexed { i, _ -> i != index })
    }

    // ---- drawer folders --------------------------------------------------------

    fun createDrawerFolder(name: String, appKey: String): Boolean = update { l ->
        val folder = DrawerFolder(newId(), name, listOf(appKey))
        // In a custom order, the new folder takes the app's place.
        val order = if (l.drawerOrder.isEmpty()) l.drawerOrder else {
            val i = l.drawerOrder.indexOf("app:$appKey")
            if (i >= 0) l.drawerOrder.toMutableList().apply { this[i] = "folder:${folder.id}" }
            else l.drawerOrder + "folder:${folder.id}"
        }
        l.copy(
            drawerFolders = l.drawerFolders
                .map { it.copy(appKeys = it.appKeys - appKey) }
                .filter { it.appKeys.isNotEmpty() } + folder,
            drawerOrder = order,
        )
    }

    /** Saves a custom app-drawer order ("app:<key>" / "folder:<id>"). */
    fun setDrawerOrder(order: List<String>): Boolean = update { it.copy(drawerOrder = dedupeOrder(order)) }

    /** Back to alphabetical. */
    fun clearDrawerOrder(): Boolean = update { it.copy(drawerOrder = emptyList()) }

    /**
     * Replaces the whole layout (used for the built-in "my phone" layout). Returns
     * the app-widget ids that are no longer used, so the caller can free them.
     */
    fun replaceLayout(newLayout: HomeLayout): List<Int> {
        val keep = newLayout.pages.flatMap { it.items }.filterIsInstance<HomeItem.Widget>().mapTo(HashSet()) { it.appWidgetId }
        val dropped = _layout.value.pages.flatMap { it.items }.filterIsInstance<HomeItem.Widget>()
            .map { it.appWidgetId }.filter { it !in keep }
        val l = normalize(newLayout.copy(pages = newLayout.pages.ifEmpty { listOf(HomePage(newId(), emptyList())) }))
        isFresh = false
        _layout.value = l
        save(l)
        return dropped
    }

    /** Places a widget at an exact cell if it fits there, otherwise wherever there's room. Returns the page. */
    fun addWidgetAt(page: Int, appWidgetId: Int, x: Int, y: Int, spanX: Int, spanY: Int): Int {
        val sx = spanX.coerceIn(1, columns)
        val sy = spanY.coerceIn(1, rows)
        val p = _layout.value.pages.getOrNull(page)
        if (p != null && fits(p.items, null, x.toFloat(), y.toFloat(), sx, sy)) {
            update { l -> l.replacePage(page, p.copy(items = p.items + HomeItem.Widget(newId(), appWidgetId, x.toFloat(), y.toFloat(), sx, sy))) }
            return page
        }
        return addWidget(page, appWidgetId, sx, sy)
    }

    /** Switches a home folder between normal (1 × 1) and large (2 × 2). False if there's no room. */
    fun setFolderLarge(page: Int, folderId: String, large: Boolean): Boolean = update { l ->
        val p = l.pages.getOrNull(page) ?: return@update l
        val f = p.items.firstOrNull { it.id == folderId } as? HomeItem.Folder ?: return@update l
        val span = if (large) 2 else 1
        val x = minOf(f.cellX, (columns - span).toFloat())
        val y = minOf(f.cellY, (rows - span).toFloat())
        if (!fits(p.items, f.id, x, y, span, span)) l
        else l.replacePage(page, p.copy(items = p.items.map {
            if (it.id == f.id) f.copy(cellX = x, cellY = y, spanX = span, spanY = span) else it
        }))
    }

    fun moveToDrawerFolder(folderId: String, appKey: String): Boolean = update { l ->
        l.copy(drawerFolders = l.drawerFolders.map { f ->
            if (f.id == folderId) f.copy(appKeys = (f.appKeys + appKey).distinct()) else f.copy(appKeys = f.appKeys - appKey)
        }.filter { it.appKeys.isNotEmpty() })
    }

    fun removeFromDrawerFolder(folderId: String, appKey: String): Boolean = update { l ->
        l.copy(drawerFolders = l.drawerFolders.map { f ->
            if (f.id == folderId) f.copy(appKeys = f.appKeys - appKey) else f
        }.filter { it.appKeys.isNotEmpty() })
    }

    // ---- housekeeping ----------------------------------------------------------

    /** Drops apps that are no longer installed. Widgets are left alone. */
    fun prune(installedKeys: Set<String>): Boolean = update { l ->
        l.copy(
            pages = l.pages.map { p ->
                p.copy(items = p.items.mapNotNull { item ->
                    when (item) {
                        is HomeItem.App -> item.takeIf { it.appKey in installedKeys }
                        is HomeItem.Folder -> item.copy(appKeys = item.appKeys.filter { it in installedKeys })
                            .takeIf { it.appKeys.isNotEmpty() }
                        is HomeItem.Widget -> item
                    }
                })
            },
            drawerFolders = l.drawerFolders
                .map { f -> f.copy(appKeys = f.appKeys.filter { it in installedKeys }) }
                .filter { it.appKeys.isNotEmpty() },
        ).let { pruned ->
            val folderIds = pruned.drawerFolders.mapTo(HashSet()) { it.id }
            pruned.copy(drawerOrder = pruned.drawerOrder.filter { entry ->
                when {
                    entry == HomeLayout.PAGE_BREAK -> true
                    entry.startsWith("app:") -> entry.removePrefix("app:") in installedKeys
                    entry.startsWith("folder:") -> entry.removePrefix("folder:") in folderIds
                    else -> false
                }
            })
        }
    }

    /** First run only: puts [appKeys] along the bottom row of page 1, like a dock. */
    fun seed(appKeys: List<String>) {
        if (!isFresh) return
        isFresh = false
        var l = _layout.value
        appKeys.distinct().forEachIndexed { i, key ->
            val page = l.pages[0]
            val bottom = (rows - 1).toFloat()
            val spot = if (i < columns && fits(page.items, null, i.toFloat(), bottom, 1, 1)) i.toFloat() to bottom
            else (findFree(page.items, 1, 1) ?: return@forEachIndexed)
            l = l.replacePage(0, page.copy(items = page.items + HomeItem.App(newId(), key, spot.first, spot.second)))
        }
        _layout.value = l
        save(l)
    }

    // ---- internals ---------------------------------------------------------------

    /** Removes duplicate entries but keeps every page break. */
    private fun dedupeOrder(order: List<String>): List<String> {
        val seen = HashSet<String>()
        return order.filter { it == HomeLayout.PAGE_BREAK || seen.add(it) }
    }

    /** Moves items that don't fit the current grid (e.g. after it shrank from 5 to 4 columns). */
    private fun normalize(l: HomeLayout): HomeLayout {
        var out = l.copy(pages = l.pages.map { it.copy(items = emptyList()) })
        var changed = false
        l.pages.forEachIndexed { pageIndex, page ->
            page.items.forEach { item ->
                val sx = item.spanX.coerceIn(1, columns)
                val sy = item.spanY.coerceIn(1, rows)
                val resized = when {
                    sx == item.spanX && sy == item.spanY -> item
                    item is HomeItem.Widget -> item.copy(spanX = sx, spanY = sy)
                    item is HomeItem.Folder -> item.copy(spanX = sx, spanY = sy)
                    else -> item
                }
                val target = out.pages[pageIndex]
                if (fits(target.items, null, resized.cellX, resized.cellY, sx, sy)) {
                    out = out.replacePage(pageIndex, target.copy(items = target.items + resized))
                    if (resized !== item) changed = true
                } else {
                    changed = true
                    out = out.placeFrom(pageIndex, sx, sy) { x, y -> resized.movedTo(x, y) }
                }
            }
        }
        if (changed) save(out)
        return if (changed) out else l
    }

    private fun update(transform: (HomeLayout) -> HomeLayout): Boolean {
        val current = _layout.value
        val next = transform(current)
        if (next == current) return false
        isFresh = false
        _layout.value = next
        save(next)
        return true
    }

    private fun fits(items: List<HomeItem>, ignoreId: String?, x: Float, y: Float, sx: Int, sy: Int): Boolean {
        if (x < -EPS || y < -EPS || x + sx > columns + EPS || y + sy > rows + EPS) return false
        return items.none { it.id != ignoreId && overlaps(x, y, sx, sy, it) }
    }

    private fun overlaps(x: Float, y: Float, sx: Int, sy: Int, o: HomeItem): Boolean =
        x < o.cellX + o.spanX - EPS && o.cellX < x + sx - EPS &&
            y < o.cellY + o.spanY - EPS && o.cellY < y + sy - EPS

    private fun findFree(items: List<HomeItem>, sx: Int, sy: Int): Pair<Float, Float>? {
        for (y in 0..rows - sy) for (x in 0..columns - sx) {
            if (fits(items, null, x.toFloat(), y.toFloat(), sx, sy)) return x.toFloat() to y.toFloat()
        }
        return null
    }

    private fun HomeLayout.replacePage(index: Int, page: HomePage): HomeLayout =
        copy(pages = pages.mapIndexed { i, p -> if (i == index) page else p })

    /** Puts a new item on the first page from [startPage] with room, else on a new page. */
    private fun HomeLayout.placeFrom(startPage: Int, sx: Int, sy: Int, make: (Float, Float) -> HomeItem): HomeLayout {
        val start = startPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        for (i in start until pages.size) {
            val spot = findFree(pages[i].items, sx, sy) ?: continue
            return replacePage(i, pages[i].copy(items = pages[i].items + make(spot.first, spot.second)))
        }
        return copy(pages = pages + HomePage(newId(), listOf(make(0f, 0f))))
    }

    private fun emptyLayout() = HomeLayout(listOf(HomePage(newId(), emptyList())), emptyList())

    private fun newId() = UUID.randomUUID().toString()

    private fun save(l: HomeLayout) {
        val root = JSONObject()
        root.put("version", 1)
        root.put("pages", JSONArray().apply {
            l.pages.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id)
                    put("items", JSONArray().apply { p.items.forEach { put(itemToJson(it)) } })
                })
            }
        })
        root.put("drawerFolders", JSONArray().apply {
            l.drawerFolders.forEach { f ->
                put(JSONObject().apply {
                    put("id", f.id)
                    put("name", f.name)
                    put("keys", JSONArray(f.appKeys))
                    put("color", f.color)
                })
            }
        })
        root.put("drawerOrder", JSONArray(l.drawerOrder))
        prefs.edit().putString(KEY_LAYOUT, root.toString()).apply()
    }

    private fun itemToJson(item: HomeItem): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("x", item.cellX.toDouble())
        put("y", item.cellY.toDouble())
        when (item) {
            is HomeItem.App -> {
                put("type", "app")
                put("key", item.appKey)
            }
            is HomeItem.Folder -> {
                put("type", "folder")
                put("name", item.name)
                put("keys", JSONArray(item.appKeys))
                put("sx", item.spanX)
                put("sy", item.spanY)
            }
            is HomeItem.Widget -> {
                put("type", "widget")
                put("wid", item.appWidgetId)
                put("sx", item.spanX)
                put("sy", item.spanY)
            }
        }
    }

    private fun load(): HomeLayout {
        val raw = prefs.getString(KEY_LAYOUT, null) ?: return emptyLayout()
        return try {
            val root = JSONObject(raw)
            val pagesJson = root.optJSONArray("pages") ?: JSONArray()
            val pages = (0 until pagesJson.length()).map { i ->
                val p = pagesJson.getJSONObject(i)
                val itemsJson = p.optJSONArray("items") ?: JSONArray()
                HomePage(
                    id = p.optString("id").ifEmpty { newId() },
                    items = (0 until itemsJson.length()).mapNotNull { j -> itemFromJson(itemsJson.getJSONObject(j)) },
                )
            }
            val foldersJson = root.optJSONArray("drawerFolders") ?: JSONArray()
            val folders = (0 until foldersJson.length()).map { i ->
                val f = foldersJson.getJSONObject(i)
                DrawerFolder(
                    f.optString("id").ifEmpty { newId() },
                    f.optString("name", "Folder"),
                    f.optJSONArray("keys").toStringList(),
                    f.optInt("color", 0),
                )
            }
            val order = root.optJSONArray("drawerOrder").toStringList()
            if (pages.isEmpty()) emptyLayout() else HomeLayout(pages, folders, order)
        } catch (e: Exception) {
            emptyLayout()
        }
    }

    private fun itemFromJson(o: JSONObject): HomeItem? {
        val id = o.optString("id").ifEmpty { newId() }
        val x = o.optDouble("x", 0.0).toFloat()
        val y = o.optDouble("y", 0.0).toFloat()
        return when (o.optString("type")) {
            "app" -> HomeItem.App(id, o.getString("key"), x, y)
            "folder" -> HomeItem.Folder(
                id, o.optString("name", "Folder"), o.optJSONArray("keys").toStringList(), x, y,
                o.optInt("sx", 1), o.optInt("sy", 1),
            )
            "widget" -> HomeItem.Widget(id, o.getInt("wid"), x, y, o.optInt("sx", 1), o.optInt("sy", 1))
            else -> null
        }
    }

    private fun JSONArray?.toStringList(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }

    private companion object {
        const val PREFS = "kinetic_home_layout"
        const val KEY_LAYOUT = "layout"
        const val EPS = 0.01f
    }
}
