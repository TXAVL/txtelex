// MIT License — Copyright (c) 2026 Phil Trinh
// Extracted from ptrinh/viettelex (https://github.com/ptrinh/viettelex), see assets/licenses/viettelex-LICENSE.txt.
// Modified for com.customkey.vietime: the engine-only slices of KeyboardSettings, FieldTraits and
// LearnedWords (ref keyboard/KeyboardSettings.kt, KeyboardSession.kt, Backup.kt).

package com.customkey.vietime.engine

/**
 * Cài đặt bộ gõ mà [EngineBridge] cần — phần còn lại (theme, âm thanh, bố cục, gợi ý…)
 * thuộc tầng app. Mặc định y bản gốc (iOS): simpleTelex BẬT.
 */
data class EngineSettings(
    var freeMarking: Boolean = true,
    var simpleTelex: Boolean = true,
    var liveSpellCheck: Boolean = true,
    var autoRestore: Boolean = true,
    var quickTelex: Boolean = false,
    var modernTone: Boolean = false,
    /** Chính tả teencode — mặc định TẮT. */
    var teencode: Boolean = false,
    /** Kiểu gõ VNI — mặc định TẮT (Telex). */
    var vniMode: Boolean = false,
    /** Gợi ý sửa lỗi chạm trượt (AdjacentKeyFixer) — mặc định BẬT. */
    var autoFixAdjacent: Boolean = true,
    /** Quyết định theo ngữ cảnh ("he is" giữ tiếng Anh) — mặc định BẬT. */
    var contextualEnglish: Boolean = true,
    /** Sửa dấu từ đã gõ xong — mặc định BẬT. */
    var reEditWords: Boolean = true,
    /** Tự sửa lỗi chạm trượt khi gõ dấu cách (⌫ ngay sau đó = hoàn tác) — mặc định BẬT. */
    var autoCorrect: Boolean = true,
    var shortcutsEnabled: Boolean = true,
    var shortcuts: ShortcutTable = ShortcutTable(),
) {
    companion object {
        /** Đọc từ SharedPreferences ([Keys]); [get] trả giá trị thô của key. */
        fun load(get: (String) -> Any?): EngineSettings {
            val s = EngineSettings()
            fun b(key: String, cur: Boolean): Boolean = (get(key) as? Boolean) ?: cur
            s.freeMarking = b(Keys.FREE_MARKING, s.freeMarking)
            s.simpleTelex = b(Keys.SIMPLE_TELEX, s.simpleTelex)
            s.liveSpellCheck = b(Keys.LIVE_SPELL_CHECK, s.liveSpellCheck)
            s.autoRestore = b(Keys.AUTO_RESTORE, s.autoRestore)
            s.quickTelex = b(Keys.QUICK_TELEX, s.quickTelex)
            s.modernTone = b(Keys.MODERN_TONE, s.modernTone)
            s.teencode = b(Keys.TEENCODE, s.teencode)
            s.vniMode = b(Keys.VNI_MODE, s.vniMode)
            s.autoFixAdjacent = b(Keys.AUTO_FIX_ADJACENT, s.autoFixAdjacent)
            s.contextualEnglish = b(Keys.CONTEXTUAL_ENGLISH, s.contextualEnglish)
            s.reEditWords = b(Keys.RE_EDIT_WORDS, s.reEditWords)
            s.autoCorrect = b(Keys.AUTO_CORRECT, s.autoCorrect)
            s.shortcutsEnabled = b(Keys.SHORTCUTS_ENABLED, s.shortcutsEnabled)
            if (s.shortcutsEnabled) s.shortcuts = ShortcutTable(ShortcutFile.parse(get(Keys.SHORTCUTS) as? String))
            return s
        }
    }
}

/** Đặc tính ô nhập (từ EditorInfo) — tầng ime điền, engine chỉ đọc. */
data class FieldTraits(
    val isSecure: Boolean = false,
    /** Gõ literal, bỏ Telex (URI/EMAIL/VISIBLE_PASSWORD/FILTER). */
    val passthrough: Boolean = false,
    /** TYPE_TEXT_FLAG_CAP_SENTENCES — auto-shift đầu câu. */
    val capSentences: Boolean = false,
    /** Ô cho phép thanh gợi ý. */
    val suggestionsAllowed: Boolean = true,
    /** TYPE_TEXT_FLAG_CAP_WORDS. */
    val capWords: Boolean = false,
    /** TYPE_TEXT_FLAG_CAP_CHARACTERS. */
    val capCharacters: Boolean = false,
    /** EditorInfo.initialCapsMode ≠ 0. */
    val initialCaps: Boolean = false,
    /** IME_FLAG_NO_PERSONALIZED_LEARNING: không ghi từ vào UserLangModel. */
    val noLearning: Boolean = false,
    /** EditorInfo.packageName. */
    val packageName: String? = null,
    /** Ô URL (TYPE_TEXT_VARIATION_URI): không gõ tắt. */
    val urlField: Boolean = false,
    /** Ô email. */
    val emailField: Boolean = false,
    /** Ô email gõ Telex có bảo vệ địa chỉ ([EmailTokenGuard]). */
    val emailCompose: Boolean = false,
    /** Ô chữ được hiện thanh công cụ dù cấm gợi ý. */
    val stripTools: Boolean = false,
    /** Ô địa chỉ / tìm kiếm: gợi ý luôn chữ thường. */
    val lowercaseSuggestions: Boolean = false,
    /** App tắt gợi ý ở ô chữ không nhạy cảm. */
    val appNoSuggestions: Boolean = false,
)

/** Từ đã học — cùng cấu trúc [UserLangModel] (tri key = "p2\u0001p1"). */
data class LearnedWords(
    val uni: Map<String, Int> = emptyMap(),
    val bi: Map<String, Map<String, Int>> = emptyMap(),
    val tri: Map<String, Map<String, Int>> = emptyMap(),
    /** Từ thêm tay (Từ điển cá nhân), dạng hiển thị. */
    val manual: List<String> = emptyList(),
) {
    val isEmpty get() = uni.isEmpty() && bi.isEmpty() && tri.isEmpty() && manual.isEmpty()

    /** Gộp khi nhập: count lớn hơn mỗi mục (nhập lại cùng file không nhân đôi). */
    fun merged(o: LearnedWords): LearnedWords {
        fun m1(a: Map<String, Int>, b: Map<String, Int>) =
            HashMap(a).apply { for ((k, v) in b) merge(k, v) { x, y -> maxOf(x, y) } }
        fun m2(a: Map<String, Map<String, Int>>, b: Map<String, Map<String, Int>>): Map<String, Map<String, Int>> {
            val out = HashMap<String, Map<String, Int>>(a)
            for ((k, v) in b) out[k] = m1(out[k] ?: emptyMap(), v)
            return out
        }
        val man = LinkedHashMap<String, String>()
        for (w in manual + o.manual) man.putIfAbsent(w.lowercase(), w)
        return LearnedWords(m1(uni, o.uni), m2(bi, o.bi), m2(tri, o.tri), man.values.toList())
    }
}
