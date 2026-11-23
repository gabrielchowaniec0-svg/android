package com.kinetic.launcher

import android.appwidget.AppWidgetProviderInfo
import android.content.pm.ApplicationInfo
import android.os.Process
import com.kinetic.core.apps.AppEntry
import com.kinetic.core.home.DrawerFolder
import com.kinetic.core.home.HomeItem
import com.kinetic.core.home.HomeLayout
import com.kinetic.core.home.HomePage
import java.util.UUID

/**
 * Gabriel's Galaxy S25 Ultra layout, copied from his One UI Home screenshots
 * (One UI won't let other apps read its layout, so it's written out here).
 *
 * Apps are found by package name first, then by their label, so it keeps working
 * if an app is updated. Anything not listed here (e.g. newly installed apps)
 * goes at the end of the app drawer, like One UI does.
 *
 * Grid: 4 columns × 7 rows; row 6 is the dock.
 */
object MyPhoneLayout {

    /** Bump when the layout below changes, so it's re-applied once. */
    const val VERSION = 1

    data class AppRef(val label: String, val packages: List<String>)

    private fun app(label: String, vararg packages: String) = AppRef(label, packages.toList())

    // ---- app drawer ------------------------------------------------------------

    private sealed interface Slot
    private data class SApp(val ref: AppRef) : Slot
    private data class SFolder(
        val name: String,
        val members: List<AppRef>,
        val rule: ((AppEntry) -> Boolean)? = null,
    ) : Slot
    private data object SPageBreak : Slot

    private fun pkgStarts(vararg prefixes: String): (AppEntry) -> Boolean =
        { a -> prefixes.any { a.packageName.startsWith(it) } }

    private val isGame: (AppEntry) -> Boolean = { it.category == ApplicationInfo.CATEGORY_GAME }

    private val DRAWER: List<Slot> = listOf(
        // ---- page 1 ----
        SFolder(
            "Google",
            listOf(
                app("Chrome", "com.android.chrome"),
                app("Gmail", "com.google.android.gm"),
                app("Maps", "com.google.android.apps.maps"),
                app("Drive", "com.google.android.apps.docs"),
                app("Google TV", "com.google.android.videos"),
                app("Meet", "com.google.android.apps.tachyon"),
                app("Photos", "com.google.android.apps.photos"),
                app("Settings", "com.android.settings"),
                app("YT Music", "com.google.android.apps.youtube.music"),
            ),
            rule = pkgStarts("com.google."),
        ),
        SFolder(
            "Microsoft",
            listOf(
                app("Copilot", "com.microsoft.copilot"),
                app("OneDrive", "com.microsoft.skydrive"),
                app("Outlook", "com.microsoft.office.outlook"),
            ),
            rule = pkgStarts("com.microsoft."),
        ),
        SFolder(
            "Samsung",
            listOf(
                app("SmartThings Find", "com.samsung.android.fmm"),
                app("Samsung Health", "com.sec.android.app.shealth"),
                app("Samsung Members", "com.samsung.android.voc"),
                app("My Files", "com.sec.android.app.myfiles"),
                app("SmartThings", "com.samsung.android.oneconnect"),
                app("Samsung TV Plus", "com.samsung.android.tvplus"),
                app("Voice Recorder", "com.sec.android.app.voicenote"),
                app("Galaxy Wearable", "com.samsung.android.app.watchmanager"),
            ),
            rule = pkgStarts("com.samsung.", "com.sec."),
        ),
        SFolder(
            "Xfinity",
            emptyList(),
            rule = { a -> a.label.contains("xfinity", ignoreCase = true) || a.packageName.contains("comcast") || a.packageName.contains("xfinity") },
        ),
        SApp(app("Calculator", "com.sec.android.app.popupcalculator")),
        SApp(app("Calendar", "com.samsung.android.calendar")),
        SApp(app("Camera", "com.sec.android.app.camera")),
        SApp(app("Expert RAW", "com.samsung.android.app.galaxyraw")),
        SApp(app("Clock", "com.sec.android.app.clockpackage")),
        SApp(app("Gallery", "com.sec.android.gallery3d")),
        SApp(app("Gaming Hub", "com.samsung.android.game.gamehome")),
        SApp(app("Life360", "com.life360.android.safetymapd")),
        SApp(app("Browser", "com.sec.android.app.sbrowser")),
        SApp(app("Messages", "com.google.android.apps.messaging")),
        SApp(app("Phone", "com.samsung.android.dialer")),
        SApp(app("News", "com.samsung.android.app.spage", "com.samsung.android.news")),
        SApp(app("Notes", "com.samsung.android.app.notes")),
        SApp(app("Phone", "com.google.android.dialer")),
        SApp(app("Play Store", "com.android.vending")),
        SApp(app("Modes and Routines", "com.samsung.android.app.routines")),
        SApp(app("Store", "com.sec.android.app.samsungapps")),
        SApp(app("Music", "com.sec.android.app.music")),
        SApp(app("Tips", "com.samsung.android.app.tips")),
        SApp(app("Gemini", "com.google.android.apps.bard")),
        SPageBreak,

        // ---- page 2 (One UI shows these folders without names) ----
        SApp(app("Discord", "com.discord")),
        SFolder("", listOf(app("Roblox", "com.roblox.client")), rule = isGame),
        SApp(app("Wallet", "com.google.android.apps.walletnfcrel")),
        SFolder(
            "",
            listOf(
                app("DoorDash", "com.dd.doordash"),
                app("Pizza Hut", "com.yum.pizzahut"),
                app("Starbucks", "com.starbucks.mobilecard"),
                app("Chick-fil-A", "com.chickfila.cfaflagship"),
            ),
        ),
        SFolder("", listOf(app("Netflix", "com.netflix.mediaclient"), app("Disney+", "com.disney.disneyplus"))),
        SFolder(
            "",
            listOf(
                app("Canva", "com.canva.editor"),
                app("Picsart", "com.picsart.studio"),
                app("Docs", "com.google.android.apps.docs.editors.docs"),
                app("CamScanner", "com.intsig.camscanner"),
                app("CapCut", "com.lemon.lvoverseas"),
            ),
        ),
        SFolder(
            "",
            listOf(
                app("Alexa", "com.amazon.dee.app"),
                app("Amazon Shopping", "com.amazon.mShop.android.shopping"),
                app("Shop", "com.shopify.arrive"),
            ),
        ),
        SFolder(
            "",
            listOf(
                app("Feit Electric"),
                app("myQ", "com.chamberlain.android.liftmaster.myq"),
                app("Ring", "com.ringapp"),
            ),
        ),
        SApp(app("Samsung Pass", "com.samsung.android.samsungpass")),
        SPageBreak,

        // ---- page 3 ----
        SApp(app("Home", "com.google.android.apps.chromecast.app")),
        SApp(app("Google", "com.google.android.googlequicksearchbox")),
        SApp(app("Opuscan")),
        SApp(app("Claude", "com.anthropic.claude")),
        SFolder(
            "business 🤑💵",
            listOf(
                app("Messenger", "com.facebook.orca"),
                app("YouTube", "com.google.android.youtube"),
                app("Instagram", "com.instagram.android"),
                app("Facebook", "com.facebook.katana"),
                app("Chowaniec"),
            ),
        ),
        SApp(app("Netlify")),
        SApp(app("Off Grid AI")),
        SApp(app("Temu", "com.einnovation.temu")),
        SApp(app("Flightsky")),
        SApp(app("GitHub", "com.github.android")),
        SApp(app("Authenticator", "com.google.android.apps.authenticator2")),
        SApp(app("Nodely AI")),
        SApp(app("Beanstox")),
        SApp(app("Govee Home", "com.govee.home")),
    )

