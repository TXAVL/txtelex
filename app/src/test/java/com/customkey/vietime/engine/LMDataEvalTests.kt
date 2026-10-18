// MIT License — Copyright (c) 2026 Phil Trinh
// Ported from ptrinh/viettelex tests; modified for com.customkey.vietime.

package com.customkey.vietime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Dữ liệu vnlm.bin (giai đoạn 1 dựng lại với nguồn hội thoại, 18/10/2026 — docs/DATA-SOURCES.md
 * mục "Nguồn hội thoại"):
 *  - [chatCases]: Fixtures/chat-cases.txt (câu Phil + câu chat tự soạn, đầu vào ⇥ đáp án) — khoá
 *    câu của Phil + sàn số câu đúng.
 *  - [safety]: Fixtures/safety-cases.txt (câu lành có dạng không dấu trùng một từ tục/tình dục:
 *    lon → lớn/lồn, cac → các/cặc…) — Thêm dấu + gõ vuốt KHÔNG được ra từ tục khi đáp án lành.
 *  - [eval] (VT_LM_EVAL=1, in ra, không assert): mọi số đo cho lưới trọng số nguồn. VT_LM =
 *    vnlm.bin ứng viên (TestAssets), VT_CHAT_SETS = "tên=đường-dẫn;…" (chuỗi âm tiết như
 *    bigram-heldout.txt — tập dev/kiểm thử hội thoại riêng, KHÔNG commit, xem docs).
 */
class LMDataEvalTests {
    @Before fun setUp() = TestAssets.install()

    private fun fixtureDir(): File = listOf("src/test", "app/src/test").map { File(it, "fixtures") }
        .firstOrNull { it.exists() } ?: error("không thấy KeyboardTests/Fixtures")

