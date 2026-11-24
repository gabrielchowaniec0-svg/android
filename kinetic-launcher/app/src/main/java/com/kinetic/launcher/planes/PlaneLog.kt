package com.kinetic.launcher.planes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Every plane you've locked onto, kept on the phone (newest first, up to 1000). */
object PlaneLog {
    data class Entry(
        val callsign: String,
        val type: String?,
        val airline: String?,
        val from: String?,
        val to: String?,
        val registration: String?,
        val at: Long,
    )

    private fun prefs(context: Context) = context.getSharedPreferences("plane_log", Context.MODE_PRIVATE)

    fun all(context: Context): List<Entry> = runCatching {
        val arr = JSONArray(prefs(context).getString("log", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(
                o.optString("c"), o.optString("t").ifBlank { null }, o.optString("a").ifBlank { null },
                o.optString("f").ifBlank { null }, o.optString("o").ifBlank { null }, o.optString("r").ifBlank { null }, o.optLong("at"),
            )
        }
    }.getOrDefault(emptyList())

    /** Saves a sighting (the same flight on the same day counts once). True if it was new. */
    fun add(context: Context, e: Entry): Boolean {
        val list = all(context)
        val day = e.at / 86_400_000L
        if (list.any { it.callsign == e.callsign && it.at / 86_400_000L == day }) return false
        val out = JSONArray()
        (listOf(e) + list).take(1000).forEach { x ->
            out.put(
                JSONObject().put("c", x.callsign).put("t", x.type ?: "").put("a", x.airline ?: "")
                    .put("f", x.from ?: "").put("o", x.to ?: "").put("r", x.registration ?: "").put("at", x.at)
            )
        }
        prefs(context).edit().putString("log", out.toString()).apply()
        return true
    }
}
