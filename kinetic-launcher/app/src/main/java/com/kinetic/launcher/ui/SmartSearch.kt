package com.kinetic.launcher.ui

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import com.kinetic.core.apps.AppEntry
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.E
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Everything the drawer's search box can find besides apps: quick maths, Chowaniec
 * pages, Settings pages, contacts and a web search.
 */
object SmartSearch {

    // ---- Chowaniec ----------------------------------------------------------------------

    const val CHOWANIEC_URL = "https://chowaniec.net"

    data class ChowaniecPage(val title: String, val path: String, val keywords: List<String> = emptyList())

    /** The pages of chowaniec.net, matching the shortcuts in its app manifest. */
    val chowaniecPages = listOf(
        ChowaniecPage("Group Chat", "/group/", listOf("group", "chat", "family")),
        ChowaniecPage("Messages", "/messages/", listOf("messages", "dm", "text", "inbox")),
        ChowaniecPage("Chowaniec AI", "/ai/", listOf("ai", "assistant", "ask")),
        ChowaniecPage("Notes", "/notes/", listOf("notes", "note")),
        ChowaniecPage("Calendar", "/calendar/", listOf("calendar", "events", "schedule")),
        ChowaniecPage("Lists", "/lists/", listOf("lists", "list", "todo", "shopping")),
        ChowaniecPage("Meet", "/meet/", listOf("meet", "call", "video")),
        ChowaniecPage("Watch Party", "/watch/", listOf("watch", "party", "movie")),
        ChowaniecPage("Games", "/games/", listOf("games", "play")),
        ChowaniecPage("Trophies", "/trophies/", listOf("trophies", "awards")),
        ChowaniecPage("Studio", "/studio/", listOf("studio", "create")),
        ChowaniecPage("Uploader", "/uploader/", listOf("upload", "uploader", "files")),
        ChowaniecPage("Chowaniec Settings", "/settings/", listOf("settings", "account")),
    )

    /** The first few pages shown in Chowaniec's long-press menu when it publishes no shortcuts itself. */
    val chowaniecMenuPages = chowaniecPages.take(5)

    /** The installed Chowaniec app (Chrome's WebAPK for chowaniec.net), found by its label. */
    fun chowaniecApp(apps: List<AppEntry>): AppEntry? =
        apps.firstOrNull { it.label.equals("Chowaniec", ignoreCase = true) }
            ?: apps.firstOrNull { it.label.startsWith("Chowaniec", ignoreCase = true) }

    fun isChowaniec(entry: AppEntry): Boolean = entry.label.startsWith("Chowaniec", ignoreCase = true)

