// MIT License — Copyright (c) 2026 Phil Trinh
// Extracted from ptrinh/viettelex (https://github.com/ptrinh/viettelex), see assets/licenses/viettelex-LICENSE.txt.
// Modified for com.customkey.vietime.

package com.customkey.vietime.engine

import java.nio.ByteBuffer

/**
 * Dữ liệu bàn phím emoji — nạp từ assets/emoji.bin.
 * Emoji theo các nhóm chuẩn (smileys, animals, food, activity, travel, objects, symbols, flags - cờ VN đầu).
 */
object EmojiData {
    data class Category(val name: String, val emoji: List<String>)

    internal val buf: ByteBuffer by lazy {
        KeyboardData.buffer(Keys.ASSET_EMOJI_DATA).also {
            require(it.get(0) == 'V'.code.toByte() && it.get(3) == '1'.code.toByte()) { "bad emoji.bin" }
        }
    }

    internal fun u32(off: Int): Int = buf.getInt(off)
    internal fun u16(off: Int): Int = buf.getShort(off).toInt() and 0xFFFF
    internal fun str(off: Int, len: Int): String {
        val b = ByteArray(len)
        for (i in 0 until len) b[i] = buf.get(off + i)
        return String(b, Charsets.UTF_8)
    }

    /** Offset tuyệt đối của section [tag] (header: "VTE1" | n | n × (tag, off, len)). */
    internal fun section(tag: String): Int {
        val n = u32(4)
        for (i in 0 until n) {
            val h = 8 + i * 12
            if ((0 until 4).all { buf.get(h + it) == tag[it].code.toByte() }) return u32(h + 4)
        }
        error("emoji.bin thiếu section $tag")
    }

    private val emojBase by lazy { section("EMOJ") }
    private val verBase by lazy { section("EVER") }

    /** Số emoji (id 0 until count). */
    val count: Int get() = u32(emojBase)

    fun emoji(id: Int): String {
        val o = emojBase + 4 + id * 4
        val s = u32(o); val e = u32(o + 4)
        return str(emojBase + 4 + (count + 1) * 4 + s, e - s)
    }

    /** Phiên bản Emoji ×10 (E15.1 → 151). */
    fun version(id: Int): Int = buf.get(verBase + id).toInt() and 0xFF

    data class RawCategory(val name: String, val start: Int, val count: Int)

    val rawCategories: List<RawCategory> by lazy {
        val b = section("ECAT")
        val k = u32(b)
        val names = b + 4 + k * 16
        List(k) { i ->
            val r = b + 4 + i * 16
            RawCategory(str(names + u32(r), u32(r + 4)), u32(r + 8), u32(r + 12))
        }
    }

    // --- lọc glyph ---
    @Volatile var glyphCheck: ((String) -> Boolean)? = null
        set(v) { field = v; synchronized(glyphCache) { glyphCache.clear() }; cachedCategories = null }
    @Volatile var trusted: Int = 170
    private val glyphCache = HashMap<Int, Boolean>()

    fun isSupported(id: Int): Boolean {
        if (version(id) <= trusted) return true
        val check = glyphCheck ?: return true
        synchronized(glyphCache) { glyphCache[id]?.let { return it } }
        val v = check(emoji(id))
        synchronized(glyphCache) { glyphCache[id] = v }
        return v
    }

    @Volatile private var cachedCategories: List<Category>? = null

    fun releaseCategories() { cachedCategories = null }

    val categories: List<Category>
        get() = cachedCategories ?: rawCategories.map { c ->
            Category(c.name, (c.start until c.start + c.count).filter(::isSupported).map(::emoji))
        }.also { cachedCategories = it }

    // --- kaomoji ---
    data class KaomojiGroup(val name: String, val items: List<String>)

    val kaomoji: List<KaomojiGroup> by lazy {
        val b = section("KAOM")
        val g = u32(b)
        val head = b + 4 + g * 16
        val n = u32(head)
        val offBase = head + 4
        val strBase = offBase + (n + 1) * 4
        var namesLen = 0
        for (i in 0 until g) namesLen += u32(b + 4 + i * 16 + 4)
        val itemBase = strBase + namesLen
        List(g) { i ->
            val r = b + 4 + i * 16
            val start = u32(r + 8); val cnt = u32(r + 12)
            KaomojiGroup(str(strBase + u32(r), u32(r + 4)), List(cnt) { j ->
                val s = u32(offBase + (start + j) * 4); val e = u32(offBase + (start + j) * 4 + 4)
                str(itemBase + s, e - s)
            })
        }
    }

