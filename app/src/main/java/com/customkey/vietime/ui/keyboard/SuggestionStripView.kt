package com.customkey.vietime.ui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import com.customkey.vietime.ime.Suggestion

/**
 * Thanh gợi ý phía trên bàn phím.
 *
 * - Chế độ GỢI Ý: 3–5 ô; khi đang gõ, ô đầu là chữ gốc trong ngoặc, ô thứ hai (giữa với 3 ô —
 *   như iOS) là gợi ý tốt nhất, tô nền nhấn. Chip đặc biệt (phép tính, thêm dấu, dán nhanh, số,
 *   đuôi email, ↩ hoàn tác tự sửa) tô màu nhấn.
 * - Chế độ CÔNG CỤ (khi thu gọn, hoặc không có gợi ý): hàng biểu tượng Công cụ / Emoji / Clipboard
 *   / Sửa văn bản / Một tay / Cài đặt.
 * - Một dòng thông báo (xem trước xoá theo từ…), nút "↶ Hoàn tác" khi vừa xoá bằng vuốt ⌫.
 */
@SuppressLint("ViewConstructor")
class SuggestionStripView(context: Context) : View(context) {
    interface Listener {
        fun onSuggestion(s: Suggestion)
        fun onUndoDelete()
        /** Chạm nút công cụ bên trái. */
        fun onToolsButton() {}
        /** Chạm một biểu tượng ở chế độ công cụ ([Tool]). */
        fun onToolbar(tool: Tool) {}
        /** Người dùng thu gọn / mở lại thanh gợi ý. */
        fun onCollapseChanged(collapsed: Boolean) {}
    }

    /** Biểu tượng ở chế độ công cụ. */
    enum class Tool(val icon: String, val label: String) {
        EMOJI("😊", "Emoji"), CLIPBOARD("📋", "Clipboard"), EDIT("✂", "Sửa"),
        TONES("✨", "Thêm dấu"), ONE_HAND("✋", "Một tay"), SETTINGS("⚙", "Cài đặt"),
    }

    var listener: Listener? = null
    var style: KeyboardStyle = KeyboardStyle.neon
        set(v) { field = v; invalidate() }

    /** false = nền do [KeyboardPanel] vẽ. */
    var drawsBackground = true
    /** Hiện nút công cụ (ẩn ở bản xem trước / ô mật khẩu). */
    var showToolsButton = true
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Đang mở bảng công cụ ⇒ nút đổi thành ⌨. */
    var toolsOpen = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Thu gọn: không hiện gợi ý, chỉ hàng công cụ (người dùng bật bằng nút ⌄). */
    var collapsed = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Cho phép hàng công cụ (tắt ở ô mật khẩu / bản xem trước). */
    var toolbarEnabled = true
        set(v) { if (field != v) { field = v; invalidate() } }

