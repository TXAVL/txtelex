// MIT License — Copyright (c) 2026 Phil Trinh
// Extracted from ptrinh/viettelex (https://github.com/ptrinh/viettelex), see assets/licenses/viettelex-LICENSE.txt.
// Modified for com.customkey.vietime.

package com.customkey.vietime.engine

import java.nio.ByteBuffer
import java.text.Normalizer

/**
 * MÔ HÌNH NGÔN NGỮ ÂM TIẾT TRIGRAM tĩnh cho gõ vuốt (assets/vnlm.bin, GENERATED bởi
 * Scripts/gen-syllable-lm.py — nguồn/giấy phép: docs/DATA-SOURCES.md). Song sinh
 * iOS/Keyboard/SyllableLM.swift — sửa hành vi ở đây thì sửa y hệt bên kia (fixture parity
 * iOS/KeyboardTests/Fixtures/vnlm-parity.txt).
 *
 * Kneser-Ney nội suy bậc 3 đã cắt tỉa. Điểm s (nat) của âm tiết c sau (a, b) =
 * ln(P(c|a,b) / P1(c)): > 0 = hay theo sau hơn bình thường, < 0 = hiếm. Thiếu mục ⇒ lùi:
 * dòng trigram (a,b) có mà thiếu c → γ3(ab) + s2(b,c); không có dòng → s2(b,c); bigram
 * thiếu → γ2(b).
 *
 * Cũng là nguồn BIGRAM của thanh gợi ý gõ chạm + Thêm dấu (thay vnbigram.bin cũ): [bigram]
 * trả PMI ≈ ln(P(c|b) / P(c)) = s2(b, c) + uniAdj(c), uniAdj = ln(P1(c)·N / c(c)) đổi mẫu số
 * từ P1 (continuation) sang tần suất thật.
 *
 * Định dạng v3 (LE; đặc tả đầy đủ ở docstring script + docs/DATA-SOURCES.md): header 128 byte
 * (0 "VNM1" | 4 version=3 | 8 count | 12 lexHash | 16 qPerNat | 20 biEntries | 24 triContexts |
 * 28 triEntries | 32 triL, 8, bits3, bitsG3, biSel, triSel (u8) | 40 payloadHash |
 * 48 offset u32 ×19 của 18 phần + kết thúc). Bigram: Elias–Fano THEO DÒNG (dòng b: id c tăng
 * dần, L_b = ⌊log2(count/n_b)⌋; biRow/biZero/biLowOff = chỉ số mục / số bit 0 / bit low đầu
 * dòng; bit high cả bảng nối liền, mẫu select0 mỗi 2^biSel số 0). Trigram: một dãy EF toàn cục
 * khoá k·count + c (k = số thứ tự ngữ cảnh = rank cờ hasTri của mục bigram (a, b)). Điểm (bội
 * 1/16 nat): bigram i8 thô (đã lượng tử 64 mức); trigram / γ3 = mã bits3 / bitsG3 bit tra
 * codebook i8.
 *
 * Đọc TẠI CHỖ (mmap, absolute get — thread-safe), không giải mã vào heap: RAM bẩn ≈ 0.
 */
class SyllableLM private constructor(@PublishedApi internal val buf: ByteBuffer, off: IntArray) {
    val count: Int = buf.getInt(8)
    private val qPerNat: Float = buf.getInt(16).toFloat()
    val biEntries: Int = buf.getInt(20)
    val triContexts: Int = buf.getInt(24)
    val triEntries: Int = buf.getInt(28)
    private val triL = u8(32)
    private val bits3 = u8(34)
    private val bitsG = u8(35)
    private val biSel = u8(36)
    private val triSel = u8(37)
    private val g2Base = off[0]
    @PublishedApi internal val adjBase = off[1]
    private val cb3Base = off[2]
    private val cbgBase = off[3]
    private val biRow = off[4]
    private val biZero = off[5]
    private val biLowOff = off[6]
    @PublishedApi internal val biLow = off[7]
    @PublishedApi internal val biHigh = off[8]
    private val biSelBase = off[9]
    @PublishedApi internal val biWords = (off[9] - off[8]) / 8
    @PublishedApi internal val biScore = off[10]
    private val hasTri = off[11]
    private val triRank = off[12]
    private val g3Base = off[13]
    private val triLow = off[14]
    private val triHigh = off[15]
    private val triSelBase = off[16]
    private val triWords = (off[16] - off[15]) / 8
    private val triScore = off[17]
    val byteCount: Long = off[18].toLong()

