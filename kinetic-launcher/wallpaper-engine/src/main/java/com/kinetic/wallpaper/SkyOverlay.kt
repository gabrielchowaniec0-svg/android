package com.kinetic.wallpaper

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The real sky over you, drawn on top of the wallpaper: the stars and planets as they
 * are right now (they turn slowly through the night), and planes and the space
 * station passing overhead, with their real lights.
 *
 * The view: you're facing south, the horizon is near the bottom of the screen and the
 * point straight above you is near the top (east on the left, west on the right).
 */
object SkyOverlay {

    // ---- the catalogue --------------------------------------------------------------

    /** Right ascension (hours), declination (degrees), magnitude, colour. */
    private class Star(val ra: Double, val dec: Double, val mag: Float, val color: Int, val name: String = "")

    private const val BLUE = 0xFFC9D7FF.toInt()
    private const val WHITE = 0xFFF4F6FF.toInt()
    private const val YELLOW = 0xFFFFF1D6.toInt()
    private const val ORANGE = 0xFFFFD9A8.toInt()
    private const val RED = 0xFFFFBE9A.toInt()

    private val BRIGHT = listOf(
        Star(6.752, -16.716, -1.46f, WHITE, "Sirius"), Star(6.399, -52.696, -0.74f, WHITE), Star(14.261, 19.182, -0.05f, ORANGE, "Arcturus"),
        Star(18.616, 38.784, 0.03f, BLUE, "Vega"), Star(5.278, 45.998, 0.08f, YELLOW, "Capella"), Star(5.242, -8.202, 0.13f, BLUE, "Rigel"),
        Star(7.655, 5.225, 0.34f, YELLOW, "Procyon"), Star(5.919, 7.407, 0.50f, RED, "Betelgeuse"), Star(1.629, -57.237, 0.46f, BLUE),
        Star(19.846, 8.868, 0.77f, WHITE, "Altair"), Star(4.599, 16.509, 0.85f, ORANGE, "Aldebaran"), Star(16.490, -26.432, 1.09f, RED, "Antares"),
        Star(13.420, -11.161, 0.97f, BLUE, "Spica"), Star(7.755, 28.026, 1.14f, ORANGE, "Pollux"), Star(22.961, -29.622, 1.16f, WHITE, "Fomalhaut"),
        Star(20.690, 45.280, 1.25f, WHITE, "Deneb"), Star(10.139, 11.967, 1.35f, BLUE, "Regulus"), Star(6.977, -28.972, 1.50f, BLUE),
        Star(7.577, 31.888, 1.58f, WHITE, "Castor"), Star(17.560, -37.104, 1.62f, BLUE), Star(5.419, 6.350, 1.64f, BLUE, "Bellatrix"),
        Star(5.438, 28.608, 1.65f, BLUE), Star(5.603, -1.202, 1.69f, BLUE, "Alnilam"), Star(5.679, -1.943, 1.74f, BLUE, "Alnitak"),
        Star(12.900, 55.960, 1.77f, WHITE, "Alioth"), Star(11.062, 61.751, 1.79f, ORANGE, "Dubhe"), Star(3.405, 49.861, 1.79f, YELLOW),
        Star(7.140, -26.393, 1.83f, YELLOW), Star(18.403, -34.385, 1.85f, BLUE), Star(13.792, 49.313, 1.86f, BLUE, "Alkaid"),
        Star(5.992, 44.948, 1.90f, WHITE), Star(6.629, 16.399, 1.93f, WHITE), Star(2.530, 89.264, 1.98f, YELLOW, "Polaris"),
        Star(6.378, -17.956, 1.98f, BLUE), Star(9.460, -8.659, 1.98f, ORANGE), Star(2.120, 23.462, 2.00f, ORANGE),
        Star(10.333, 19.842, 2.08f, ORANGE), Star(0.727, -17.987, 2.04f, ORANGE), Star(18.921, -26.297, 2.05f, BLUE),
        Star(1.162, 35.621, 2.05f, RED), Star(0.140, 29.091, 2.06f, BLUE), Star(17.582, 12.560, 2.07f, WHITE),
        Star(14.845, 74.156, 2.08f, ORANGE), Star(5.796, -9.670, 2.09f, BLUE, "Saiph"), Star(11.818, 14.572, 2.14f, WHITE),
        Star(3.136, 40.956, 2.10f, BLUE), Star(2.065, 42.330, 2.10f, ORANGE), Star(5.533, -0.299, 2.23f, BLUE, "Mintaka"),
        Star(0.675, 56.537, 2.24f, ORANGE, "Schedar"), Star(0.153, 59.150, 2.28f, WHITE, "Caph"), Star(0.945, 60.717, 2.15f, BLUE, "Navi"),
        Star(1.430, 60.235, 2.68f, WHITE, "Ruchbah"), Star(1.907, 63.670, 3.37f, BLUE, "Segin"), Star(11.031, 56.382, 2.37f, WHITE, "Merak"),
        Star(11.897, 53.695, 2.44f, WHITE, "Phecda"), Star(12.257, 57.033, 3.31f, WHITE, "Megrez"), Star(13.399, 54.925, 2.23f, WHITE, "Mizar"),
        Star(17.943, 51.489, 2.24f, ORANGE), Star(21.736, 9.875, 2.39f, ORANGE), Star(23.063, 28.083, 2.42f, RED),
        Star(23.079, 15.205, 2.49f, WHITE), Star(0.221, 15.184, 2.83f, BLUE), Star(20.370, 40.257, 2.23f, YELLOW),
        Star(19.512, 27.960, 3.05f, ORANGE), Star(20.770, 33.970, 2.48f, ORANGE), Star(19.750, 45.131, 2.87f, WHITE),
        Star(21.310, 62.585, 2.45f, WHITE), Star(15.738, 6.426, 2.63f, ORANGE), Star(15.283, -9.383, 2.61f, BLUE),
        Star(5.546, -17.822, 2.58f, YELLOW), Star(3.038, 4.090, 2.54f, RED), Star(3.791, 24.105, 2.87f, BLUE, "Alcyone"),
        Star(3.749, 24.113, 3.70f, BLUE), Star(3.820, 24.053, 3.62f, BLUE), Star(3.763, 24.368, 3.87f, BLUE),
    )

