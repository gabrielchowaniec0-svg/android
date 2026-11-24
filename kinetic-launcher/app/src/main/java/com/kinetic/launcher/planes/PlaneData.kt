package com.kinetic.launcher.planes

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One aircraft in the sky near you. */
data class Plane(
    val hex: String,
    val callsign: String,
    val registration: String?,
    val typeCode: String?,
    val lat: Double,
    val lon: Double,
    val altitudeFt: Int,
    val speedKt: Int?,
    val trackDeg: Double?,
) {
    val label: String get() = callsign.ifBlank { registration ?: hex.uppercase() }
}

/** Where a flight is going, from adsbdb (free, no account). */
data class Route(val airline: String?, val fromCode: String, val fromCity: String, val toCode: String, val toCity: String)

/**
 * Live aircraft positions come from the community ADS-B networks adsb.lol and adsb.fi
 * (free, no account; whichever answers first). Routes come from adsbdb.com.
 */
object PlaneData {
    // Remembers routes, including "no route known" (ConcurrentHashMap can't hold null,
    // which crashed the spotter the first time it locked onto a plane with no route).
    private val routes = java.util.Collections.synchronizedMap(HashMap<String, Route?>())

    /** Aircraft within [radiusNm] nautical miles of a point. Empty if both networks fail. */
    fun nearby(lat: Double, lon: Double, radiusNm: Int = 30): List<Plane> =
        runCatching { nearbyOrThrow(lat, lon, radiusNm) }.getOrDefault(emptyList())

    private fun nearbyOrThrow(lat: Double, lon: Double, radiusNm: Int): List<Plane> {
        val la = "%.4f".format(Locale.US, lat)
        val lo = "%.4f".format(Locale.US, lon)
        val urls = listOf(
            "https://api.adsb.lol/v2/lat/$la/lon/$lo/dist/$radiusNm",
            "https://opendata.adsb.fi/api/v2/lat/$la/lon/$lo/dist/$radiusNm",
        )
        for (u in urls) {
            val json = get(u) ?: continue
            val ac = json.optJSONArray("ac") ?: json.optJSONArray("aircraft") ?: continue
            val out = ArrayList<Plane>(ac.length())
            for (i in 0 until ac.length()) {
                val a = ac.optJSONObject(i) ?: continue
                // Skip anything with a missing or odd position instead of failing on it.
                if (a.optDouble("lat").isNaN() || a.optDouble("lon").isNaN()) continue
                val alt = a.opt("alt_baro").let { it as? Number }?.toInt() ?: a.optInt("alt_geom", -1)
                if (alt <= 0) continue // on the ground or unknown
                out += Plane(
                    hex = a.optString("hex"),
                    callsign = a.optString("flight").trim(),
                    registration = a.optString("r").takeIf { it.isNotBlank() },
                    typeCode = a.optString("t").takeIf { it.isNotBlank() },
                    lat = a.getDouble("lat"),
                    lon = a.getDouble("lon"),
                    altitudeFt = alt,
                    speedKt = if (a.has("gs")) a.optDouble("gs").toInt() else null,
                    trackDeg = if (a.has("track")) a.optDouble("track") else null,
                )
            }
            return out
        }
        return emptyList()
    }

    /** The aircraft flying as [callsign] right now (e.g. "UAL1234"), or null if it isn't in the air. */
    fun byCallsign(callsign: String): Plane? = runCatching { byCallsignOrThrow(callsign) }.getOrNull()

