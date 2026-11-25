package com.kinetic.launcher.pen

import android.content.Context
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ======================================================================================
// Shapes: draw one with the S Pen to run the command you chose for it.
// Uses the "$1 unistroke recognizer" (a classic, tiny shape matcher).
// ======================================================================================
object PenShapes {
    val names = listOf("Heart", "Circle", "Check", "Star", "Triangle")

    private fun prefs(c: Context) = com.kinetic.launcher.KineticApp.pen(c)

    /** The command a shape runs ("" = none). */
    fun command(c: Context, shape: String): String = prefs(c).getString("shape_$shape", "") ?: ""

    fun setCommand(c: Context, shape: String, cmd: String) {
        prefs(c).edit().putString("shape_$shape", cmd.trim()).apply()
    }

    private const val N = 64
    private const val SIZE = 250.0

    private data class P(val x: Double, val y: Double)

    private val templates: List<Pair<String, List<P>>> by lazy {
        val raw = mutableListOf<Pair<String, List<P>>>()
        // Heart, from its top dip, round the left, down to the tip, back up the right.
        raw += "Heart" to (0..120).map { i ->
            val t = Math.PI * 2 * i / 120.0
            val x = 16 * Math.pow(sin(t), 3.0)
            val y = -(13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t))
            P(-x, y)
        }
        raw += "Circle" to (0..96).map { i -> val t = Math.PI * 2 * i / 96.0; P(sin(t), -cos(t)) }
        raw += "Check" to listOf(P(0.0, 0.55), P(0.35, 1.0), P(1.0, 0.0))
        raw += "Triangle" to listOf(P(0.5, 0.0), P(0.0, 1.0), P(1.0, 1.0), P(0.5, 0.0))
        raw += "Star" to (0..5).map { i ->
            val a = -Math.PI / 2 + i * 4 * Math.PI / 5
            P(cos(a), sin(a))
        }
        // Either direction counts.
        raw.flatMap { (n, pts) -> listOf(n to normalize(pts), n to normalize(pts.reversed())) }
    }

    /** The best matching shape and how sure it is (0..1). */
    fun match(points: List<Offset>): Pair<String, Double>? {
        if (points.size < 10) return null
        val c = normalize(points.map { P(it.x.toDouble(), it.y.toDouble()) })
        var best: String? = null
        var bestD = Double.MAX_VALUE
        for ((name, t) in templates) {
            val d = distanceAtBestAngle(c, t)
            if (d < bestD) {
                bestD = d
                best = name
            }
        }
        val score = 1.0 - bestD / (0.5 * sqrt(SIZE * SIZE * 2))
        return best?.let { it to score }
    }

    private fun normalize(pts: List<P>): List<P> {
        var p = resample(pts, N)
        val c = centroid(p)
        val angle = atan2(c.y - p[0].y, c.x - p[0].x)
        p = rotate(p, -angle)
        p = scale(p)
        val c2 = centroid(p)
        return p.map { P(it.x - c2.x, it.y - c2.y) }
    }

    private fun resample(pts: List<P>, n: Int): List<P> {
        val total = pts.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }.sum()
        val step = total / (n - 1)
        if (step <= 0.0) return List(n) { pts.first() }
        val src = pts.toMutableList()
        val out = mutableListOf(src[0])
        var acc = 0.0
        var i = 1
        while (i < src.size) {
            val a = src[i - 1]
            val b = src[i]
            val d = hypot(b.x - a.x, b.y - a.y)
            if (acc + d >= step && d > 0) {
                val t = (step - acc) / d
                val q = P(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
                out += q
                src.add(i, q)
                acc = 0.0
            } else {
                acc += d
            }
            i++
        }
        while (out.size < n) out += src.last()
        return out.take(n)
    }

    private fun centroid(p: List<P>) = P(p.sumOf { it.x } / p.size, p.sumOf { it.y } / p.size)

    private fun rotate(p: List<P>, a: Double): List<P> {
        val c = centroid(p)
        val cs = cos(a)
        val sn = sin(a)
        return p.map { P((it.x - c.x) * cs - (it.y - c.y) * sn + c.x, (it.x - c.x) * sn + (it.y - c.y) * cs + c.y) }
    }

    private fun scale(p: List<P>): List<P> {
        val minX = p.minOf { it.x }
        val maxX = p.maxOf { it.x }
        val minY = p.minOf { it.y }
        val maxY = p.maxOf { it.y }
        val w = max(maxX - minX, 1e-6)
        val h = max(maxY - minY, 1e-6)
        return p.map { P(it.x * SIZE / w, it.y * SIZE / h) }
    }

    private fun pathDistance(a: List<P>, b: List<P>) = a.indices.sumOf { hypot(a[it].x - b[it].x, a[it].y - b[it].y) } / a.size

    private fun distanceAtBestAngle(c: List<P>, t: List<P>): Double {
        val phi = 0.5 * (-1 + sqrt(5.0))
        var a = -Math.toRadians(45.0)
        var b = Math.toRadians(45.0)
        var x1 = phi * a + (1 - phi) * b
        var f1 = pathDistance(rotate(c, x1), t)
        var x2 = (1 - phi) * a + phi * b
        var f2 = pathDistance(rotate(c, x2), t)
        while (abs(b - a) > Math.toRadians(2.0)) {
            if (f1 < f2) {
                b = x2; x2 = x1; f2 = f1
                x1 = phi * a + (1 - phi) * b
                f1 = pathDistance(rotate(c, x1), t)
            } else {
                a = x1; x1 = x2; f1 = f2
                x2 = (1 - phi) * a + phi * b
                f2 = pathDistance(rotate(c, x2), t)
            }
        }
        return min(f1, f2)
    }
}

