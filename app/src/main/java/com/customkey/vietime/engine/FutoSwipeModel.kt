// MIT License — Copyright (c) 2026 Phil Trinh
// Extracted from ptrinh/viettelex (https://github.com/ptrinh/viettelex), see assets/licenses/viettelex-LICENSE.txt.
// Modified for com.customkey.vietime.

package com.customkey.vietime.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * FutoSwipeModel.kt — SUY LUẬN TỰ VIẾT cho encoder FUTO Swipe ("honorable_sturgeon",
 * TCN ~634K tham số, https://huggingface.co/futo-org/futo-swipe — weights theo FUTO Model
 * Weights License 1.0, powered by FUTO Swipe; xem docs/DATA-SOURCES.md). KHÔNG dùng code
 * GPL của FUTO: kiến trúc đọc từ đồ thị .pte (Scripts/futo-swipe/), số học viết lại ở đây.
 * Bản Swift song sinh: iOS/Keyboard/FutoSwipeModel.swift — sửa ở đây thì sửa y hệt bên kia
 * (test parity chung fixture iOS/KeyboardTests/Fixtures/futo-swipe-fixture.txt).
 *
 * Đầu vào: đường vuốt resample 64 điểm (x hàng rồi y hàng, toạ độ vùng phím chữ chuẩn hoá
 * 0–1) + tâm nk phím (0–1). Đầu ra: log-xác suất [32 × (nk+1)] (cột cuối = blank, CTC).
 * Kiến trúc (khớp đồ thị gốc tới 5e-6 khi weights fp32):
 *   đặc trưng 8 kênh: x, y, vx, vy (Savitzky–Golay bậc 1, 7 điểm, đệm lặp biên), ax, ay
 *   (SG bậc 2), tốc độ √(vx²+vy²+1e-8), độ cong = SG bậc 1 của góc hướng đã unwrap (kẹp ±2)
 *   → conv 8→128 k7 + hardswish → 5 khối [dwconv k7 dilation 1,2,3,5,8 → pw 128→512 →
 *   GLU (sigmoid(a)·b) → GRN → pw 256→128 → SE (128→32 ReLU →128 sigmoid) + residual]
 *   → adapter conv k2 stride 2 (128→256, 64→32 bước) → λ = sigmoid(lin 256→1) và hệ số
 *   DCT 8×8 (lin 256→64) → logit phím = Σ hệ số·cos(π·u·x_phím)·cos(π·v·y_phím) →
 *   log_softmax trên phím + ln λ; blank = ln(1−λ); kẹp ≥ −100.
 *
 * Bộ nhớ: weights fp16 trong asset (1.27 MB) → FloatArray 2.5 MB + scratch ~0.35 MB, chỉ khi
 * công tắc thử nghiệm bật (FutoSwipe giữ/nhả). KHÔNG thread-safe.
 */
class FutoSwipeModel private constructor(private val w: FloatArray) {
    // scratch cấp một lần
    private val feat = FloatArray(8 * T)
    private val pad = FloatArray(T + 6)
    private val vx = FloatArray(T); private val vy = FloatArray(T)
    private val th = FloatArray(T)
    private var h = FloatArray(T * C)
    private val z = FloatArray(T * C)
    private val p = FloatArray(T * E)
    private val g = FloatArray(T * H)
    private var u = FloatArray(T * C)
    private val gx = FloatArray(H)
    private val m = FloatArray(C); private val s1 = FloatArray(SE); private val s2 = FloatArray(C)
    private val a = FloatArray(T2 * AD)
    private val coef = FloatArray(T2 * 64)
    private val lam = FloatArray(T2)
    private var basis = FloatArray(64 * 64)
    private val bx = FloatArray(8); private val by = FloatArray(8)
    private val logits = FloatArray(64)

