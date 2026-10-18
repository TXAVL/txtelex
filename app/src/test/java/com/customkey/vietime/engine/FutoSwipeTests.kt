// MIT License — Copyright (c) 2026 Phil Trinh
// Ported from ptrinh/viettelex tests; modified for com.customkey.vietime.

package com.customkey.vietime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * FUTO Swipe (thử nghiệm): parity suy luận tự viết với forward tham chiếu python (fixture
 * chung iOS/KeyboardTests/Fixtures/futo-swipe-fixture.txt), công tắc tắt = không tải model,
 * CTC/tìm kiếm. Song sinh iOS FutoSwipeTests.swift.
 */
class FutoSwipeTests {
    @Before fun setUp() = TestAssets.install()

    class Case(val name: String, val keys: FloatArray, val raw: List<DoubleArray>?, val feats: FloatArray,
               val logem: FloatArray, val greedy: String)

    companion object {
        val layout = SwipeLayout.qwerty(keyWidth = 40f, rowHeight = 54f)

        fun fixture(): List<Case> {
            val f = listOf("src/test", "app/src/test").map { File(it, "fixtures/futo-swipe-fixture.txt") }
                .firstOrNull { it.exists() } ?: error("không thấy futo-swipe-fixture.txt")
            val out = ArrayList<Case>()
            var name = ""; var keys = FloatArray(0); var raw: List<DoubleArray>? = null
            var feats = FloatArray(0); var logem = FloatArray(0)
            fun floats(s: String) = s.split(',').map { it.toFloat() }.toFloatArray()
            for (line in f.readLines()) {
                if (line.startsWith("#") || line.isBlank()) continue
                val sp = line.indexOf(' ')
                val tag = line.substring(0, sp); val v = line.substring(sp + 1)
                when (tag) {
                    "case" -> { name = v; raw = null }
                    "keys" -> keys = floats(v)
                    "raw" -> raw = v.split(';').map { q -> q.split(',').map { it.toDouble() }.toDoubleArray() }
                    "feats" -> feats = floats(v)
                    "logem" -> logem = floats(v)
                    "greedy" -> out.add(Case(name, keys, raw, feats, logem, v))
                }
            }
            return out
        }

        fun model(): FutoSwipeModel = FutoSwipeModel.load(KeyboardData.buffer(Keys.ASSET_FUTO))!!

        fun greedy(em: FloatArray, nk: Int): String {
            val sb = StringBuilder(); var prev = -1
            for (t in 0 until FutoSwipeModel.T2) {
                var best = 0
                for (c in 1..nk) if (em[t * (nk + 1) + c] > em[t * (nk + 1) + best]) best = c
                if (best != prev && best != nk) sb.append('a' + best)
                prev = best
            }
            return sb.toString()
        }

        fun loaded(): FutoSwipe = FutoSwipe { KeyboardData.buffer(Keys.ASSET_FUTO) }.also {
            it.setLayout(layout); assertTrue(it.load())
        }
    }

    @Test fun halfToFloat() {
        assertEquals(1f, FutoSwipeModel.halfToFloat(0x3c00), 0f)
        assertEquals(-2f, FutoSwipeModel.halfToFloat(0xc000), 0f)
        assertEquals(65504f, FutoSwipeModel.halfToFloat(0x7bff), 0f)
        assertEquals(5.9604645e-8f, FutoSwipeModel.halfToFloat(0x0001), 0f)
        assertEquals(0f, FutoSwipeModel.halfToFloat(0x0000), 0f)
        assertTrue(FutoSwipeModel.halfToFloat(0x7c00).isInfinite())
    }

    @Test fun rejectsCorruptWeights() {
        val b = KeyboardData.buffer(Keys.ASSET_FUTO)
        val bytes = ByteArray(b.capacity()); b.duplicate().apply { position(0) }.get(bytes)
        assertNotNull(FutoSwipeModel.load(ByteBuffer.wrap(bytes)))
        assertNull(FutoSwipeModel.load(ByteBuffer.wrap(bytes.copyOf(bytes.size - 2))))
        val bad = bytes.copyOf(); bad[0] = 'X'.code.toByte()
        assertNull(FutoSwipeModel.load(ByteBuffer.wrap(bad)))
        val ver = bytes.copyOf(); ver[4] = 2
        assertNull(FutoSwipeModel.load(ByteBuffer.wrap(ver)))
    }