// ======================================================================================
// Sticky notes: "note buy milk" (written or typed) sticks to the first home page.
// Written ones keep your handwriting.
// ======================================================================================
object StickyNotes {
    data class Note(val id: Long, val text: String, val ink: List<List<Offset>>)

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()
    private var loaded = false

    /** The handwriting of the note being written right now (set by the home screen). */
    @Volatile
    var pendingInk: List<List<Offset>> = emptyList()

    private fun prefs(c: Context) = com.kinetic.launcher.KineticApp.pen(c)

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        _notes.value = runCatching {
            val arr = JSONArray(prefs(c).getString("notes", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val strokes = o.optJSONArray("ink") ?: JSONArray()
                Note(
                    o.getLong("id"), o.optString("text"),
                    (0 until strokes.length()).map { s ->
                        val pts = strokes.getJSONArray(s)
                        (0 until pts.length() / 2).map { k -> Offset(pts.getDouble(k * 2).toFloat(), pts.getDouble(k * 2 + 1).toFloat()) }
                    },
                )
            }
        }.getOrDefault(emptyList())
    }

    fun add(c: Context, text: String) {
        load(c)
        // Keep the handwriting, squeezed into a 0..1 box (and thinned out to keep it small).
        val ink = pendingInk
        pendingInk = emptyList()
        val all = ink.flatten()
        val norm = if (all.isEmpty()) {
            emptyList()
        } else {
            val minX = all.minOf { it.x }
            val minY = all.minOf { it.y }
            val span = max(all.maxOf { it.x } - minX, all.maxOf { it.y } - minY).coerceAtLeast(1f)
            ink.map { s -> s.filterIndexed { i, _ -> i % 2 == 0 || i == s.lastIndex }.map { Offset((it.x - minX) / span, (it.y - minY) / span) } }
        }
        // Only as many as fit on the home page are kept, so none are ever hidden.
        val all = listOf(Note(System.currentTimeMillis(), text, norm)) + _notes.value
        if (all.size > MAX) {
            com.kinetic.launcher.island.IslandBus.tell(c, "✎", "Removed your oldest note (\u201C${all.last().text}\u201D) to make room")
        }
        _notes.value = all.take(MAX)
        save(c)
    }

    /** How many fit on the first home page. */
    const val MAX = 4

    /** The note that best matches what you wrote ("milk" finds "buy milk"). */
    fun find(c: Context, query: String): Note? {
        load(c)
        val q = query.lowercase().trim()
        val notes = _notes.value
        notes.firstOrNull { it.text.lowercase() == q }?.let { return it }
        notes.firstOrNull { it.text.lowercase().contains(q) }?.let { return it }
        val words = q.split(' ').filter { it.length > 2 }
        return notes.map { n -> n to words.count { n.text.lowercase().contains(it) } }
            .filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    fun remove(c: Context, id: Long) {
        _notes.value = _notes.value.filter { it.id != id }
        save(c)
    }

    private fun save(c: Context) {
        val arr = JSONArray()
        _notes.value.forEach { n ->
            val strokes = JSONArray()
            n.ink.forEach { s -> strokes.put(JSONArray().apply { s.forEach { p -> put(p.x.toDouble()); put(p.y.toDouble()) } }) }
            arr.put(JSONObject().put("id", n.id).put("text", n.text).put("ink", strokes))
        }
        prefs(c).edit().putString("notes", arr.toString()).apply()
    }
}

// ======================================================================================
// Command chains: one name runs several commands ("gaming mode" → focus + volume + app).
// ======================================================================================
object CommandChains {
    private fun prefs(c: Context) = com.kinetic.launcher.KineticApp.pen(c)

    fun all(c: Context): Map<String, List<String>> = runCatching {
        val o = JSONObject(prefs(c).getString("chains", null) ?: "{}")
        o.keys().asSequence().associateWith { k -> o.getString(k).lines().map { it.trim() }.filter { it.isNotEmpty() } }
    }.getOrDefault(emptyMap())

