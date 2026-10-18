// MIT License — Copyright (c) 2026 Phil Trinh
// Ported from ptrinh/viettelex tests; modified for com.customkey.vietime.

package com.customkey.vietime.engine

// BẢN SAO NGUYÊN VẸN UserLangModel TRƯỚC khi đổi sang bảng gọn (intern id + file VTL2). Chỉ dùng
// trong test: property test so đầu ra cũ ≡ mới, ghi file v1 kiểu cũ cho test chuyển đổi, mốc "trước"
// khi đo RAM/độ trễ. KHÔNG sửa logic ở đây.

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.pow

/**
 * Datastore cá nhân hoá cho thanh gợi ý (port 1:1 iOS UserLangModel): uni / bi
 * (prev → next → count) / tri ("p2␁p1" → next → count), shrinkage, learning-vs-
 * suggesting (từ lạ cần count ≥3), decay ×0.7/tuần, cap halve-when-full.
 *
 * THREADING: mọi đọc/ghi bảng trên MAIN thread (như iOS). Decode/encode/IO trên [io];
 * kết quả load trả về qua [main]. Persist binary `filesDir/userlm.bin`, ghi atomic
 * (file tạm + rename), coalesce 5 s sau record cuối.
 *
 * PRIVACY: chỉ đếm tần suất.
 */
