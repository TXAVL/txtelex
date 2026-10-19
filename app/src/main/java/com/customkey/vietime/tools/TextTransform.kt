package com.customkey.vietime.tools

import java.text.Normalizer
import java.util.Locale

/** Công cụ văn bản trên bàn phím: đổi kiểu chữ, bỏ dấu tiếng Việt. Thuần Kotlin, test được. */
enum class TextTransform(val label: String) {
    UPPER("HOA"),
    LOWER("thường"),
    TITLE("Hoa Đầu Từ"),
    SENTENCE("Hoa đầu câu"),
    STRIP_TONES("Bỏ dấu");

    fun apply(s: String): String = when (this) {
        UPPER -> s.uppercase(VI)
        LOWER -> s.lowercase(VI)
        TITLE -> titleCase(s)
        SENTENCE -> sentenceCase(s)
        STRIP_TONES -> stripTones(s)
    }

    companion object {
        private val VI = Locale("vi", "VN")

        /** Hoa chữ đầu mỗi từ, phần còn lại thường ("xin CHÀO bạn" → "Xin Chào Bạn"). */
        fun titleCase(s: String): String {
            val sb = StringBuilder(s.length)
            var start = true
            for (c in s.lowercase(VI)) {
                sb.append(if (start && c.isLetter()) c.titlecaseChar() else c)
                start = c.isWhitespace() || c == '-' || c == '(' || c == '"' || c == '“'
            }
            return sb.toString()
        }

        /** Hoa đầu mỗi câu (sau . ! ? xuống dòng), phần còn lại giữ nguyên chữ thường. */
        fun sentenceCase(s: String): String {
            val sb = StringBuilder(s.length)
            var start = true
            for (c in s.lowercase(VI)) {
                if (start && c.isLetter()) { sb.append(c.titlecaseChar()); start = false } else sb.append(c)
                if (c in ".!?\n…") start = true
            }
            return sb.toString()
        }

        /** "Tiếng Việt đẹp" → "Tieng Viet dep" (bỏ mọi dấu thanh / mũ / móc, đ → d). */
        fun stripTones(s: String): String {
            val n = Normalizer.normalize(s, Normalizer.Form.NFD)
            val sb = StringBuilder(n.length)
            for (c in n) {
                if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
                sb.append(when (c) { 'đ' -> 'd'; 'Đ' -> 'D'; else -> c })
            }
            return Normalizer.normalize(sb, Normalizer.Form.NFC)
        }
    }
}
