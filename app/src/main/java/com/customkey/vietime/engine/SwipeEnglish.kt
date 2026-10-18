// MIT License — Copyright (c) 2026 Phil Trinh
// Extracted from ptrinh/viettelex (https://github.com/ptrinh/viettelex), see assets/licenses/viettelex-LICENSE.txt.
// Modified for com.customkey.vietime.

package com.customkey.vietime.engine


/*
 * SwipeEnglish.kt — GÕ VUỐT giai đoạn 3: vuốt ra TỪ TIẾNG ANH xen trong câu tiếng Việt.
 * Bản song sinh của iOS/Keyboard/SwipeEnglish.swift — sửa ở đây thì sửa y hệt bên kia.
 *  - [SwipeEnglish.lexicon]: ~20k từ + tần suất 1 byte cùng thang vnlexicon
 *    (assets/enlexicon.bin, Scripts/gen-enlexicon.py; nguồn: docs/DATA-SOURCES.md).
 *    Không giữ template: decoder dựng template lúc chấm cho từ lọt lọc phím đầu/cuối.
 *  - [SwipeLangContext]: P(ngôn ngữ | ≤ 3 từ trước, qua dấu câu) → độ lệch + biên độ cho tiếng Anh.
 *    MẶC ĐỊNH NGHIÊNG TIẾNG VIỆT (the/thế, can/cần trùng hẳn nét vuốt).
 */

enum class SwipeLang { VI, EN }

object SwipeEnglish {
    class Lexicon(
        val words: Array<String>,   // chữ thường a–z, sort tăng dần
        val freq: IntArray,         // 0-255, thang vnlexicon
        val keys: ByteArray,        // phím gộp chữ lặp (0…25)
        val keyStart: IntArray,
        val order: IntArray,        // chỉ số từ xếp theo cặp (phím đầu·26 + phím cuối)
        val bucketStart: IntArray,  // 677
    ) { val count: Int get() = words.size }

    private val EMPTY = Lexicon(emptyArray(), IntArray(0), ByteArray(0), IntArray(1), IntArray(0), IntArray(677))

    @Volatile private var loaded: Lexicon? = null

    /**
     * Từ điển Anh (~1.4 MB heap: ~25k String) — nạp lười, thread-safe. IME [release] khi bàn
     * phím ẩn lâu và nạp lại NỀN lúc hiện ([isLoaded] cho biết có cần hâm nóng).
     */
    val lexicon: Lexicon
        get() = loaded ?: synchronized(this) {
            loaded ?: (try { parse(KeyboardData.buffer(Keys.ASSET_EN_LEXICON)) ?: EMPTY } catch (e: Exception) { EMPTY })
                .also { loaded = it }
        }

    val isLoaded: Boolean get() = loaded != null

    /** Bỏ bảng (GC được); lần dùng sau nạp lại. Ai đang giữ tham chiếu cũ vẫn dùng được. */
    fun release() { loaded = null }

    /** Giải mã enlexicon.bin ("VTE1" | count u32 | [len][freq][ASCII]…). null = hỏng. */
    fun parse(buf: java.nio.ByteBuffer): Lexicon? {
        val b = ByteArray(buf.capacity())
        val d = buf.duplicate(); d.position(0); d.get(b)
        if (b.size < 8 || b[0] != 0x56.toByte() || b[1] != 0x54.toByte() || b[2] != 0x45.toByte() || b[3] != 0x31.toByte()) return null
        val n = (b[4].toInt() and 255) or ((b[5].toInt() and 255) shl 8) or
            ((b[6].toInt() and 255) shl 16) or ((b[7].toInt() and 255) shl 24)
        val words = arrayOfNulls<String>(n)
        val freq = IntArray(n)
        val keys = ByteArray(b.size)
        val keyStart = IntArray(n + 1)
        val first = IntArray(n); val last = IntArray(n)
        var kc = 0
        var p = 8
        for (i in 0 until n) {
            if (p + 2 > b.size) return null
            val len = b[p].toInt() and 255
            val f = b[p + 1].toInt() and 255
            if (len == 0 || p + 2 + len > b.size) return null
            var prev = -1
            for (j in 0 until len) {
                val k = (b[p + 2 + j].toInt() and 255) - 97
                if (k !in 0 until 26) return null
                if (k != prev) { keys[kc++] = k.toByte(); prev = k }
            }
            first[i] = keys[keyStart[i]].toInt(); last[i] = keys[kc - 1].toInt()
            keyStart[i + 1] = kc
            words[i] = String(b, p + 2, len, Charsets.US_ASCII)
            freq[i] = f
            p += 2 + len
        }
        val bucketStart = IntArray(677)
        for (i in 0 until n) bucketStart[first[i] * 26 + last[i] + 1]++
        for (bk in 0 until 676) bucketStart[bk + 1] += bucketStart[bk]
        val fill = bucketStart.copyOf(676)
        val order = IntArray(n)
        for (i in 0 until n) { val bk = first[i] * 26 + last[i]; order[fill[bk]++] = i }
        @Suppress("UNCHECKED_CAST")
        return Lexicon(words as Array<String>, freq, keys.copyOf(kc), keyStart, order, bucketStart)
    }