class LegacyUserLangModel(
    /** null = in-memory (tests): sẵn sàng ngay, không IO. Có file ⇒ load nền ngay. */
    private val file: File? = null,
    private val main: MainThread = ImmediateMainThread(),
    ioExecutor: Executor? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val ioLazy = lazy {
        ioExecutor ?: Executors.newSingleThreadExecutor { r ->
            Thread(r, "vt-userlm-legacy-io").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }
    private val io: Executor by ioLazy
    private val ownsIo = ioExecutor == null
    private var closed = false

    private var uni = HashMap<String, Int>()
    private var bi = HashMap<String, HashMap<String, Int>>()
    private var tri = HashMap<String, HashMap<String, Int>>()
    private var lastDecay = clock()
    private var biPairs = 0
    private var triPairs = 0
    private var uniTotal = 0
    /** Từ user tự thêm (màn Từ điển cá nhân): key lowercase → dạng hiển thị ("VietTelex"). */
    private var manual = LinkedHashMap<String, String>()

    /** Lexicon tĩnh (VNSuggest.contains). Mặc định false để test kiểm soát. */
    var isKnownWord: (String) -> Boolean = { false }
        set(v) { field = v; knownCache.clear(); topCache = null }

    /** Gọi trên main khi bảng trên đĩa swap-in xong (IME refresh bar). */
    var onReady: (() -> Unit)? = null

    private var saveWork: Cancellable? = null
    private var isLoaded = false
    private var loadGeneration = 0
    internal class PendingRecord(val word: String, val prev1: String?, val prev2: String?, val weight: Int) {
        /** Biên nhận khi đã phát lại sau lúc nạp xong (để [retract] vẫn rút được). */
        var replayed: Learned? = null
    }

    /**
     * Biên nhận một lần [record]: đúng những gì đã cộng (uni / bi / tri) để [retract] trừ
     * lại chính xác — dùng khi ⌫ mở lại từ vừa chốt.
     */
    class Learned internal constructor(
        internal val word: String?, internal val prev1: String?, internal val triKey: String?,
        internal val weight: Int, internal val pending: PendingRecord?,
    )
    private val pendingRecords = ArrayList<PendingRecord>()
    private var pendingSeed: (() -> SeedData.Seed)? = null
    /** Nguồn seed gần nhất — để [reloadAfterExternalErase] seed lại. */
    private var seedSource: (() -> SeedData.Seed)? = null

    private val knownCache = HashMap<String, Boolean>()
    private var topCacheK = 0
    private var topCache: List<String>? = null
    /** Khoá (chữ thường) của [topCache], cùng thứ tự — để [bumpTop] cập nhật tăng dần. */
    private var topKeys: ArrayList<String>? = null

    companion object {
        const val UNI_CAP = 3000
        const val BI_CAP = 6000
        const val TRI_CAP = 3000
        const val UNKNOWN_SUGGEST_THRESHOLD = 3
        const val PENDING_RECORD_CAP = 128
        const val SEP = "\u0001"
        private const val MAGIC = 0x56544C4D // "VTLM"
        private const val VERSION = 1
        private const val WEEK_MS = 7L * 86_400_000L
        /** Đuôi tuỳ chọn sau bảng tri: danh sách từ thêm tay (bản cũ đọc bỏ qua đuôi). */
        private const val MANUAL_MAGIC = 0x4D414E55 // "MANU"
        /** Count khởi điểm của từ thêm tay: ≥ ngưỡng gợi ý, xếp trên từ học 1-2 lần. */
        const val MANUAL_COUNT = 5
        const val MANUAL_MAX_LEN = 24

        /**
         * Chuẩn hoá từ user gõ vào màn Từ điển cá nhân: bỏ khoảng trắng đầu/cuối, chỉ chữ
         * cái (tên riêng, thuật ngữ — một "từ" không dấu cách), 1..24 ký tự. null = không hợp lệ.
         */
        fun normalizeManual(raw: String): String? {
            val w = java.text.Normalizer.normalize(raw.trim(), java.text.Normalizer.Form.NFC)
            if (w.isEmpty()) return null
            var n = 0; var i = 0
            while (i < w.length) {
                val c = w.codePointAt(i)
                if (!Character.isLetter(c)) return null
                n++; i += Character.charCount(c)
            }
            return if (n <= MANUAL_MAX_LEN) w else null
        }

        /** Bỏ dấu + lowercase + đ→d — so tiền tố "kube"/"phuc" với từ thêm tay. */
        fun fold(s: String): String {
            val d = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            val sb = StringBuilder(d.length)
            for (ch in d) {
                if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
                sb.append(if (ch == 'đ') 'd' else ch)
            }
            return sb.toString()
        }

        private fun readTablesFile(f: File): Tables? {
            if (!f.exists()) return null
            return try {
                DataInputStream(BufferedInputStream(FileInputStream(f), 32 * 1024)).use { d ->
                    if (d.readInt() != MAGIC) return null
                    if (d.readInt() != VERSION) return null
                    val lastDecay = d.readLong()
                    val nu = d.readInt()
                    val uni = HashMap<String, Int>(nu * 2)
                    repeat(nu) { uni[d.readUTF()] = d.readInt() }
                    fun nested(): HashMap<String, HashMap<String, Int>> {
                        val n = d.readInt()
                        val m = HashMap<String, HashMap<String, Int>>(n * 2)
                        repeat(n) {
                            val k = d.readUTF(); val c = d.readInt()
                            val inner = HashMap<String, Int>(c * 2)
                            repeat(c) { inner[d.readUTF()] = d.readInt() }
                            m[k] = inner
                        }
                        return m
                    }
                    val bi = nested(); val tri = nested()
                    // Đuôi tuỳ chọn: từ thêm tay (file cũ không có ⇒ EOF ⇒ rỗng).
                    val manual = LinkedHashMap<String, String>()
                    try {
                        if (d.readInt() == MANUAL_MAGIC) {
                            repeat(d.readInt()) { val disp = d.readUTF(); manual[disp.lowercase()] = disp }
                        }
                    } catch (_: java.io.EOFException) { }
                    Tables(uni, bi, tri, lastDecay, manual)
                }
            } catch (e: Exception) { null }
        }

        private fun writeFile(uni: Map<String, Int>, bi: Map<String, Map<String, Int>>,
                              tri: Map<String, Map<String, Int>>, lastDecay: Long, f: File,
                              manual: Collection<String> = emptyList()) {
            val tmp = File(f.parentFile, f.name + ".tmp")
            try {
                DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 32 * 1024)).use { d ->
                    d.writeInt(MAGIC); d.writeInt(VERSION); d.writeLong(lastDecay)
                    d.writeInt(uni.size)
                    for ((k, v) in uni) { d.writeUTF(k); d.writeInt(v) }
                    for (m in listOf(bi, tri)) {
                        d.writeInt(m.size)
                        for ((k, inner) in m) {
                            d.writeUTF(k); d.writeInt(inner.size)
                            for ((w, c) in inner) { d.writeUTF(w); d.writeInt(c) }
                        }
                    }
                    if (manual.isNotEmpty()) {
                        d.writeInt(MANUAL_MAGIC); d.writeInt(manual.size)
                        for (m in manual) d.writeUTF(m)
                    }
                }
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            } catch (_: Exception) { tmp.delete() }
        }

        /** Sao lưu: đọc userlm.bin thành [LearnedWords]; null nếu vắng/hỏng. */
        fun readLearned(f: File): LearnedWords? =
            readTablesFile(f)?.let { LearnedWords(it.uni, it.bi, it.tri, it.manual.values.toList()) }

        /**
         * Nhập sao lưu: gộp [lw] vào file (count lớn hơn, giữ lastDecay). Gọi TRƯỚC khi
         * báo IME nạp lại ([reloadAfterExternalErase] qua pref userlmResetAt).
         */
        fun mergeLearnedIntoFile(lw: LearnedWords, f: File, now: Long = System.currentTimeMillis()) {
            val cur = readTablesFile(f)
            val merged = (cur?.let { LearnedWords(it.uni, it.bi, it.tri, it.manual.values.toList()) }
                ?: LearnedWords()).merged(lw)
            // Từ thêm tay luôn có mặt trong uni (như keepManual của model).
            val uni = HashMap(merged.uni).apply { for (m in merged.manual) merge(m.lowercase(), 1) { a, _ -> a } }
            writeFile(uni, merged.bi, merged.tri, cur?.lastDecay ?: now, f, merged.manual)
        }

        /** Từ "học được": chữ cái thuần, ≤12, không chuỗi lặp ≥3 ("heeeyyy"). */
        fun learnable(w: String): Boolean {
            if (w.isEmpty()) return false
            var n = 0; var run = 1; var prev = -1
            var i = 0
            while (i < w.length) {
                val c = w.codePointAt(i)
                if (!Character.isLetter(c)) return false
                run = if (c == prev) run + 1 else 1
                if (run >= 3) return false
                prev = c; n++
                i += Character.charCount(c)
            }
            return n <= 12
        }
    }

    init {
        if (file != null) loadAsync(file) else isLoaded = true
    }

    private fun loadAsync(f: File) {
        if (closed) return
        val gen = loadGeneration
        io.execute {
            val t = readTables(f)
            main.post { if (loadGeneration == gen) finishLoad(t) }
        }
    }

    private class Tables(val uni: HashMap<String, Int>, val bi: HashMap<String, HashMap<String, Int>>,
                         val tri: HashMap<String, HashMap<String, Int>>, val lastDecay: Long,
                         val manual: LinkedHashMap<String, String>)

    private fun finishLoad(t: Tables?) {
        if (t != null) {
            uni = t.uni; bi = t.bi; tri = t.tri; lastDecay = t.lastDecay; manual = t.manual
            keepManual()
            recount()
        }
        isLoaded = true
        topCache = null
        val seed = pendingSeed
        if (uni.isEmpty() && seed != null) applySeed(seed())
        pendingSeed = null
        val queued = pendingRecords.toList()
        pendingRecords.clear()
        for (r in queued) r.replayed = record(r.word, r.prev1, r.prev2, r.weight)
        decayIfDue()
        onReady?.invoke()
    }

    private fun recount() {
        biPairs = bi.values.sumOf { it.size }
        triPairs = tri.values.sumOf { it.size }
        uniTotal = uni.values.sum()
    }

    // MARK: học

    /** Một từ vừa chốt; weight 1 gõ thường, 2 khi bấm nhận gợi ý. */
    fun record(word: String, after: String?, prev2: String? = null, weight: Int = 1): Learned? {
        if (!isLoaded) {
            if (pendingRecords.size >= PENDING_RECORD_CAP) return null
            val pr = PendingRecord(word, after, prev2, weight)
            pendingRecords.add(pr)
            return Learned(null, null, null, weight, pr)
        }
        if (!learnable(word)) return null
        val w = word.lowercase()
        uni[w] = (uni[w] ?: 0) + weight
        uniTotal += weight
        bumpTop(w)
        var biKey: String? = null
        var triKey: String? = null
        val p1 = after?.lowercase()
        if (p1 != null && learnable(p1)) {
            val b = bi.getOrPut(p1) { HashMap() }
            if (b[w] == null) biPairs++
            b[w] = (b[w] ?: 0) + weight
            biKey = p1
            val p2 = prev2?.lowercase()
            if (p2 != null && learnable(p2) && (bi[p2]?.get(p1) ?: 0) >= 2) {
                val k = p2 + SEP + p1
                val t = tri.getOrPut(k) { HashMap() }
                if (t[w] == null) triPairs++
                t[w] = (t[w] ?: 0) + weight
                triKey = k
            }
        }
        pruneIfNeeded()
        scheduleSave()
        return Learned(w, biKey, triKey, weight, null)
    }

    /** Rút lại một lần [record] (từ vừa chốt được mở lại để sửa). Không âm, bỏ mục về 0. */
    fun retract(l: Learned) {
        l.pending?.let { p ->
            if (!pendingRecords.remove(p)) p.replayed?.let { retract(it) }
            return
        }
        val w = l.word ?: return
        val had = uni[w] ?: return
        val dec = minOf(had, l.weight)
        if (had <= l.weight) uni.remove(w) else uni[w] = had - l.weight
        uniTotal -= dec
        topCache = null
        l.prev1?.let { k -> if (decNested(bi, k, w, l.weight)) biPairs-- }
        l.triKey?.let { k -> if (decNested(tri, k, w, l.weight)) triPairs-- }
        scheduleSave()
    }

    /** Trừ [m][[k]][[w]]; true nếu mục bị bỏ hẳn. */
    private fun decNested(m: HashMap<String, HashMap<String, Int>>, k: String, w: String, by: Int): Boolean {
        val inner = m[k] ?: return false
        val c = inner[w] ?: return false
        if (c > by) { inner[w] = c - by; return false }
        inner.remove(w)
        if (inner.isEmpty()) m.remove(k)
        return true
    }

    private fun cachedKnown(w: String): Boolean {
        knownCache[w]?.let { return it }
        if (knownCache.size > 4096) knownCache.clear()
        val v = isKnownWord(w)
        knownCache[w] = v
        return v
    }

    private fun suggestable(w: String) =
        cachedKnown(w) || w in manual || (uni[w] ?: 0) >= UNKNOWN_SUGGEST_THRESHOLD

    // MARK: từ điển cá nhân (màn quản lý trong app)

    /** Một dòng của màn Từ điển cá nhân. */
    data class Entry(val word: String, val count: Int, val manual: Boolean)

    /**
     * Mọi từ đã học + từ thêm tay, tần suất giảm dần (hoà: theo chữ cái). [query] ≠ rỗng:
     * chỉ từ CHỨA chuỗi tìm (bỏ dấu, không phân biệt hoa/thường — "viet" khớp "việt").
     */
    fun entries(query: String = ""): List<Entry> {
        val q = fold(query.trim())
        return uni.entries.asSequence()
            .filter { q.isEmpty() || fold(it.key).contains(q) }
            .map { (k, c) -> Entry(manual[k] ?: k, c, k in manual) }
            .sortedWith(compareByDescending<Entry> { it.count }.thenBy { it.word.lowercase() })
            .toList()
    }

    val isManualEmpty: Boolean get() = manual.isEmpty()

    /**
     * Xoá hẳn một từ: unigram, mọi bigram/trigram có từ đó (ở vị trí trước hay sau), và
     * dấu "thêm tay". true nếu có gì bị xoá.
     */
    fun removeWord(word: String): Boolean {
        val w = word.lowercase()
        var changed = manual.remove(w) != null
        uni.remove(w)?.let { changed = true }
        if (bi.remove(w) != null) changed = true
        val biIt = bi.entries.iterator()
        while (biIt.hasNext()) {
            val e = biIt.next()
            if (e.value.remove(w) != null) changed = true
            if (e.value.isEmpty()) biIt.remove()
        }
        val triIt = tri.entries.iterator()
        while (triIt.hasNext()) {
            val e = triIt.next()
            val parts = e.key.split(SEP)
            if (w in parts) { triIt.remove(); changed = true; continue }
            if (e.value.remove(w) != null) changed = true
            if (e.value.isEmpty()) triIt.remove()
        }
        if (!changed) return false
        recount()
        topCache = null
        knownCache.remove(w)
        scheduleSave()
        return true
    }

    /**
     * Thêm tay một từ (tên riêng, thuật ngữ) — được gợi ý ngay (không cần đạt ngưỡng
     * từ lạ), hiện ở hoàn thiện từ khi gõ tiền tố, không bị decay/prune xoá. false nếu
     * không hợp lệ ([normalizeManual]).
     */
    fun addWord(raw: String): Boolean {
        val display = normalizeManual(raw) ?: return false
        val w = display.lowercase()
        manual[w] = display
        val had = uni[w] ?: 0
        if (had < MANUAL_COUNT) { uni[w] = MANUAL_COUNT; uniTotal += MANUAL_COUNT - had }
        topCache = null
        scheduleSave()
        return true
    }

    /**
     * Từ thêm tay khớp tiền tố đang gõ (bỏ dấu, không phân biệt hoa/thường), dạng hiển
     * thị. Bỏ từ trùng hệt chuỗi đang gõ.
     */
    fun manualCompletions(prefix: String, limit: Int = 2): List<String> {
        if (manual.isEmpty() || prefix.isEmpty()) return emptyList()
        val f = fold(prefix)
        return manual.entries.asSequence()
            .filter { (k, d) -> d != prefix && fold(k).startsWith(f) }
            .sortedByDescending { uni[it.key] ?: 0 }
            .map { it.value }.take(limit).toList()
    }

    /** Từ thêm tay luôn còn trong uni (decay/prune không được xoá). */
    private fun keepManual() {
        for (k in manual.keys) if ((uni[k] ?: 0) < 1) uni[k] = 1
    }

    // MARK: gợi ý

    /** Top từ hay dùng (ô trống). Cache tới khi uni đổi. */
    fun topWords(limit: Int): List<String> {
        topCache?.let { if (topCacheK >= limit) return it.take(limit) }
        val sorted = uni.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val out = ArrayList<String>(limit)
        val keys = ArrayList<String>(limit + 1)
        for ((w, _) in sorted) {
            if (!suggestable(w)) continue
            out.add(manual[w] ?: w); keys.add(w)
            if (out.size >= limit) break
        }
        topCache = out; topCacheK = limit; topKeys = keys
        return out
    }

    /**
     * [record] vừa TĂNG uni[w]: cập nhật top-K tại chỗ thay vì bỏ cache (trước đây mỗi dấu cách
     * sắp xếp lại cả ≤3000 mục trên main thread ở lượt gợi ý kế). Đếm chỉ tăng ⇒ tập top-K chỉ
     * có thể thêm đúng [w]; thứ tự như [topWords] (đếm giảm dần, rồi khoá tăng dần).
     */
    private fun bumpTop(w: String) {
        val keys = topKeys ?: run { topCache = null; return }
        if (topCache == null) return
        val had = keys.remove(w)
        if (!suggestable(w)) { if (had) topCache = null; return }
        val c = uni[w] ?: 0
        var i = 0
        while (i < keys.size) {
            val o = keys[i]; val oc = uni[o] ?: 0
            if (oc < c || (oc == c && o > w)) break
            i++
        }
        if (i >= topCacheK) { if (had) topCache = null; return }   // (không xảy ra khi đếm chỉ tăng)
        keys.add(i, w)
        while (keys.size > topCacheK) keys.removeAt(keys.size - 1)
        topCache = keys.map { manual[it] ?: it }
    }

    /** Từ kế tiếp sau (prev2, prev1): tri ⊕ bi ⊕ uni ⊕ seed với shrinkage. */
    fun nextWords(after: String, prev2: String? = null, limit: Int): List<String> {
        val p1 = after.lowercase()
        val biBucket: Map<String, Int> = bi[p1] ?: emptyMap()
        val triBucket: Map<String, Int> = prev2?.let { tri[it.lowercase() + SEP + p1] } ?: emptyMap()
        val seeds = seedNext[p1] ?: emptyList()
        val biTotal = biBucket.values.sum()
        val triTotal = triBucket.values.sum()
        val uniT = maxOf(uniTotal, 1)
        val lamTri = triTotal.toDouble() / (triTotal + 2.0)
        val lamBi = biTotal.toDouble() / (biTotal + 4.0)
        val cands = LinkedHashSet<String>()
        cands.addAll(biBucket.keys); cands.addAll(triBucket.keys); cands.addAll(seeds)
        fun score(w: String): Double {
            val pTri = if (triTotal > 0) (triBucket[w] ?: 0).toDouble() / triTotal else 0.0
            val pBi = if (biTotal > 0) (biBucket[w] ?: 0).toDouble() / biTotal else 0.0
            val pUni = (uni[w] ?: 0).toDouble() / uniT
            val i = seeds.indexOf(w)
            val pSeed = if (i >= 0) 0.5.pow(i) else 0.0
            return lamTri * pTri + lamBi * pBi + 0.1 * pUni + (1 - lamBi) * 0.9 * pSeed
        }
        return cands.filter { suggestable(it) || it in seeds }
            .map { it to score(it) }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
            .take(limit).map { manual[it.first] ?: it.first }
    }

    /** Điểm cá nhân của một từ. */
    fun count(of: String): Int = uni[of] ?: uni[of.lowercase()] ?: 0

    /** Từ của người dùng: thêm tay, hoặc đã gõ đủ nhiều để được gợi ý — tự sửa không đụng. */
    fun isUserWord(w: String): Boolean {
        val k = w.lowercase()
        return k in manual || (uni[k] ?: 0) >= UNKNOWN_SUGGEST_THRESHOLD
    }

    /** Số lần cặp (prev → word) đã gặp (bigram cá nhân + seed) — AddTones chấm lưới âm tiết. */
    fun bigramCount(prev: String, word: String): Int = bi[prev.lowercase()]?.get(word) ?: 0

    /** Số lần bộ ba (prev2, prev → word) người dùng đã gõ (không có seed) — thanh gợi ý ưu tiên. */
    fun trigramCount(prev2: String, prev: String, word: String): Int =
        tri[prev2.lowercase() + SEP + prev.lowercase()]?.let { it[word] ?: it[word.lowercase()] } ?: 0

    /**
     * Seed khi store trống. [seed] chỉ được gọi khi thật sự seed; đang chờ load thì
     * giữ lại quyết sau swap-in.
     */
    fun seedIfEmpty(seed: () -> SeedData.Seed = SeedData::load) {
        seedSource = seed
        if (!isLoaded) { pendingSeed = seed; return }
        if (uni.isNotEmpty()) return
        applySeed(seed())
    }

    /** Tiện cho test: seed từ map/list trực tiếp. */
    fun seedIfEmpty(unigrams: Map<String, Int>, bigrams: List<Triple<String, String, Int>>) =
        seedIfEmpty { SeedData.Seed(unigrams, bigrams) }

    private fun applySeed(s: SeedData.Seed) {
        uni = HashMap(s.unigrams)
        uniTotal = uni.values.sum()
        for ((a, b, c) in s.bigrams) {
            val m = bi.getOrPut(a) { HashMap() }
            if (m[b] == null) biPairs++
            m[b] = c
        }
        topCache = null
        save()
    }

    // MARK: persistence

    private fun readTables(f: File): Tables? = readTablesFile(f)

    private class Snapshot(val uni: Map<String, Int>, val bi: Map<String, Map<String, Int>>,
                           val tri: Map<String, Map<String, Int>>, val lastDecay: Long,
                           val manual: List<String>)

    private fun snapshot(): Snapshot {
        // copy nông trên main (rẻ so với encode + IO) — nền encode bản copy
        return Snapshot(HashMap(uni), bi.mapValuesTo(HashMap()) { HashMap(it.value) },
            tri.mapValuesTo(HashMap()) { HashMap(it.value) }, lastDecay, manual.values.toList())
    }

    private fun write(s: Snapshot, f: File, gen: Int) {
        if (gen != loadGenerationSnapshot()) return   // đã bị erase/reload: không ghi đè
        writeFile(s.uni, s.bi, s.tri, s.lastDecay, f, s.manual)
    }

    @Volatile private var volatileGen = 0
    private fun loadGenerationSnapshot() = volatileGen

    /** Encode + ghi atomic trên io. */
    fun save() {
        saveWork?.cancel(); saveWork = null
        val f = file
        if (!isLoaded || f == null) return
        val snap = snapshot()
        val gen = volatileGen
        io.execute { write(snap, f, gen) }
    }

    /** Flush khi bàn phím ẩn: chỉ ghi nếu có thay đổi chờ; giữ FIFO với save(). */
    fun saveNow() {
        if (saveWork == null) return
        save()
    }

    private fun scheduleSave() {
        saveWork?.cancel()
        saveWork = main.postDelayed(TypingTimings.USERLM_SAVE_COALESCE_MS, Runnable { saveWork = null; save() })
    }

    /** Có thay đổi đang chờ ghi (test/IME). */
    val hasPendingSave: Boolean get() = saveWork != null

    /**
     * Service IME bị huỷ (đổi bàn phím): ghi thay đổi đang chờ, bỏ bảng trong RAM (vỏ service
     * cũ có thể bị framework giữ lâu — đừng kéo theo 0.2–1.5 MB bảng), dừng luồng IO sau khi
     * ghi xong. Sau đó model không ghi/nạp nữa (không bao giờ ghi bảng rỗng đè file).
     */
    fun close() {
        if (closed) return
        saveNow()
        closed = true
        isLoaded = false
        loadGeneration++
        saveWork?.cancel(); saveWork = null
        uni = HashMap(); bi = HashMap(); tri = HashMap(); manual = LinkedHashMap()
        biPairs = 0; triPairs = 0; uniTotal = 0
        pendingRecords.clear(); pendingSeed = null; seedSource = null
        topCache = null; topKeys = null
        knownCache.clear()
        onReady = null
        if (ownsIo && ioLazy.isInitialized()) (ioLazy.value as? java.util.concurrent.ExecutorService)?.shutdown()
    }

    fun eraseAll() {
        dropInMemory()
        isLoaded = true
        val f = file
        if (f != null) { if (closed) f.delete() else io.execute { f.delete() } }
    }

    private fun dropInMemory() {
        loadGeneration++
        volatileGen++
        saveWork?.cancel(); saveWork = null
        uni = HashMap(); bi = HashMap(); tri = HashMap(); manual = LinkedHashMap()
        biPairs = 0; triPairs = 0; uniTotal = 0
        pendingRecords.clear(); pendingSeed = null
        topCache = null
        knownCache.clear()
    }

    /**
     * App đã xoá userlm.bin ("Xóa từ đã học", pref userlmResetAt đổi): bỏ bảng trong RAM,
     * huỷ ghi chờ (không bao giờ ghi lại dữ liệu cũ), load lại file (vắng ⇒ seed lại).
     */
    fun reloadAfterExternalErase() {
        dropInMemory()
        val f = file
        if (f == null) {
            isLoaded = true
            seedSource?.let { applySeed(it()) }
            return
        }
        isLoaded = false
        pendingSeed = seedSource
        loadAsync(f)
    }

    // MARK: decay / prune

    /** Mỗi ≥7 ngày: count ×0.7^tuần (một timestamp toàn cục). */
    internal fun decayIfDue(now: Long = clock()) {
        val weeks = ((now - lastDecay) / WEEK_MS).toInt()
        if (weeks < 1) return
        val f = 0.7.pow(weeks)
        fun decayMap(m: Map<String, Int>): HashMap<String, Int> {
            val out = HashMap<String, Int>()
            for ((k, c) in m) { val v = (c * f).toInt(); if (v > 0) out[k] = v }
            return out
        }
        uni = decayMap(uni)
        bi = nestedMap(bi) { decayMap(it) }
        tri = nestedMap(tri) { decayMap(it) }
        keepManual()
        recount()
        lastDecay = now
        topCache = null
        save()
    }

    /** Test: giả lập lần decay cuối. */
    internal fun setLastDecayForTest(t: Long) { lastDecay = t }

    private fun nestedMap(m: Map<String, HashMap<String, Int>>,
                          f: (Map<String, Int>) -> HashMap<String, Int>): HashMap<String, HashMap<String, Int>> {
        val out = HashMap<String, HashMap<String, Int>>()
        for ((k, v) in m) { val d = f(v); if (d.isNotEmpty()) out[k] = d }
        return out
    }

    private fun halve(m: Map<String, Int>): HashMap<String, Int> {
        val out = HashMap<String, Int>()
        for ((k, c) in m) if (c / 2 != 0) out[k] = c / 2
        return out
    }

    private fun pruneIfNeeded() {
        if (uni.size > UNI_CAP) { uni = halve(uni); keepManual(); uniTotal = uni.values.sum(); topCache = null }
        if (biPairs > BI_CAP) {
            bi = nestedMap(bi) { halve(it) }
            biPairs = bi.values.sumOf { it.size }
        }
        if (triPairs > TRI_CAP) {
            tri = nestedMap(tri) { halve(it) }
            triPairs = tri.values.sumOf { it.size }
        }
    }

    internal val uniSize: Int get() = uni.size

    // seed bigrams tĩnh
    val seedNext: Map<String, List<String>> get() = SEED_NEXT
}