    /** Faint background stars, spread evenly over the sky (fixed, so they turn with the rest). */
    private val FAINT: List<Star> = run {
        val r = java.util.Random(42)
        List(260) {
            val ra = r.nextDouble() * 24.0
            val dec = Math.toDegrees(asin(2.0 * r.nextDouble() - 1.0))
            val mag = 3.4f + r.nextFloat() * 1.4f
            val c = when (r.nextInt(6)) { 0 -> BLUE; 1 -> YELLOW; 2 -> ORANGE; else -> WHITE }
            Star(ra, dec, mag, c)
        }
    }

    private val LINES = listOf(
        // Orion
        "Betelgeuse" to "Bellatrix", "Bellatrix" to "Mintaka", "Mintaka" to "Alnilam", "Alnilam" to "Alnitak",
        "Alnitak" to "Saiph", "Saiph" to "Rigel", "Rigel" to "Mintaka", "Betelgeuse" to "Alnitak",
        // Big Dipper
        "Dubhe" to "Merak", "Merak" to "Phecda", "Phecda" to "Megrez", "Megrez" to "Dubhe",
        "Megrez" to "Alioth", "Alioth" to "Mizar", "Mizar" to "Alkaid",
        // Cassiopeia
        "Caph" to "Schedar", "Schedar" to "Navi", "Navi" to "Ruchbah", "Ruchbah" to "Segin",
    )

    // ---- where things are --------------------------------------------------------------

    private fun rad(d: Double) = d * PI / 180.0
    private fun deg(r: Double) = r * 180.0 / PI
    private fun norm360(d: Double) = ((d % 360.0) + 360.0) % 360.0

    private fun julian(ms: Long) = ms / 86_400_000.0 + 2440587.5

    /** Altitude and azimuth (degrees; azimuth from north, clockwise) of RA (hours) / Dec. */
    internal fun altAz(raH: Double, decD: Double, latD: Double, lstD: Double, out: DoubleArray) {
        val ha = rad(lstD - raH * 15.0)
        val dec = rad(decD)
        val lat = rad(latD)
        val sinAlt = sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(ha)
        val alt = asin(sinAlt.coerceIn(-1.0, 1.0))
        val az = atan2(-sin(ha) * cos(dec), sin(dec) * cos(lat) - cos(dec) * sin(lat) * cos(ha))
        out[0] = deg(alt)
        out[1] = norm360(deg(az))
    }

    internal fun lst(ms: Long, lonD: Double): Double = norm360(280.46061837 + 360.98564736629 * (julian(ms) - 2451545.0) + lonD)

