package com.kinetic.launcher.ui

/** Typo-tolerant matching for search and Commands ("rout to ohare", "call mon", "spotfy"). */
object Fuzzy {

    /** Edit distance (with swapped letters counting as one), stopping early past [max]. */
    fun distance(a: String, b: String, max: Int = 3): Int {
        val s = a.lowercase()
        val t = b.lowercase()
        if (kotlin.math.abs(s.length - t.length) > max) return max + 1
        val d = Array(s.length + 1) { IntArray(t.length + 1) }
        for (i in 0..s.length) d[i][0] = i
        for (j in 0..t.length) d[0][j] = j
        for (i in 1..s.length) {
            var rowMin = Int.MAX_VALUE
            for (j in 1..t.length) {
                val cost = if (s[i - 1] == t[j - 1]) 0 else 1
                var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && s[i - 1] == t[j - 2] && s[i - 2] == t[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
                d[i][j] = v
                rowMin = minOf(rowMin, v)
            }
            if (rowMin > max) return max + 1
        }
        return d[s.length][t.length]
    }

    /** How many typos we forgive for a word of this length. */
    fun allowance(word: String) = when {
        word.length <= 3 -> 0
        word.length <= 5 -> 1
        else -> 2
    }

    /** True if every letter of [query] appears in [text] in order ("ytm" → "YouTube Music"). */
    fun isSubsequence(query: String, text: String): Boolean {
        var i = 0
        val q = query.lowercase().filter { !it.isWhitespace() }
        if (q.length < 2) return false
        for (c in text.lowercase()) {
            if (i < q.length && c == q[i]) i++
        }
        return i == q.length
    }

    /**
     * Fuzzy score of [query] against a label: 0 = no match, higher = better.
     * Used after the exact/prefix/contains passes find nothing.
     */
    fun score(query: String, label: String): Int {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return 0
        val words = label.lowercase().split(' ', '-', '.', '_').filter { it.isNotBlank() }
        // A typo in one word ("spotfy" → "Spotify", "whatsap" → "WhatsApp").
        val best = (words + label.lowercase().replace(" ", "")).minOf { w ->
            distance(q, w.take(q.length + 2), 3).let { minOf(it, distance(q, w, 3)) }
        }
        if (best <= allowance(q)) return 3 - best
        // Initials / skipped letters ("ytm" → YouTube Music).
        if (isSubsequence(q, label) && label.lowercase().startsWith(q.first())) return 1
        return 0
    }

    /** Fixes a misspelt first word against [verbs] ("rout to x" → "route to x"). */
    fun fixFirstWord(input: String, verbs: List<String>): String {
        val trimmed = input.trim()
        val first = trimmed.substringBefore(' ')
        if (first.length < 3 || verbs.any { it.equals(first, ignoreCase = true) }) return trimmed
        val match = verbs.minByOrNull { distance(first, it) } ?: return trimmed
        return if (distance(first, match) <= maxOf(1, allowance(match) - 0)) match + trimmed.drop(first.length) else trimmed
    }
}
