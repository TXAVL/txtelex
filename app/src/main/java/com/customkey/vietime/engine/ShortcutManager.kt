// MIT License — Copyright (c) 2026 Phil Trinh
// Extracted from ptrinh/viettelex (https://github.com/ptrinh/viettelex), see assets/licenses/viettelex-LICENSE.txt.
// Modified for com.customkey.vietime.

package com.customkey.vietime.engine

/**
 * Gõ tắt (text expansion) — logic THUẦN, port 1:1 iOS `Shortcuts.swift` (giữ hai bản y
 * hệt; hành vi như macOS TelexInputController.boundary / ShortcutImporter).
 *
 * Hai loại khoá:
 *  • khoá CHỮ ("ko", "đc"): tra lúc gõ ranh giới (space/Enter/dấu câu) sau từ đang soạn —
 *    dạng ĐÃ BIẾN ĐỔI trước ("ddc" → "đc"), rồi phím THÔ ("dc"), như macOS. Ưu tiên TRƯỚC
 *    tự khôi phục tiếng Anh.
 *  • khoá có KÝ HIỆU/SỐ ("->", "√√", "k2"): tra lúc gõ khoảng trắng/Enter, so với cả cụm
 *    liền nhau ngay trước con trỏ.
 *  • khoá CHỮ+SỐ ("ad1", "2fa" — #109): như khoá ký hiệu/số nhưng nở cả ở dấu câu (như khoá
 *    chữ); VNI khớp qua phím thô ("ad1" soạn thành "ád").
 * Không nở khi từ dính liền sau chữ số (#82: "5h", "5h30") hoặc sau / # @ (#87).
 * Giữ hoa: ko → không, Ko → Không, KO → KHÔNG (khoá viết thường).
 */
class ShortcutTable(entries: Map<String, String> = emptyMap()) {
    val entries: Map<String, String> = entries.filter { (k, v) -> isValidKey(k) && v.isNotEmpty() }
    /** Có khoá không thuần chữ (cần đọc cụm trước con trỏ lúc gõ khoảng trắng). */
    val hasTokenKeys: Boolean = this.entries.keys.any { !isWordKey(it) }
    val isEmpty: Boolean get() = entries.isEmpty()

    override fun equals(other: Any?) = other is ShortcutTable && other.entries == entries
    override fun hashCode() = entries.hashCode()

    /** Nội dung sẽ bung cho [typed] (đã áp hoa/thường), null = không khớp. */
    fun expansion(typed: String): String? {
        if (typed.isEmpty()) return null
        entries[typed]?.let { return it }
        val lower = typed.lowercase()
        if (lower == typed) return null
        val e = entries[lower] ?: return null
        return applyCase(typed, e)
    }

    /**
     * Từ đang soạn: thử dạng hiển thị rồi phím thô. Khoá thuần chữ, hoặc chữ+số (VNI: chữ số
     * nằm trong từ — "ad1" soạn thành "ád", phím thô "ad1").
     */
    fun wordExpansion(composed: String, raw: String): String? {
        fun ok(k: String) = isWordKey(k) || isAlnumKey(k)
        if (ok(composed)) expansion(composed)?.let { return it }
        if (raw != composed && ok(raw)) expansion(raw)?.let { return it }
        return null
    }

    /** Khoá ký hiệu/số khớp cụm cuối [context]: (cụm, nội dung). */
    fun tokenExpansion(context: String): Pair<String, String>? {
        if (!hasTokenKeys) return null
        val token = trailingToken(context) ?: return null
        if (isWordKey(token)) return null
        val e = expansion(token) ?: return null
        return token to e
    }

