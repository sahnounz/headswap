package com.yegorkapomidorka.headswap

/**
 * Fitting roadName (NAVI_NEXT_TURN_INFO, field 3) under instrument cluster Honda (head unit CarLife 1.1.0, STATISTIC_INFO channel 2077xxxx/2083xxxx,
 * HU_INFO empty). Formulas found by car tests:
 * - S = string length in bytes of UTF-8 to the first NUL; head unit counts these bytes as UTF-16, so only visible
 *   first floor(S/2)-1 characters. With S >= 32 text is empty or garbage, with S <= 31 works.
 * - Cyrillic and other non-ASCII drawn full-width (2 cells), ASCII - 1 cell. If the visible part is wider
 *   ~24 cells, instrument cluster discards the whole line (the previous text remains); 24 passes, 26 and no more.
 * - Tail spaces work like padding (NUL is not suitable): "World" + 2 space shown as "World".
 *
 * Therefore, we take the longest prefix, which has a width <= [MAX_CELLS], characters <= [MAX_CHARS] and
 * max(Bytes, 2*symbols+2) <= [MAX_BYTES]and we'll get the gaps to S = max(Bytes, 2*symbols+2): then all prefix symbols are visible.
 */
object HondaClusterRoad {
    /** Maximum cells visible part: wider instrument cluster line casts entirely. */
    const val MAX_CELLS = 24

    /** Maximum visible characters: S = 2c+2 and S <= 31 comes out c <= 14. */
    const val MAX_CHARS = 14

    /** Maximum S (byte of string up to NUL): with 32 text blank or garbage. */
    const val MAX_BYTES = 31

    private const val ASCII_LIMIT = 0x80
    private const val WIDE_CELLS = 2
    private const val UTF16_OVERHEAD = 2        // can be seen floor(S/2)-1 characters, so S >= 2c+2
    private const val DIRECTION_PREFIX = "В сторону "

    fun fit(s: String): String {
        var text = normalize(s)
        if (text.length > DIRECTION_PREFIX.length && text.regionMatches(0, DIRECTION_PREFIX, 0, DIRECTION_PREFIX.length, ignoreCase = true)) {
            text = text.substring(DIRECTION_PREFIX.length).trimStart()
        }
        if (text.isEmpty()) return ""

        var end = 0
        var cells = 0
        var chars = 0
        var bytes = 0
        while (end < text.length) {
            val cp = text.codePointAt(end)
            val nCells = cells + cellWidth(cp)
            val nChars = chars + 1
            val nBytes = bytes + utf8Len(cp)
            if (nCells > MAX_CELLS || nChars > MAX_CHARS || maxOf(nBytes, UTF16_OVERHEAD * nChars + UTF16_OVERHEAD) > MAX_BYTES) break
            cells = nCells
            chars = nChars
            bytes = nBytes
            end += Character.charCount(cp)
        }
        val head = text.substring(0, end).trimEnd(' ')
        if (head.isEmpty()) return ""
        val headChars = head.codePointCount(0, head.length)
        val headBytes = head.toByteArray(Charsets.UTF_8).size
        val padTo = maxOf(headBytes, UTF16_OVERHEAD * headChars + UTF16_OVERHEAD)
        return head + " ".repeat(padTo - headBytes)
    }

    /** Space symbols - in the usual space, control and format (U+00AD, etc.) - throw out, pop-up spaces, trim. */
    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when {
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                Character.getType(cp) == Character.CONTROL.toInt() || Character.getType(cp) == Character.FORMAT.toInt() -> Unit
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString().trim()
    }

    private fun cellWidth(cp: Int) = if (cp < ASCII_LIMIT) 1 else WIDE_CELLS

    private fun utf8Len(cp: Int) = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }
}
