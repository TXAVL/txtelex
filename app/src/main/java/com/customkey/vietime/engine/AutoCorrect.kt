// MIT License — Copyright (c) 2026 Phil Trinh
// Extracted from ptrinh/viettelex (https://github.com/ptrinh/viettelex), see assets/licenses/viettelex-LICENSE.txt.
// Modified for com.customkey.vietime.

package com.customkey.vietime.engine

/**
 * TỰ SỬA TỪ GÕ SAI (Thử nghiệm, mặc định TẮT — maintainer 27/09/2026). Port 1:1 iOS AutoCorrect.
 *
 * Chỉ ở ranh giới từ ([triggers]), chỉ khi từ vừa gõ KHÔNG là âm tiết Việt (kể cả dạng
 * thiếu dấu / tiền tố — người gõ không dấu) và không là từ đã biết (tiếng Anh, từ người
 * dùng, gõ tắt, từng hoàn tác). Ứng viên = thay ĐÚNG MỘT phím bằng phím kề
 * ([AdjacentKeyFixer.oneEditCandidates]) mà điểm chạm thật nghiêng về phím đó ([evidence]);
 * chỉ sửa khi ứng viên đủ phổ biến VÀ bỏ xa ứng viên thứ hai ([Params]). Không có điểm chạm
 * (từ vuốt, từ mở lại, bàn phím cứng) ⇒ không sửa. ⌫ ngay sau đó trả lại chữ gốc.
 * Ngưỡng dò bằng AutoCorrectEvalTests (mô phỏng chạm TouchSim trên câu heldout).
 */
object AutoCorrect {
    /**
     * [minFreq]: tần suất lexicon (0–255) tối thiểu của ứng viên — chỉ từ phổ biến (không, này,
     * tôi…; "phím" = 120 không đủ); [margin]: hơn tần suất ứng viên thứ hai ít nhất bấy nhiêu;
     * [minEv]: điểm chạm phải lệch về phía phím sửa ít nhất bấy nhiêu ([evidence]) — không thì
     * loại ứng viên đó (cũng không tính là đối thủ).
     */
    data class Params(val minLen: Int, val minFreq: Int, val margin: Int, val minEv: Float)

    // Dò trên DEV (AutoCorrectEvalTests.report, 27/09/2026): P 98.4% R 26.8%. Hạ ngưỡng tần suất
    // (kể cả khi chỉ một ứng viên) hay cộng điểm theo độ lệch chạm đều tụt P dưới 97%. 18/10/2026
    // (vnlexicon tần suất corpus hội thoại): 170 → 175 giữ P DEV 98.7% (170: 98.0%; cũ 98.8%), R 26.8 → 27.0%.
    // GIỮ Y HỆT bản Swift.
    val DEFAULT = Params(minLen = 3, minFreq = 175, margin = 30, minEv = 0.1f)

    /** Điểm chạm của một phím: lệch so với TÂM phím đã ra, theo cỡ mặt phím (x phải, y xuống). */
    data class Touch(val dx: Float, val dy: Float)

    /**
     * Điểm chạm lệch về phía phím [to] bao nhiêu (so với phím đã ra [from]): hình chiếu lên vectơ
     * tâm→tâm, 0 = giữa phím, ~0.5 = sát mép giáp phím kia. Chạm giữa phím ⇒ không phải trượt.
     */
    fun evidence(t: Touch, from: Char, to: Char): Float {
        val a = AdjacentKeyFixer.keyPosition(from) ?: return 0f
        val b = AdjacentKeyFixer.keyPosition(to) ?: return 0f
        val dc = (b.second - a.second).toFloat(); val dr = (b.first - a.first).toFloat()
        return (t.dx * dc + t.dy * dr) / (dc * dc + dr * dr)
    }

    /**
     * Ranh giới được sửa: khoảng trắng (không Enter — có thể đã gửi tin, không hoàn tác được)
     * và dấu câu chắc chắn kết thúc từ. KHÔNG . : ' ’ " (tên miền, giờ, "don't").
     */
    fun triggers(boundary: String): Boolean {
        val c = boundary.firstOrNull() ?: return false
        return c == ' ' || c == ' ' || c in ",;!?)"
    }

    /**
     * Ô được tự sửa: không mật khẩu / passthrough (email, URL, số), không URL, không ô app xin
     * tắt gợi ý, không ô tên (viết hoa mỗi từ / toàn bộ — tên riêng, mã).
     */
    fun fieldAllows(f: FieldTraits): Boolean =
        !f.isSecure && !f.passthrough && !f.urlField && f.suggestionsAllowed && !f.capWords && !f.capCharacters