    // ---- home screen -------------------------------------------------------------

    /** A widget the layout wants, matched against installed widget providers. */
    data class WidgetSlot(
        val description: String,
        val page: Int,
        val x: Int,
        val y: Int,
        val spanX: Int,
        val spanY: Int,
        val packages: List<String>,
        /** Preferred provider labels, best first (case-insensitive "contains"). */
        val labelKeywords: List<String>,
    )

    private val HOME_WIDGETS = listOf(
        WidgetSlot("Weather & clock", 0, 0, 0, 4, 2, listOf("com.sec.android.daemonapp"), listOf("clock", "weather")),
        WidgetSlot("Device care", 0, 0, 3, 2, 1, listOf("com.samsung.android.lool", "com.samsung.android.sm"), listOf("device care", "memory", "storage")),
        WidgetSlot("Google search bar", 0, 0, 5, 4, 1, listOf("com.google.android.googlequicksearchbox"), listOf("search bar", "search")),
    )

    private val GOOGLE_LARGE_FOLDER = listOf(
        app("Maps", "com.google.android.apps.maps"),
        app("Drive", "com.google.android.apps.docs"),
        app("YT Music", "com.google.android.apps.youtube.music"),
        app("Google TV", "com.google.android.videos"),
        app("Meet", "com.google.android.apps.tachyon"),
        app("Photos", "com.google.android.apps.photos"),
        app("Gmail", "com.google.android.gm"),
        app("Wallet", "com.google.android.apps.walletnfcrel"),
        app("Play Store", "com.android.vending"),
    )

    private val DOCK = listOf(
        app("Phone", "com.samsung.android.dialer"),
        app("Messages", "com.google.android.apps.messaging"),
        app("Gmail", "com.google.android.gm"),
        app("Camera", "com.sec.android.app.camera"),
    )

    // ---- building ----------------------------------------------------------------

    data class Result(
        val layout: HomeLayout,
        val widgets: List<WidgetSlot>,
        /** Labels from the layout that aren't installed (or weren't recognised). */
        val missing: List<String>,
    )