    /**
     * Chạy encoder. [feats] 128 số (x0…x63, y0…y63), [keys] 2·nk số (nk ≤ 64), [out] ≥ 32·(nk+1).
     * [lambdaOut] (tuỳ chọn) nhận λ "ý định" 32 bước.
     */
    fun run(feats: FloatArray, keys: FloatArray, nk: Int, out: FloatArray, lambdaOut: FloatArray? = null) {
        require(nk in 1..MAX_KEYS && keys.size >= 2 * nk && out.size >= T2 * (nk + 1))
        features(feats)
        inputConv()
        for (b in 0 until NB) block(b)
        // adapter k2 s2: hàng t2 = h[2t2] ‖ h[2t2+1] (256 số liền) · ad_w[o][k][c] (256 số liền)
        pointwise(h, T2, 2 * C, O_AD_W, O_AD_B, AD, a)
        for (t in 0 until T2) {
            var s = w[O_LAM_B]
            val base = t * AD
            for (c in 0 until AD) s += a[base + c] * w[O_LAM_W + c]
            lam[t] = sigmoid(s)
        }
        pointwise(a, T2, AD, O_CO_W, O_CO_B, 64, coef)
        // cơ sở DCT tại tâm phím
        if (basis.size < nk * 64) basis = FloatArray(nk * 64)
        for (k in 0 until nk) {
            val kx = keys[2 * k]; val ky = keys[2 * k + 1]
            for (q in 0 until 8) {
                bx[q] = cos(PI * kx * q).toFloat()
                by[q] = cos(PI * ky * q).toFloat()
            }
            for (uu in 0 until 8) for (v in 0 until 8) basis[k * 64 + uu * 8 + v] = bx[uu] * by[v]
        }
        val stride = nk + 1
        for (t in 0 until T2) {
            var mx = Float.NEGATIVE_INFINITY
            for (k in 0 until nk) {
                var s = 0f
                val cb = t * 64; val bb = k * 64
                for (c in 0 until 64) s += coef[cb + c] * basis[bb + c]
                logits[k] = s
                if (s > mx) mx = s
            }
            var sum = 0.0
            for (k in 0 until nk) sum += exp((logits[k] - mx).toDouble())
            val lse = mx + ln(sum).toFloat()
            val l = lam[t]
            val ll = ln(max(l, 1e-7f))
            val lb = ln(max(1f - l, 1e-7f))
            for (k in 0 until nk) out[t * stride + k] = max(logits[k] - lse + ll, -100f)
            out[t * stride + nk] = max(lb, -100f)
            lambdaOut?.let { it[t] = l }
        }
    }

    private fun savgol(src: FloatArray, off: Int, wOff: Int, dst: FloatArray, dOff: Int) {
        for (i in 0 until 3) pad[i] = src[off]
        for (i in 0 until T) pad[3 + i] = src[off + i]
        for (i in 0 until 3) pad[3 + T + i] = src[off + T - 1]
        for (t in 0 until T) {
            var s = 0f
            for (k in 0 until 7) s += pad[t + k] * w[wOff + k]
            dst[dOff + t] = s
        }
    }

    /** feat[c*T + t]: x, y, vx, vy, ax, ay, tốc độ, độ cong. */
    private fun features(f: FloatArray) {
        System.arraycopy(f, 0, feat, 0, 2 * T)
        savgol(f, 0, O_SG_D1X, vx, 0)
        savgol(f, T, O_SG_D1Y, vy, 0)
        System.arraycopy(vx, 0, feat, 2 * T, T)
        System.arraycopy(vy, 0, feat, 3 * T, T)
        savgol(f, 0, O_SG_D2X, feat, 4 * T)
        savgol(f, T, O_SG_D2Y, feat, 5 * T)
        for (t in 0 until T) feat[6 * T + t] = sqrt(vx[t] * vx[t] + vy[t] * vy[t] + 1e-8f)
        // góc hướng unwrap: θ[t] + Σ hiệu chỉnh ±2π khi bước nhảy > π
        var acc = 0f
        var prev = 0f
        for (t in 0 until T) {
            val a = kotlin.math.atan2(vy[t], vx[t])
            if (t > 0) {
                val d = a - prev
                var corr = if (d > PI_F) -TWO_PI_F else 0f
                if (d < -PI_F) corr += TWO_PI_F
                acc += corr
            }
            prev = a
            th[t] = a + acc
        }
        savgol(th, 0, O_SG_D1C, feat, 7 * T)
        for (t in 0 until T) feat[7 * T + t] = min(max(feat[7 * T + t], -2f), 2f)
    }

    private fun inputConv() {
        for (t in 0 until T) {
            for (o in 0 until C) {
                var s = w[O_IN_B + o]
                for (k in 0 until 7) {
                    val ti = t - 3 + k
                    if (ti < 0 || ti >= T) continue
                    val wb = O_IN_W + (o * 7 + k) * 8
                    for (c in 0 until 8) s += feat[c * T + ti] * w[wb + c]
                }
                h[t * C + o] = hardswish(s)
            }
        }
    }

