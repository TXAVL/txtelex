// MIT License — Copyright (c) 2026 Phil Trinh
// Extracted from ptrinh/viettelex (https://github.com/ptrinh/viettelex), see assets/licenses/viettelex-LICENSE.txt.
// Modified for com.customkey.vietime.

package com.customkey.vietime.engine

/**
 * Case chuẩn cho proper noun khi HIỂN THỊ gợi ý (port iOS). Chỉ token không nhập nhằng;
 * token nhập nhằng viết hoa THEO CHUỖI ([chains]).
 */
object DisplayCase {
    fun apply(w: String, after: String? = null): String {
        val p = after?.lowercase()
        if (p != null && chains[p]?.contains(w) == true) return Cp.capitalizeFirst(w)
        return proper[w] ?: w
    }

    private fun hasUpper(w: String): Boolean = w.codePoints().anyMatch { Character.isUpperCase(it) || Character.isTitleCase(it) }

    /**
     * Dạng tên riêng của một ứng viên gợi ý / từ vuốt [w]: đã có chữ hoa ⇒ giữ nguyên; người dùng
     * đã viết nhất quán một dạng ([UserLangModel.learnedCase] — [learned]) ⇒ dạng đó; còn lại [apply].
     * Không bao giờ hạ chữ hoa. = iOS DisplayCase.resolve.
     */
    fun resolve(w: String, after: String? = null, learned: ((String) -> String?)? = null): String {
        if (hasUpper(w)) return w
        learned?.invoke(w)?.let { return it }
        return apply(w, after)
    }

    /**
     * Hoa/thường của [word] (đúng như đã gõ, sau [prev]) có là bằng chứng cho bảng hoa/thường đã
     * học: chỉ GIỮA CÂU (caller cắt prev ở đầu ô / sau dấu câu / xuống dòng / dời con trỏ) và
     * không do CHUỖI tên riêng ("Việt Nam" không dạy "nam"). = iOS casingEvidence.
     */
    fun casingEvidence(word: String, prev: String?): Boolean {
        if (prev.isNullOrEmpty()) return false
        return chains[prev.lowercase()]?.contains(word.lowercase()) != true
    }

    /** [next] (chữ thường) viết hoa vì CHUỖI sau [prev]? */
    fun chainCapitalizes(next: String, prev: String): Boolean = chains[prev.lowercase()]?.contains(next) == true

    val chains: Map<String, Set<String>> = hashMapOf(
        // địa danh VN
        "hà" to setOf("nội", "nam", "tĩnh", "giang"),
        "đà" to setOf("nẵng", "lạt"),
        "nha" to setOf("trang"),
        "phú" to setOf("quốc", "thọ", "yên"),
        "sài" to setOf("gòn"),
        "hồ" to setOf("chí"),
        "chí" to setOf("minh"),
        "việt" to setOf("nam"),
        "cần" to setOf("thơ"),
        "hải" to setOf("phòng", "dương"),
        "hạ" to setOf("long"),
        "quy" to setOf("nhơn"),
        "vũng" to setOf("tàu"),
        "biên" to setOf("hòa"),
        "bình" to setOf("dương", "định", "thuận"),
        "thanh" to setOf("hóa"),
        "hội" to setOf("an"),
        "quảng" to setOf("ninh", "nam", "ngãi", "bình", "trị"),
        "nghệ" to setOf("an"),
        "lâm" to setOf("đồng"),
        "đồng" to setOf("nai", "tháp"),
        "long" to setOf("an"),
        "tây" to setOf("ninh"),
        "new" to setOf("york"),
        "san" to setOf("francisco"),
        "hong" to setOf("kong"),
        // họ → đệm (chuỗi tên người)
        "nguyễn" to setOf("văn", "thị", "đức", "minh", "ngọc", "hữu", "xuân", "thùy", "kim", "hồng", "quốc", "đình"),
        "trần" to setOf("văn", "thị", "đức", "minh", "ngọc", "quốc"),
        "lê" to setOf("văn", "thị", "đức", "minh", "ngọc", "hữu"),
        "phạm" to setOf("văn", "thị", "minh", "ngọc"),
        "hoàng" to setOf("văn", "thị", "minh", "anh"),
        "vũ" to setOf("văn", "thị", "minh"),
        "đặng" to setOf("văn", "thị"),
        "bùi" to setOf("văn", "thị"),
        "đỗ" to setOf("văn", "thị"),
        "ngô" to setOf("văn", "thị"),
        "dương" to setOf("văn", "thị"),
        "trịnh" to setOf("văn", "thị", "xuân"),
        // đệm → tên
        "văn" to setOf("hùng", "tuấn", "dũng", "sơn", "nam", "long", "hải", "minh"),
        "thị" to setOf("hương", "lan", "thu", "ngọc", "hồng", "phương", "hà", "linh"),
    )

