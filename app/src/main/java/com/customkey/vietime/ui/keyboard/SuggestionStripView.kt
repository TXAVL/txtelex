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
 * Thanh gợi ý phía trên bàn phím: nút công cụ (✦ — mở Clipboard / Mẫu câu / Thêm dấu…) bên
 * trái, tối đa 3 ô gợi ý (chip đặc biệt — phép tính, thêm dấu, dán nhanh — tô nổi ở ô đầu),
 * hoặc một dòng thông báo; nút "↶ Hoàn tác" khi vừa xoá bằng vuốt ⌫.
 */
@SuppressLint("ViewConstructor")
class SuggestionStripView(context: Context) : View(context) {
    interface Listener {
        fun onSuggestion(s: Suggestion)
        fun onUndoDelete()
        /** Chạm nút công cụ bên trái. */
        fun onToolsButton() {}
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

    private val d = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 17f * sp }
    private val boldPaint = TextPaint(textPaint).apply { isFakeBoldText = true }
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
        val h = (42 * d).toInt()
        setMeasuredDimension(w, h)
    }

    fun show(list: List<Suggestion>) {
        if (list == items && message == null) return
        items = list; message = null; invalidate()
    }

    fun showMessage(text: String?) {
        if (text != null && TxaToast.isLong(text)) {
            val panel = (parent as? android.view.View)?.let { p ->
                (p as? KeyboardPanel) ?: (p.parent as? KeyboardPanel)
            }
            if (panel != null) {
                message = null
                panel.showToast(text)
                invalidate()
                return
            }
        }
        message = text
        invalidate()
    }

    fun showUndo(on: Boolean) { if (undo != on) { undo = on; invalidate() } }

    private val toolsWidth get() = if (showToolsButton) 46 * d else 0f
    private val undoWidth get() = if (undo) 104 * d else 0f

    override fun onDraw(canvas: Canvas) {
        val s = style
        if (drawsBackground) canvas.drawColor(s.stripBackground)
        textPaint.color = s.stripText; boldPaint.color = s.stripText
        linePaint.color = s.divider
        val cy = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        val left = toolsWidth
        val avail = width - undoWidth - left

        if (showToolsButton) {
            chipPaint.color = if (pressed == TOOLS || toolsOpen) s.pressedFill else s.modifierFill
            rect.set(6 * d, 6 * d, left - 6 * d, height - 6 * d)
            canvas.drawRoundRect(rect, 10 * d, 10 * d, chipPaint)
            boldPaint.color = s.accent
            canvas.drawText(if (toolsOpen) "⌨" else "✦", rect.centerX(), cy, boldPaint)
            boldPaint.color = s.stripText
        }

        message?.let { m ->
            val t = TextUtils.ellipsize(m, textPaint, avail - 24 * d, TextUtils.TruncateAt.START).toString()
            canvas.drawText(t, left + avail / 2f, cy, textPaint)
        } ?: run {
            val list = items
            if (list.isNotEmpty()) {
                val cell = avail / list.size
                for ((i, it) in list.withIndex()) {
                    val x0 = left + i * cell
                    if (it.special || i == pressed) {
                        chipPaint.color = if (i == pressed) s.pressedFill else withAlpha(s.accent, 0.22f)
                        rect.set(x0 + 4 * d, 6 * d, x0 + cell - 4 * d, height - 6 * d)
                        canvas.drawRoundRect(rect, 12 * d, 12 * d, chipPaint)
                    }
                    val p = if (it.kind == Suggestion.Kind.LITERAL) textPaint else boldPaint
                    p.color = if (it.special) s.accent else s.stripText
                    val label = if (it.kind == Suggestion.Kind.LITERAL) "“${it.text}”" else it.text
                    val t = TextUtils.ellipsize(label, p, cell - 14 * d, TextUtils.TruncateAt.END).toString()
                    canvas.drawText(t, x0 + cell / 2f, cy, p)
                    if (i > 0 && !it.special && !list[i - 1].special) canvas.drawRect(x0, 10 * d, x0 + d, height - 10 * d, linePaint)
                }
                boldPaint.color = s.stripText
            }
        }
        if (undo) {
            chipPaint.color = s.accent
            rect.set(width - undoWidth + 6 * d, 6 * d, width - 6 * d, height - 6 * d)
            canvas.drawRoundRect(rect, 14 * d, 14 * d, chipPaint)
            boldPaint.color = 0xFF0A1024.toInt()
            canvas.drawText("↶ Hoàn tác", rect.centerX(), cy, boldPaint)
            boldPaint.color = s.stripText
        }
    }

    private fun withAlpha(c: Int, a: Float) = (c and 0x00FFFFFF) or ((255 * a).toInt() shl 24)

    private fun indexAt(x: Float): Int {
        val left = toolsWidth
        val avail = width - undoWidth - left
        return when {
            showToolsButton && x < left -> TOOLS
            undo && x >= width - undoWidth -> UNDO
            message != null || items.isEmpty() -> NONE
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
                    i >= 0 -> items.getOrNull(i)?.let { listener?.onSuggestion(it) }
                }
                pressed = NONE; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = NONE; invalidate() }
        }
        return true
    }

    private companion object { const val UNDO = 1000; const val TOOLS = 1001; const val NONE = -1 }
}