    /** Planets (RA hours, Dec degrees) by Paul Schlyter's method: good to a fraction of a degree. */
    /** For tests: planets as (name, RA hours, Dec degrees). */
    internal fun planetPositions(ms: Long): List<Triple<String, Double, Double>> = planets(ms).map { Triple(it.name, it.ra, it.dec) }

    private fun planets(ms: Long): List<Star> {
        val d = julian(ms) - 2451543.5
        fun kepler(mDeg: Double, e: Double): Double {
            val m = rad(norm360(mDeg))
            var ea = m + e * sin(m) * (1.0 + e * cos(m))
            repeat(5) { ea -= (ea - e * sin(ea) - m) / (1.0 - e * cos(ea)) }
            return ea
        }
        // The sun (where the earth is, seen backwards).
        val ws = 282.9404 + 4.70935e-5 * d
        val es = 0.016709 - 1.151e-9 * d
        val ms0 = 356.0470 + 0.9856002585 * d
        val eS = kepler(ms0, es)
        val xvS = cos(eS) - es
        val yvS = sqrt(1.0 - es * es) * sin(eS)
        val lonSun = atan2(yvS, xvS) + rad(ws)
        val rS = sqrt(xvS * xvS + yvS * yvS)
        val xs = rS * cos(lonSun)
        val ys = rS * sin(lonSun)
        val ecl = rad(23.4393 - 3.563e-7 * d)

        data class El(val n: Double, val i: Double, val w: Double, val a: Double, val e: Double, val m: Double, val mag: Float, val color: Int, val name: String)
        val list = listOf(
            El(76.6799 + 2.46590e-5 * d, 3.3946 + 2.75e-8 * d, 54.8910 + 1.38374e-5 * d, 0.723330, 0.006773 - 1.302e-9 * d, 48.0052 + 1.6021302244 * d, -4.2f, 0xFFFFFBEF.toInt(), "Venus"),
            El(49.5574 + 2.11081e-5 * d, 1.8497 - 1.78e-8 * d, 286.5016 + 2.92961e-5 * d, 1.523688, 0.093405 + 2.516e-9 * d, 18.6021 + 0.5240207766 * d, 0.6f, 0xFFFFB58A.toInt(), "Mars"),
            El(100.4542 + 2.76854e-5 * d, 1.3030 - 1.557e-7 * d, 273.8777 + 1.64505e-5 * d, 5.20256, 0.048498 + 4.469e-9 * d, 19.8950 + 0.0830853001 * d, -2.3f, 0xFFFFF5E0.toInt(), "Jupiter"),
            El(113.6634 + 2.38980e-5 * d, 2.4886 - 1.081e-7 * d, 339.3939 + 2.97661e-5 * d, 9.55475, 0.055546 - 9.499e-9 * d, 316.9670 + 0.0334442282 * d, 0.6f, 0xFFFFE6B8.toInt(), "Saturn"),
        )
        return list.map { p ->
            val ea = kepler(p.m, p.e)
            val xv = p.a * (cos(ea) - p.e)
            val yv = p.a * sqrt(1.0 - p.e * p.e) * sin(ea)
            val v = atan2(yv, xv)
            val r = sqrt(xv * xv + yv * yv)
            val n = rad(p.n)
            val i = rad(p.i)
            val vw = v + rad(p.w)
            val xh = r * (cos(n) * cos(vw) - sin(n) * sin(vw) * cos(i))
            val yh = r * (sin(n) * cos(vw) + cos(n) * sin(vw) * cos(i))
            val zh = r * sin(vw) * sin(i)
            val xg = xh + xs
            val yg = yh + ys
            val ye = yg * cos(ecl) - zh * sin(ecl)
            val ze = yg * sin(ecl) + zh * cos(ecl)
            val ra = norm360(deg(atan2(ye, xg))) / 15.0
            val dec = deg(atan2(ze, sqrt(xg * xg + ye * ye)))
            Star(ra, dec, p.mag, p.color, p.name)
        }
    }

    // ---- the view -------------------------------------------------------------------------

    /** Screen position for altitude/azimuth (degrees), or false if it's off screen. */
    private fun project(alt: Double, az: Double, w: Int, h: Int, out: FloatArray): Boolean {
        if (alt < 1.0) return false
        var daz = az - 180.0
        if (daz > 180) daz -= 360.0
        if (daz < -180) daz += 360.0
        // Squeezes towards the zenith, like looking up into a dome.
        val x = w / 2.0 + (daz / 58.0) * (w / 2.0) * cos(rad(alt)).coerceAtLeast(0.12)
        val y = h * 0.93 - (alt / 90.0) * h * 0.90
        if (x < -20 || x > w + 20) return false
        out[0] = x.toFloat()
        out[1] = y.toFloat()
        return true
    }