    private fun u8(i: Int) = buf.get(i).toInt() and 0xFF

    // ---- bit thô ----

    @PublishedApi internal fun u64(base: Int, wi: Int): Long = buf.getLong(base + (wi shl 3))
    private fun u32(base: Int, i: Int): Int = buf.getInt(base + (i shl 2))

    /** [width] (≤ 32) bit tại vị trí bit [pos] của mảng bắt đầu ở [base]. */
    @PublishedApi internal fun bits(base: Int, pos: Int, width: Int): Int {
        val wi = pos ushr 6; val sh = pos and 63
        var v = u64(base, wi) ushr sh
        if (sh + width > 64) v = v or (u64(base, wi + 1) shl (64 - sh))
        return (v and ((1L shl width) - 1)).toInt()
    }

    private fun bit(base: Int, p: Int): Boolean = ((buf.get(base + (p ushr 3)).toInt() ushr (p and 7)) and 1) != 0

    /** Giá trị codebook (i8, đơn vị 1/16 nat) của mã thứ [i] ([w] bit) trong mảng [base]. */
    @PublishedApi internal fun cbValue(cb: Int, base: Int, i: Int, w: Int): Int = buf.get(cb + bits(base, i * w, w)).toInt()

    @PublishedApi internal fun q(v: Int): Float = v / qPerNat

    // ---- Elias–Fano ----

    /** Vị trí số 0 thứ [r] (từ 0) trong mảng high ([high], [sel], [words], mẫu 2^[shift]). */
    private fun select0(high: Int, sel: Int, words: Int, shift: Int, r: Int): Int {
        val t = r ushr shift
        val p = u32(sel, t)
        var k = r - (t shl shift)
        if (k == 0) return p
        var wi = (p + 1) ushr 6
        if (wi >= words) return words shl 6                       // file hỏng: không đọc tràn
        var w = u64(high, wi).inv() and (-1L shl ((p + 1) and 63))
        while (true) {
            val c = java.lang.Long.bitCount(w)
            if (c >= k) break
            k -= c; wi++
            if (wi >= words) return words shl 6
            w = u64(high, wi).inv()
        }
        return (wi shl 6) + selectInWord(w, k - 1)
    }

    /** Số bit 1 liên tiếp từ [pos] (= cỡ phần còn lại của xô). */
    private fun runLength(high: Int, words: Int, pos: Int): Int {
        var wi = pos ushr 6; var sh = pos and 63; var n = 0
        while (wi < words) {
            val ones = java.lang.Long.numberOfTrailingZeros((u64(high, wi) ushr sh).inv())
            if (ones < 64 - sh) return n + ones
            n += 64 - sh; wi++; sh = 0
        }
        return n
    }

    // trigram: EF toàn cục (khoá k·count + c)

    @PublishedApi internal fun triLowAt(m: Int): Int = bits(triLow, m * triL, triL)

    /** Chỉ số khoá [x] trong trigram, -1 nếu không có (select0 + tìm nhị phân trong xô). */
    private fun triFind(x: Int): Int {
        val h = x ushr triL; val t = x and ((1 shl triL) - 1)
        var pos = 0; var idx = 0
        if (h > 0) { val z = select0(triHigh, triSelBase, triWords, triSel, h - 1); pos = z + 1; idx = z + 1 - h }
        var lo = idx; var hi = idx + runLength(triHigh, triWords, pos)
        while (lo < hi) {
            val m = (lo + hi) ushr 1
            val v = triLowAt(m)
            if (v < t) lo = m + 1 else if (v > t) hi = m else return m
        }
        return -1
    }

    /** (chỉ số, vị trí bit để duyệt tiếp) của khoá trigram đầu tiên ≥ x, gói thành Long. */
    private fun triLowerBound(x: Int): Long {
        val h = x ushr triL; val t = x and ((1 shl triL) - 1)
        var pos = 0; var idx = 0
        if (h > 0) { val z = select0(triHigh, triSelBase, triWords, triSel, h - 1); pos = z + 1; idx = z + 1 - h }
        var lo = idx; var hi = idx + runLength(triHigh, triWords, pos)
        while (lo < hi) {
            val m = (lo + hi) ushr 1
            if (triLowAt(m) < t) lo = m + 1 else hi = m
        }
        return (lo.toLong() shl 32) or (pos + lo - idx).toLong()
    }