    private fun block(b: Int) {
        val o = O_BLOCK + b * BLOCK_SIZE
        val d = DIL[b]
        // depthwise k7, dilation d, đệm "same"
        for (t in 0 until T) {
            val zb = t * C
            for (c in 0 until C) z[zb + c] = w[o + B_DW_B + c]
            for (k in 0 until 7) {
                val ti = t + (k - 3) * d
                if (ti < 0 || ti >= T) continue
                val hb = ti * C; val wb = o + B_DW_W + k * C
                for (c in 0 until C) z[zb + c] += h[hb + c] * w[wb + c]
            }
        }
        pointwise(z, T, C, o + B_PW1_W, o + B_PW1_B, E, p)
        // GLU: sigmoid(nửa đầu) · nửa sau
        for (t in 0 until T) {
            val pb = t * E; val gb = t * H
            for (j in 0 until H) g[gb + j] = sigmoid(p[pb + j]) * p[pb + H + j]
        }
        // GRN
        var mean = 0f
        for (j in 0 until H) {
            var s = 0f
            for (t in 0 until T) { val v = g[t * H + j]; s += v * v }
            gx[j] = sqrt(s / T + 1e-6f)
            mean += gx[j]
        }
        val mg = mean / H + 1e-6f
        for (t in 0 until T) {
            val gb = t * H
            for (j in 0 until H) {
                val v = g[gb + j]
                g[gb + j] = w[o + B_GRN_G + j] * (v * (gx[j] / mg)) + w[o + B_GRN_B + j] + v
            }
        }
        pointwise(g, T, H, o + B_PW2_W, o + B_PW2_B, C, u)
        // SE
        for (c in 0 until C) m[c] = 0f
        for (t in 0 until T) { val ub = t * C; for (c in 0 until C) m[c] += u[ub + c] }
        for (c in 0 until C) m[c] /= T.toFloat()
        for (j in 0 until SE) {
            var s = w[o + B_SE1_B + j]
            val wb = o + B_SE1_W + j * C
            for (c in 0 until C) s += m[c] * w[wb + c]
            s1[j] = max(0f, s)
        }
        for (c in 0 until C) {
            var s = w[o + B_SE2_B + c]
            val wb = o + B_SE2_W + c * SE
            for (j in 0 until SE) s += s1[j] * w[wb + j]
            s2[c] = sigmoid(s)
        }
        for (t in 0 until T) {
            val b0 = t * C
            for (c in 0 until C) u[b0 + c] = u[b0 + c] * s2[c] + h[b0 + c]
        }
        val tmp = h; h = u; u = tmp
    }

    /**
     * out[t][o] = b[o] + Σ_c inp[t][c]·W[o][c] — khối 4 hàng × 2 đầu ra (8 bộ cộng dồn: mỗi
     * lượt đọc 4 số vào + 2 số weight cho 8 phép nhân). rows, cout chia hết 4 và 2 (T=64/32).
     */
    private fun pointwise(inp: FloatArray, rows: Int, cin: Int, wOff: Int, bOff: Int, cout: Int, out: FloatArray) {
        val w = w
        var t = 0
        while (t < rows) {
            val i0 = t * cin; val i1 = i0 + cin; val i2 = i1 + cin; val i3 = i2 + cin
            var o = 0
            while (o < cout) {
                val wa = wOff + o * cin; val wb = wa + cin
                var a0 = 0f; var a1 = 0f; var a2 = 0f; var a3 = 0f
                var b0 = 0f; var b1 = 0f; var b2 = 0f; var b3 = 0f
                for (c in 0 until cin) {
                    val x0 = inp[i0 + c]; val x1 = inp[i1 + c]; val x2 = inp[i2 + c]; val x3 = inp[i3 + c]
                    val va = w[wa + c]; val vb = w[wb + c]
                    a0 += x0 * va; a1 += x1 * va; a2 += x2 * va; a3 += x3 * va
                    b0 += x0 * vb; b1 += x1 * vb; b2 += x2 * vb; b3 += x3 * vb
                }
                val ba = w[bOff + o]; val bb = w[bOff + o + 1]
                out[t * cout + o] = a0 + ba; out[(t + 1) * cout + o] = a1 + ba
                out[(t + 2) * cout + o] = a2 + ba; out[(t + 3) * cout + o] = a3 + ba
                out[t * cout + o + 1] = b0 + bb; out[(t + 1) * cout + o + 1] = b1 + bb
                out[(t + 2) * cout + o + 1] = b2 + bb; out[(t + 3) * cout + o + 1] = b3 + bb
                o += 2
            }
            t += 4
        }
    }

