package com.baer.cupola.orbit.geo

import java.text.Normalizer
import java.util.Locale

/** Search-key folding shared by the build-time generator and the runtime index. */
object GeoFold {
    private val marks = Regex("\\p{M}+")

    /** Lowercase, diacritics stripped, ß/æ/œ/ø/ł/đ/ð/ı mapped, non-alphanumerics collapsed to single spaces. */
    fun fold(text: String): String {
        val decomposed = marks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
        val sb = StringBuilder(decomposed.length + 2)
        var pendingSpace = false
        for (ch in decomposed) {
            val mapped: String? = when (ch) {
                'ß' -> "ss"
                'æ' -> "ae"
                'œ' -> "oe"
                'ø' -> "o"
                'ł' -> "l"
                'đ', 'ð' -> "d"
                'ı' -> "i"
                else -> null
            }
            if (mapped != null || Character.isLetterOrDigit(ch)) {
                if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                pendingSpace = false
                if (mapped != null) sb.append(mapped) else sb.append(ch)
            } else {
                pendingSpace = true
            }
        }
        return sb.toString()
    }
}
