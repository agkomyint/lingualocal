package com.example.lingualocal.ai

/**
 * Cleans the live preview: the app's own voice heard back, and late words of the piece above, don't belong
 * to the piece being recorded. Only the preview is touched, never the final words.
 */
internal object PreviewText {
    private const val SEED = 4
    private const val SHORT = 5
    private const val LONG = 8
    private const val OPENING = "\"'(["

    /** Ways to step over a misheard letter or two, smallest first. */
    private val SKIPS = listOf(1 to 1, 1 to 0, 0 to 1, 2 to 2, 2 to 1, 1 to 2, 2 to 0, 0 to 2)

    private class Units(val ch: String, val at: IntArray)
    private class Source(val text: String, val own: Boolean)

    private fun isMark(c: Char): Boolean = when (c.category) {
        CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK -> true
        else -> false
    }

    private fun isWord(c: Char) = c.isLetterOrDigit() || isMark(c)
    private fun isLatin(c: Char) = c in 'a'..'z' || c in '0'..'9'

    private fun units(text: String): Units {
        val at = text.indices.filter { isWord(text[it]) }.toIntArray()
        return Units(String(CharArray(at.size) { text[at[it]].lowercaseChar() }), at)
    }

    /** How far [a] from [i] and [b] from [j] say the same, allowing small mishearings. */
    private fun matchEnd(a: String, i: Int, b: String, j: Int): Pair<Int, Int> {
        var x = i
        var y = j
        while (true) {
            while (x < a.length && y < b.length && a[x] == b[y]) {
                x++
                y++
            }
            if (x - i < SEED || x == a.length || y == b.length) return x to y
            val skip = SKIPS.firstOrNull { (da, db) -> a.regionMatches(x + da, b, y + db, SEED) } ?: return x to y
            x += skip.first
            y += skip.second
        }
    }

    /** [raw] without what the app just said ([spoken]) and without the end of the pieces [above] at its start. */
    fun clean(raw: String, spoken: Collection<String>, above: Collection<String>): String {
        val u = units(raw)
        val ch = u.ch
        val at = u.at
        val n = ch.length
        val sources = (spoken.map { Source(units(it).ch, true) } + above.map { Source(units(it).ch, false) })
            .filter { it.text.isNotEmpty() }
        if (n == 0 || sources.isEmpty()) return raw
        fun insideWord(k: Int) = k > 0 && k < n && at[k] == at[k - 1] + 1 && isLatin(ch[k - 1]) && isLatin(ch[k])

        val out = StringBuilder()
        var from = 0
        var kept = false
        var i = 0
        while (i < n) {
            var end = i
            if (!isMark(ch[i]) && !insideWord(i)) {
                for (s in sources) {
                    if (kept && !s.own) continue
                    val text = s.text
                    for (j in text.indices) {
                        if (text[j] != ch[i]) continue
                        var (x, y) = matchEnd(ch, i, text, j)
                        // The last letters are often still being corrected.
                        if (x - i >= SHORT && n - x <= 2 && y < text.length) x = n
                        while (insideWord(x) && x > i) {
                            x--
                            y--
                        }
                        val len = x - i
                        val growing = x == n
                        val whole = y >= text.length
                        val mostOfIt = len >= text.length * 0.6
                        val hide = when {
                            kept -> len >= LONG && (mostOfIt || (j == 0 && growing))
                            s.own -> (growing && len >= (if (j == 0) 2 else SHORT)) ||
                                (whole && len >= (if (j == 0) SEED else SHORT)) || (len >= LONG && mostOfIt)
                            else -> (growing || whole) && len >= SHORT
                        }
                        if (hide && x > end) end = x
                    }
                }
            }
            if (end == i) {
                kept = true
                i++
                continue
            }
            if (kept) out.append(raw, from, at[i])
            from = at[end - 1] + 1
            val next = if (end < n) at[end] else raw.length
            while (from < next && raw[from] !in OPENING) from++
            i = end
        }
        if (from == 0) return raw
        return if (kept) out.toString() + raw.substring(from) else ""
    }
}