    /**
     * Tìm khoá [x] bằng quét tuần tự từ phần tử [lo] (bit high ở [pos]) tối đa [SHORT_ROW]
     * phần tử; quá thì [triFind]. Dòng trigram ngắn khỏi select0.
     */
    private fun triScanFind(lo: Int, pos: Int, x: Int): Int {
        var wi = pos ushr 6
        var w = u64(triHigh, wi) and (-1L shl (pos and 63))
        var m = lo
        val end = minOf(triEntries, lo + SHORT_ROW)
        while (m < end) {
            while (w == 0L) { wi++; if (wi >= triWords) return -1; w = u64(triHigh, wi) }
            val k = (((wi shl 6) + java.lang.Long.numberOfTrailingZeros(w) - m) shl triL) or triLowAt(m)
            if (k >= x) return if (k == x) m else -1
            w = w and (w - 1); m++
        }
        return if (m < triEntries) triFind(x) else -1
    }

    // bigram: EF theo dòng

    /**
     * Một dòng bigram (EF riêng): chỉ số [lo, hi), [l] bit thấp, số bit 0 trước dòng [zero]
     * (bit high đầu dòng = lo + zero), bit low đầu dòng [lowOff].
     */
    @PublishedApi internal class BiRow(@JvmField val lo: Int, @JvmField val hi: Int, @JvmField val l: Int,
                                       @JvmField val zero: Int, @JvmField val lowOff: Int) {
        val start: Int get() = lo + zero
    }

    private fun biRowAt(b: Int): BiRow {
        val lo = u32(biRow, b); val hi = u32(biRow, b + 1)
        return BiRow(lo, hi, efL(hi - lo, count), u32(biZero, b), u32(biLowOff, b))
    }

    @PublishedApi internal fun biLowAt(r: BiRow, m: Int): Int = bits(biLow, r.lowOff + (m - r.lo) * r.l, r.l)

    @PublishedApi internal fun biScoreAt(j: Int): Float = q(buf.get(biScore + j).toInt())

    @PublishedApi internal fun adj(w: Int): Float = q(buf.get(adjBase + w).toInt())

    /**
     * Chỉ số mục (b, c) trong dòng [r], -1 nếu không có. Dòng ngắn: quét tuần tự; dài:
     * select0(zero + h − 1) mở xô h = c >> L rồi quét các bit 1 liền nhau của xô (0–2 mục).
     */
    private fun biFind(r: BiRow, c: Int): Int {
        val n = r.hi - r.lo
        if (n == 0) return -1
        if (n <= SHORT_ROW) {
            val start = r.start
            var wi = start ushr 6
            var w = u64(biHigh, wi) and (-1L shl (start and 63))
            for (m in r.lo until r.hi) {
                while (w == 0L) { wi++; if (wi >= biWords) return -1; w = u64(biHigh, wi) }
                val k = (((wi shl 6) + java.lang.Long.numberOfTrailingZeros(w) - start - (m - r.lo)) shl r.l) or biLowAt(r, m)
                if (k >= c) return if (k == c) m else -1
                w = w and (w - 1)
            }
            return -1
        }
        val h = c ushr r.l; val t = c and ((1 shl r.l) - 1)
        var pos = r.start; var idx = r.lo
        if (h > 0) {
            val z = r.zero + h - 1
            pos = select0(biHigh, biSelBase, biWords, biSel, z) + 1
            idx = pos - z - 1
        }
        if (pos ushr 6 >= biWords) return -1
        var w = u64(biHigh, pos ushr 6) ushr (pos and 63)
        while ((w and 1L) != 0L) {
            val v = biLowAt(r, idx)
            if (v >= t) return if (v == t) idx else -1
            pos++; idx++
            if ((pos and 63) == 0) { if (pos ushr 6 >= biWords) return -1; w = u64(biHigh, pos ushr 6) } else w = w ushr 1
        }
        return -1
    }

    /** Số thứ tự ngữ cảnh trigram của mục bigram [j] (rank cờ hasTri), -1 nếu không có dòng. */
    private fun contextIndex(j: Int): Int {
        if (!bit(hasTri, j)) return -1
        return u32(triRank, j ushr 6) + java.lang.Long.bitCount(u64(hasTri, j ushr 6) and ((1L shl (j and 63)) - 1))
    }

    // ---- API ----