    /** Suy luận Kotlin khớp forward python (fp16 weights) trên fixture chung với Swift. */
    @Test fun fixtureParity() {
        val m = model()
        val cases = fixture()
        assertEquals(4, cases.size)
        for (c in cases) {
            val nk = c.keys.size / 2
            val out = FloatArray(FutoSwipeModel.T2 * (nk + 1))
            m.run(c.feats, c.keys, nk, out)
            // float32 vs python double: sai số tương đối nhỏ; log-prob rất âm (≈ −50) lệch nhiều hơn
            var worst = 0f; var at = 0
            for (i in out.indices) {
                val r = abs(out[i] - c.logem[i]) / (1e-2f + 1e-3f * abs(c.logem[i]))
                if (r > worst) { worst = r; at = i }
            }
            println("FUTO parity ${c.name}: max Δ=${out.indices.maxOf { abs(out[it] - c.logem[it]) }}")
            assertTrue("${c.name}: Δ=${abs(out[at] - c.logem[at])} tại ${c.logem[at]}", worst <= 1f)
            assertEquals(c.name, c.greedy, greedy(out, nk))
        }
    }

    /** Tiền xử lý (chuẩn hoá khung phím + resample theo thời gian) khớp python. */
    @Test fun preprocessingParity() {
        val fs = FutoSwipe { null }
        fs.setLayout(layout)
        for (c in fixture()) {
            val raw = c.raw ?: continue
            val nkeys = fs.normalizedKeys()
            for (i in nkeys.indices) assertEquals("${c.name} key $i", c.keys[i], nkeys[i], 1e-5f)
            val xs = FloatArray(raw.size) { raw[it][0].toFloat() }
            val ys = FloatArray(raw.size) { raw[it][1].toFloat() }
            val ts = DoubleArray(raw.size) { raw[it][2] / 1000.0 }
            val f = fs.features(xs, ys, ts, raw.size, FloatArray(128))
            for (i in 0 until 128) assertEquals("${c.name} feat $i", c.feats[i], f[i], 2e-4f)
        }
    }

    /** Công tắc tắt ⇒ không ai tạo FutoSwipe; tạo rồi mà chưa load ⇒ không đọc asset, decode = null. */
    @Test fun noLoadUntilAsked() {
        var reads = 0
        val fs = FutoSwipe { reads++; KeyboardData.buffer(Keys.ASSET_FUTO) }
        fs.setLayout(layout)
        val p = SwipeSim(1).path("khong", layout)
        assertNull(fs.decode(p, 5, null, null, null, null))
        assertEquals(0, reads)
        assertFalse(fs.isLoaded)
        assertTrue(fs.load()); assertEquals(1, reads)
        assertTrue(fs.load()); assertEquals(1, reads)
        assertNotNull(fs.decode(p, 5, null, null, null, null))
        fs.release()
        assertFalse(fs.isLoaded)
        assertNull(fs.decode(p, 5, null, null, null, null))
    }

    /**
     * Vuốt trong lúc worker đang tải weights: decode KHÔNG chờ (trước: main chặn trên swipeLock
     * suốt lúc tải) — trả null ngay để IME dùng SHARK2. Nhả giữa lúc tải ⇒ không hồi sinh model.
     */
    @Test fun decodeDoesNotWaitForLoad() {
        val started = java.util.concurrent.CountDownLatch(1)
        val gate = java.util.concurrent.CountDownLatch(1)
        val fs = FutoSwipe { started.countDown(); gate.await(); KeyboardData.buffer(Keys.ASSET_FUTO) }
        fs.setLayout(layout)
        val p = SwipeSim(1).path("khong", layout)
        var loaded: Boolean? = null
        val t = Thread { loaded = fs.load() }.apply { start() }
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        assertNull(fs.decode(p, 5, null, null, null, null))       // chưa sẵn ⇒ null ngay
        assertTrue("decode không được chờ tải", (System.nanoTime() - t0) / 1e6 < 1000)
        fs.release()                                               // nhả khi đang tải
        gate.countDown(); t.join(10_000)
        assertEquals(false, loaded)
        assertFalse(fs.isLoaded)
        assertTrue(fs.load())                                      // tải lại bình thường
        assertNotNull(fs.decode(p, 5, null, null, null, null))
    }

    @Test fun missingAssetFailsSoft() {
        val fs = FutoSwipe { null }
        fs.setLayout(layout)
        assertFalse(fs.load())
        assertNull(fs.decode(SwipeSim(1).path("anh", layout), 5, null, null, null, null))
    }