    private val d = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 16.5f * sp }
    private val boldPaint = TextPaint(textPaint).apply { isFakeBoldText = true; textSize = 17f * sp }
    private val iconPaint = TextPaint(textPaint).apply { textSize = 19f * sp }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var items: List<Suggestion> = emptyList()
    private var message: String? = null
    private var undo = false
    private var pressed = NONE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rawW = MeasureSpec.getSize(widthMeasureSpec)
        val w = if (rawW > 0) rawW else resources.displayMetrics.widthPixels
        setMeasuredDimension(w, (42 * d).toInt())
    }

    fun show(list: List<Suggestion>) {
        if (list == items && message == null) return
        items = list; message = null; invalidate()
    }

    fun showMessage(text: String?) {
        if (text != null && TxaToast.isLong(text)) {
            val panel = (parent as? View)?.let { p -> (p as? KeyboardPanel) ?: (p.parent as? KeyboardPanel) ?: (p.parent?.parent as? KeyboardPanel) }
            if (panel != null) { message = null; panel.showToast(text); invalidate(); return }
        }
        message = text
        invalidate()
    }

    fun showUndo(on: Boolean) { if (undo != on) { undo = on; invalidate() } }

    private val toolsWidth get() = if (showToolsButton) 46 * d else 0f
    private val undoWidth get() = if (undo) 104 * d else 0f
    /** Nút thu gọn ⌄ / mở ⌃ ở mép phải (chỉ khi có hàng công cụ). */
    private val collapseWidth get() = if (toolbarEnabled && !undo) 34 * d else 0f

    /** Đang hiện hàng công cụ thay cho gợi ý. Chip đặc biệt vẫn được hiện kể cả khi thu gọn. */
    private val toolbarMode: Boolean
        get() = toolbarEnabled && message == null && (items.isEmpty() || (collapsed && items.none { it.special || it.kind == Suggestion.Kind.UNDO_CORRECTION }))

    private fun isAccent(s: Suggestion) = s.special || s.kind == Suggestion.Kind.UNDO_CORRECTION

    /** Ô "tốt nhất": sau chữ gốc khi đang gõ, không thì ô đầu (từ kế tiếp). */
    private fun bestIndex(list: List<Suggestion>): Int = when {
        list.size >= 2 && list[0].kind == Suggestion.Kind.LITERAL -> 1
        else -> -1
    }

    override fun onDraw(canvas: Canvas) {
        val s = style
        if (drawsBackground) canvas.drawColor(s.stripBackground)
        textPaint.color = s.stripText; boldPaint.color = s.stripText; iconPaint.color = s.stripText
        linePaint.color = s.divider
        val left = toolsWidth
        val right = width - undoWidth - collapseWidth
        val avail = right - left
        val cy = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f

        if (showToolsButton) {
            chipPaint.color = if (pressed == TOOLS || toolsOpen) s.pressedFill else s.modifierFill
            rect.set(6 * d, 6 * d, left - 6 * d, height - 6 * d)
            canvas.drawRoundRect(rect, 10 * d, 10 * d, chipPaint)
            boldPaint.color = s.accent
            canvas.drawText(if (toolsOpen) "⌨" else "✦", rect.centerX(), cy, boldPaint)
            boldPaint.color = s.stripText
        }

        val msg = message
        when {
            msg != null -> {
                val t = TextUtils.ellipsize(msg, textPaint, avail - 24 * d, TextUtils.TruncateAt.START).toString()
                canvas.drawText(t, left + avail / 2f, cy, textPaint)
            }
            toolbarMode -> {
                val tools = Tool.entries
                val cell = avail / tools.size
                val icy = height / 2f - (iconPaint.descent() + iconPaint.ascent()) / 2f
                for ((i, t) in tools.withIndex()) {
                    val x0 = left + i * cell
                    if (pressed == TOOLBAR_BASE + i) {
                        chipPaint.color = s.pressedFill
                        rect.set(x0 + 4 * d, 6 * d, x0 + cell - 4 * d, height - 6 * d)
                        canvas.drawRoundRect(rect, 12 * d, 12 * d, chipPaint)
                    }
                    canvas.drawText(t.icon, x0 + cell / 2f, icy, iconPaint)
                }
            }
            items.isNotEmpty() -> {
                val list = items
                val cell = avail / list.size
                val best = bestIndex(list)
                for ((i, it) in list.withIndex()) {
                    val x0 = left + i * cell
                    val accent = isAccent(it)
                    if (accent || i == pressed || i == best) {
                        chipPaint.color = when {
                            i == pressed -> s.pressedFill
                            accent -> withAlpha(s.accent, 0.22f)
                            else -> withAlpha(s.keyFill, 0.75f)
                        }
                        rect.set(x0 + 4 * d, 6 * d, x0 + cell - 4 * d, height - 6 * d)
                        canvas.drawRoundRect(rect, 12 * d, 12 * d, chipPaint)
                    }
                    val p = if (it.kind == Suggestion.Kind.LITERAL) textPaint else boldPaint
                    p.color = when {
                        accent -> s.accent
                        it.kind == Suggestion.Kind.LITERAL -> withAlpha(s.stripText, 0.7f)
                        else -> s.stripText
                    }
                    val label = if (it.kind == Suggestion.Kind.LITERAL) "“${it.text}”" else it.text
                    val t = TextUtils.ellipsize(label, p, cell - 14 * d, TextUtils.TruncateAt.END).toString()
                    canvas.drawText(t, x0 + cell / 2f, cy, p)
                    val prevPlain = i > 0 && !isAccent(list[i - 1]) && i - 1 != best && i != best && !accent
                    if (prevPlain) canvas.drawRect(x0, 12 * d, x0 + d, height - 12 * d, linePaint)
                }
            }
        }
        boldPaint.color = s.stripText
        if (collapseWidth > 0f) {
            val x0 = width - undoWidth - collapseWidth
            if (pressed == COLLAPSE) {
                chipPaint.color = s.pressedFill
                rect.set(x0 + 2 * d, 8 * d, x0 + collapseWidth - 4 * d, height - 8 * d)
                canvas.drawRoundRect(rect, 10 * d, 10 * d, chipPaint)
            }
            textPaint.color = withAlpha(s.stripText, 0.6f)
            canvas.drawText(if (collapsed) "⌃" else "⌄", x0 + collapseWidth / 2f - d, cy, textPaint)
            textPaint.color = s.stripText
        }
        if (undo) {
            chipPaint.color = s.accent
            rect.set(width - undoWidth + 6 * d, 6 * d, width - 6 * d, height - 6 * d)
            canvas.drawRoundRect(rect, 14 * d, 14 * d, chipPaint)
            boldPaint.color = com.customkey.vietime.customization.Colors.onColor(s.accent)
            canvas.drawText("↶ Hoàn tác", rect.centerX(), cy, boldPaint)
            boldPaint.color = s.stripText
        }
    }

    private fun withAlpha(c: Int, a: Float) = (c and 0x00FFFFFF) or ((255 * a).toInt() shl 24)

    private fun indexAt(x: Float): Int {
        val left = toolsWidth
        val right = width - undoWidth - collapseWidth
        val avail = right - left
        return when {
            showToolsButton && x < left -> TOOLS
            undo && x >= width - undoWidth -> UNDO
            collapseWidth > 0f && x >= right && x < width - undoWidth -> COLLAPSE
            message != null -> NONE
            toolbarMode -> TOOLBAR_BASE + ((x - left) / (avail / Tool.entries.size)).toInt().coerceIn(0, Tool.entries.size - 1)
            items.isEmpty() -> NONE
            else -> ((x - left) / (avail / items.size)).toInt().coerceIn(0, items.size - 1)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = indexAt(e.x); invalidate() }
            MotionEvent.ACTION_UP -> {
                val i = indexAt(e.x)
                if (i == pressed) when {
                    i == TOOLS -> listener?.onToolsButton()
                    i == UNDO -> listener?.onUndoDelete()
                    i == COLLAPSE -> { collapsed = !collapsed; listener?.onCollapseChanged(collapsed) }
                    i >= TOOLBAR_BASE -> Tool.entries.getOrNull(i - TOOLBAR_BASE)?.let { listener?.onToolbar(it) }
                    i >= 0 -> items.getOrNull(i)?.let { listener?.onSuggestion(it) }
                }
                pressed = NONE; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = NONE; invalidate() }
        }
        return true
    }

    private companion object {
        const val UNDO = 1000; const val TOOLS = 1001; const val COLLAPSE = 1002; const val TOOLBAR_BASE = 2000; const val NONE = -1
    }
}
