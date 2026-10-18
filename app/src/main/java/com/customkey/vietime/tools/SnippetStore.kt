package com.customkey.vietime.tools

/** Mẫu câu trả lời nhanh. */
data class Snippet(val id: Long, val text: String)

/** Danh sách mẫu câu (thứ tự người dùng sắp). Thuần Kotlin, lưu qua [encode]/[decode]. */
class SnippetStore(items: List<Snippet> = DEFAULTS) {
    private val list = ArrayList(items)
    private var nextId = (items.maxOfOrNull { it.id } ?: 0L) + 1

    val items: List<Snippet> get() = list.toList()

    fun add(text: String): Snippet? {
        val t = text.trim()
        if (t.isEmpty() || list.any { it.text == t }) return null
        return Snippet(nextId++, t).also { list.add(0, it) }
    }

    fun update(id: Long, text: String): Boolean {
        val i = list.indexOfFirst { it.id == id }
        if (i < 0 || text.isBlank()) return false
        list[i] = list[i].copy(text = text.trim()); return true
    }

    fun delete(id: Long): Boolean = list.removeAll { it.id == id }

    /** Dời mục lên/xuống [delta] bậc. */
    fun move(id: Long, delta: Int): Boolean {
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return false
        val x = list.removeAt(i); list.add(j, x); return true
    }

    fun encode(): String = list.joinToString("\n") { "${it.id}\t${Esc.escape(it.text)}" }

    companion object {
        val DEFAULTS = listOf(
            "👋 Chào buổi sáng",
            "😴 Chúc ngủ ngon",
            "🙏 Cảm ơn bạn nhiều nhé!",
            "🎂 Chúc mừng sinh nhật! 🎉",
            "⏳ Đợi mình một lát nhé",
            "📞 Gọi lại cho mình nhé",
            "🚗 Đang trên đường tới đây",
            "🕐 Mình trễ một chút, sorry nha",
            "💪 Cố lên nha!",
            "🤝 Rất vui được gặp bạn",
            "🏠 Mình về tới nhà rồi",
            "☕ Đi cà phê không?",
            "🍚 Bạn ăn cơm chưa?",
            "👍 Ok chốt vậy nhé",
            "✅ Xong rồi nhé",
            "📍 Bạn đang ở đâu vậy?",
            "💭 Đang làm gì vậy?",
            "📅 Khi nào gặp được nhỉ?",
            "🥰 Yêu bạn nhiều",
            "😂 Haha hay quá",
            "Ok, để mình xem rồi báo lại bạn.",
            "Đã nhận, cảm ơn bạn!",
            "Bạn gửi giúp mình địa chỉ nhé.",
        ).mapIndexed { i, t -> Snippet(i + 1L, t) }

        fun decode(s: String?): SnippetStore {
            if (s == null) return SnippetStore()
            if (s.isEmpty()) return SnippetStore(emptyList())
            return SnippetStore(s.split('\n').mapNotNull { line ->
                val p = line.split('\t', limit = 2)
                val id = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                Snippet(id, Esc.unescape(p.getOrElse(1) { "" }))
            })
        }
    }
}
