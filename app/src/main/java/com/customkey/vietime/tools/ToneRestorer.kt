package com.customkey.vietime.tools

import com.customkey.vietime.engine.AddTones
import com.customkey.vietime.engine.UserLangModel

/**
 * Tự thêm dấu tiếng Việt cho câu gõ không dấu ("hom nay troi dep" → "hôm nay trời đẹp"), dùng
 * mô hình ngôn ngữ trigram + từ điển của engine (Viterbi), có tính cả từ người dùng hay gõ.
 * Giữ nguyên từ tiếng Anh, tên riêng lạ, số, URL, email, camelCase.
 */
object ToneRestorer {
    /** Một lần thay: xoá [original] (đuôi văn bản trước con trỏ) rồi chèn [replacement]. */
    data class Edit(val original: String, val replacement: String)

    private fun personal(lm: UserLangModel?): AddTones.Personal? =
        lm?.let { m -> AddTones.Personal({ w -> m.count(w) }, { a, b -> m.bigramCount(a, b) }) }

    /**
     * Thêm dấu cho cả đoạn [text] (vùng chọn / nội dung clipboard). Trả null nếu không đổi gì.
     * Độ dài và mọi ký tự không phải chữ Việt giữ nguyên.
     */
    fun restore(text: String, lm: UserLangModel? = null): String? {
        if (text.isBlank()) return null
        val r = AddTones.restore(text, personal(lm))
        return r.text.takeIf { it != text }
    }

    /**
     * Gợi ý thêm dấu cho câu trước con trỏ (chip "Thêm dấu" trên thanh gợi ý). null nếu không
     * nên mời: dưới 2 từ Việt, nghiêng tiếng Anh, hoặc đã có dấu.
     */
    fun planBefore(before: String, lm: UserLangModel? = null): Edit? =
        AddTones.plan(before, personal(lm))?.let { Edit(it.original, it.replacement) }

    /** Văn bản trước con trỏ có vẻ là câu tiếng Việt không dấu (rẻ — chạy trước [planBefore]). */
    fun looksUntoned(before: String): Boolean {
        val tail = before.takeLast(120)
        var letters = 0
        for (c in tail) {
            if (c.code > 0x7F && c.isLetter()) return false
            if (c.isLetter()) letters++
        }
        return letters >= 6 && tail.count { it == ' ' } >= 1
    }
}
