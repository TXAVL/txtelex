package com.customkey.vietime.ui.keyboard

import com.customkey.vietime.ime.EnterAction
import com.customkey.vietime.ime.FieldKind

/** Hành động của một phím. */
sealed class KeyAction {
    data class Letter(val ch: Char) : KeyAction()
    data class Text(val text: String) : KeyAction()
    object Shift : KeyAction()
    object Backspace : KeyAction()
    object Space : KeyAction()
    object Enter : KeyAction()
    /** Chuyển plane: chữ ↔ ký hiệu ↔ ký hiệu 2. */
    data class Plane(val target: KeyboardPlane) : KeyAction()
    /** Đổi bàn phím hệ thống (giữ) / đổi VI-EN (chạm). */
    object Globe : KeyAction()
}

enum class KeyboardPlane { LETTERS, SYMBOLS, SYMBOLS2, NUMBER, PHONE }

/** Định nghĩa phím (độ rộng tính theo đơn vị "1 phím chữ"). */
data class KeyDef(
    val action: KeyAction,
    val label: String,
    val width: Float = 1f,
    /** Ký tự phụ khi giữ phím (phần tử đầu = chọn mặc định). */
    val alternates: List<String> = emptyList(),
    /** Phím chức năng (nền đậm hơn). */
    val modifier: Boolean = false,
)

/** Phím đã đặt vị trí (px, toạ độ của KeyboardView). */
class LaidKey(
    val def: KeyDef, val left: Float, val top: Float, val right: Float, val bottom: Float, val row: Int,
    /** Vùng chạm ngang (phím rìa hàng mở tới sát mép); vẽ vẫn theo left/right. */
    val hitLeft: Float = left, val hitRight: Float = right,
) {
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
    val width get() = right - left
    fun contains(x: Float, y: Float) = x >= hitLeft && x < hitRight && y >= top && y < bottom
}

/** Tuỳ chọn dựng bố cục (GĐ3 mở rộng: hàng số, tách đôi, độ rộng phím…). */
data class LayoutOptions(
    val numberRow: Boolean = false,
    /** Tách đôi bàn phím (màn ngang / tablet): khoảng trống giữa, tính theo đơn vị phím. */
    val splitGap: Float = 0f,
    val showGlobe: Boolean = false,
    /** Phần chiều ngang dùng cho phím (0.75…1), căn giữa. */
    val widthFraction: Float = 1f,
)

object KeyboardLayout {
    private val TOP = "qwertyuiop"
    private val MID = "asdfghjkl"
    private val BOT = "zxcvbnm"
    private val DIGITS = "1234567890"

    /** Ký tự phụ khi giữ (Telex không cần dấu ở đây — engine lo; chỉ số + dấu câu). */
    private val LETTER_ALTS: Map<Char, List<String>> = buildMap {
        TOP.forEachIndexed { i, c -> put(c, listOf(DIGITS[i].toString())) }
        put('a', listOf("@")); put('s', listOf("#")); put('d', listOf("đ", "₫")); put('f', listOf("&"))
        put('g', listOf("_")); put('h', listOf("-")); put('j', listOf("+")); put('k', listOf("(")); put('l', listOf(")"))
        put('z', listOf("*")); put('x', listOf("\"")); put('c', listOf("'")); put('v', listOf(":"))
        put('b', listOf(";")); put('n', listOf("!")); put('m', listOf("?"))
    }

    fun rows(plane: KeyboardPlane, kind: FieldKind, enter: EnterAction, opts: LayoutOptions = LayoutOptions()): List<List<KeyDef>> {
        val enterKey = KeyDef(KeyAction.Enter, enterLabel(enter), 1.5f, modifier = true)
        val back = KeyDef(KeyAction.Backspace, "⌫", 1.5f, modifier = true)
        return when (plane) {
            KeyboardPlane.LETTERS -> {
                val rows = ArrayList<List<KeyDef>>()
                if (opts.numberRow) rows += DIGITS.map { KeyDef(KeyAction.Text(it.toString()), it.toString()) }
                rows += TOP.map { letter(it) }
                rows += MID.map { letter(it) }
                rows += listOf(KeyDef(KeyAction.Shift, "⇧", 1.5f, modifier = true)) + BOT.map { letter(it) } + back
                rows += bottomRow(KeyDef(KeyAction.Plane(KeyboardPlane.SYMBOLS), "?123", 1.5f, modifier = true), kind, enterKey, opts)
                rows
            }
            KeyboardPlane.SYMBOLS -> listOf(
                DIGITS.map { KeyDef(KeyAction.Text(it.toString()), it.toString()) },
                listOf("@", "#", "₫", "_", "&", "-", "+", "(", ")", "/").map { text(it) },
                listOf(KeyDef(KeyAction.Plane(KeyboardPlane.SYMBOLS2), "=\\<", 1.5f, modifier = true)) +
                    listOf("*", "\"", "'", ":", ";", "!", "?").map { text(it) } + back,
                bottomRow(KeyDef(KeyAction.Plane(KeyboardPlane.LETTERS), "ABC", 1.5f, modifier = true), kind, enterKey, opts),
            )
            KeyboardPlane.SYMBOLS2 -> listOf(
                listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆").map { text(it) },
                listOf("£", "€", "¥", "$", "^", "°", "=", "{", "}", "\\").map { text(it) },
                listOf(KeyDef(KeyAction.Plane(KeyboardPlane.SYMBOLS), "?123", 1.5f, modifier = true)) +
                    listOf("%", "©", "®", "™", "✓", "[", "]").map { text(it) } + back,
                bottomRow(KeyDef(KeyAction.Plane(KeyboardPlane.LETTERS), "ABC", 1.5f, modifier = true), kind, enterKey, opts),
            )
            KeyboardPlane.NUMBER -> listOf(
                listOf(text("1"), text("2"), text("3"), text("-")),
                listOf(text("4"), text("5"), text("6"), text(",")),
                listOf(text("7"), text("8"), text("9"), KeyDef(KeyAction.Backspace, "⌫", modifier = true)),
                listOf(text("."), text("0"), KeyDef(KeyAction.Space, "␣", modifier = true), KeyDef(KeyAction.Enter, enterLabel(enter), modifier = true)),
            )
            KeyboardPlane.PHONE -> listOf(
                listOf(text("1"), text("2"), text("3"), text("+")),
                listOf(text("4"), text("5"), text("6"), text("*")),
                listOf(text("7"), text("8"), text("9"), KeyDef(KeyAction.Backspace, "⌫", modifier = true)),
                listOf(text("#"), text("0"), KeyDef(KeyAction.Space, "␣", modifier = true), KeyDef(KeyAction.Enter, enterLabel(enter), modifier = true)),
            )
        }
    }