    // Stars move slowly: their screen positions are worked out every few seconds.
    private class Placed(val x: Float, val y: Float, val r: Float, val a: Float, val color: Int, val alt: Float, val glow: Boolean)

    private var placed: List<Placed> = emptyList()
    private var placedNames: Map<String, Placed> = emptyMap()
    private var placedAt = 0L
    private var placedW = 0
    private var placedH = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tmp = DoubleArray(2)
    private val pt = FloatArray(2)

    private fun place(w: Int, h: Int, density: Float, lat: Double, lon: Double) {
        val now = System.currentTimeMillis()
        val l = lst(now, lon)
        val out = ArrayList<Placed>(BRIGHT.size + FAINT.size + 4)
        val names = HashMap<String, Placed>()
        fun add(s: Star, planet: Boolean) {
            altAz(s.ra, s.dec, lat, l, tmp)
            if (!project(tmp[0], tmp[1], w, h, pt)) return
            val mag = s.mag
            val r = (1.25f - mag * 0.32f).coerceIn(0.45f, if (planet) 2.4f else 1.9f) * density
            // Near the horizon stars are dimmer (more air in the way).
            val haze = ((tmp[0] - 1.0) / 18.0).coerceIn(0.15, 1.0).toFloat()
            val a = ((1.15f - (mag + 1.0f) / 5.6f).coerceIn(0.12f, 1f)) * haze
            val p = Placed(pt[0], pt[1], r, a, s.color, tmp[0].toFloat(), planet || mag < 0.6f)
            out += p
            if (s.name.isNotEmpty()) names[s.name] = p
        }
        FAINT.forEach { add(it, false) }
        BRIGHT.forEach { add(it, false) }
        planets(now).forEach { add(it, true) }
        placed = out
        placedNames = names
        placedAt = SystemClock.elapsedRealtime()
        placedW = w
        placedH = h
    }

    // ---- planes and the space station --------------------------------------------------

    private const val EARTH = 6_371_000.0

    /** Where something at [lat]/[lon]/[altM] appears in your sky: (elevation, azimuth, distance m). */
    private fun look(obsLat: Double, obsLon: Double, lat: Double, lon: Double, altM: Double, out: DoubleArray): Double {
        // Earth-centred positions (spherical earth is plenty for this).
        fun ecef(la: Double, lo: Double, r: Double): DoubleArray {
            val a = rad(la)
            val b = rad(lo)
            return doubleArrayOf(r * cos(a) * cos(b), r * cos(a) * sin(b), r * sin(a))
        }
        val o = ecef(obsLat, obsLon, EARTH)
        val p = ecef(lat, lon, EARTH + altM)
        val dx = p[0] - o[0]
        val dy = p[1] - o[1]
        val dz = p[2] - o[2]
        val la = rad(obsLat)
        val lo = rad(obsLon)
        // East, north, up at the observer.
        val e = -sin(lo) * dx + cos(lo) * dy
        val n = -sin(la) * cos(lo) * dx - sin(la) * sin(lo) * dy + cos(la) * dz
        val u = cos(la) * cos(lo) * dx + cos(la) * sin(lo) * dy + sin(la) * dz
        val dist = sqrt(e * e + n * n + u * u)
        out[0] = deg(asin((u / dist).coerceIn(-1.0, 1.0)))
        out[1] = norm360(deg(atan2(e, n)))
        return dist
    }

    /** Moves a point [m] metres along [bearing] degrees. */
    private fun move(lat: Double, lon: Double, bearing: Double, m: Double, out: DoubleArray) {
        val d = m / EARTH
        val b = rad(bearing)
        val la = rad(lat)
        val lo = rad(lon)
        val la2 = asin(sin(la) * cos(d) + cos(la) * sin(d) * cos(b))
        val lo2 = lo + atan2(sin(b) * sin(d) * cos(la), cos(d) - sin(la) * sin(la2))
        out[0] = deg(la2)
        out[1] = deg(lo2)
    }

    private val ll = DoubleArray(2)
    private val ea = DoubleArray(2)
    private val path = Path()