    /** CTC dùng lại tiền tố = CTC chấm riêng từng từ; tìm kiếm có cắt nhánh ra đúng top-1 âm học. */
    @Test fun searchMatchesBruteForce() {
        val fs = loaded()
        val sim = FutoSim(7)
        for (w in listOf("khong", "nguoi", "duoc", "thuong", "viet", "anh")) {
            val p = sim.path(w, layout)
            assertTrue(fs.computeEmissions(p.xs, p.ys, p.ts, p.count))
            val forms = SwipeLexicon.forms
            var best = Float.NEGATIVE_INFINITY; var bestF = ""
            for (f in 0 until forms.count) {
                val s = fs.score(forms.keys, forms.keyStart[f], forms.keyStart[f + 1])
                if (s > best) { best = s; bestF = forms.folded[f] }
            }
            fs.search(4, 1f, 0f, null, 0f)
            val top = fs.top()
            assertEquals(w, best, top[0].second, 1e-4f)
            assertEquals(w, bestF, forms.folded[top[0].first])
        }
    }

    /** Đường vuốt có nhịp thật (chậm ở phím): FUTO một mình nhận đúng các âm tiết thường. */
    @Test fun futoAloneDecodesRealisticPaths() {
        val fs = loaded()
        fs.params.mode = FutoSwipe.Mode.FUTO
        val sim = FutoSim(3, sigma = 0.0, jitter = 0.0)
        for (w in listOf("khong", "nguoi", "duoc", "thuong", "nhung", "viet", "toi", "chung")) {
            val r = fs.decode(sim.path(w, layout), 5, null, null, null, null)!!
            assertEquals(w, r.first().folded)
        }
    }
}

/**
 * Đường vuốt giả có NHỊP THỜI GIAN kiểu người (minimum-jerk mỗi đoạn: chậm dần tới phím, nhanh
 * giữa hai phím; thời lượng đoạn 120 ms + 300 ms·√(khoảng cách/bề rộng bàn phím)) — FUTO dùng
 * nhịp này (resample theo thời gian), SHARK2 thì không. Hình học như SwipeSim (tâm phím +
 * nhiễu σ, Catmull-Rom, rung) rồi qua SwipePath lọc 1/5 phím. Song sinh iOS FutoSim.
 */
class FutoSim(seed: Long, val sigma: Double = 0.25, val jitter: Double = 0.04) {
    private val rnd = SwipeSim(seed)

    fun path(word: String, layout: SwipeLayout, endOffset: Double = 0.0): SwipePath {
        val w = layout.keyWidth.toDouble()
        val keys = SwipeSim.collapse(word)
        val cx = DoubleArray(keys.length); val cy = DoubleArray(keys.length)
        for ((i, ch) in keys.withIndex()) {
            val c = layout.center(ch)!!
            cx[i] = c.first + rnd.gauss() * sigma * w
            cy[i] = c.second + rnd.gauss() * sigma * w
        }
        if (endOffset > 0) {
            var a = rnd.uniform() * 2 * Math.PI
            cx[0] += kotlin.math.cos(a) * endOffset * w; cy[0] += kotlin.math.sin(a) * endOffset * w
            a = rnd.uniform() * 2 * Math.PI
            val e = keys.length - 1
            cx[e] += kotlin.math.cos(a) * endOffset * w; cy[e] += kotlin.math.sin(a) * endOffset * w
        }
        val p = SwipePath(minDistance = layout.keyWidth / 5f)
        val m = keys.length
        if (m == 1) {
            repeat(12) { p.add((cx[0] + rnd.gauss() * jitter * w).toFloat(), (cy[0] + rnd.gauss() * jitter * w).toFloat(), it / 60.0) }
            return p
        }
        var t = 0.0
        val board = 10 * w
        for (s in 0 until m - 1) {
            val i0 = max(s - 1, 0); val i1 = s; val i2 = s + 1; val i3 = min(s + 2, m - 1)
            val d = hypot(cx[i2] - cx[i1], cy[i2] - cy[i1])
            val dur = 0.120 + 0.300 * sqrt(d / board)
            val n = max(2, ceil(dur * 60).toInt())
            for (j in 0 until n) {
                val tau = j.toDouble() / n
                val u = tau * tau * tau * (10 - 15 * tau + 6 * tau * tau)
                val x = catmull(cx[i0], cx[i1], cx[i2], cx[i3], u) + rnd.gauss() * jitter * w
                val y = catmull(cy[i0], cy[i1], cy[i2], cy[i3], u) + rnd.gauss() * jitter * w
                p.add(x.toFloat(), y.toFloat(), t); t += 1 / 60.0
            }
        }
        p.add(cx[m - 1].toFloat(), cy[m - 1].toFloat(), t, force = true)
        return p
    }

    private fun catmull(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
        val t2 = t * t; val t3 = t2 * t
        return 0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
    }
}