    companion object {
        /** Khoá hợp lệ: không rỗng, ≤ 64 code point, không khoảng trắng (như macOS parse()). */
        fun isValidKey(k: String): Boolean =
            k.isNotEmpty() && Cp.count(k) <= 64 && k.codePoints().noneMatch { Character.isWhitespace(it) || Character.isSpaceChar(it) }

        /** Khoá thuần chữ — tra theo từ engine đang soạn. */
        fun isWordKey(k: String): Boolean = k.isNotEmpty() && k.codePoints().allMatch { Character.isLetter(it) }

        /**
         * Khoá CHỮ+SỐ ("ad1", "sdt2", "2fa" — issue #109): chỉ chữ và chữ số, có cả hai. Đi
         * đường cụm ([tokenExpansion]) nhưng nở ở cùng ranh giới với khoá chữ ([triggersAlnum]);
         * VNI khớp qua phím thô ([wordExpansion]). Thuần số ("123") vẫn là khoá ký hiệu/số.
         */
        fun isAlnumKey(k: String): Boolean {
            var letter = false
            var digit = false
            for (cp in k.codePoints()) {
                when {
                    Character.isLetter(cp) -> letter = true
                    Character.isDigit(cp) -> digit = true
                    else -> return false
                }
            }
            return letter && digit
        }

        /** Khoá chữ+số (#109) nở ở cùng ranh giới với khoá chữ (cả dấu câu kết thúc từ). */
        fun triggersAlnum(boundary: String): Boolean = triggersWord(boundary)

        fun applyCase(typed: String, e: String): String? {
            val letters = typed.filter { it.isLetter() }
            if (letters.isEmpty()) return e
            if (letters.length >= 2 && typed == typed.uppercase()) return e.uppercase()
            val rest = letters.substring(1)
            if (letters[0].isUpperCase() && rest == rest.lowercase()) {
                if (e.isEmpty()) return e
                val first = e.codePointAt(0)
                val n = Character.charCount(first)
                return String(Character.toChars(first)).uppercase() + e.substring(n)
            }
            return null
        }

        /** Cụm không khoảng trắng cuối [context]; null nếu rỗng. */
        fun trailingToken(context: String): String? {
            var start = context.length
            while (start > 0) {
                val cp = context.codePointBefore(start)
                if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) break
                start -= Character.charCount(cp)
            }
            return if (start < context.length) context.substring(start) else null
        }

        private val WORD_TRIGGERS = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'', '…', '”', '’', '»')

        /** Ranh giới kích hoạt khoá CHỮ: khoảng trắng/xuống dòng hoặc dấu câu kết thúc từ. */
        fun triggersWord(boundary: String): Boolean {
            val c = boundary.firstOrNull() ?: return false
            return c.isWhitespace() || c in WORD_TRIGGERS
        }

        /** Khoá ký hiệu/số chỉ nở ở khoảng trắng / xuống dòng. */
        fun triggersToken(boundary: String): Boolean = boundary.firstOrNull()?.isWhitespace() == true

        private val GLUE = setOf('/', '#', '@', '.', '_', '-', ':', '\\', '~', '&', '=', '+', '%')

        /**
         * [word] cuối [context] dính liền sau ký tự không cho nở (#82 số, #87 / # @, chữ, nối
         * URL/email/tên file)? context null ⇒ false; context không kết thúc bằng [word] ⇒ true.
         */
        fun isGlued(word: String, context: String?): Boolean {
            if (context == null) return false
            if (!context.endsWith(word)) return true
            val head = context.substring(0, context.length - word.length)
            if (head.isEmpty()) return false
            val cp = head.codePointBefore(head.length)
            return Character.isLetterOrDigit(cp) || (cp < 0x10000 && cp.toChar() in GLUE)
        }
    }
}

/**
 * Nhập/xuất bảng gõ tắt — CÙNG định dạng macOS ShortcutImporter (YAML phẳng "khoá: nội
 * dung", `#` chú thích), port 1:1 iOS `ShortcutFile`. Nội dung nhiều dòng xuất trong ngoặc
 * kép với `\n`; khoá chứa ":" xuất trong ngoặc kép. Pref [Keys.SHORTCUTS] lưu CHÍNH chuỗi
 * YAML này.
 */
object ShortcutFile {
    const val HEADER = "# VietTelex — bảng gõ tắt"

    fun exportYAML(table: Map<String, String>): String {
        val sb = StringBuilder(HEADER).append('\n')
        for (k in table.keys.sorted()) sb.append(quoteKey(k)).append(": ").append(quoteValue(table.getValue(k))).append('\n')
        return sb.toString()
    }

    private fun quoteKey(k: String): String {
        val needs = k.contains(':') || k.startsWith("#") || k.startsWith(";") || k.startsWith("//") ||
            k.startsWith("\"") || k.startsWith("'")
        return if (needs) "\"" + escape(k) + "\"" else k
    }