    /** Screen point for something at lat/lon/alt, or null. Also returns the distance in [distOut]. */
    private fun screenOf(obsLat: Double, obsLon: Double, lat: Double, lon: Double, altM: Double, w: Int, h: Int, out: FloatArray, distOut: DoubleArray? = null): Boolean {
        val dist = look(obsLat, obsLon, lat, lon, altM, ea)
        distOut?.set(0, dist)
        return project(ea[0], ea[1], w, h, out)
    }

    private val wingL = FloatArray(2)
    private val wingR = FloatArray(2)
    private val tail = FloatArray(2)
    private val dist = DoubleArray(1)

    private fun drawPlane(c: Canvas, p: WallpaperSignals.SkyPlane, obsLat: Double, obsLon: Double, w: Int, h: Int, density: Float, night: Float, vis: Float, nowRt: Long) {
        // Where it is now (it keeps flying between the updates that come every few seconds).
        val ahead = p.speedMps * (nowRt - p.at) / 1000.0
        move(p.lat, p.lon, p.trackDeg, ahead, ll)
        val lat = ll[0]
        val lon = ll[1]
        if (!screenOf(obsLat, obsLon, lat, lon, p.altM, w, h, pt, dist)) return
        val elev = ea[0]
        if (elev < 4.0 || dist[0] > 40_000) return
        val x = pt[0]
        val y = pt[1]
        // Wing tips: 17 m either side of the nose direction (real positions, so the
        // red light is always on the real left wing).
        move(lat, lon, p.trackDeg - 90.0, 17.0, ll)
        val okL = screenOf(obsLat, obsLon, ll[0], ll[1], p.altM, w, h, wingL)
        move(lat, lon, p.trackDeg + 90.0, 17.0, ll)
        val okR = screenOf(obsLat, obsLon, ll[0], ll[1], p.altM, w, h, wingR)
        // Keep the lights a believable few pixels apart, however near or far.
        fun clampSpread(t: FloatArray) {
            val dx = t[0] - x
            val dy = t[1] - y
            val len = sqrt(dx * dx + dy * dy)
            val want = len.coerceIn(2.2f * density, 9f * density)
            if (len > 0.01f) {
                t[0] = x + dx / len * want
                t[1] = y + dy / len * want
            } else {
                t[0] = x
                t[1] = y
            }
        }
        if (okL) clampSpread(wingL) else { wingL[0] = x; wingL[1] = y }
        if (okR) clampSpread(wingR) else { wingR[0] = x; wingR[1] = y }

        val sec = (nowRt % 100_000L) / 1000.0 + (p.id.hashCode() and 0xFF) / 97.0
        if (night > 0.35f) {
            val n = night * vis
            // Steady navigation lights: red on the left wing, green on the right.
            glowDot(c, wingL[0], wingL[1], 1.05f * density, 0xFFFF3B30.toInt(), 0.95f * n)
            glowDot(c, wingR[0], wingR[1], 1.05f * density, 0xFF34C759.toInt(), 0.95f * n)
            // White wing-tip strobes: a quick double flash every 1.2 s.
            val ph = sec % 1.2
            if (ph < 0.05 || (ph > 0.13 && ph < 0.18)) {
                glowDot(c, wingL[0], wingL[1], 1.6f * density, 0xFFFFFFFF.toInt(), n, glow = 3.5f)
                glowDot(c, wingR[0], wingR[1], 1.6f * density, 0xFFFFFFFF.toInt(), n, glow = 3.5f)
            }
            // Red anti-collision beacon on the body, about once a second.
            val pb = (sec + 0.5) % 1.0
            if (pb < 0.12) glowDot(c, x, y, 1.2f * density, 0xFFFF2D20.toInt(), n * (1f - (pb / 0.12).toFloat() * 0.4f), glow = 2.5f)
        } else {
            val d = (1f - night) * vis
            // Daytime: a contrail behind high planes, then a tiny dark speck for the plane.
            if (p.altM > 7_500) {
                path.reset()
                var first = true
                var k = 0
                while (k <= 24) {
                    move(lat, lon, p.trackDeg + 180.0, p.speedMps * k * 4.0, ll)
                    if (screenOf(obsLat, obsLon, ll[0], ll[1], p.altM, w, h, tail)) {
                        if (first) path.moveTo(tail[0], tail[1]) else path.lineTo(tail[0], tail[1])
                        first = false
                    }
                    k++
                }
                linePaint.color = 0xFFFFFFFF.toInt()
                linePaint.alpha = (70 * d).toInt().coerceIn(0, 255)
                linePaint.strokeWidth = 1.6f * density
                c.drawPath(path, linePaint)
            }
            linePaint.color = 0xFF2A2D33.toInt()
            linePaint.alpha = (200 * d).toInt().coerceIn(0, 255)
            linePaint.strokeWidth = 1.3f * density
            c.drawLine(wingL[0], wingL[1], wingR[0], wingR[1], linePaint)
            paint.color = 0xFF2A2D33.toInt()
            paint.alpha = (220 * d).toInt().coerceIn(0, 255)
            c.drawCircle(x, y, 1.1f * density, paint)
        }
    }