private val SEED_NEXT: Map<String, List<String>> = hashMapOf(
    "anh" to listOf("ơi", "đang", "có", "yêu"),
    "em" to listOf("ơi", "yêu", "đang", "nhé"),
    "chị" to listOf("ơi", "đang", "có"),
    "mẹ" to listOf("ơi", "đang", "có"),
    "bạn" to listOf("ơi", "có", "đang"),
    "mình" to listOf("đang", "có", "sẽ", "nghĩ"),
    "tôi" to listOf("đang", "có", "sẽ", "nghĩ"),
    "cảm" to listOf("ơn"),
    "xin" to listOf("chào", "lỗi", "phép"),
    "chúc" to listOf("mừng", "ngủ", "sức"),
    "không" to listOf("có", "phải", "biết", "sao"),
    "rất" to listOf("vui", "đẹp", "ngon", "tốt"),
    "hôm" to listOf("nay", "qua"),
    "ngày" to listOf("mai", "mới", "nào"),
    "buổi" to listOf("sáng", "trưa", "chiều", "tối"),
    "đi" to listOf("làm", "học", "chơi", "ăn"),
    "ăn" to listOf("cơm", "sáng", "trưa", "tối"),
    "đang" to listOf("làm", "ăn", "đi", "ở"),
    "có" to listOf("khỏe", "thể", "gì", "ai"),
    "làm" to listOf("gì", "việc", "sao"),
    "yêu" to listOf("em", "anh", "quá"),
    "ngủ" to listOf("ngon", "sớm", "dậy"),
    "tạm" to listOf("biệt"),
    "hẹn" to listOf("gặp"),
    "gặp" to listOf("lại", "nhau"),
    "vui" to listOf("quá", "lắm", "vẻ"),
    "được" to listOf("không", "rồi", "chưa"),
    "nhớ" to listOf("em", "anh", "nhé"),
)