    private fun quoteValue(v: String): String {
        if (v.contains('\n') || v.contains('\r')) return "\"" + escape(v) + "\""
        val needs = v.startsWith(" ") || v.endsWith(" ") || v.startsWith("'") || v.startsWith("\"") || v.startsWith("#")
        return if (needs) "\"$v\"" else v
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")

    private fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c != '\\') { sb.append(c); continue }
            if (i >= s.length) { sb.append('\\'); break }
            when (val n = s[i++]) {
                'n' -> sb.append('\n'); 't' -> sb.append('\t')
                '\\' -> sb.append('\\'); '"' -> sb.append('"')
                else -> { sb.append('\\'); sb.append(n) }
            }
        }
        return sb.toString()
    }

    /** JSON {khoá: nội dung} hoặc dòng "khoá: nội dung" (`#` `;` `//` chú thích). */
    fun parse(text: String?): Map<String, String> {
        if (text.isNullOrEmpty()) return emptyMap()
        val t = text.removePrefix("\uFEFF")
        parseJsonObject(t)?.let { if (it.isNotEmpty()) return ShortcutTable(it).entries }
        val out = LinkedHashMap<String, String>()
        for (rawLine in t.split("\r\n", "\n", "\r")) {
            val line = rawLine.trim(' ', '\t')
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#") || line.startsWith("//")) continue
            var key: String
            val rest: String
            val q = if (line.startsWith("\"")) quotedPrefix(line) else null
            if (q != null) {
                val r = q.second.trimStart(' ', '\t')
                if (!r.startsWith(":")) continue
                key = q.first; rest = r.substring(1)
            } else {
                val colon = line.indexOf(':')
                if (colon < 0) continue
                key = line.substring(0, colon).trim(' ', '\t')
                rest = line.substring(colon + 1)
            }
            var value = rest.trim(' ', '\t')
            if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = unescape(value.substring(1, value.length - 1))
            } else if (value.length >= 2 && value.startsWith("'") && value.endsWith("'")) {
                value = value.substring(1, value.length - 1)
            }
            key = key.trim(' ', '\t')
            if (!ShortcutTable.isValidKey(key) || value.isEmpty()) continue
            out[key] = value
        }
        return out
    }

    private fun quotedPrefix(s: String): Pair<String, String>? {
        var i = 1
        val raw = StringBuilder()
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') {
                if (i + 1 >= s.length) return null
                raw.append(c).append(s[i + 1]); i += 2; continue
            }
            if (c == '"') return unescape(raw.toString()) to s.substring(i + 1)
            raw.append(c); i++
        }
        return null
    }

    /** JSON object phẳng chuỗi→chuỗi; null nếu không phải. */
    private fun parseJsonObject(s: String): Map<String, String>? {
        val t = s.trim()
        if (!t.startsWith("{") || !t.endsWith("}")) return null
        return try {
            val out = LinkedHashMap<String, String>()
            var i = 1
            fun ws() { while (i < t.length && t[i].isWhitespace()) i++ }
            fun str(): String {
                require(t[i] == '"'); i++
                val sb = StringBuilder()
                while (true) {
                    val c = t[i++]
                    when (c) {
                        '"' -> return sb.toString()
                        '\\' -> when (val e = t[i++]) {
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> { sb.append(t.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                        else -> sb.append(c)
                    }
                }
            }
            ws()
            if (t[i] == '}') return out
            while (true) {
                ws(); val k = str(); ws(); require(t[i] == ':'); i++; ws()
                val v = str(); out[k] = v; ws()
                if (t[i] == ',') { i++; continue }
                require(t[i] == '}'); break
            }
            out
        } catch (e: RuntimeException) { null }
    }

    /** Gộp import: mục nhập vào THẮNG (như macOS). Trả (bảng, số mới, số ghi đè). */
    fun merge(current: Map<String, String>, imported: Map<String, String>): Triple<Map<String, String>, Int, Int> {
        val out = LinkedHashMap(current)
        var added = 0; var replaced = 0
        for ((k, v) in imported) {
            val old = out[k]
            if (old == null) added++ else if (old != v) replaced++
            out[k] = v
        }
        return Triple(out, added, replaced)
    }

    /** Bộ gợi ý — mặc định bảng RỖNG (như macOS), người dùng bấm "Thêm bộ gợi ý". */
    val SUGGESTED: Map<String, String> = linkedMapOf(
        "ko" to "không",
        "dc" to "được",
        "đc" to "được",
        "vs" to "với",
        "mn" to "mọi người",
        "trc" to "trước",
        "cty" to "công ty",
        "bsi" to "bác sĩ",
        "stk" to "số tài khoản",
        "->" to "→",
    )

    /** Học từ nội dung đã bung: "mọi người" → [mọi, người]. */
    fun words(s: String): List<String> = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
}