    /** Từ tiếng Anh (enlexicon + từ mở mạch tiếng Anh của engine) — không sửa sang tiếng Việt. */
    fun isEnglish(w: String): Boolean =
        SwipeEnglish.contains(w) || EnglishContextLookup.opensEnglishRun(w)

    /** Từ đã hoàn tác tự sửa (chữ thường) — nhớ tối đa [cap] từ gần nhất. Lưu dạng dòng. */
    class Rejected(private val cap: Int = 300) {
        private val words = LinkedHashSet<String>()
        operator fun contains(w: String) = w.lowercase() in words
        /** true nếu mới thêm (caller lưu). */
        fun add(w: String): Boolean {
            val k = w.lowercase()
            if (k.isEmpty() || !words.add(k)) return false
            while (words.size > cap) words.remove(words.first())
            return true
        }
        val size: Int get() = words.size
        fun encode(): String = words.joinToString("\n")
        companion object {
            fun decode(s: String?, cap: Int = 300): Rejected = Rejected(cap).also { r ->
                s?.split('\n')?.forEach { if (it.isNotBlank()) r.add(it.trim()) }
            }
        }
    }

    /** Chữ trước từ ([before] = context đã bỏ từ) là đầu câu: rỗng hoặc sau . ! ? xuống dòng. */
    fun isSentenceStart(before: String): Boolean {
        val t = before.trimEnd { it == ' ' || it == ' ' || it == '\t' }
        if (t.isEmpty()) return true
        return t.last() in ".!?\n…"
    }

    /**
     * Chữ hoa: hoa đầu chỉ nhận ở ĐẦU CÂU (giữa câu = tên riêng); hơn một chữ hoa (VN, HCM,
     * CamelCase) ⇒ không sửa.
     */
    fun caseAllows(raw: String, sentenceStart: Boolean): Boolean {
        val upper = raw.count { it.isUpperCase() }
        if (upper == 0) return true
        return upper == 1 && raw[0].isUpperCase() && sentenceStart
    }

    /**
     * Chọn ứng viên theo [p]; null = không đủ chắc. [touches]: điểm chạm từng phím của [lower].
     */
    fun pick(lower: String, cands: List<AdjacentKeyFixer.Candidate>, touches: List<Touch>, p: Params = DEFAULT): AdjacentKeyFixer.Candidate? {
        val best = HashMap<String, AdjacentKeyFixer.Candidate>()     // theo từ (cùng từ ⇒ cùng tần suất)
        for (c in cands) {
            val t = touches.getOrNull(c.pos) ?: continue
            if (evidence(t, lower[c.pos], c.key) < p.minEv) continue   // chạm không nghiêng về phím này
            best.getOrPut(c.word) { c }
        }
        val sorted = best.values.sortedWith(compareByDescending<AdjacentKeyFixer.Candidate> { it.freq }.thenBy { it.word })
        val top = sorted.firstOrNull() ?: return null
        if (top.freq < p.minFreq) return null
        if (sorted.size > 1 && top.freq - sorted[1].freq < p.margin) return null
        return top
    }

    /**
     * Bản sửa cho từ vừa gõ (phím thô [raw]) hoặc null. [isKnown]: dạng chữ thường (phím thô
     * VÀ dạng hiển thị) là từ đã biết — tiếng Anh / từ người dùng / từng hoàn tác ⇒ không sửa.
     */
    fun correction(
        raw: String,
        touches: List<Touch>,
        compose: (String) -> String,
        frequency: (String) -> Int?,
        hasCompletion: (String) -> Boolean,
        isKnown: (String) -> Boolean,
        p: Params = DEFAULT,
    ): String? {
        if (raw.length !in p.minLen..10 || !raw.all { it in 'a'..'z' || it in 'A'..'Z' }) return null
        if (touches.size != raw.length) return null
        val lower = raw.lowercase()
        if (isKnown(lower)) return null
        val current = compose(lower)
        if (current != lower && isKnown(current.lowercase())) return null
        if (frequency(current) != null || hasCompletion(current)) return null
        val best = pick(lower, AdjacentKeyFixer.oneEditCandidates(lower, compose, frequency, hasCompletion), touches, p) ?: return null
        return if (raw[0].isUpperCase()) Cp.capitalizeFirst(best.word) else best.word
    }
}