    val proper: Map<String, String> = hashMapOf(
        // thương hiệu / sản phẩm
        "senprints" to "SenPrints", "printik" to "Printik",
        "apple" to "Apple", "iphone" to "iPhone", "ipad" to "iPad", "macbook" to "MacBook",
        "samsung" to "Samsung", "google" to "Google", "facebook" to "Facebook",
        "youtube" to "YouTube", "tiktok" to "TikTok", "zalo" to "Zalo",
        "shopee" to "Shopee", "lazada" to "Lazada", "grab" to "Grab",
        "momo" to "MoMo", "vnpay" to "VNPay", "vietcombank" to "Vietcombank",
        "techcombank" to "Techcombank", "viettel" to "Viettel", "vingroup" to "Vingroup",
        "vinfast" to "VinFast", "vietjet" to "Vietjet", "fpt" to "FPT",
        "arsenal" to "Arsenal", "liverpool" to "Liverpool", "chelsea" to "Chelsea",
        "manchester" to "Manchester", "bitcoin" to "Bitcoin", "ethereum" to "Ethereum",
        "binance" to "Binance", "netflix" to "Netflix", "spotify" to "Spotify",
        "github" to "GitHub", "claude" to "Claude", "chatgpt" to "ChatGPT",
        "tesla" to "Tesla", "openai" to "OpenAI", "anthropic" to "Anthropic",
        "yahoo" to "Yahoo", "hotmail" to "Hotmail",
        // OS / phần mềm
        "windows" to "Windows", "macos" to "macOS", "ios" to "iOS",
        "android" to "Android", "linux" to "Linux", "chrome" to "Chrome",
        "safari" to "Safari", "excel" to "Excel", "word" to "Word",
        "wifi" to "WiFi", "gmail" to "Gmail", "outlook" to "Outlook",
        // địa danh nước ngoài (token đơn, không nhập nhằng)
        "singapore" to "Singapore", "dubai" to "Dubai", "london" to "London",
        "tokyo" to "Tokyo", "sydney" to "Sydney", "canada" to "Canada",
        "houston" to "Houston", "seattle" to "Seattle", "miami" to "Miami",
        "dallas" to "Dallas", "austin" to "Austin", "texas" to "Texas",
        "california" to "California", "washington" to "Washington",
        "york" to "York", "francisco" to "Francisco", "paris" to "Paris",
        "bangkok" to "Bangkok", "seoul" to "Seoul",
        // họ Việt an toàn (gần như luôn là tên riêng khi đứng một mình).
        // CÁC TOKEN NHẬP NHẰNG bị loại có chủ ý: vũ (vũ khí), đỗ (đỗ xe),
        // ngô (bắp ngô), dương (đại dương), trang (trang web), nội (nội bộ),
        // quốc (tổ quốc) — hiện thường an toàn hơn hoa sai.
        "nguyễn" to "Nguyễn", "trịnh" to "Trịnh", "đặng" to "Đặng", "bùi" to "Bùi",
    )
}

/**
 * Hoa/thường của gợi ý theo Shift + chữ đang gõ — như Gboard (feedback 10/2026: bật Shift
 * mà gợi ý vẫn thường). Song sinh iOS `SuggestionCase` (DisplayCase.swift). Chỉ áp lên vài
 * ứng viên đang hiện.
 *  • Caps Lock ⇒ CHỮ HOA HẾT ("BÊN").
 *  • Đang gõ dở: theo chữ đã gõ — ≥2 chữ cái đều hoa ⇒ HOA HẾT; chữ cái đầu hoa ⇒ Hoa đầu
 *    ("Bê" → "Bên"); còn lại giữ nguyên.
 *  • Chưa gõ (gợi ý từ kế tiếp / mở đầu): Shift một-lần (tay hoặc đầu câu) ⇒ Hoa đầu.
 *  • Hoa đầu KHÔNG đụng từ đã có chữ hoa (tên riêng/viết tắt: "iPhone", "SenPrints"); ứng
 *    viên không bắt đầu bằng chữ cái (emoji, số, chip @/.) giữ nguyên.
 */
object SuggestionCase {
    enum class Shift { OFF, ONCE, CAPS_LOCK }
    enum class Mode { AS_IS, CAPITALIZED, UPPER }

    fun mode(shift: Shift, typed: String): Mode {
        if (shift == Shift.CAPS_LOCK) return Mode.UPPER
        var letters = 0; var upper = 0; var firstUpper = false
        var i = 0
        while (i < typed.length) {
            val cp = typed.codePointAt(i)
            if (Character.isLetter(cp)) {
                if (Character.isUpperCase(cp) || Character.isTitleCase(cp)) { upper++; if (letters == 0) firstUpper = true }
                letters++
            }
            i += Character.charCount(cp)
        }
        if (letters == 0) return if (shift == Shift.ONCE) Mode.CAPITALIZED else Mode.AS_IS
        if (letters >= 2 && upper == letters) return Mode.UPPER
        return if (firstUpper) Mode.CAPITALIZED else Mode.AS_IS
    }

    fun apply(w: String, mode: Mode): String {
        if (mode == Mode.AS_IS || w.isEmpty() || !Character.isLetter(w.codePointAt(0))) return w
        return when (mode) {
            Mode.AS_IS -> w
            Mode.UPPER -> w.uppercase(VI)
            Mode.CAPITALIZED -> if (w.codePoints().anyMatch { Character.isUpperCase(it) }) w else Cp.capitalizeFirst(w)
        }
    }

    /** Dạng hiển thị của [w] khi Shift = [shift], chữ đang gõ = [typed]. */
    fun display(w: String, shift: Shift, typed: String = ""): String = apply(w, mode(shift, typed))

    /** Chọn gợi ý = gõ một từ: Shift một-lần nhả, Caps Lock giữ. */
    fun afterCommit(shift: Shift): Shift = if (shift == Shift.ONCE) Shift.OFF else shift

    private val VI: java.util.Locale = java.util.Locale.forLanguageTag("vi")
}