    private fun drawStation(c: Canvas, s: WallpaperSignals.SkyPlane, obsLat: Double, obsLon: Double, w: Int, h: Int, density: Float, night: Float, vis: Float, nowRt: Long) {
        if (night < 0.4f) return
        val ahead = s.speedMps * (nowRt - s.at) / 1000.0
        move(s.lat, s.lon, s.trackDeg, ahead, ll)
        if (!screenOf(obsLat, obsLon, ll[0], ll[1], s.altM, w, h, pt)) return
        if (ea[0] < 10.0) return
        // A steady, bright, slightly warm point gliding across: no blinking (it reflects sunlight).
        glowDot(c, pt[0], pt[1], 1.9f * density, 0xFFFFF8EC.toInt(), night * vis, glow = 4f)
    }

    private fun glowDot(c: Canvas, x: Float, y: Float, r: Float, color: Int, a: Float, glow: Float = 2.6f) {
        if (a <= 0.01f) return
        paint.color = color
        paint.alpha = (60 * a).toInt().coerceIn(0, 255)
        c.drawCircle(x, y, r * glow, paint)
        paint.alpha = (255 * a).toInt().coerceIn(0, 255)
        c.drawCircle(x, y, r, paint)
    }

    /**
     * Draws everything. [vis] fades it (lock screen only, by default), [night] 0..1,
     * [clouds] 0..1 hides the stars. [tSec] makes the stars twinkle.
     */
    fun draw(c: Canvas, w: Int, h: Int, density: Float, vis: Float, night: Float, clouds: Float, tSec: Float, stars: Boolean, planes: Boolean, lines: Boolean) {
        if (vis <= 0.01f) return
        val lat = WallpaperSignals.lat
        val lon = WallpaperSignals.lon
        if (lat.isNaN() || lon.isNaN()) return
        val starVis = vis * night * (1f - 0.92f * clouds)
        if (stars && starVis > 0.02f) {
            if (w != placedW || h != placedH || SystemClock.elapsedRealtime() - placedAt > 4_000) place(w, h, density, lat, lon)
            if (lines) {
                linePaint.color = 0xFFB8C6FF.toInt()
                linePaint.strokeWidth = 0.7f * density
                linePaint.alpha = (30 * starVis).toInt().coerceIn(0, 255)
                LINES.forEach { (a, b) ->
                    val p = placedNames[a]
                    val q = placedNames[b]
                    if (p != null && q != null) c.drawLine(p.x, p.y, q.x, q.y, linePaint)
                }
            }
            for ((i, s) in placed.withIndex()) {
                // Twinkle: stronger low in the sky; planets hardly twinkle.
                val tw = if (s.glow && s.r > 1.9f * density) 0.04f else 0.10f + 0.25f * (1f - (s.alt / 50f).coerceIn(0f, 1f))
                val flicker = 1f - tw * (0.5f + 0.5f * sin(tSec * (2.1f + (i % 7) * 0.53f) + i * 1.7f))
                val a = s.a * starVis * flicker
                if (a < 0.02f) continue
                if (s.glow) {
                    paint.color = s.color
                    paint.alpha = (45 * a).toInt().coerceIn(0, 255)
                    c.drawCircle(s.x, s.y, s.r * 3.2f, paint)
                }
                paint.color = s.color
                paint.alpha = (255 * a).toInt().coerceIn(0, 255)
                c.drawCircle(s.x, s.y, s.r, paint)
            }
        }
        if (planes) {
            val nowRt = SystemClock.elapsedRealtime()
            WallpaperSignals.planes.forEach { drawPlane(c, it, lat, lon, w, h, density, night, vis, nowRt) }
            WallpaperSignals.station?.let { drawStation(c, it, lat, lon, w, h, density, night, vis, nowRt) }
        }
    }
}
