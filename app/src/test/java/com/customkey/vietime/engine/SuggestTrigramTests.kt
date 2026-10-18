// MIT License — Copyright (c) 2026 Phil Trinh
// Ported from ptrinh/viettelex tests; modified for com.customkey.vietime.

package com.customkey.vietime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * TRIGRAM vnlm.bin trên thanh gợi ý (song sinh iOS SuggestTrigramTests.swift). Mô phỏng gõ chạm
 * cả chuỗi (người dùng mới / học dần), đo: từ kế tiếp top-1 / top-3 (3 ô sau dấu cách), slot1 /
 * 3 slot khi gõ đủ chữ không dấu (như SuggestBigramTests), và ƯỚC LƯỢNG PHÍM TIẾT KIỆM: mỗi âm
 * tiết tốn phím Telex (chữ không dấu + phím dấu/mũ/móc, đ = 2) + dấu cách; chạm chip từ kế tiếp
 * trước khi gõ = 1, hoặc ứng viên 1–2 sau tiền tố k chữ = k + 1.
 * Tham số chọn trên tập DEV (suggest-dev.txt, `VT_TUNE=1` → [tuneDev]), đo MỘT lần trên tập kiểm
 * thử (bigram-heldout.txt, [heldoutBeforeAfter]).
 */
class SuggestTrigramTests {
    @Before fun setUp() = TestAssets.install()

    private fun fixtureDir(): File = listOf("src/test", "app/src/test").map { File(it, "fixtures") }
        .firstOrNull { it.exists() } ?: error("không thấy KeyboardTests/Fixtures")