    fun indexOf(word: String): Int {
        val a = lexicon.words
        var lo = 0; var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (a[mid] < word) lo = mid + 1 else hi = mid
        }
        return if (lo < a.size && a[lo] == word) lo else -1
    }

    fun contains(word: String): Boolean = indexOf(word) >= 0

    /**
     * Như [contains] nhưng KHÔNG nạp (main thread: parse ~1.4 MB mất hàng chục ms). Chưa nạp
     * (đang hâm nóng nền / vừa nhả lúc ẩn lâu) ⇒ coi như chưa biết = false.
     */
    fun containsIfLoaded(word: String): Boolean {
        val a = (loaded ?: return false).words
        var lo = 0; var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (a[mid] < word) lo = mid + 1 else hi = mid
        }
        return lo < a.size && a[lo] == word
    }

    /**
     * Chế độ Tiếng Anh (vuốt phím cách): từ bắt đầu bằng [typed] (a–z, không phân biệt hoa
     * thường), tần suất giảm dần, tối đa [limit], bỏ chính từ đang gõ. Chữ hoa theo chữ đang
     * gõ ("Hel" → "Hello", "HEL" → "HELLO"). Chữ ngoài a–z ⇒ rỗng. Giống iOS.
     */
    fun completions(typed: String, limit: Int = 24, lex: Lexicon = lexicon): List<VNSuggest.Match> {
        val low = typed.lowercase()
        if (low.isEmpty() || limit <= 0 || low.any { it !in 'a'..'z' }) return emptyList()
        val a = lex.words
        var lo = 0; var hi = a.size
        while (lo < hi) { val mid = (lo + hi) / 2; if (a[mid] < low) lo = mid + 1 else hi = mid }
        val hits = ArrayList<Int>()
        var i = lo
        while (i < a.size && a[i].startsWith(low)) { if (a[i] != low) hits.add(i); i++ }
        hits.sortWith { x, y -> if (lex.freq[x] != lex.freq[y]) lex.freq[y] - lex.freq[x] else x - y }
        return hits.take(limit).map { VNSuggest.Match(matchCase(a[it], typed), lex.freq[it]) }
    }

    /** Từ phổ biến nhất (đệm thanh gợi ý ở chế độ Tiếng Anh). */
    fun topWords(limit: Int, lex: Lexicon = lexicon): List<String> {
        if (limit <= 0) return emptyList()
        return lex.freq.indices.sortedWith { x, y -> if (lex.freq[x] != lex.freq[y]) lex.freq[y] - lex.freq[x] else x - y }
            .take(limit).map { lex.words[it] }
    }
    @Volatile private var topCache: List<String>? = null
    /** 12 từ phổ biến nhất — nạp từ điển nếu cần (gọi trên luồng nền: [warm]). Giữ qua [release] (12 String). */
    val top: List<String> get() = topCache ?: topWords(12).also { topCache = it }
    /** [top] nếu đã có, KHÔNG nạp/sắp xếp (main thread); null = chưa hâm nóng xong. */
    val topIfReady: List<String>? get() = topCache

    fun matchCase(w: String, typed: String): String {
        val f = typed.firstOrNull() ?: return w
        if (!f.isUpperCase()) return w
        if (typed.length > 1 && typed.all { it.isUpperCase() }) return w.uppercase()
        return w.replaceFirstChar { it.uppercaseChar() }
    }
}

/** Tham số tiếng Anh cho một lần decode (xem [SwipeLangContext.prior]). */
data class SwipeEnglishPrior(
    /** Cộng vào điểm mọi ứng viên tiếng Anh (âm = nghiêng Việt). */
    val bias: Float,
    /** Tiếng Anh chỉ giữ top-1 khi hơn ứng viên Việt tốt nhất ≥ margin. */
    val margin: Float,
)

object SwipeLangContext {
    enum class Kind { VI, EN, NEUTRAL }

    // GIỮ Y HỆT bản Swift. Chọn bằng SwipeEnglishTests.sweepPriors (xem bản Swift).
    val DEFAULT = SwipeEnglishPrior(bias = -0.9f, margin = 0.3f)
    val ENGLISH = SwipeEnglishPrior(bias = 0.2f, margin = 0f)
    /** Chế độ Tiếng Anh: đẩy hẳn tiếng Anh lên (ứng viên Việt bị lọc sau decode). */
    val ONLY_ENGLISH = SwipeEnglishPrior(bias = 4f, margin = 0f)

    /** Phân loại một từ đã có trên màn hình; [swipedEnglish] = vừa vuốt ra như từ tiếng Anh. */
    fun classify(word: String?, swipedEnglish: Boolean = false): Kind {
        if (word.isNullOrEmpty()) return Kind.NEUTRAL
        if (swipedEnglish) return Kind.EN
        val w = word.lowercase()
        if (!w.all { it in 'a'..'z' }) return if (w.any { it.code > 127 }) Kind.VI else Kind.NEUTRAL
        if (EnglishContextLookup.isNeutralLoanword(w)) return Kind.NEUTRAL
        if (EnglishContextLookup.opensEnglishRun(w)) return Kind.EN
        val vnForm = SwipeLexicon.indexOf(w) >= 0
        if (SwipeEnglish.contains(w)) return if (vnForm) Kind.NEUTRAL else Kind.EN
        return if (vnForm) Kind.VI else Kind.NEUTRAL
    }