    const val RECENTS = "recents"

    val displayNames: Map<String, String> = mapOf(
        "recents" to "THƯỜNG DÙNG", "smileys" to "MẶT CƯỜI & NGƯỜI",
        "animals" to "ĐỘNG VẬT & THIÊN NHIÊN", "food" to "ĐỒ ĂN & ĐỒ UỐNG",
        "activity" to "HOẠT ĐỘNG", "travel" to "DU LỊCH & ĐỊA ĐIỂM",
        "objects" to "ĐỒ VẬT", "symbols" to "BIỂU TƯỢNG", "flags" to "CỜ",
    )
    fun displayName(name: String): String = displayNames[name] ?: name.uppercase()

    val categoryIcons = listOf("🕒", "😀", "🐻", "🍔", "⚽", "🚗", "💡", "❤️", "🚩")

    private val modifierBaseRanges = intArrayOf(
        0x261D, 0x261D, 0x26F9, 0x26F9, 0x270A, 0x270D, 0x1F385, 0x1F385, 0x1F3C2, 0x1F3C4,
        0x1F3C7, 0x1F3C7, 0x1F3CA, 0x1F3CC, 0x1F442, 0x1F443, 0x1F446, 0x1F450, 0x1F466, 0x1F478,
        0x1F47C, 0x1F47C, 0x1F481, 0x1F483, 0x1F485, 0x1F487, 0x1F48F, 0x1F48F, 0x1F491, 0x1F491,
        0x1F4AA, 0x1F4AA, 0x1F574, 0x1F575, 0x1F57A, 0x1F57A, 0x1F590, 0x1F590, 0x1F595, 0x1F596,
        0x1F645, 0x1F647, 0x1F64B, 0x1F64F, 0x1F6A3, 0x1F6A3, 0x1F6B4, 0x1F6B6, 0x1F6C0, 0x1F6C0,
        0x1F6CC, 0x1F6CC, 0x1F90C, 0x1F90C, 0x1F90F, 0x1F90F, 0x1F918, 0x1F91F, 0x1F926, 0x1F926,
        0x1F930, 0x1F939, 0x1F93C, 0x1F93E, 0x1F977, 0x1F977, 0x1F9B5, 0x1F9B6, 0x1F9B8, 0x1F9B9,
        0x1F9BB, 0x1F9BB, 0x1F9CD, 0x1F9CF, 0x1F9D1, 0x1F9DD, 0x1FAC3, 0x1FAC5, 0x1FAF0, 0x1FAF8,
    )

    fun isModifierBase(cp: Int): Boolean {
        var i = 0
        while (i < modifierBaseRanges.size) {
            if (cp < modifierBaseRanges[i]) return false
            if (cp <= modifierBaseRanges[i + 1]) return true
            i += 2
        }
        return false
    }

    fun toneVariants(e: String): List<String>? {
        val cps = e.codePoints().toArray()
        if (cps.none(::isModifierBase)) return null
        val out = ArrayList<String>(6)
        out.add(e)
        for (t in 0x1F3FB..0x1F3FF) {
            val sb = StringBuilder()
            for (c in cps) {
                if (c in 0x1F3FB..0x1F3FF) continue
                sb.appendCodePoint(c)
                if (isModifierBase(c)) sb.appendCodePoint(t)
            }
            out.add(sb.toString())
        }
        return out
    }
}

object EmojiRecents {
    const val MAX = 30
    fun noteUsed(recents: List<String>, e: String): List<String> {
        val r = ArrayList<String>(recents.size + 1)
        r.add(e)
        for (x in recents) if (x != e) r.add(x)
        return if (r.size > MAX) r.subList(0, MAX).toList() else r
    }
    fun encode(list: List<String>): String = list.joinToString("\n")
    fun decode(s: String?): List<String> = s?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
}