    fun find(c: Context, name: String): Pair<String, List<String>>? =
        all(c).entries.firstOrNull { it.key.equals(name.trim(), ignoreCase = true) }?.let { it.key to it.value }

    fun put(c: Context, name: String, lines: String) {
        val o = runCatching { JSONObject(prefs(c).getString("chains", null) ?: "{}") }.getOrDefault(JSONObject())
        all(c).keys.firstOrNull { it.equals(name.trim(), true) }?.let { o.remove(it) }
        o.put(name.trim(), lines.trim())
        prefs(c).edit().putString("chains", o.toString()).apply()
    }

    fun remove(c: Context, name: String) {
        val o = runCatching { JSONObject(prefs(c).getString("chains", null) ?: "{}") }.getOrDefault(JSONObject())
        o.remove(name)
        prefs(c).edit().putString("chains", o.toString()).apply()
    }
}

// ======================================================================================
// Maths with steps: "2x + 5 = 17" → the answer and how to get there.
// ======================================================================================
object EquationSolver {
    /** Returns the steps (last one is the answer), or null if it isn't a one-x equation. */
    fun solve(raw: String): List<String>? {
        val s = raw.lowercase().replace(" ", "").replace("×", "*").replace("÷", "/").replace("−", "-")
        if (!s.contains('x') || s.count { it == '=' } != 1) return null
        if (!Regex("""^[0-9x+\-*/.()=]+$""").matches(s)) return null
        val (l, r) = s.split('=')
        if (l.isEmpty() || r.isEmpty()) return null
        fun f(x: Double) = eval(l, x) - eval(r, x)
        val b = runCatching { f(0.0) }.getOrNull() ?: return null
        val a = runCatching { f(1.0) - b }.getOrNull() ?: return null
        // Check it's really linear.
        val f2 = runCatching { f(2.0) }.getOrNull() ?: return null
        if (abs(f2 - (2 * a + b)) > 1e-6 || abs(a) < 1e-12) return null
        val x = -b / a
        val steps = mutableListOf(pretty(raw.trim()))
        // The classic shape: ax + b = c
        val m = Regex("""^(-?[0-9.]*)\*?x([+-][0-9.]+)?=(-?[0-9.]+)$""").matchEntire(s)
        if (m != null) {
            val coef = m.groupValues[1].let { if (it.isEmpty()) 1.0 else if (it == "-") -1.0 else it.toDouble() }
            val add = m.groupValues[2].takeIf { it.isNotEmpty() }?.toDouble() ?: 0.0
            val rhs = m.groupValues[3].toDouble()
            if (add != 0.0) steps += (if (add > 0) "Subtract ${num(add)}" else "Add ${num(-add)}") + ": ${num(coef)}x = ${num(rhs - add)}".replace("1x", "x").replace("-1x", "-x")
            if (coef != 1.0) steps += "Divide by ${num(coef)}"
        } else {
            steps += "Collect the x terms: ${num(a)}x = ${num(-b)}"
            if (a != 1.0) steps += "Divide by ${num(a)}"
        }
        steps += "x = ${num(x)}"
        return steps
    }

    private fun pretty(s: String) = s.replace("*", "×")

    private fun num(v: Double): String {
        val r = Math.round(v * 10000) / 10000.0
        return if (r == Math.floor(r) && abs(r) < 1e12) r.toLong().toString() else r.toString()
    }

    /** A tiny calculator for + − × ÷ and brackets, with x and implied × ("2x", "3(x+1)"). */
    private fun eval(expr: String, x: Double): Double {
        val src = expr
        var i = 0
        fun peek() = if (i < src.length) src[i] else '\u0000'
        lateinit var parseExpr: () -> Double
        fun parseFactor(): Double {
            val c = peek()
            return when {
                c == '-' -> { i++; -parseFactor() }
                c == '+' -> { i++; parseFactor() }
                c == '(' -> { i++; val v = parseExpr(); if (peek() == ')') i++; v }
                c == 'x' -> { i++; x }
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (peek().isDigit() || peek() == '.') i++
                    src.substring(start, i).toDouble()
                }
                else -> throw IllegalArgumentException("bad")
            }
        }
        fun parseTerm(): Double {
            var v = parseFactor()
            while (true) {
                val c = peek()
                v = when {
                    c == '*' -> { i++; v * parseFactor() }
                    c == '/' -> { i++; v / parseFactor() }
                    c == 'x' || c == '(' -> v * parseFactor() // implied ×
                    else -> return v
                }
            }
        }
        parseExpr = {
            var v = parseTerm()
            while (peek() == '+' || peek() == '-') {
                val op = src[i++]
                val t = parseTerm()
                v = if (op == '+') v + t else v - t
            }
            v
        }
        val v = parseExpr()
        if (i != src.length) throw IllegalArgumentException("trailing")
        return v
    }
}
