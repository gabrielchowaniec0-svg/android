package com.kinetic.launcher.island

/**
 * Keeps Kinetic (mostly) emoji-free: colourful emoji become plain, single-colour
 * symbols that take the island's colours, and anything without a plain version is
 * left out.
 */
object Glyphs {
    private val MAP = mapOf(
        0x1F6EC to "✈", // 🛬 landing
        0x1F6EB to "✈", // 🛫
        0x1F4CC to "•", // 📌
        0x1F4DA to "✎", // 📚
        0x1F0CF to "▭", // 🃏
        0x1F4C5 to "▦", // 📅
        0x1F327 to "☂", // 🌧
        0x1F514 to "♪", // 🔔
        0x1F5BC to "▣", // 🖼
        0x1F508 to "♪", // 🔈
        0x1F4D0 to "▤", // 📐
        0x1F4E1 to "≋", // 📡
        0x1F3EB to "✎", // 🏫
        0x1F3B2 to "⚄", // 🎲
        0x1F604 to "☺", // 😄
        0x1F4F7 to "◉", // 📷
        0x1F9EE to "±", // 🧮
        0x1F399 to "●", // 🎙
        0x23F1 to "◷", // ⏱
        0x23F2 to "◷", // ⏲
        0x23F0 to "◷", // ⏰
        0x231B to "◷", // ⌛
    )

    /** An icon in plain symbols ("" if it was only emoji with no plain version). */
    fun plain(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val mapped = MAP[cp]
            when {
                mapped != null -> out.append(mapped).append('︎')
                cp == 0xFE0F || cp == 0x200D -> Unit // emoji style / joiner
                cp >= 0x1F000 -> Unit // other emoji: left out
                cp in 0x2600..0x27BF || cp in 0x2300..0x23FF || cp in 0x2B00..0x2BFF -> out.appendCodePoint(cp).append('︎')
                else -> out.appendCodePoint(cp)
            }
        }
        return out.toString().trim()
    }

    /** Text with any emoji turned plain (and spaces tidied). */
    fun text(s: String): String = plain(s).replace(Regex(" {2,}"), " ")
}
