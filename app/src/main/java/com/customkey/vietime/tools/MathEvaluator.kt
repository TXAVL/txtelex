package com.customkey.vietime.tools

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Máy tính trên thanh gợi ý: văn bản trước con trỏ kết thúc bằng "biểu thức =" ⇒ chip kết quả,
 * chạm để chèn sau "=". Không eval, không biến/hàm — parser đệ quy xuống riêng.
 *
 * - Phép tính: + - − * × / ÷ : ^ ( ) %; "x"/"X" là nhân chỉ khi đứng giữa hai số.
 *   ^ kết hợp phải và mạnh hơn trừ một ngôi (-2^2 = -4). "A ± B%" = A ± A·B/100; còn lại B% = B/100.
 * - Dấu "," / "." suy từ cả biểu thức: dấu chắc chắn là thập phân ("1,5", "0.500") quyết định
 *   dấu kia là nhóm nghìn; chỉ có dạng mơ hồ "d,ddd" ⇒ nhóm nghìn; mâu thuẫn ⇒ không có chip.
 * - Kết quả: tối đa 6 chữ số lẻ (≤ 12 chữ số có nghĩa), dùng đúng dấu thập phân đã suy; chia
 *   nhóm nghìn khi biểu thức có dùng nhóm nghìn.
 */
object MathEvaluator {
    const val MAX_LENGTH = 64
    private const val LIMIT = 1e15

    /** Kết quả tính: [text] là chuỗi hiển thị/chèn. */
    data class Result(val value: Double, val text: String)

    private sealed class Outcome {
        class Ok(val r: Result) : Outcome()
        object Invalid : Outcome()
        /** Đúng dạng phép tính nhưng dấu phân cách mâu thuẫn ⇒ dừng hẳn. */
        object Conflict : Outcome()
    }

    /** Tính biểu thức (không có "="). null = không phải phép tính hợp lệ. */
    fun evaluate(expr: String): Result? = (analyze(expr) as? Outcome.Ok)?.r

    /**
     * Chip cho văn bản trước con trỏ: phải kết thúc bằng "=". Lấy đoạn sau dấu "=" trước đó (nếu
     * có), thử cả đoạn rồi các đuôi sau khoảng trắng ("năm 2024 12*3=" → "12*3").
     */
    fun chip(before: String): String? {
        if (!before.endsWith("=")) return null
        val body = before.dropLast(1)
        if (body.endsWith("=")) return null
        val seg = body.substringAfterLast('=').trimEnd()
        if (seg.isEmpty()) return null
        val starts = ArrayList<Int>()
        starts += 0
        for (i in seg.indices) if (seg[i] == ' ' && i + 1 < seg.length && seg[i + 1] != ' ') starts += i + 1
        for (s in starts) {
            when (val o = analyze(seg.substring(s))) {
                is Outcome.Ok -> return o.r.text
                Outcome.Conflict -> return null
                Outcome.Invalid -> continue
            }
        }
        return null
    }

    // MARK: token

    private sealed class Tok {
        class Num(val lit: String) : Tok() { var value = 0.0 }
        class Op(val c: Char) : Tok()
        object Lp : Tok()
        object Rp : Tok()
        object Pct : Tok()
    }

