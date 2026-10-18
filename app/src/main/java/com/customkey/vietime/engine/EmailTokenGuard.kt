// MIT License — Copyright (c) 2026 Phil Trinh
// Extracted from ptrinh/viettelex (https://github.com/ptrinh/viettelex), see assets/licenses/viettelex-LICENSE.txt.
// Modified for com.customkey.vietime.

package com.customkey.vietime.engine

/**
 * Bảo vệ địa chỉ ở ô email (TYPE_TEXT_VARIATION_(WEB_)EMAIL_ADDRESS) — THUẦN, song sinh iOS
 * `EmailTokenGuard` (FieldPolicy.swift). Ô người nhận Gmail/Outlook cũng là ô TÌM danh bạ theo tên
 * ("Sơn") nên Telex/VNI vẫn BẬT; token = chuỗi không có khoảng trắng / "," / ";" tính từ ranh giới
 * gần nhất.
 * - Token chưa có "@"/"." ⇒ soạn như ô chữ ("Sown" → "Sơn").
 * - Gõ "@" hoặc "." ⇒ phần token DO BÀN PHÍM GÕ được trả lại ĐÚNG phím thô đã bấm (ASCII, không
 *   dấu — "nguyeenx" + "@" → "nguyeenx@", "dduc" + "@" → "dduc@"), rồi gõ thô tới hết token.
 *   Chữ số và "_" "-" "+" trong token: soạn như thường, tới "@"/"." thì cũng được trả lại thô.
 * - Chữ đã có trên màn hình trước khi bridge theo dõi (app chèn chip người nhận, con trỏ dời tới)
 *   KHÔNG bao giờ bị viết lại — chỉ đọc để biết token đã là địa chỉ chưa (một lần sau reset).
 * Chi phí mỗi phím: một phép so "@"/"." ở phím ranh giới; phím chữ chỉ đọc cờ [literal].
 */
class EmailTokenGuard {
    companion object {
        fun isSeparator(c: Char): Boolean = c.isWhitespace() || c == ',' || c == ';'
        fun isMark(c: Char): Boolean = c == '@' || c == '.'
        fun hasMark(s: CharSequence): Boolean = s.any(::isMark)
        /** Token cuối của [context] (sau ranh giới cuối cùng). */
        fun tokenTail(context: String): String {
            val i = context.indexOfLast(::isSeparator)
            return if (i < 0) context else context.substring(i + 1)
        }
    }

    /** Đã biết trạng thái token (sau reset phải đọc lại context một lần). */
    var synced = false
        private set
    /** Token đã có "@"/"." ⇒ phím chữ chèn thô, không qua engine. */
    var literal = false
        private set
    /** Phần token có sẵn trước khi theo dõi (không phải mình gõ) có "@"/".". */
    private var prefixMark = false
    /** Phần token bàn phím đã gõ VÀ đã chốt (không gồm từ engine đang soạn): chữ đang hiện… */
    var shown = ""
        private set
    /** …và đúng phím thô tương ứng. */
    var raw = ""
        private set

    /** Đổi ô / con trỏ dời / host đổi chữ: quên — phím kế đọc lại context. */
    fun invalidate() { synced = false; literal = false; prefixMark = false; shown = ""; raw = "" }

    /** Đọc trạng thái token từ chữ trước con trỏ (null = không biết ⇒ coi như token mới). */
    fun sync(context: String?) {
        val tail = context?.let(::tokenTail) ?: ""
        prefixMark = hasMark(tail)
        literal = prefixMark
        shown = ""; raw = ""
        synced = true
    }

    /** Phím ranh giới [text] phải trả token về phím thô trước khi chèn? */
    fun needsRestore(text: String): Boolean = !literal && hasMark(text)

    /**
     * Kế hoạch viết lại khi gõ "@"/".": (chữ đang hiện cần xoá, phím thô chèn lại) cho phần token
     * mình gõ + từ đang soạn. null = đã đúng phím thô, không cần sửa.
     */
    fun restorePlan(composed: String, composedRaw: String): Pair<String, String>? {
        val s = shown + composed
        val r = raw + composedRaw
        return if (s == r) null else s to r
    }

    /** Màn hình vừa thêm [s] (ứng với phím thô [r]) rồi ký tự ranh giới [text]. */
    fun appended(s: String, r: String, text: String = "") {
        val i = text.indexOfLast(::isSeparator)
        if (i >= 0) {
            // token mới bắt đầu sau ranh giới cuối
            val rest = text.substring(i + 1)
            prefixMark = false
            shown = rest; raw = rest
            literal = hasMark(rest)
            synced = true
            return
        }
        shown += s + text; raw += r + text
        if (hasMark(text) || hasMark(s)) literal = true
    }

    /** Phần token mình gõ vừa được viết lại thành phím thô [r] (sau đó là [text]). */
    fun restored(r: String, text: String) {
        shown = ""; raw = ""
        appended(r, r, text)
    }

    /**
     * ⌫ xoá MỘT ký tự đã chốt trước con trỏ (engine trống). Ký tự cuối khớp phím thô ⇒ bớt cả
     * hai; lệch (chữ có dấu) ⇒ phần còn lại thành "có sẵn" (không bao giờ viết lại nữa). Hết phần
     * mình gõ ⇒ đang xoá chữ có sẵn: quên, phím kế đọc lại context.
     */
    fun deletedOne() {
        if (!synced) return
        if (shown.isEmpty()) { invalidate(); return }
        if (Cp.lastCodePoint(raw) == Cp.lastCodePoint(shown)) {
            shown = Cp.dropLast(shown, 1); raw = Cp.dropLast(raw, 1)
        } else {
            shown = Cp.dropLast(shown, 1)
            prefixMark = prefixMark || hasMark(shown)
            shown = ""; raw = ""
        }
        literal = prefixMark || hasMark(shown)
    }
}