    private fun letter(c: Char) = KeyDef(KeyAction.Letter(c), c.toString(), alternates = LETTER_ALTS[c].orEmpty())
    private fun text(s: String) = KeyDef(KeyAction.Text(s), s)

    private fun bottomRow(planeKey: KeyDef, kind: FieldKind, enter: KeyDef, opts: LayoutOptions): List<KeyDef> {
        val (left, right) = when (kind) {
            FieldKind.URL -> text("/") to KeyDef(KeyAction.Text("."), ".", alternates = listOf(".com", ".vn", ".net", ":"))
            FieldKind.EMAIL -> text("@") to KeyDef(KeyAction.Text("."), ".", alternates = listOf(".com", ".vn", "-", "_"))
            else -> KeyDef(KeyAction.Text(","), ",", alternates = listOf(";", ":", "-")) to
                KeyDef(KeyAction.Text("."), ".", alternates = listOf("?", "!", "…", "\"", "'"))
        }
        val globe = if (opts.showGlobe) listOf(KeyDef(KeyAction.Globe, "🌐", modifier = true)) else emptyList()
        val space = KeyDef(KeyAction.Space, "", if (opts.showGlobe) 4f else 5f)
        return listOf(planeKey) + globe + left + space + right + enter
    }

    fun enterLabel(a: EnterAction) = when (a) {
        EnterAction.GO -> "Đi"
        EnterAction.SEARCH -> "🔍"
        EnterAction.SEND -> "Gửi"
        EnterAction.NEXT -> "Tiếp"
        EnterAction.DONE -> "Xong"
        EnterAction.PREVIOUS, EnterAction.NEWLINE -> "↵"
    }

    /**
     * Đặt phím vào khung [width]×(rows×[rowHeight]): mỗi hàng chia theo tổng độ rộng của hàng
     * DÀI NHẤT (10 phím) ⇒ phím chữ cùng cỡ, hàng giữa tự thụt vào. [splitGap] chèn khoảng
     * trống ở giữa mỗi hàng (tách đôi).
     */
    fun place(rows: List<List<KeyDef>>, width: Float, rowHeight: Float, top: Float = 0f, opts: LayoutOptions = LayoutOptions()): List<LaidKey> {
        if (rows.isEmpty() || width <= 0f) return emptyList()
        val unitsMax = rows.maxOf { r -> r.sumOf { it.width.toDouble() }.toFloat() }.coerceAtLeast(1f) + opts.splitGap
        val inner = width * opts.widthFraction.coerceIn(0.5f, 1f)
        val unit = inner / unitsMax
        val out = ArrayList<LaidKey>()
        for ((ri, row) in rows.withIndex()) {
            val total = row.sumOf { it.width.toDouble() }.toFloat()
            val gapUnits = if (opts.splitGap > 0f && row.size > 1) opts.splitGap else 0f
            var x = (width - (total + gapUnits) * unit) / 2f
            val y = top + ri * rowHeight
            var acc = 0f
            var gapDone = false
            for (k in row) {
                if (gapUnits > 0f && !gapDone && acc + k.width / 2f > total / 2f) { x += gapUnits * unit; gapDone = true }
                out += LaidKey(k, x, y, x + k.width * unit, y + rowHeight, ri)
                x += k.width * unit; acc += k.width
            }
        }
        return stretchEdges(out, width)
    }

    /** Phím đầu/cuối mỗi hàng: mở vùng chạm tới sát mép (không đổi chỗ vẽ ở View). */
    private fun stretchEdges(keys: List<LaidKey>, width: Float): List<LaidKey> {
        val byRow = keys.groupBy { it.row }
        return keys.map { k ->
            val row = byRow[k.row]!!
            val l = if (k === row.first()) 0f else k.left
            val r = if (k === row.last()) width else k.right
            if (l == k.left && r == k.right) k else LaidKey(k.def, k.left, k.top, k.right, k.bottom, k.row, l, r)
        }
    }

    /** Tâm phím a–z cho SwipeLayout. */
    fun letterCenters(keys: List<LaidKey>): Map<Char, Pair<Float, Float>> =
        keys.mapNotNull { k -> (k.def.action as? KeyAction.Letter)?.let { it.ch to (k.centerX to k.centerY) } }.toMap()
}