    private fun fixture(name: String): List<List<String>> =
        File(fixtureDir(), name).readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split(' ') }

    private fun seeded() = UserLangModel().apply { isKnownWord = { VNSuggest.contains(it) }; seedIfEmpty() }

    /** Phím Telex của một âm tiết (không tính dấu cách). */
    private fun cost(w: String): Int = Normalizer.normalize(w, Normalizer.Form.NFD).sumOf { if (it == 'đ') 2L else 1L }.toInt()

    data class M(val n: Int, val nextTop1: Int, val nextTop3: Int, val nAcc: Int, val slot1: Int, val slot3: Int,
                 val keys: Int, val keysSaved: Int, val compoundHits: Int = 0, val compoundShown: Int = 0) {
        fun fmt() = String.format(Locale.ROOT,
            "kế tiếp top1 %.2f top3 %.2f | slot1 %.2f 3slot %.2f | phím tiết kiệm %.2f%% | cụm %d/%d (n=%d)",
            100.0 * nextTop1 / n, 100.0 * nextTop3 / n, 100.0 * slot1 / nAcc, 100.0 * slot3 / n,
            100.0 * keysSaved / keys, compoundHits, compoundShown, n)
    }

    /** Cụm (thử nghiệm): ô chứa âm tiết c đổi thành "c d" khi âm tiết kế đủ chắc (điểm, lề với hạng 2). */
    data class Compound(val minScore: Double, val minMargin: Double, val maxLen: Int = 2)

    /** Từ kế tiếp như bàn phím (KeyboardSession): SuggestRank.nextFill rồi đệm topWords. */
    private fun nextSlots(m: UserLangModel, prev: String, prev2: String?, lm: SyllableLM?, p: SuggestRank.Params): List<String> {
        val personal = SensitiveWords.filter(m.nextWords(prev, prev2, 6), true)
        val next = if (lm == null) personal.take(3) else SuggestRank.nextFill(prev, prev2, personal,
            { w -> if (prev2 == null) 0 else m.trigramCount(prev2, prev, w) }, { SensitiveWords.filter(it, true) }, lm, p)
        return SuggestionFill.pad(next, SensitiveWords.filter(m.topWords(15), true), 3)
    }

    /** THỬ NGHIỆM (không ship — docs/DATA-SOURCES.md): nối âm tiết chắc chắn vào một ô ("việt" → "việt nam"). */
    private fun compoundNext(slots: List<String>, prev: String, lm: SyllableLM, c: Compound,
                             p: SuggestRank.Params): Pair<Int, List<String>>? {
        var best: Pair<Int, List<String>>? = null; var bestS = Double.NEGATIVE_INFINITY
        for ((j, w) in slots.withIndex()) {
            if (VNSuggest.lexiconId(w) < 0) continue
            val out = mutableListOf(w); var a = prev; var b = w; var s0 = Double.NaN
            while (out.size < c.maxLen) {
                val t = SuggestRank.topNext(b, a, 2, lm, p) ?: break
                val s = t.scores[0]; val mg = if (t.n > 1) s - t.scores[1] else s
                if (s < c.minScore || mg < c.minMargin) break
                if (s0.isNaN()) s0 = s
                val d = VNSuggest.display(t.ids[0]); out.add(d); a = b; b = d
            }
            if (out.size > 1 && s0 > bestS) { bestS = s0; best = j to out }
        }
        return best
    }

    private fun ranked(m: UserLangModel, typed: String, prev: String, prev2: String?, lm: SyllableLM?,
                       p: SuggestRank.Params): List<String> {
        val pool = VNSuggest.matches(typed, poolLimit = 24, excluding = typed)
        val ctx = m.nextWords(prev, prev2, 24).toSet()
        return SensitiveWords.filter(SuggestRank.rankInline(pool,
            SuggestRank.inlinePmi(pool, prev, lm, prev2, p), Cp.count(typed), m::count, ctx, p), true)
    }

    internal fun eval(chains: List<List<String>>, lm: SyllableLM?, p: SuggestRank.Params = SuggestRank.DEFAULT,
                     learn: Boolean = false, kss: Boolean = true, compound: Compound? = null): M {
        val m = seeded()
        var n = 0; var t1 = 0; var t3 = 0; var nAcc = 0; var s1 = 0; var s3 = 0; var keys = 0; var saved = 0
        var cHit = 0; var cShown = 0
        for (chain in chains) {
            var i = 1
            while (i < chain.size) {
                val w = chain[i]; val prev = chain[i - 1]; val prev2 = chain.getOrNull(i - 2)
                var slots = nextSlots(m, prev, prev2, lm, p)
                var comp: List<String>? = null
                if (compound != null && lm != null) {
                    compoundNext(slots, prev, lm, compound, p)?.let { (j, c) ->
                        cShown++; comp = c
                        slots = slots.toMutableList().also { it[j] = c.joinToString(" ") }
                    }
                }
                n++
                if (slots.firstOrNull() == w) t1++
                if (w in slots) t3++
                val typed = SwipeSuggest.fold(w)
                val rk = ranked(m, typed, prev, prev2, lm, p)
                if (w == typed || w in rk.take(2)) s3++
                if (w != typed) { nAcc++; if (rk.firstOrNull() == w) s1++ }
                val full = cost(w) + 1
                var span = 1
                val c = comp
                if (c != null && chain.size >= i + c.size && chain.subList(i, i + c.size) == c) {
                    span = c.size; cHit++                       // một chạm cho cả cụm
                    val all = (i until i + span).sumOf { cost(chain[it]) + 1 }
                    keys += all; saved += all - 1
                } else {
                    var spent = full
                    if (w in slots) spent = 1
                    else if (kss) {
                        for (k in 1 until Cp.count(typed)) {
                            if (w in ranked(m, typed.substring(0, k), prev, prev2, lm, p).take(2)) { spent = k + 1; break }
                        }
                        if (spent == full && w != typed && w in rk.take(2)) spent = Cp.count(typed) + 1
                    }
                    keys += full; saved += full - spent
                }
                if (learn) for (j in i until i + span) {
                    if (j == 1) m.record(chain[0], null)
                    m.record(chain[j], chain[j - 1], chain.getOrNull(j - 2))
                }
                i += span
            }
        }
        return M(n, t1, t3, nAcc, s1, s3, keys, saved, cHit, cShown)
    }

    /** Trước 28/09/2026: chỉ bigram, tham số inline cũ. */
    private val before = SuggestRank.Params(bigramWeight = 2.5, bigramCap = 8.0, bigramDamp = 0.45, useTrigram = false)

    @Test fun forEachNextMatchesScore() {
        val lm = SyllableLM.shared!!
        val id = { w: String -> SyllableLM.idOf(w) }
        var checked = 0; var withTri = 0
        for ((a, b) in listOf("thành" to "phố", "cảm" to "ơn", "hôm" to "nay", "tôi" to "không", "việt" to "nam",
                              "hello" to "bạn", "của" to "tôi", "là" to "một")) {
            val ctx = lm.context(id(a), id(b)) ?: continue
            if (ctx.hasTrigram) withTri++
            val seen = HashSet<Int>(); var last = -1
            ctx.forEachNext { c, pmi ->
                assertTrue("tăng dần", c > last); last = c; seen.add(c)
                assertEquals("$a $b → $c", ctx.score(c) + lm.uniAdj(c), pmi, 1e-5f)
                checked++
            }
            // ⊇ dòng bigram; không dòng trigram ⇒ đúng bằng dòng bigram
            val bi = HashSet<Int>(); lm.bigram(id(b)).forEach { c, _ -> bi.add(c) }
            assertTrue(seen.containsAll(bi))
            if (!ctx.hasTrigram) assertEquals(bi, seen)
            // explicit: ngoài cả hai dòng ⇒ 0; chỉ ở trigram ⇒ pmi đầy đủ; chỉ ở bigram ⇒ Bigram.explicit
            val ids = IntArray((lm.count + 6) / 7) { it * 7 }
            val all = ctx.explicitAll(ids)
            // pool thật (≤ 64 id, lộn xộn, có trùng / -1) ⇒ đường đi song song dòng trigram
            val pool = (seen.shuffled(java.util.Random(7)).take(40) + listOf(-1, ids[3], ids[3]) + ids.take(12)).toIntArray()
            val allPool = ctx.explicitAll(pool)
            for (i in pool.indices) assertEquals(ctx.explicit(pool[i]), allPool[i])
            for ((i, c) in ids.withIndex()) {
                val e = ctx.explicit(c)
                assertEquals(e, all[i])
                if (c !in seen) assertEquals(0f, e)
                else if (c !in bi) assertEquals(ctx.score(c) + lm.uniAdj(c), e, 1e-5f)
            }
        }
        assertTrue("checked=$checked withTri=$withTri", checked > 1000 && withTri >= 6)
        assertEquals(lm.bigram(id("nay")).explicit(id("tôi")), lm.context(-1, id("nay"))!!.explicit(id("tôi")))
    }

    @Test fun trigramExamples() {
        assertEquals(listOf("biết", "thể", "muốn"), SuggestRank.contextNext("không", "tôi", 3))   // vnlm hội thoại 18/10/2026 (trước: biết, muốn, nghĩ)
        assertEquals("thể", SuggestRank.contextNext("có", "bạn", 3)[0])
        assertEquals("sinh", SuggestRank.contextNext("mừng", "chúc", 3)[0])   // vnlm hội thoại 18/10/2026 (trước: giáng)
        // không dòng trigram / không prev2 ⇒ y hệt bigram
        assertEquals(SuggestRank.bigramNext("điện", 3), SuggestRank.contextNext("điện", "hello", 3))
        assertEquals(SuggestRank.bigramNext("điện", 3), SuggestRank.contextNext("điện", null, 3))
        assertTrue(SuggestRank.contextNext("hello", "tôi", 3).isEmpty())
    }

    @Test fun nextFillKeepsLearnedTrigramFirst() {
        val m = seeded()
        val personal = { SensitiveWords.filter(m.nextWords("không", "tôi", 6), true) }
        val tc = { w: String -> m.trigramCount("tôi", "không", w) }
        // người dùng mới: trigram tĩnh dẫn
        assertEquals(listOf("biết", "thể", "muốn"), SuggestRank.nextFill("không", "tôi", personal(), tc))
        // gõ "tôi không đi" vài lượt ⇒ "đi" lên đầu, còn lại trigram tĩnh
        repeat(3) { m.record("tôi", null); m.record("không", "tôi"); m.record("đi", "không", "tôi") }
        val f = SuggestRank.nextFill("không", "tôi", personal(), tc)
        assertEquals(listOf("đi", "biết", "thể"), f)
        // không prev2 ⇒ như cũ: cá nhân/seed trước
        val p1 = SensitiveWords.filter(m.nextWords("không", null, 6), true)
        assertEquals(p1.take(3), SuggestRank.nextFill("không", null, p1, { 0 }))
    }

    /** Fixture song sinh Swift: top-6 contextNext + PMI inline (×16, làm tròn) trên ngữ cảnh heldout. */
    @Test fun parityFixture() {
        val out = File(fixtureDir(), "suggest-trigram-parity.txt")
        val lines = ArrayList<String>()
        lines += "# suggest-trigram-parity v1 — SuggestRank.contextNext(prev, prev2, 6) (N) + inlinePmi ×16 (I). " +
            "Sinh bởi android SuggestTrigramTests.parityFixture (VT_WRITE_FIXTURE=1); iOS SuggestTrigramTests khớp từng dòng."
        var k = 0
        for (c in fixture("bigram-heldout.txt")) for (i in 2 until c.size) {
            if (k++ % 23 != 0) continue
            val a = c[i - 2]; val b = c[i - 1]; val typed = SwipeSuggest.fold(c[i])
            val pool = VNSuggest.matches(typed, 24, typed)
            val pmi = SuggestRank.inlinePmi(pool, b, prev2 = a)
            lines += "N $a $b " + SuggestRank.contextNext(b, a, 6).joinToString(",")
            lines += "I $a $b $typed " + pool.indices.joinToString(",") { "${pool[it].word}:${Math.round((pmi?.get(it) ?: 0f) * 16)}" }
        }
        if (System.getenv("VT_WRITE_FIXTURE") != null) out.writeText(lines.joinToString("\n") + "\n")
        assertEquals("chạy VT_WRITE_FIXTURE=1 để sinh lại", out.readLines(), lines)
    }

    @Test fun heldoutBeforeAfter() {
        SlowTests.assume()
        val test = fixture("bigram-heldout.txt")
        val lm = SyllableLM.shared
        val b = eval(test, lm, before); val a = eval(test, lm)
        val bl = eval(test, lm, before, learn = true); val al = eval(test, lm, learn = true)
        println("TEST mới trước: ${b.fmt()}\nTEST mới sau:   ${a.fmt()}")
        println("TEST học trước: ${bl.fmt()}\nTEST học sau:   ${al.fmt()}")
        // số đo 28/09/2026: docs/DATA-SOURCES.md mục "Trigram trên thanh gợi ý"
        assertTrue(a.fmt(), a.nextTop3 - b.nextTop3 >= 0.05 * a.n && a.nextTop1 - b.nextTop1 >= 0.03 * a.n)
        assertTrue(a.fmt(), a.slot1 >= b.slot1 && a.slot3 >= b.slot3 && a.keysSaved > b.keysSaved)
        assertTrue(al.fmt(), al.nextTop3 > bl.nextTop3 && al.slot1 >= bl.slot1 && al.slot3 >= bl.slot3 &&
            al.keysSaved > bl.keysSaved)
    }

    @Test fun latency() {
        SlowTests.assume()
        val ctx = fixture("bigram-heldout.txt").flatMap { c -> (2 until c.size).map { Triple(c[it - 2], c[it - 1], SwipeSuggest.fold(c[it])) } }
        val pools = ctx.map { VNSuggest.matches(it.third, 24, it.third) }
        val m = seeded()
        // các cặp trước/sau đo XEN KẼ nhiều vòng (JIT / nhiệt độ công bằng), lấy min mỗi bên
        val bodies = listOf<() -> Unit>(
            { for (c in ctx) SuggestRank.bigramNext(c.second, 6) },
            { for (c in ctx) SuggestRank.contextNext(c.second, c.first, 6) },
            { for (i in ctx.indices) SuggestRank.inlinePmi(pools[i], ctx[i].second, prev2 = null) },
            { for (i in ctx.indices) SuggestRank.inlinePmi(pools[i], ctx[i].second, prev2 = ctx[i].first) },
            // cả lượt gợi ý như bàn phím: đang gõ (pool + PMI + xếp) / sau dấu cách (nextWords + lấp + đệm)
            { for (c in ctx) ranked(m, c.third, c.second, c.first, SyllableLM.shared, before) },
            { for (c in ctx) ranked(m, c.third, c.second, c.first, SyllableLM.shared, SuggestRank.DEFAULT) },
            { for (c in ctx) nextSlots(m, c.second, c.first, SyllableLM.shared, before) },
            { for (c in ctx) nextSlots(m, c.second, c.first, SyllableLM.shared, SuggestRank.DEFAULT) },
        )
        val best = DoubleArray(bodies.size) { Double.MAX_VALUE }
        repeat(9) { r ->
            for ((i, f) in bodies.withIndex()) {
                val t = System.nanoTime(); f()
                if (r >= 2) best[i] = minOf(best[i], (System.nanoTime() - t) / 1e3 / ctx.size)
            }
        }
        val (bi, tri, pb, pt) = best.take(4)
        val (ib, ia, nb, na) = best.drop(4)
        println(String.format(Locale.ROOT, "LAT JVM (n=%d): top từ kế tiếp bigram %.2f µs → trigram %.2f µs (×%.2f) | PMI pool %.2f → %.2f µs (×%.2f)",
            ctx.size, bi, tri, tri / bi, pb, pt, pt / pb))
        println(String.format(Locale.ROOT, "LAT JVM cả lượt: đang gõ %.2f → %.2f µs (×%.2f) | sau dấu cách %.2f → %.2f µs (×%.2f)",
            ib, ia, ia / ib, nb, na, na / nb))
        assertTrue(tri < 200 && pt < 200)
        assertTrue("đang gõ", ia < ib * 1.35)   // đo yên ≤ ×1.1; ngưỡng rộng vì nhiễu JIT / tải máy
        // seed 18/10/2026 (933 cặp, nhiều cặp cá nhân hơn): đường bigram nhanh hẳn (4,4 → 2,9 µs) còn trigram
        // 3,45 → 3,86 µs ⇒ tỉ lệ ×0,78 → ×1,36; nới 1,35 → 1,5 (tuyệt đối vẫn < 4 µs)
        assertTrue("sau dấu cách", na < nb * 1.5)
    }

    @Test fun tuneDev() {
        if (System.getenv("VT_TUNE") == null) return
        val dev = fixture("suggest-dev.txt")
        val lm = SyllableLM.shared
        println("DEV trước: " + eval(dev, lm, before).fmt() + " || học: " + eval(dev, lm, before, learn = true).fmt())
        println("DEV sau:   " + eval(dev, lm).fmt() + " || học: " + eval(dev, lm, learn = true).fmt())
        for (r in listOf(3, 6, 12, Int.MAX_VALUE)) println("DEV triOnlyRow=$r: " +
            eval(dev, lm, SuggestRank.Params(triOnlyRow = r), kss = false).fmt())
        for (lmin in listOf(1, 3)) println("DEV learnedTriMin=$lmin học: " +
            eval(dev, lm, SuggestRank.Params(learnedTriMin = lmin), learn = true, kss = false).fmt())
        for ((w, cap, d) in listOf(Triple(2.5, 8.0, 0.45), Triple(3.0, 10.0, 0.39), Triple(3.0, 12.0, 0.33), Triple(4.0, 12.0, 0.33)))
            println("DEV inline w=$w cap=$cap damp=$d: " +
                eval(dev, lm, SuggestRank.Params(bigramWeight = w, bigramCap = cap, bigramDamp = d), kss = false).fmt())
        for ((ms, mg) in listOf(7.0 to 2.0, 13.0 to 4.0, 13.0 to 6.0, 13.0 to 8.0))
            println("DEV cụm s=$ms m=$mg: " + eval(dev, lm, compound = Compound(ms, mg)).fmt())
    }
}
