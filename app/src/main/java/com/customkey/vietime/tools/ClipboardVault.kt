package com.customkey.vietime.tools

/** Một mục lịch sử clipboard. */
data class ClipItem(val id: Long, val text: String, val time: Long, val pinned: Boolean = false)

/** Loại nội dung nhận diện được trong clipboard — để hiện nút dán nhanh phù hợp. */
enum class ClipKind(val icon: String, val label: String) {
    OTP("🔑", "Dán mã"),
    URL("🔗", "Dán link"),
    PHONE("📞", "Dán SĐT"),
    EMAIL("✉️", "Dán email"),
    ACCOUNT("🏦", "Dán STK"),
    MATH("🧮", "Kết quả"),
    TEXT("📋", "Dán"),
}

/** Kết quả nhận diện: [value] là phần nên chèn (mã OTP tách khỏi tin nhắn, kết quả phép tính…). */
data class ClipMatch(val kind: ClipKind, val value: String)

/**
 * Lịch sử bộ nhớ tạm: tối đa [MAX_UNPINNED] mục chưa ghim (mới nhất trước), mục ghim giữ mãi.
 * Thuần Kotlin; lưu/đọc qua [encode]/[decode] (một dòng một mục, đã thoát \t \n \\).
 */
class ClipboardVault(items: List<ClipItem> = emptyList()) {
    private val list = ArrayList(items)
    private var nextId = (items.maxOfOrNull { it.id } ?: 0L) + 1

    /** Ghim trước, rồi mới nhất trước. */
    val items: List<ClipItem> get() = list.sortedWith(compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.time })

    /** Thêm nội dung vừa copy; trùng thì đưa lên đầu (giữ trạng thái ghim). Trả true nếu đổi. */
    fun add(text: String, now: Long): Boolean {
        val t = text.trim('\u0000')
        if (t.isBlank() || t.length > MAX_LENGTH) return false
        val old = list.firstOrNull { it.text == t }
        if (old != null) {
            if (list.maxOfOrNull { it.time } == old.time) return false
            list.remove(old); list += old.copy(time = now); return true
        }
        list += ClipItem(nextId++, t, now)
        val unpinned = list.filter { !it.pinned }.sortedByDescending { it.time }
        if (unpinned.size > MAX_UNPINNED) list.removeAll(unpinned.drop(MAX_UNPINNED).toSet())
        return true
    }

    fun setPinned(id: Long, pinned: Boolean): Boolean {
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return false
        list[i] = list[i].copy(pinned = pinned); return true
    }

    fun delete(id: Long): Boolean = list.removeAll { it.id == id }

    /** Xoá mọi mục chưa ghim. */
    fun clearUnpinned(): Boolean = list.removeAll { !it.pinned }

    fun encode(): String = list.joinToString("\n") { "${it.id}\t${it.time}\t${if (it.pinned) 1 else 0}\t${Esc.escape(it.text)}" }

    companion object {
        const val MAX_UNPINNED = 50
        const val MAX_LENGTH = 20_000

        fun decode(s: String?): ClipboardVault {
            if (s.isNullOrEmpty()) return ClipboardVault()
            val items = s.split('\n').mapNotNull { line ->
                val p = line.split('\t', limit = 4)
                if (p.size != 4) return@mapNotNull null
                val id = p[0].toLongOrNull() ?: return@mapNotNull null
                val time = p[1].toLongOrNull() ?: return@mapNotNull null
                ClipItem(id, Esc.unescape(p[3]), time, p[2] == "1")
            }
            return ClipboardVault(items)
        }

        private val OTP_CONTEXT = Regex("(?i)(otp|mã|ma xac|code|verification|xác (minh|thực)|pin)")
        private val DIGITS_ONLY = Regex("^\\s*(\\d[\\s-]?){4,8}\\s*$")
        private val OTP_IN_TEXT = Regex("(?<!\\d)(?<!\\d[.,])(\\d{4,8})(?!\\d)(?![.,]\\d)")
        private val URL = Regex("(?i)^(https?://|www\\.)\\S+$|^[a-z0-9-]+(\\.[a-z0-9-]+)+(/\\S*)?$")
        private val EMAIL = Regex("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$")
        private val PHONE = Regex("^(\\+?84|0)(\\s?\\d){9,10}$")
        private val ACCOUNT = Regex("^\\d[\\d\\s]{7,18}\\d$")

        /** Nhận diện nội dung clipboard (để chọn nút dán nhanh). */
        fun detect(raw: String): ClipMatch {
            val t = raw.trim()
            if (t.isEmpty()) return ClipMatch(ClipKind.TEXT, raw)
            if (DIGITS_ONLY.matches(t) && t.count { it.isDigit() } in 4..8) return ClipMatch(ClipKind.OTP, t.filter { it.isDigit() })
            if (OTP_CONTEXT.containsMatchIn(t) && t.length <= 300) {
                val codes = OTP_IN_TEXT.findAll(t).map { it.value }.toList()
                if (codes.size == 1) return ClipMatch(ClipKind.OTP, codes[0])
            }
            if (EMAIL.matches(t)) return ClipMatch(ClipKind.EMAIL, t)
            if (URL.matches(t) && !t.contains(' ') && t.any { it.isLetter() }) return ClipMatch(ClipKind.URL, t)
            val compact = t.replace(" ", "").replace(".", "").replace("-", "")
            if (PHONE.matches(compact)) return ClipMatch(ClipKind.PHONE, compact)
            if (ACCOUNT.matches(t) && compact.all { it.isDigit() }) return ClipMatch(ClipKind.ACCOUNT, compact)
            MathEvaluator.chip(if (t.endsWith("=")) t else "$t=")?.let { if (t.any { c -> c in "+-*/×÷x^%:" }) return ClipMatch(ClipKind.MATH, it) }
            return ClipMatch(ClipKind.TEXT, raw)
        }

        /** Nhãn ngắn cho chip dán nhanh. */
        fun chipLabel(m: ClipMatch): String {
            val v = m.value.replace('\n', ' ')
            val short = if (v.length > 22) v.take(20) + "…" else v
            return "${m.kind.icon} $short"
        }
    }
}

/** Thoát ký tự cho định dạng dòng/tab. */
internal object Esc {
    fun escape(s: String): String = buildString {
        for (c in s) when (c) { '\\' -> append("\\\\"); '\n' -> append("\\n"); '\t' -> append("\\t"); '\r' -> append("\\r"); else -> append(c) }
    }

    fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 'n' -> append('\n'); 't' -> append('\t'); 'r' -> append('\r'); else -> append(s[i + 1]) }
                i += 2
            } else { append(c); i++ }
        }
    }
}