    fun build(apps: List<AppEntry>, columns: Int, rows: Int): Result {
        val missing = mutableListOf<String>()

        // Home page 1
        val home = Resolver(apps)
        val dockRow = (rows - 1).toFloat()
        val pageItems = mutableListOf<HomeItem>()
        val googleKeys = GOOGLE_LARGE_FOLDER.mapNotNull { ref -> home.find(ref, unique = false)?.key ?: run { missing += ref.label; null } }
        if (googleKeys.isNotEmpty()) {
            pageItems += HomeItem.Folder(newId(), "Google", googleKeys, cellX = 2f, cellY = 3f, spanX = 2, spanY = 2)
        }
        // "Samsung 8 Series" (cell 0,4) is a SmartThings device shortcut — not an app, so it isn't recreated.
        home.find(app("Chowaniec"), unique = false)?.let { pageItems += HomeItem.App(newId(), it.key, 1f, 4f) }
            ?: run { missing += "Chowaniec" }
        DOCK.take(columns).forEachIndexed { i, ref ->
            home.find(ref, unique = false)?.let { pageItems += HomeItem.App(newId(), it.key, i.toFloat(), dockRow) }
                ?: run { missing += ref.label }
        }

        // App drawer: explicit apps first (each app used once), then folder rules, then leftovers.
        val drawer = Resolver(apps)
        val resolved = DRAWER.map { slot ->
            when (slot) {
                is SApp -> drawer.find(slot.ref, unique = true).also { if (it == null) missing += slot.ref.label }
                is SFolder -> slot.members.mapNotNull { drawer.find(it, unique = true) }.toMutableList()
                SPageBreak -> null
            }
        }
        DRAWER.forEachIndexed { i, slot ->
            if (slot is SFolder && slot.rule != null) {
                @Suppress("UNCHECKED_CAST")
                val members = resolved[i] as MutableList<AppEntry>
                members += drawer.remaining().filter(slot.rule).onEach { drawer.markUsed(it) }
            }
        }

        val folders = mutableListOf<DrawerFolder>()
        val order = mutableListOf<String>()
        DRAWER.forEachIndexed { i, slot ->
            when (slot) {
                is SApp -> (resolved[i] as AppEntry?)?.let { order += "app:${it.key}" }
                is SFolder -> {
                    @Suppress("UNCHECKED_CAST")
                    val members = resolved[i] as List<AppEntry>
                    if (members.isNotEmpty()) {
                        val f = DrawerFolder(newId(), slot.name, members.map { it.key })
                        folders += f
                        order += "folder:${f.id}"
                    }
                }
                SPageBreak -> order += HomeLayout.PAGE_BREAK
            }
        }
        // Everything else at the end, A–Z, like newly installed apps in One UI.
        drawer.remaining().forEach { order += "app:${it.key}" }

        val layout = HomeLayout(
            pages = listOf(HomePage(newId(), pageItems)),
            drawerFolders = folders,
            drawerOrder = order,
        )
        return Result(layout, HOME_WIDGETS, missing.distinct())
    }

    /** Best installed provider for a widget slot, or null. */
    fun pickProvider(
        slot: WidgetSlot,
        providers: List<AppWidgetProviderInfo>,
        labelOf: (AppWidgetProviderInfo) -> String,
    ): AppWidgetProviderInfo? {
        for (pkg in slot.packages) {
            val candidates = providers.filter { it.provider.packageName == pkg }
            if (candidates.isEmpty()) continue
            for (keyword in slot.labelKeywords) {
                candidates.firstOrNull { labelOf(it).contains(keyword, ignoreCase = true) }?.let { return it }
            }
            return candidates.first()
        }
        return null
    }

    private fun newId() = UUID.randomUUID().toString()

    /** Finds apps by package, then exact label, then label prefix. */
    private class Resolver(apps: List<AppEntry>) {
        private val me = Process.myUserHandle()
        private val ordered = apps.sortedWith(compareBy<AppEntry>({ if (it.user == me) 0 else 1 }, { it.label.lowercase() }))
        private val used = HashSet<String>()

        fun find(ref: AppRef, unique: Boolean): AppEntry? {
            fun free(a: AppEntry) = !unique || a.key !in used
            val wanted = norm(ref.label)
            val hit = ref.packages.firstNotNullOfOrNull { pkg -> ordered.firstOrNull { it.packageName == pkg && free(it) } }
                ?: wanted.takeIf { it.isNotEmpty() }?.let { w -> ordered.firstOrNull { free(it) && norm(it.label) == w } }
                ?: wanted.takeIf { it.isNotEmpty() }?.let { w -> ordered.firstOrNull { free(it) && norm(it.label).startsWith(w) } }
            if (hit != null && unique) used += hit.key
            return hit
        }

        fun markUsed(app: AppEntry) {
            used += app.key
        }

        /** Apps not placed yet, A–Z (personal profile first). */
        fun remaining(): List<AppEntry> = ordered.filter { it.key !in used }

        private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    }
}