    private fun tokenize(s: String): List<Tok>? {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == ' ' -> { i++; continue }
                c in '0'..'9' -> {
                    var j = i
                    while (j < s.length && (s[j] in '0'..'9' || s[j] == '.' || s[j] == ',')) j++
                    out += Tok.Num(s.substring(i, j)); i = j; continue
                }
                c == '+' -> out += Tok.Op('+')
                c == '-' || c == '−' -> out += Tok.Op('-')
                c == '*' || c == '×' -> out += Tok.Op('*')
                c == '/' || c == '÷' || c == ':' -> out += Tok.Op('/')
                c == '^' -> out += Tok.Op('^')
                c == '(' -> out += Tok.Lp
                c == ')' -> out += Tok.Rp
                c == '%' -> out += Tok.Pct
                c == 'x' || c == 'X' -> {
                    val prev = out.lastOrNull()
                    var k = i + 1
                    while (k < s.length && s[k] == ' ') k++
                    val next = s.getOrNull(k)
                    val prevOk = prev is Tok.Num || prev is Tok.Rp || prev is Tok.Pct
                    val nextOk = next != null && (next in '0'..'9' || next == '(')
                    if (!prevOk || !nextOk) return null
                    out += Tok.Op('*')
                }
                else -> return null
            }
            i++
        }
        return out
    }

    // MARK: dấu phân cách

    private enum class Kind { PLAIN, DECIMAL, GROUP, BOTH, AMBIGUOUS }

    /** Phân tích một literal số. null = sai dạng. [sep] = dấu duy nhất / dấu thập phân (BOTH). */
    private class Lit(val kind: Kind, val sep: Char = ' ', val other: Char = ' ')

    private fun classify(lit: String): Lit? {
        if (lit.first() !in '0'..'9' || lit.last() !in '0'..'9') return null
        val seps = lit.filter { it == '.' || it == ',' }
        if (seps.isEmpty()) return Lit(Kind.PLAIN)
        val parts = lit.split('.', ',')
        if (parts.any { it.isEmpty() }) return null
        val distinct = seps.toSet()
        if (distinct.size == 2) {
            val dec = seps.last()
            if (seps.count { it == dec } != 1) return null
            val grp = if (dec == '.') ',' else '.'
            val intPart = lit.substringBeforeLast(dec)
            if (!validGroups(intPart.split(grp))) return null
            return Lit(Kind.BOTH, dec, grp)
        }
        val c = seps[0]
        if (seps.length > 1) {
            if (!validGroups(parts)) return null
            return Lit(Kind.GROUP, c)
        }
        val (a, b) = parts
        val ambiguous = a.length in 1..3 && a[0] != '0' && b.length == 3
        return Lit(if (ambiguous) Kind.AMBIGUOUS else Kind.DECIMAL, c)
    }

    private fun validGroups(g: List<String>): Boolean =
        g.size >= 2 && g[0].length in 1..3 && g[0][0] != '0' && g.drop(1).all { it.length == 3 }

    private class Seps(val dec: Char, val grp: Char, val grouped: Boolean)

    /** Suy dấu thập phân/nhóm cho cả biểu thức; null = mâu thuẫn. */
    private fun resolve(lits: List<Lit>): Seps? {
        val dec = HashSet<Char>(); val grp = HashSet<Char>(); val amb = HashSet<Char>()
        for (l in lits) when (l.kind) {
            Kind.DECIMAL -> dec += l.sep
            Kind.GROUP -> grp += l.sep
            Kind.BOTH -> { dec += l.sep; grp += l.other }
            Kind.AMBIGUOUS -> amb += l.sep
            Kind.PLAIN -> {}
        }
        if (dec.size > 1 || grp.size > 1 || dec.any { it in grp }) return null
        val d: Char; val g: Char
        when {
            dec.isNotEmpty() -> { d = dec.first(); g = other(d); if (grp.isNotEmpty() && grp.first() != g) return null }
            grp.isNotEmpty() -> { g = grp.first(); d = other(g) }
            amb.isNotEmpty() -> { if (amb.size > 1) return null; g = amb.first(); d = other(g) }
            else -> return Seps(',', '.', false)
        }
        // Số mơ hồ dùng dấu nhóm ⇒ có nhóm nghìn.
        val grouped = lits.any { it.kind == Kind.GROUP || it.kind == Kind.BOTH || (it.kind == Kind.AMBIGUOUS && it.sep == g) }
        return Seps(d, g, grouped)
    }

    private fun other(c: Char) = if (c == '.') ',' else '.'

    private fun valueOf(lit: String, s: Seps): Double =
        lit.replace(s.grp.toString(), "").replace(s.dec, '.').toDouble()

    // MARK: phân tích + tính

    private fun analyze(expr: String): Outcome {
        if (expr.length > MAX_LENGTH || expr.isBlank()) return Outcome.Invalid
        val toks = tokenize(expr) ?: return Outcome.Invalid
        val nums = toks.filterIsInstance<Tok.Num>()
        if (nums.isEmpty()) return Outcome.Invalid
        val lits = nums.map { classify(it.lit) ?: return Outcome.Invalid }
        // Cấu trúc trước (số = 1 tạm) — sai dạng thì không bàn tới mâu thuẫn dấu.
        nums.forEach { it.value = 1.0 }
        val p0 = Parser(toks)
        if (p0.run() == null || !p0.hasOperation) return Outcome.Invalid
        val seps = resolve(lits) ?: return Outcome.Conflict
        for (n in nums) n.value = valueOf(n.lit, seps)
        val p = Parser(toks)
        val v = p.run() ?: return Outcome.Invalid
        if (v.isNaN() || v.isInfinite() || abs(v) >= LIMIT) return Outcome.Invalid
        val text = format(v, seps) ?: return Outcome.Invalid
        return Outcome.Ok(Result(v, text))
    }

    private class Parser(val t: List<Tok>) {
        var i = 0
        var hasOperation = false
        private var failed = false

        fun run(): Double? {
            val v = expr()
            return if (failed || i != t.size) null else v
        }

        private fun peek(): Tok? = t.getOrNull(i)

        private fun expr(): Double {
            var (v, _) = term()
            while (true) {
                val op = (peek() as? Tok.Op)?.c ?: break
                if (op != '+' && op != '-') break
                i++; hasOperation = true
                val (r, pctOnly) = term()
                val rhs = if (pctOnly) v * r else r   // A ± B% = A ± A·B/100 (r đã là B/100)
                v = if (op == '+') v + rhs else v - rhs
            }
            return v
        }

        /** Trả (giá trị, có phải chỉ một thừa số "B%" không). */
        private fun term(): Pair<Double, Boolean> {
            var (v, pct) = unary()
            var only = pct
            while (true) {
                val op = (peek() as? Tok.Op)?.c ?: break
                if (op != '*' && op != '/') break
                i++; hasOperation = true; only = false
                val (r, _) = unary()
                v = if (op == '*') v * r else { if (r == 0.0) { failed = true; return 0.0 to false }; v / r }
            }
            return v to only
        }

        private fun unary(): Pair<Double, Boolean> {
            val tk = peek()
            if (tk is Tok.Op && tk.c == '-') { i++; val (v, _) = unary(); return -v to false }
            return power()
        }

        private fun power(): Pair<Double, Boolean> {
            val (b, pct) = primary()
            val tk = peek()
            if (tk is Tok.Op && tk.c == '^') {
                i++; hasOperation = true
                val (e, _) = unary()
                return b.pow(e) to false
            }
            return b to pct
        }

        private fun primary(): Pair<Double, Boolean> {
            var v: Double
            when (val tk = peek()) {
                is Tok.Num -> { i++; v = tk.value }
                Tok.Lp -> {
                    i++
                    v = expr()
                    if (peek() != Tok.Rp) { failed = true; return 0.0 to false }
                    i++
                }
                else -> { failed = true; return 0.0 to false }
            }
            var pct = false
            while (peek() == Tok.Pct) { i++; v /= 100.0; pct = true; hasOperation = true }
            return v to pct
        }
    }

    private fun format(v: Double, s: Seps): String? {
        val r = if (v == floor(v) && abs(v) < LIMIT) BigDecimal(v.toLong())
        else {
            val intDigits = if (abs(v) < 1) 1 else floor(log10(abs(v))).toInt() + 1
            val scale = minOf(6, maxOf(0, 12 - intDigits))
            BigDecimal(v).setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros()
        }
        if (r.signum() == 0 && v != 0.0) return null   // khác 0 mà làm tròn thành 0
        val plain = r.abs().toPlainString()
        val intPart = plain.substringBefore('.')
        val frac = plain.substringAfter('.', "")
        val grouped = if (s.grouped) intPart.reversed().chunked(3).joinToString(s.grp.toString()).reversed() else intPart
        val sign = if (r.signum() < 0) "-" else ""
        return sign + grouped + (if (frac.isNotEmpty()) s.dec + frac else "")
    }
}