    companion object {
        const val T = 64
        const val T2 = 32
        const val MAX_KEYS = 64
        private const val C = 128
        private const val E = 512
        private const val H = 256
        private const val SE = 32
        private const val AD = 256
        private const val NB = 5
        private val DIL = intArrayOf(1, 2, 3, 5, 8)
        private const val PI_F = PI.toFloat()
        private const val TWO_PI_F = (2 * PI).toFloat()

        // bố cục phẳng (Scripts/futo-swipe/convert.py, cùng thứ tự)
        private const val O_SG_D1X = 0
        private const val O_SG_D1Y = 7
        private const val O_SG_D2X = 14
        private const val O_SG_D2Y = 21
        private const val O_SG_D1C = 28
        private const val O_IN_W = 35
        private const val O_IN_B = O_IN_W + C * 56
        private const val O_BLOCK = O_IN_B + C
        private const val B_DW_W = 0
        private const val B_DW_B = B_DW_W + 7 * C
        private const val B_PW1_W = B_DW_B + C
        private const val B_PW1_B = B_PW1_W + E * C
        private const val B_GRN_G = B_PW1_B + E
        private const val B_GRN_B = B_GRN_G + H
        private const val B_PW2_W = B_GRN_B + H
        private const val B_PW2_B = B_PW2_W + C * H
        private const val B_SE1_W = B_PW2_B + C
        private const val B_SE1_B = B_SE1_W + SE * C
        private const val B_SE2_W = B_SE1_B + SE
        private const val B_SE2_B = B_SE2_W + C * SE
        private const val BLOCK_SIZE = B_SE2_B + C
        private const val O_AD_W = O_BLOCK + NB * BLOCK_SIZE
        private const val O_AD_B = O_AD_W + AD * 2 * C
        private const val O_LAM_W = O_AD_B + AD
        private const val O_LAM_B = O_LAM_W + AD
        private const val O_CO_W = O_LAM_B + 1
        private const val O_CO_B = O_CO_W + 64 * AD
        /** Tổng số tham số trong file. */
        const val PARAM_COUNT = O_CO_B + 64
        const val HEADER = 16

        private fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()
        private fun hardswish(x: Float): Float = x * min(max(x + 3f, 0f), 6f) / 6f

        /** fp16 IEEE → Float (không cần API 26+ / Float16 của JDK 20). */
        fun halfToFloat(hbits: Int): Float {
            val s = (hbits ushr 15) and 1
            val e = (hbits ushr 10) and 0x1f
            val f = hbits and 0x3ff
            val bits = when {
                e == 0 -> {
                    if (f == 0) s shl 31
                    else {
                        // subnormal: chuẩn hoá
                        var mant = f; var exp = -14
                        while (mant and 0x400 == 0) { mant = mant shl 1; exp-- }
                        (s shl 31) or ((exp + 127) shl 23) or ((mant and 0x3ff) shl 13)
                    }
                }
                e == 31 -> (s shl 31) or (0xff shl 23) or (f shl 13)
                else -> (s shl 31) or ((e - 15 + 127) shl 23) or (f shl 13)
            }
            return java.lang.Float.intBitsToFloat(bits)
        }

        /** Header "VTFS" v1 + đúng [PARAM_COUNT] số fp16; null nếu hỏng/lệch. */
        fun load(buf: ByteBuffer): FutoSwipeModel? {
            val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            if (b.capacity() != HEADER + 2 * PARAM_COUNT) return null
            if (b.get(0) != 'V'.code.toByte() || b.get(1) != 'T'.code.toByte() ||
                b.get(2) != 'F'.code.toByte() || b.get(3) != 'S'.code.toByte()) return null
            if (b.getInt(4) != 1 || b.getInt(8) != PARAM_COUNT) return null
            val w = FloatArray(PARAM_COUNT)
            for (i in 0 until PARAM_COUNT) {
                val v = halfToFloat(b.getShort(HEADER + 2 * i).toInt() and 0xffff)
                if (!v.isFinite()) return null
                w[i] = v
            }
            return FutoSwipeModel(w)
        }
    }
}