    /** Độ lệch theo 2 từ trước (prev1 = liền trước) — = [prior] trên danh sách. */
    fun prior(prev1: Kind, prev2: Kind): SwipeEnglishPrior = prior(listOf(prev1, prev2))

    /**
     * Tiền nghiệm LIÊN TỤC NGÔN NGỮ (28/09/2026): đang gõ tiếng Anh thì từ kế hay là tiếng Anh,
     * đang gõ tiếng Việt thì tiếng Việt. [Continuity] — GIỮ Y HỆT bản Swift. Chỉnh trên dev
     * (SwipeLangTuneTests): mạch Việt KHÔNG cần thêm — DEFAULT đã nghiêng Việt; viBias/viMargin > 0
     * chỉ +0,1 điểm câu Việt mà −1,5…−3 điểm từ Anh chen sau từ Việt ⇒ 0. docs/DATA-SOURCES.md.
     */
    data class Continuity(
        /** Trọng số từ lùi i vị trí = decay^i (từ trung tính vẫn tính khoảng cách). */
        val decay: Float = 0.6f,
        /** Mạch Anh s ≥ 1 (một từ Anh liền trước): bias = enBias, margin 0; 0 < s < 1: nội suy từ DEFAULT. */
        val enBias: Float = 0.2f,
        val enGain: Float = 0.5f,
        /** Trần s mạch Anh (ba từ Anh ⇒ 1 + 0,6 + 0,36 = 1,96). */
        val enCap: Float = 2f,
        /** Mạch Việt: bias −= viBias·v, margin += viMargin·v, v = min(s Việt, viCap). */
        val viBias: Float = 0f,
        val viMargin: Float = 0f,
        val viCap: Float = 2f,
    )

    val CONTINUITY = Continuity()
    /** Số từ trước con trỏ được xét. */
    const val WINDOW = 3

    /**
     * Ngôn ngữ ≤ [WINDOW] từ trước con trỏ, gần nhất trước: [composing] ⇒ từ đang soạn [pending]
     * (sẽ chốt trước từ vuốt; null = không học được ⇒ trung tính) đứng đầu; [recent] = từ đã chốt,
     * CŨ → MỚI; [english] = từ đó vừa được vuốt ra như tiếng Anh.
     */
    fun kinds(composing: Boolean, pending: String?, pendingEnglish: Boolean, recent: Collection<String>,
              english: (String?) -> Boolean): List<Kind> {
        val out = ArrayList<Kind>(WINDOW)
        if (composing) out.add(classify(pending, pendingEnglish))
        val r = recent.toList()
        var i = r.size - 1
        while (out.size < WINDOW && i >= 0) { out.add(classify(r[i], english(r[i]))); i-- }
        return out
    }

    /**
     * [kinds] = ngôn ngữ ≤ 3 từ trước con trỏ, gần nhất trước. Mạch Anh s = Σ decay^i trên các
     * từ Anh, đi từ gần ra xa, DỪNG ở từ Việt đầu tiên (một từ Việt cắt mạch Anh — "check mail
     * cho" → mạch Việt); mạch Việt đối xứng (dừng ở từ Anh). Không có từ nào rõ ngôn ngữ ⇒
     * đúng [DEFAULT] (từ rời không đổi). Mạch Anh: [ENGLISH] ở s = 1, +enGain·(s − 1) tới
     * trần; mạch Việt chỉ nới biên độ/độ lệch có trần — hình học + LM mạnh vẫn thắng, từ Anh
     * chen giữa câu Việt vẫn ra.
     */
    fun prior(kinds: List<Kind>, c: Continuity = CONTINUITY): SwipeEnglishPrior {
        val en = run(kinds, Kind.EN, Kind.VI, c.decay)
        if (en >= 1f) return SwipeEnglishPrior(c.enBias + c.enGain * (minOf(en, c.enCap) - 1f), 0f)
        if (en > 0f) return SwipeEnglishPrior(DEFAULT.bias + (c.enBias - DEFAULT.bias) * en, DEFAULT.margin * (1f - en))
        val vi = minOf(run(kinds, Kind.VI, Kind.EN, c.decay), c.viCap)
        if (vi <= 0f) return DEFAULT
        return SwipeEnglishPrior(DEFAULT.bias - c.viBias * vi, DEFAULT.margin + c.viMargin * vi)
    }

    private fun run(kinds: List<Kind>, same: Kind, stop: Kind, decay: Float): Float {
        var s = 0f; var w = 1f
        for (i in 0 until minOf(3, kinds.size)) {
            val k = kinds[i]
            if (k == stop) break
            if (k == same) s += w
            w *= decay
        }
        return s
    }
}