    /**
     * Ngữ cảnh đã tra sẵn cho một vị trí (1–2 âm tiết trước): dòng bigram prev1 + dòng trigram
     * (prev2, prev1) định vị một lần; mỗi [score] chỉ còn tìm trong dòng.
     */
    inner class Context internal constructor(
        private val g2: Float, @PublishedApi internal val bi: BiRow,
        /** Khoá đầu dòng trigram (k·count); < 0 ⇒ không có dòng trigram cho (prev2, prev1). */
        @PublishedApi internal val triBase: Int, @PublishedApi internal val triLo: Int,
        @PublishedApi internal val triPos: Int, @PublishedApi internal val g3: Float,
    ) {
        /** s (nat) của âm tiết id [w]. */
        fun score(w: Int): Float {
            if (w < 0 || w >= count) return 0f
            if (triBase >= 0) {
                val t = triScanFind(triLo, triPos, triBase + w)
                if (t >= 0) return q(cbValue(cb3Base, triScore, t, bits3))
            }
            val j = biFind(bi, w)
            val s2 = if (j >= 0) biScoreAt(j) else g2
            return if (triBase >= 0) g3 + s2 else s2
        }

        /**
         * PMI của mục TRIGRAM tường minh (prev2, prev1, [w]) = s3 + uniAdj; Float.NaN nếu dòng
         * trigram không có [w] (hoặc không có dòng) — Thêm dấu (Viterbi bậc 2) chỉ dùng mục
         * trigram thật, thiếu thì về bigram (Swift: `SyllableLM.forEachTrigram`, duyệt theo dải id).
         */
        fun trigram(w: Int): Float {
            if (triBase < 0 || w < 0 || w >= count) return Float.NaN
            val t = triScanFind(triLo, triPos, triBase + w)
            return if (t >= 0) q(cbValue(cb3Base, triScore, t, bits3)) + adj(w) else Float.NaN
        }

        /**
         * PMI của mục TƯỜNG MINH (thanh gợi ý chấm ứng viên đang gõ): có mục trigram → s3 + uniAdj;
         * không thì mục bigram → s2 + uniAdj (không cộng γ3 — đo trên dev: cộng γ3 kém hơn);
         * thiếu cả hai → 0. Không dòng trigram ⇒ = [Bigram.explicit].
         */
        fun explicit(w: Int): Float {
            if (w < 0 || w >= count) return 0f
            if (triBase >= 0) {
                val t = triScanFind(triLo, triPos, triBase + w)
                if (t >= 0) return q(cbValue(cb3Base, triScore, t, bits3)) + adj(w)
            }
            val j = biFind(bi, w)
            return if (j >= 0) biScoreAt(j) + adj(w) else 0f
        }

        /**
         * [explicit] cho nhiều id một lượt (pool ứng viên đang gõ; id < 0 ⇒ 0): xếp id tăng dần
         * (chèn, pool ≤ 24) rồi đi song song với dòng trigram — mỗi mục dòng đọc MỘT lần, dừng ở id
         * lớn nhất (dòng trung vị 1 mục); dòng dài quá [LONG_TRI_ROW] mục thì phần còn lại tìm từng id.
         */
        fun explicitAll(ids: IntArray): FloatArray {
            val n = ids.size
            if (n > 64) return FloatArray(n) { explicit(ids[it]) }   // khoá gói chỉ số 6 bit
            val out = FloatArray(n)
            if (n == 0) return out
            // khoá id·64 + chỉ số (pool ≤ 64), id không hợp lệ ⇒ bỏ
            val ord = IntArray(n); var k = 0
            for (i in 0 until n) {
                val w = ids[i]
                if (w < 0 || w >= count) continue
                val j = biFind(bi, w)
                if (j >= 0) out[i] = biScoreAt(j) + adj(w)
                val key = (w shl 6) or i
                var p = k
                while (p > 0 && ord[p - 1] > key) { ord[p] = ord[p - 1]; p-- }
                ord[p] = key; k++
            }
            if (triBase < 0 || k == 0) return out
            val t = TriCursor(triLo, triBase, triBase + count)
            triStart(t, triPos)
            var qi = 0; var steps = 0
            while (qi < k && t.c != Int.MAX_VALUE) {
                val w = ord[qi] ushr 6
                if (t.c < w) {
                    if (++steps > LONG_TRI_ROW) {
                        while (qi < k) {
                            val w2 = ord[qi] ushr 6
                            val m = triFind(triBase + w2)
                            if (m >= 0) out[ord[qi] and 63] = q(cbValue(cb3Base, triScore, m, bits3)) + adj(w2)
                            qi++
                        }
                        return out
                    }
                    triNext(t)
                } else {
                    if (t.c == w) out[ord[qi] and 63] = t.v + adj(w)
                    qi++
                }
            }
            return out
        }

        /**
         * Duyệt CHỈ các mục trigram tường minh của (prev2, prev1) theo id tăng dần: action(id, pmi),
         * pmi = s3 + uniAdj. Trả số mục (0 nếu không có dòng trigram).
         */
        inline fun forEachTrigram(action: (next: Int, pmi: Float) -> Unit): Int {
            if (triBase < 0) return 0
            val t = TriCursor(triLo, triBase, triBase + count)
            triStart(t, triPos)
            var n = 0
            while (t.c != Int.MAX_VALUE) { action(t.c, t.v + adj(t.c)); n++; triNext(t) }
            return n
        }

        /** Số mục dòng bigram prev1 (0 ⇒ không biết gì về prev1 — như [Bigram.size]). */
        val bigramSize: Int get() = bi.hi - bi.lo

        /** Có dòng trigram cho (prev2, prev1) không. */
        val hasTrigram: Boolean get() = triBase >= 0

        /**
         * Duyệt mọi âm tiết sau có mục TƯỜNG MINH — dòng bigram prev1 ∪ dòng trigram (prev2, prev1)
         * — theo id tăng dần: action(id, pmi), pmi = [score] + uniAdj ≈ ln(P(c | prev2, prev1) / P(c))
         * (mục chỉ có ở bigram lùi γ3 + s2 như [score]). Không dòng trigram ⇒ y hệt
         * [Bigram.forEach]. Trộn hai dãy tăng dần tại chỗ (dòng trigram duyệt tuần tự, không
         * select0 lại), không cấp phát mảng. Song sinh Swift `Context.forEachNext`.
         */
        inline fun forEachNext(action: (next: Int, pmi: Float) -> Unit) {
            val t = TriCursor(triLo, triBase, triBase + count)
            if (triBase >= 0) triStart(t, triPos)
            val back = if (triBase >= 0) g3 else 0f
            biScan(bi) { m, c ->
                while (t.c < c) { action(t.c, t.v + adj(t.c)); triNext(t) }
                if (t.c == c) { action(c, t.v + adj(c)); triNext(t) }
                else action(c, back + biScoreAt(m) + adj(c))
            }
            while (t.c != Int.MAX_VALUE) { action(t.c, t.v + adj(t.c)); triNext(t) }
        }
    }