    /**
     * Opens a chowaniec.net page inside the installed Chowaniec app (so it looks like
     * the app, not a browser tab). Falls back to whatever handles the link.
     */
    fun openChowaniec(context: Context, path: String, appPackage: String?): Boolean {
        val uri = Uri.parse(CHOWANIEC_URL + path)
        if (appPackage != null) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).setPackage(appPackage).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return start(context, Intent(Intent.ACTION_VIEW, uri))
    }

    fun chowaniecSearchPath(query: String): String = "/search/?q=" + Uri.encode(query.trim())

    fun matchChowaniec(query: String): List<ChowaniecPage> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val brand = "chowaniec".startsWith(q) || q.startsWith("chow")
        return chowaniecPages.filter { page ->
            brand || page.title.lowercase().contains(q) || page.keywords.any { it.startsWith(q) }
        }.take(if (brand) 6 else 3)
    }

    // ---- Settings -----------------------------------------------------------------------

    data class SettingsPage(val title: String, val action: String, val keywords: List<String> = emptyList())

    val settingsPages = listOf(
        SettingsPage("Wi-Fi", Settings.ACTION_WIFI_SETTINGS, listOf("wifi", "wi-fi", "internet", "network")),
        SettingsPage("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS, listOf("bluetooth", "headphones", "earbuds", "pair")),
        SettingsPage("Connections", Settings.ACTION_WIRELESS_SETTINGS, listOf("connections", "hotspot", "sim", "mobile")),
        SettingsPage("Airplane mode", Settings.ACTION_AIRPLANE_MODE_SETTINGS, listOf("airplane", "flight", "plane")),
        SettingsPage("Mobile data usage", Settings.ACTION_DATA_USAGE_SETTINGS, listOf("data", "usage")),
        SettingsPage("NFC and payments", Settings.ACTION_NFC_SETTINGS, listOf("nfc", "tap", "pay")),
        SettingsPage("Display", Settings.ACTION_DISPLAY_SETTINGS, listOf("display", "brightness", "screen", "dark", "refresh")),
        SettingsPage("Eye comfort / night light", Settings.ACTION_NIGHT_DISPLAY_SETTINGS, listOf("night", "eye", "blue light")),
        SettingsPage("Sounds and vibration", Settings.ACTION_SOUND_SETTINGS, listOf("sound", "volume", "ringtone", "vibration")),
        SettingsPage("Notifications", "android.settings.NOTIFICATION_SETTINGS", listOf("notifications", "alerts")),
        SettingsPage("Do not disturb", "android.settings.ZEN_MODE_SETTINGS", listOf("dnd", "do not disturb", "silence", "focus")),
        SettingsPage("Battery", Settings.ACTION_BATTERY_SAVER_SETTINGS, listOf("battery", "power", "saver", "charging")),
        SettingsPage("Location", Settings.ACTION_LOCATION_SOURCE_SETTINGS, listOf("location", "gps")),
        SettingsPage("Security", Settings.ACTION_SECURITY_SETTINGS, listOf("security", "lock", "fingerprint", "face", "password")),
        SettingsPage("Privacy", Settings.ACTION_PRIVACY_SETTINGS, listOf("privacy", "permissions")),
        SettingsPage("Apps", Settings.ACTION_APPLICATION_SETTINGS, listOf("apps", "applications", "uninstall")),
        SettingsPage("Default apps", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, listOf("default", "browser")),
        SettingsPage("Home app", Settings.ACTION_HOME_SETTINGS, listOf("home", "launcher")),
        SettingsPage("Storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, listOf("storage", "space")),
        SettingsPage("Accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS, listOf("accessibility", "font", "text size")),
        SettingsPage("Keyboard", Settings.ACTION_INPUT_METHOD_SETTINGS, listOf("keyboard", "typing")),
        SettingsPage("Language", Settings.ACTION_LOCALE_SETTINGS, listOf("language")),
        SettingsPage("Date and time", Settings.ACTION_DATE_SETTINGS, listOf("date", "time", "clock")),
        SettingsPage("About phone", Settings.ACTION_DEVICE_INFO_SETTINGS, listOf("about", "phone", "software", "version")),
        SettingsPage("All settings", Settings.ACTION_SETTINGS, listOf("settings")),
    )

    fun matchSettings(query: String): List<SettingsPage> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        return settingsPages.filter { page ->
            page.title.lowercase().contains(q) || page.keywords.any { it.startsWith(q) || q.startsWith(it) }
        }.take(3)
    }

    fun openSettings(context: Context, page: SettingsPage): Boolean =
        start(context, Intent(page.action)) || start(context, Intent(Settings.ACTION_SETTINGS))

    // ---- Contacts -----------------------------------------------------------------------

    data class Contact(val name: String, val lookupUri: Uri, val photo: Uri?)

    /** Contacts whose name matches [query]. Needs READ_CONTACTS; returns nothing without it. */
    fun contacts(context: Context, query: String, limit: Int = 4): List<Contact> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(q))
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
        )
        val out = ArrayList<Contact>()
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(0)
                    val lookup = c.getString(1) ?: continue
                    val name = c.getString(2) ?: continue
                    val photo = c.getString(3)?.let(Uri::parse)
                    out += Contact(name, ContactsContract.Contacts.getLookupUri(id, lookup), photo)
                }
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
        return out
    }

    fun openContact(context: Context, contact: Contact): Boolean =
        start(context, Intent(Intent.ACTION_VIEW, contact.lookupUri))

    // ---- Web ----------------------------------------------------------------------------

    fun webSearch(context: Context, query: String): Boolean =
        start(context, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)) ||
            start(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))

    // ---- Quick maths --------------------------------------------------------------------

    /**
     * Evaluates simple maths like "12*(3+4)", "15% of 80", "2^10", "sqrt 2".
     * Returns the result text, or null if [input] isn't a sum (plain numbers don't count).
     */
    fun calculate(input: String): String? {
        val text = input.trim().lowercase()
            .replace("×", "*").replace("x", "*").replace("÷", "/").replace("−", "-")
            .replace(",", "")
            .replace(Regex("""(\d+(?:\.\d+)?)\s*%\s*of\s*"""), "($1/100)*")
        if (text.isEmpty() || !text.any { it in "+-*/^%(" || text.contains("sqrt") }) return null
        if (!text.any { it.isDigit() } && !text.contains("pi")) return null
        // Phone numbers and dates ("555-1234", "2026-11-23") aren't sums.
        if (Regex("""^\+?\d{3,}(-\d+)+$""").matches(text.replace(" ", ""))) return null
        val value = try {
            Parser(text).parse()
        } catch (_: Exception) {
            return null
        }
        if (value.isNaN() || value.isInfinite()) return null
        return format(value)
    }

    private fun format(v: Double): String {
        if (abs(v) >= 1e15 || (abs(v) < 1e-9 && v != 0.0)) return "%.6g".format(v)
        val bd = BigDecimal(v).round(MathContext(12, RoundingMode.HALF_UP)).stripTrailingZeros()
        return if (bd.scale() <= 0) "%,d".format(bd.toLong()) else bd.toPlainString()
    }

    /** A tiny recursive-descent parser: + - * / % ^, brackets, sqrt, pi and e. */
    private class Parser(private val s: String) {
        private var i = 0

        fun parse(): Double {
            val v = expr()
            skip()
            require(i == s.length)
            return v
        }

        private fun skip() {
            while (i < s.length && s[i] == ' ') i++
        }

        private fun eat(ch: Char): Boolean {
            skip()
            if (i < s.length && s[i] == ch) {
                i++
                return true
            }
            return false
        }

        private fun expr(): Double {
            var v = term()
            while (true) {
                v = when {
                    eat('+') -> v + term()
                    eat('-') -> v - term()
                    else -> return v
                }
            }
        }

        private fun term(): Double {
            var v = power()
            while (true) {
                v = when {
                    eat('*') -> v * power()
                    eat('/') -> v / power()
                    else -> return v
                }
            }
        }

        private fun power(): Double {
            val base = unary()
            return if (eat('^')) base.pow(power()) else base
        }

        private fun unary(): Double = when {
            eat('-') -> -unary()
            eat('+') -> unary()
            else -> postfix()
        }

        private fun postfix(): Double {
            var v = atom()
            while (eat('%')) v /= 100.0
            return v
        }

        private fun atom(): Double {
            skip()
            if (eat('(')) {
                val v = expr()
                require(eat(')'))
                return v
            }
            if (s.startsWith("sqrt", i)) {
                i += 4
                return sqrt(unary())
            }
            if (s.startsWith("pi", i)) {
                i += 2
                return PI
            }
            if (i < s.length && s[i] == 'e' && (i + 1 == s.length || !s[i + 1].isLetter())) {
                i++
                return E
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            require(i > start)
            return s.substring(start, i).toDouble()
        }
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
