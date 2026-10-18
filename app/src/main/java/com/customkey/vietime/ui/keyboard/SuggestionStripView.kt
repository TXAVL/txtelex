package com.customkey.vietime.ui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import com.customkey.vietime.ime.Suggestion

/**
 * Thanh gợi ý phía trên bàn phím: tối đa 3 ô (ô giữa = gợi ý tốt nhất, như iOS), hoặc một dòng
 * thông báo (xem trước xoá theo từ), kèm nút "↶ Hoàn tác" khi vừa xoá bằng vuốt ⌫.
 */
@SuppressLint("ViewConstructor")
class SuggestionStripView(context: Context) : View(context) {
    interface Listener {
        fun onSuggestion(s: Suggestion)
        fun onUndoDelete()
    }

    var listener: Listener? = null
    var style: KeyboardStyle = KeyboardStyle.light
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 17f * resources.displayMetrics.scaledDensity }
    private val boldPaint = TextPaint(textPaint).apply { isFakeBoldText = true }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var items: List<Suggestion> = emptyList()
    private var message: String? = null
    private var undo = false
    private var pressed = -1

    /** Thứ tự hiển thị: ô giữa = phần tử thứ 2 (gợi ý tốt nhất) khi đủ 3 ô. */
    private fun ordered(): List<Suggestion> = if (items.size == 3) listOf(items[0], items[1], items[2]) else items

    fun show(list: List<Suggestion>) {
        if (list == items && message == null) return
        items = list; message = null; invalidate()
    }

    fun showMessage(text: String?) { message = text; invalidate() }

    fun showUndo(on: Boolean) { if (undo != on) { undo = on; invalidate() } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (42 * d).toInt())
    }

    private val undoWidth get() = if (undo) 104 * d else 0f

    /** false = nền do [KeyboardPanel] vẽ. */
    var drawsBackground = true

    override fun onDraw(canvas: Canvas) {
        val s = style
        if (drawsBackground) canvas.drawColor(s.stripBackground)
        textPaint.color = s.stripText; boldPaint.color = s.stripText
        linePaint.color = s.divider
        val cy = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        val avail = width - undoWidth
        message?.let { m ->
            val t = TextUtils.ellipsize(m, textPaint, avail - 24 * d, TextUtils.TruncateAt.START).toString()
            canvas.drawText(t, avail / 2f, cy, textPaint)
        } ?: run {
            val list = ordered()
            if (list.isNotEmpty()) {
                val cell = avail / list.size
                for ((i, it) in list.withIndex()) {
                    val x0 = i * cell
                    if (i == pressed) {
                        chipPaint.color = s.pressedFill
                        rect.set(x0 + 3 * d, 4 * d, x0 + cell - 3 * d, height - 4 * d)
                        canvas.drawRoundRect(rect, 8 * d, 8 * d, chipPaint)
                    }
                    val p = if (it.kind == Suggestion.Kind.LITERAL) textPaint else boldPaint
                    val label = if (it.kind == Suggestion.Kind.LITERAL) "“${it.text}”" else it.text
                    val t = TextUtils.ellipsize(label, p, cell - 12 * d, TextUtils.TruncateAt.END).toString()
                    canvas.drawText(t, x0 + cell / 2f, cy, p)
                    if (i > 0) canvas.drawRect(x0, 10 * d, x0 + d, height - 10 * d, linePaint)
                }
            }
        }
        if (undo) {
            chipPaint.color = s.accent
            rect.set(width - undoWidth + 6 * d, 6 * d, width - 6 * d, height - 6 * d)
            canvas.drawRoundRect(rect, 14 * d, 14 * d, chipPaint)
            boldPaint.color = 0xFFFFFFFF.toInt()
            canvas.drawText("↶ Hoàn tác", rect.centerX(), cy, boldPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val avail = width - undoWidth
        val list = ordered()
        fun indexAt(x: Float): Int = when {
            undo && x >= avail -> UNDO
            message != null || list.isEmpty() -> -1
            else -> (x / (avail / list.size)).toInt().coerceIn(0, list.size - 1)
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = indexAt(e.x); invalidate() }
            MotionEvent.ACTION_UP -> {
                val i = indexAt(e.x)
                if (i == pressed) {
                    if (i == UNDO) listener?.onUndoDelete()
                    else if (i >= 0) list.getOrNull(i)?.let { listener?.onSuggestion(it) }
                }
                pressed = -1; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }

    private companion object { const val UNDO = 1000 }
}