    /**
     * Con trỏ duyệt tuần tự một dòng trigram (khoá [base] ≤ k < [end]): phần tử [m], từ bit high
     * [wi]/[w]; [c] = id âm tiết kế (MAX = hết dòng), [v] = s3 của nó.
     */
    @PublishedApi internal class TriCursor(@JvmField var m: Int, @JvmField val base: Int, @JvmField val end: Int) {
        @JvmField var wi = 0
        @JvmField var w = 0L
        @JvmField var c = Int.MAX_VALUE
        @JvmField var v = 0f
    }

    @PublishedApi internal fun triStart(t: TriCursor, pos: Int) {
        t.wi = pos ushr 6
        if (t.wi >= triWords) return
        t.w = u64(triHigh, t.wi) and (-1L shl (pos and 63))
        triNext(t)
    }

    @PublishedApi internal fun triNext(t: TriCursor) {
        t.c = Int.MAX_VALUE
        if (t.m >= triEntries) return
        while (t.w == 0L) { t.wi++; if (t.wi >= triWords) return; t.w = u64(triHigh, t.wi) }
        val k = (((t.wi shl 6) + java.lang.Long.numberOfTrailingZeros(t.w) - t.m) shl triL) or triLowAt(t.m)
        if (k >= t.end) return
        t.c = k - t.base; t.v = q(cbValue(cb3Base, triScore, t.m, bits3))
        t.w = t.w and (t.w - 1); t.m++
    }

    /** Ngữ cảnh sau ([prev2], [prev1]) — id vnlexicon, -1 = không có. null nếu prev1 không hợp lệ. */
    fun context(prev2: Int, prev1: Int): Context? {
        if (prev1 < 0 || prev1 >= count) return null
        val g2 = q(buf.get(g2Base + prev1).toInt())
        var k = -1
        if (prev2 in 0 until count) {
            val j = biFind(biRowAt(prev2), prev1)
            if (j >= 0) k = contextIndex(j)
        }
        val row = biRowAt(prev1)
        if (k < 0) return Context(g2, row, -1, 0, 0, 0f)
        val lb = triLowerBound(k * count)
        return Context(g2, row, k * count, (lb ushr 32).toInt(), lb.toInt(), q(cbValue(cbgBase, g3Base, k, bitsG)))
    }

    /** s (nat) của [w] sau ([prev2], [prev1]); 0 nếu không có ngữ cảnh. */
    fun score(prev2: Int, prev1: Int, w: Int): Float = context(prev2, prev1)?.score(w) ?: 0f