    private fun byCallsignOrThrow(callsign: String): Plane? {
        for (u in listOf("https://api.adsb.lol/v2/callsign/$callsign", "https://opendata.adsb.fi/api/v2/callsign/$callsign")) {
            val ac = get(u)?.let { it.optJSONArray("ac") ?: it.optJSONArray("aircraft") } ?: continue
            for (i in 0 until ac.length()) {
                val a = ac.optJSONObject(i) ?: continue
                if (a.optDouble("lat").isNaN() || a.optDouble("lon").isNaN()) continue
                val alt = a.opt("alt_baro").let { it as? Number }?.toInt() ?: a.optInt("alt_geom", -1)
                if (alt <= 0) continue
                return Plane(
                    hex = a.optString("hex"), callsign = a.optString("flight").trim(),
                    registration = a.optString("r").takeIf { it.isNotBlank() }, typeCode = a.optString("t").takeIf { it.isNotBlank() },
                    lat = a.getDouble("lat"), lon = a.getDouble("lon"), altitudeFt = alt,
                    speedKt = if (a.has("gs")) a.optDouble("gs").toInt() else null,
                    trackDeg = if (a.has("track")) a.optDouble("track") else null,
                )
            }
            return null
        }
        return null
    }

    /** Airline and from → to for a callsign (cached). Null if unknown. */
    fun route(callsign: String): Route? {
        if (callsign.isBlank()) return null
        synchronized(routes) { if (routes.containsKey(callsign)) return routes[callsign] }
        val r = runCatching { routeLookup(callsign) }.getOrNull()
        if (routes.size < 500) routes[callsign] = r
        return r
    }

    private fun routeLookup(callsign: String): Route? {
        return get("https://api.adsbdb.com/v0/callsign/$callsign")
            ?.optJSONObject("response")?.optJSONObject("flightroute")
            ?.let { fr ->
                val o = fr.optJSONObject("origin") ?: return@let null
                val d = fr.optJSONObject("destination") ?: return@let null
                Route(
                    airline = fr.optJSONObject("airline")?.optString("name")?.takeIf { it.isNotBlank() },
                    fromCode = o.optString("iata_code").ifBlank { o.optString("icao_code") },
                    fromCity = o.optString("municipality").ifBlank { o.optString("name") },
                    toCode = d.optString("iata_code").ifBlank { d.optString("icao_code") },
                    toCity = d.optString("municipality").ifBlank { d.optString("name") },
                )
            }
    }

    private fun get(url: String): JSONObject? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", "Kinetic-Launcher (personal plane spotter)")
        }
        try {
            if (c.responseCode !in 200..299) null else JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    // ---- geometry ---------------------------------------------------------------------

    private const val EARTH_M = 6_371_000.0

    /** Ground distance in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Compass bearing (true north, degrees) from you to the plane. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Degrees above the horizon (allows for the Earth curving away). */
    fun elevationDeg(groundM: Double, planeAltM: Double, myAltM: Double): Double {
        val drop = groundM * groundM / (2 * EARTH_M)
        return Math.toDegrees(atan2(planeAltM - myAltM - drop, groundM))
    }

    /** Friendly names for common aircraft type codes. */
    fun typeName(code: String?): String? = when (code?.uppercase()) {
        null -> null
        "A319" -> "Airbus A319"
        "A320" -> "Airbus A320"
        "A20N" -> "Airbus A320neo"
        "A321" -> "Airbus A321"
        "A21N" -> "Airbus A321neo"
        "A332", "A333" -> "Airbus A330"
        "A339" -> "Airbus A330neo"
        "A359", "A35K" -> "Airbus A350"
        "A388" -> "Airbus A380"
        "B737" -> "Boeing 737-700"
        "B738" -> "Boeing 737-800"
        "B739" -> "Boeing 737-900"
        "B37M", "B38M", "B39M", "B3XM" -> "Boeing 737 MAX"
        "B752", "B753" -> "Boeing 757"
        "B762", "B763", "B764" -> "Boeing 767"
        "B772", "B77L", "B773", "B77W" -> "Boeing 777"
        "B788", "B789", "B78X" -> "Boeing 787 Dreamliner"
        "B744", "B748" -> "Boeing 747"
        "E170", "E75L", "E75S", "E175" -> "Embraer 175"
        "E190", "E195", "E290", "E295" -> "Embraer E190/195"
        "CRJ2" -> "Bombardier CRJ200"
        "CRJ7" -> "Bombardier CRJ700"
        "CRJ9" -> "Bombardier CRJ900"
        "BCS1", "BCS3" -> "Airbus A220"
        "C172" -> "Cessna 172"
        else -> code
    }
}