    private fun chains(f: File): List<List<String>> =
        f.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.trim().split(' ') }

    private fun pairs(name: String): List<Pair<String, String>> = File(fixtureDir(), name).readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('\t') to it.substringAfter('\t') }

    /** Câu đáp án (có dấu, có dấu câu) → chuỗi âm tiết vnlexicon chữ thường (bỏ phần khác). */
    private fun sylChain(s: String): List<String> =
        Regex("[\\p{L}]+").findAll(s.lowercase()).map { SyllableLM.normalize(it.value) }
            .filter { SyllableLM.idOf(it) >= 0 }.toList()

    @Test fun chatCases() {
        val cases = pairs("chat-cases.txt")
        var ok = 0
        val bad = ArrayList<String>()
        for ((input, want) in cases) {
            val got = AddTones.restore(input).text
            if (got == want) ok++ else bad += "$input → $got (muốn $want)"
        }
        println("CHAT-CASES $ok/${cases.size}\n  " + bad.joinToString("\n  "))
        // câu Phil 18/10/2026 — phải đúng nguyên văn
        for ((input, want) in cases.take(6)) assertEquals(input, want, AddTones.restore(input).text)
        assertTrue("$ok/${cases.size}", ok >= cases.size * 8 / 10)
    }

    // ---- an toàn: không ra từ tục khi đáp án lành ----

    private val vulgar = setOf("lồn", "cặc", "buồi", "đĩ", "điếm", "đụ", "địt", "dâm", "cứt", "đéo", "chịch",
                               "nứng", "xoạc")
    private val vulgarFold = vulgar.map { SwipeSuggest.fold(it) }.toSet()
    private val layout = SwipeLayout.qwerty(keyWidth = 40f, rowHeight = 54f)

    data class Safety(val probes: Int, val addTones: Int, val swipe: Int, val hits: List<String> = emptyList()) {
        fun fmt() = "dò $probes vị trí | Thêm dấu ra từ tục $addTones | vuốt ra từ tục $swipe" +
            (if (hits.isEmpty()) "" else " [" + hits.take(12).joinToString("; ") + "]")
    }

    private fun safetyOn(cs: List<List<String>>): Safety {
        var probes = 0; var at = 0; var sw = 0
        val hits = ArrayList<String>()
        val d = SwipeDecoder().also { it.setLayout(layout) }
        val sim = SwipeSim(4242)
        for (c in cs) {
            if (c.none { SwipeSuggest.fold(it) in vulgarFold }) continue
            val out = AddTones.restore(c.joinToString(" ") { SwipeSuggest.fold(it) }).text.split(' ')
            for (i in c.indices) {
                if (SwipeSuggest.fold(c[i]) !in vulgarFold || c[i] in vulgar) continue
                probes++
                if (out.getOrNull(i) in vulgar) { at++; hits += "Thêm dấu: ${out.joinToString(" ")}" }
                val ctx = SwipeSuggest.context(null, c.getOrNull(i - 1), c.getOrNull(i - 2), lm = SyllableLM.shared)
                val p = sim.path(SwipeSuggest.fold(c[i]), layout)
                val w = SwipeSuggest.choose(d.decode(p, SwipeSuggest.TOP_K, ctx.folded), ctx.word,
                    lambdaFreq = ctx.lambdaFreq)?.word
                if (w in vulgar) { sw++; hits += "vuốt: ${c.getOrNull(i - 2) ?: ""} ${c.getOrNull(i - 1) ?: ""} → $w (muốn ${c[i]})" }
            }
        }
        return Safety(probes, at, sw, hits)
    }

    @Test fun safety() {
        val cs = pairs("safety-cases.txt").map { sylChain(it.second) }
        val s = safetyOn(cs)
        println("SAFETY safety-cases: ${s.fmt()}")
        assertTrue(s.fmt(), s.probes >= 40)
        // 18/10/2026: vnlm.bin cũ (chỉ Wikimedia + Tatoeba) ra từ tục 4/53 ở Thêm dấu (điếm thì,
        // bị cứt, cứt ra, trạm điếm), vuốt 0; thêm nguồn hội thoại: 0 / 0 — không được tăng lại
        assertEquals(s.fmt(), 0, s.addTones)
        assertEquals(s.fmt(), 0, s.swipe)
    }

    // ---- số đo cho lưới trọng số (in ra) ----

    data class Acc(val n: Int, val ok: Int, val sent: Int, val sentOk: Int) {
        fun fmt() = String.format(Locale.ROOT, "âm tiết %.2f câu %.2f (n=%d/%d)",
            100.0 * ok / n, 100.0 * sentOk / sent, n, sent)
    }

    private fun addTones(cs: List<List<String>>): Acc {
        var n = 0; var ok = 0; var sOk = 0
        for (c in cs) {
            val out = AddTones.restore(c.joinToString(" ") { SwipeSuggest.fold(it) }).text.split(' ')
            var all = true
            for (i in c.indices) { n++; if (out[i] == c[i]) ok++ else all = false }
            if (all) sOk++
        }
        return Acc(n, ok, cs.size, sOk)
    }

    private fun swipe(cs: List<List<String>>): String {
        val d = SwipeDecoder().also { it.setLayout(layout) }
        val sim = SwipeSim(2027)
        var n = 0; var t1 = 0; var t3 = 0
        for (chain in cs) for (i in 1 until chain.size) {
            val w = chain[i]
            val ctx = SwipeSuggest.context(null, chain[i - 1], chain.getOrNull(i - 2), lm = SyllableLM.shared)
            val c = SwipeSuggest.choose(d.decode(sim.path(SwipeSuggest.fold(w), layout), SwipeSuggest.TOP_K,
                ctx.folded), ctx.word, lambdaFreq = ctx.lambdaFreq) ?: continue
            n++
            if (c.word == w) t1++
            if (c.word == w || w in c.alternatives.take(2)) t3++
        }
        return String.format(Locale.ROOT, "vuốt top1 %.2f top3 %.2f (n=%d)", 100.0 * t1 / n, 100.0 * t3 / n, n)
    }

    /**
     * Cặp chọn tay (CuratedPairs) mà vnlm.bin TỰ giải đúng khi tắt bảng (VT_LM_EVAL=1, in ra):
     * Thêm dấu cặp/bộ ba không dấu → đúng nguyên văn; vuốt (nextOnly) không đo ở đây.
     */
    @Test fun curatedRedundancy() {
        if (System.getenv("VT_LM_EVAL") == null) return
        val off = AddTones.Params(curated = false)
        val ok = ArrayList<String>(); val no = ArrayList<String>()
        for (k in (CuratedPairs.pairs + CuratedPairs.triples).sorted()) {
            val got = AddTones.restore(k.split(' ').joinToString(" ") { SwipeSuggest.fold(it) }, null, off).text
            val tail = k.split(' ').takeLast(2).joinToString(" ")
            if (got.endsWith(tail)) ok += k else no += "$k (→ $got)"
        }
        println("CURATED tự đúng khi tắt bảng ${ok.size}: " + ok.joinToString(" | "))
        println("CURATED vẫn cần bảng ${no.size}: " + no.joinToString(" | "))
    }

    /** Lỗi từng từ trên câu trộn Việt–Anh của SyllableLMTests.mixedSentencesKeepEnglish (VT_LM_EVAL=1). */
    @Test fun mixedDump() {
        if (System.getenv("VT_LM_EVAL") == null) return
        val sentences = listOf("mình đang check mail", "gửi file cho anh", "em gửi email cho sếp", "tôi nay có meeting không",
            "share link cho mình", "deadline là thứ sáu", "anh ấy đang online", "cho mình xin link nhé").map { it.split(' ') }
        val d = SwipeDecoder().also { it.setLayout(layout) }; val sim = SwipeSim(99)
        val errs = HashMap<String, Int>()
        repeat(10) {
            for (s in sentences) for (i in 1 until s.size) {
                val prior = SwipeLangContext.prior(SwipeLangContext.classify(s[i - 1]), SwipeLangContext.classify(s.getOrNull(i - 2)))
                val ctx = SwipeSuggest.context(null, s[i - 1], s.getOrNull(i - 2), prior, lm = SyllableLM.shared)
                val c = SwipeSuggest.choose(d.decode(sim.path(SwipeSuggest.fold(s[i]), layout), SwipeSuggest.TOP_K,
                    ctx.folded, ctx.english, ctx.englishWord), ctx.word, lambdaFreq = ctx.lambdaFreq) ?: continue
                if (c.word != s[i]) errs.merge("${s[i - 1]} ${s[i]} → ${c.word}", 1, Int::plus)
            }
        }
        println("MIXED lỗi ${errs.values.sum()}: " + errs.entries.sortedByDescending { it.value }.joinToString(" | ") { "${it.key} ×${it.value}" })
    }

    @Test fun eval() {
        if (System.getenv("VT_LM_EVAL") == null) return
        val lm = SyllableLM.shared!!
        println("LMEVAL file ${System.getenv("VT_LM") ?: "asset"}: ${lm.biEntries} bigram, ${lm.triEntries} trigram, " +
            "${KeyboardData.buffer(Keys.ASSET_LM).capacity()} byte")
        val sets = LinkedHashMap<String, List<List<String>>>()
        sets["dev"] = chains(File(fixtureDir(), "suggest-dev.txt"))
        System.getenv("VT_CHAT_SETS")?.split(';')?.filter { it.isNotBlank() }?.forEach {
            sets[it.substringBefore('=')] = chains(File(it.substringAfter('=')))
        }
        if (System.getenv("VT_LM_TEST") != null) sets["heldout"] = chains(File(fixtureDir(), "bigram-heldout.txt"))
        val st = SuggestTrigramTests()
        val maxSuggest = System.getenv("VT_SUGGEST_MAX")?.toInt() ?: 4000
        for ((name, cs) in sets) {
            println("LMEVAL $name addtones ${addTones(cs).fmt()}")
            if (System.getenv("VT_NO_SUGGEST") == null)
                println("LMEVAL $name suggest " + st.eval(cs.take(maxSuggest), lm).fmt())
            if (System.getenv("VT_SWIPE") != null) println("LMEVAL $name ${swipe(cs.take(maxSuggest))}")
            println("LMEVAL $name safety ${safetyOn(cs).fmt()}")
        }
        val cases = pairs("chat-cases.txt")
        val ok = cases.count { AddTones.restore(it.first).text == it.second }
        println("LMEVAL chat-cases $ok/${cases.size}")
        for ((i, o) in cases.take(6)) println("LMEVAL phil $i → ${AddTones.restore(i).text}")
        println("LMEVAL safety-cases ${safetyOn(pairs("safety-cases.txt").map { sylChain(it.second) }).fmt()}")
    }
}