    /** ln(P1(c)·N / c(c)) — đổi s2 (so với P1) thành PMI (so với tần suất thật). */
    fun uniAdj(w: Int): Float = if (w < 0 || w >= count) 0f else adj(w)

    /**
     * Dải bigram của âm tiết trước — thanh gợi ý gõ chạm + Thêm dấu (thay vnbigram.bin).
     * [size] = 0 ⇒ âm tiết trước không có mục nào (không biết gì về nó).
     */
    inner class Bigram internal constructor(private val g2: Float, @PublishedApi internal val row: BiRow) {
        val size: Int get() = row.hi - row.lo

        /** PMI (nat) của âm tiết sau id [next]; thiếu mục ⇒ lùi γ2(prev) + uniAdj; dải rỗng ⇒ 0. */
        fun pmi(next: Int): Float {
            if (size == 0 || next < 0 || next >= count) return 0f
            val j = biFind(row, next)
            return (if (j >= 0) biScoreAt(j) else g2) + adj(next)
        }

        /** PMI của mục tường minh; 0 nếu thiếu. */
        fun explicit(next: Int): Float {
            if (size == 0 || next < 0 || next >= count) return 0f
            val j = biFind(row, next)
            return if (j >= 0) biScoreAt(j) + adj(next) else 0f
        }

        /** Duyệt mọi mục tường minh (id âm tiết sau, PMI) theo id tăng dần — thanh gợi ý lấy top từ kế tiếp. */
        inline fun forEach(action: (next: Int, pmi: Float) -> Unit) {
            biScan(row) { m, c -> action(c, biScoreAt(m) + adj(c)) }
        }
    }

    /** Duyệt tuần tự dòng bigram: action(chỉ số mục, id âm tiết sau). Không cấp phát. */
    @PublishedApi internal inline fun biScan(r: BiRow, action: (idx: Int, c: Int) -> Unit) {
        if (r.lo >= r.hi) return
        val start = r.start
        var wi = start ushr 6
        var w = u64(biHigh, wi) and (-1L shl (start and 63))
        // bit thấp đọc tuần tự: đệm 64 bit `lb` còn `la` bit
        val l = r.l; val mask = (1L shl l) - 1
        var li = r.lowOff ushr 6
        var lb = u64(biLow, li) ushr (r.lowOff and 63); var la = 64 - (r.lowOff and 63)
        for (m in r.lo until r.hi) {
            while (w == 0L) { wi++; if (wi >= biWords) return; w = u64(biHigh, wi) }
            val v: Long
            if (la >= l) { v = lb and mask; lb = lb ushr l; la -= l } else {
                li++
                val nx = u64(biLow, li)
                v = (lb or (nx shl la)) and mask
                lb = nx ushr (l - la); la += 64 - l
            }
            action(m, (((wi shl 6) + java.lang.Long.numberOfTrailingZeros(w) - start - (m - r.lo)) shl l) or v.toInt())
            w = w and (w - 1)
        }
    }

    /** Dải bigram của âm tiết trước id [prev] (id vnlexicon) — O(1) qua biRow. */
    fun bigram(prev: Int): Bigram {
        if (prev < 0 || prev >= count) return Bigram(0f, EMPTY_ROW)
        return Bigram(q(buf.get(g2Base + prev).toInt()), biRowAt(prev))
    }

    /**
     * FNV-1a trên toàn bộ mô hình theo thứ tự giải mã (= CHECKSUM của vnlm-parity.txt, script
     * tính từ dict): mỗi mục bigram (khoá u32, điểm i8, cờ trigram u8), γ3 mỗi ngữ cảnh, mỗi
     * mục trigram (khoá u32, điểm i8). Chỉ cho test (duyệt cả file).
     */
    fun modelChecksum(): Int {
        var h = 0x811C9DC5.toInt()
        fun mix(b: Int) { h = (h xor (b and 0xFF)) * 0x01000193 }
        fun mix32(v: Int) { mix(v); mix(v ushr 8); mix(v ushr 16); mix(v ushr 24) }
        for (b in 0 until count) {
            biScan(biRowAt(b)) { j, c ->
                mix32(b * count + c); mix(buf.get(biScore + j).toInt()); mix(if (bit(hasTri, j)) 1 else 0)
            }
        }
        for (k in 0 until triContexts) mix(cbValue(cbgBase, g3Base, k, bitsG))
        var wi = 0; var w = if (triWords > 0) u64(triHigh, 0) else 0L
        for (t in 0 until triEntries) {
            while (w == 0L) { wi++; w = u64(triHigh, wi) }
            mix32((((wi shl 6) + java.lang.Long.numberOfTrailingZeros(w) - t) shl triL) or triLowAt(t))
            mix(cbValue(cb3Base, triScore, t, bits3))
            w = w and (w - 1)
        }
        return h
    }

    /** FNV-1a phần thân (sau header) — phải bằng payloadHash ghi ở header 40. */
    fun payloadHashOK(): Boolean {
        var h = 0x811C9DC5.toInt()
        for (i in HEADER_SIZE until buf.capacity()) h = (h xor (buf.get(i).toInt() and 0xFF)) * 0x01000193
        return h == buf.getInt(40)
    }

    companion object {
        const val VERSION = 3
        const val HEADER_SIZE = 128
        private const val SECTIONS = 18
        /** Dòng ≤ chừng này mục ⇒ quét tuần tự thay vì select0. */
        private const val SHORT_ROW = 8
        /** Dòng trigram dài hơn ⇒ [Context.explicitAll] tìm từng id thay vì duyệt cả dòng. */
        private const val LONG_TRI_ROW = 48
        @PublishedApi internal val EMPTY_ROW = BiRow(0, 0, 0, 0, 0)

        /** ⌊log2(u/n)⌋ (0 nếu n = 0 hoặc u < n) — số bit thấp EF. */
        private fun efL(n: Int, u: Int): Int = if (n > 0 && u >= n) 31 - Integer.numberOfLeadingZeros(u / n) else 0

        /**
         * Vị trí bit 1 thứ [r] (từ 0) trong [x] (đủ bit 1). Broadword (Vigna): tổng dồn popcount
         * từng byte bằng một phép nhân, tìm byte chứa bit thứ r không rẽ nhánh, rồi ≤ 7 bước.
         */
        private fun selectInWord(x: Long, r: Int): Int {
            var s = x - ((x ushr 1) and 0x5555_5555_5555_5555L)
            s = (s and 0x3333_3333_3333_3333L) + ((s ushr 2) and 0x3333_3333_3333_3333L)
            s = (s + (s ushr 4)) and 0x0F0F_0F0F_0F0F_0F0FL
            val sums = s * 0x0101_0101_0101_0101L
            val msb = -0x7F7F_7F7F_7F7F_7F80L                      // 0x8080_8080_8080_8080
            val le = (((r.toLong() * 0x0101_0101_0101_0101L) or msb) - sums) and msb
            val place = java.lang.Long.numberOfTrailingZeros(le.inv() and msb) - 7
            val k = r - (((sums shl 8) ushr place) and 0xFFL).toInt()
            return place + SELECT_IN_BYTE[((((x ushr place) and 0xFFL).toInt()) shl 3) or k]
        }

        /** SELECT_IN_BYTE[b·8 + k] = vị trí bit 1 thứ k của byte b (2 KB, dựng một lần). */
        private val SELECT_IN_BYTE = ByteArray(2048).also { t ->
            for (b in 0 until 256) {
                var x = b; var k = 0
                while (x != 0) { t[(b shl 3) or k] = Integer.numberOfTrailingZeros(x).toByte(); x = x and (x - 1); k++ }
            }
        }

        /**
         * Kiểm tra header + khớp lexicon + bảng phần (cỡ từng phần phải đúng như tính từ header);
         * null nếu hỏng/lệch (chạy không LM). Không quét thân (nạp ~0 chi phí); mọi vòng quét có
         * chặn biên nên file hỏng nội dung cũng không đọc tràn.
         */
        fun load(buf: ByteBuffer, lexiconHash: Int, lexiconCount: Int): SyllableLM? {
            if (buf.capacity() < HEADER_SIZE) return null
            if (buf.get(0) != 'V'.code.toByte() || buf.get(1) != 'N'.code.toByte() ||
                buf.get(2) != 'M'.code.toByte() || buf.get(3) != '1'.code.toByte()) return null
            if (buf.getInt(4) != VERSION || buf.getInt(8) != lexiconCount || buf.getInt(12) != lexiconHash) return null
            val n = lexiconCount; val nb = buf.getInt(20); val nk = buf.getInt(24); val nt = buf.getInt(28)
            if (buf.getInt(16) <= 0 || nb < 0 || nk < 0 || nt < 0) return null
            fun byte(i: Int) = buf.get(i).toInt() and 0xFF
            val tL = byte(32); val b3 = byte(34); val bg = byte(35); val bs = byte(36); val ts = byte(37)
            if (tL >= 32 || byte(33) != 8 || listOf(b3, bg).any { it !in 1..8 } || listOf(bs, ts).any { it !in 1..16 }) return null
            if (nk.toLong() * n >= Int.MAX_VALUE || n.toLong() * n >= Int.MAX_VALUE) return null
            if (tL != efL(nt, nk * n)) return null
            val off = IntArray(SECTIONS + 1) { buf.getInt(48 + it * 4) }
            if (off[0] != HEADER_SIZE || off[SECTIONS] != buf.capacity()) return null
            for (i in 0 until SECTIONS) if (off[i] > off[i + 1]) return null
            if (off[7] - off[4] < 3 * (n + 1) * 4) return null
            // tổng ở cuối 3 bảng dòng bigram ⇒ cỡ các phần bigram
            val rowEnd = buf.getInt(off[4] + n * 4); val zeroEnd = buf.getInt(off[5] + n * 4)
            val lowEnd = buf.getInt(off[6] + n * 4)
            if (rowEnd != nb || zeroEnd < 0 || lowEnd < 0) return null
            fun pad(bytes: Int) = ((bytes + 7) and 7.inv()) + 8
            fun sel(zeros: Int, s: Int) = pad(if (zeros > 0) (((zeros - 1) ushr s) + 1) * 4 else 0)
            val tz = ((nk * n) ushr tL) + 1
            val want = intArrayOf(pad(n), pad(n), pad(1 shl b3), pad(1 shl bg),
                pad((n + 1) * 4), pad((n + 1) * 4), pad((n + 1) * 4),
                pad((lowEnd + 7) / 8), pad((nb + zeroEnd + 7) / 8), sel(zeroEnd, bs),
                pad(nb), pad((nb + 7) / 8), pad(((nb ushr 6) + 1) * 4),
                pad((nk.toLong() * bg + 7).div(8).toInt()),
                pad((nt.toLong() * tL + 7).div(8).toInt()), pad((nt + tz + 7) / 8), sel(tz, ts),
                pad((nt.toLong() * b3 + 7).div(8).toInt()))
            for (i in 0 until SECTIONS) if (off[i + 1] - off[i] != want[i]) return null
            return SyllableLM(buf, off)
        }

        /**
         * Mô hình dùng chung (gõ vuốt + thanh gợi ý + Thêm dấu — một lần map) — map lần đầu cần,
         * nên chạm lần đầu ở luồng nền (hash vnlexicon ~150KB). null nếu asset thiếu/hỏng/lệch.
         */
        val shared: SyllableLM? by lazy {
            try {
                load(KeyboardData.buffer(Keys.ASSET_LM),
                    fnv1a(KeyboardData.buffer(Keys.ASSET_LEXICON)), VNLexicon2Data.count)
            } catch (e: Exception) { null }
        }

        fun fnv1a(b: ByteBuffer): Int {
            var h = 0x811C9DC5.toInt()
            for (i in 0 until b.capacity()) {
                h = (h xor (b.get(i).toInt() and 0xFF)) * 0x01000193
            }
            return h
        }

        private val OLD_STYLE = mapOf(
            "oà" to "òa", "oá" to "óa", "oả" to "ỏa", "oã" to "õa", "oạ" to "ọa",
            "oè" to "òe", "oé" to "óe", "oẻ" to "ỏe", "oẽ" to "õe", "oẹ" to "ọe",
            "uỳ" to "ùy", "uý" to "úy", "uỷ" to "ủy", "uỹ" to "ũy", "uỵ" to "ụy",
        )

        /** NFC, chữ thường, kiểu dấu CŨ như vnlexicon (hoà → hòa; quý giữ nguyên). */
        fun normalize(w: String): String {
            val s = Normalizer.normalize(w.lowercase(), Normalizer.Form.NFC)
            if (s.length < 2) return s
            val pair = s.substring(s.length - 2)
            val rep = OLD_STYLE[pair] ?: return s
            if (pair[0] == 'u' && s.length >= 3 && s[s.length - 3] == 'q') return s
            return s.substring(0, s.length - 2) + rep
        }

        /** id vnlexicon của âm tiết [word] (đã normalize), -1 nếu không có. */
        fun idOf(word: String): Int {
            val w = normalize(word)
            val f = SwipeLexicon.indexOf(SwipeSuggest.fold(w))
            if (f < 0) return -1
            val forms = SwipeLexicon.forms
            for (id in forms.idStart[f] until forms.idStart[f + 1]) if (VNSuggest.display(id) == w) return id
            return -1
        }
    }
}
