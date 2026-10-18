package com.customkey.vietime.ime

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import com.customkey.vietime.engine.TextProxy
import java.text.BreakIterator

/**
 * Lát cắt InputConnection mà [InputBridge] dùng. Tách interface để test trên JVM bằng
 * FakeEditor (không cần Robolectric).
 */
interface EditorPort {
    fun beginBatch()
    fun endBatch()
    fun commitText(text: CharSequence): Boolean
    /** deleteSurroundingText(utf16, 0). */
    fun deleteBefore(utf16: Int): Boolean
    /** deleteSurroundingTextInCodePoints(count, 0). */
    fun deleteCodePointsBefore(count: Int): Boolean
    fun deleteSurrounding(before: Int, after: Int): Boolean
    fun textBefore(n: Int): CharSequence?
    fun textAfter(n: Int): CharSequence?
    fun selectedText(): CharSequence?
    fun setSelection(start: Int, end: Int): Boolean
    fun finishComposing(): Boolean
    fun performEditorAction(actionId: Int): Boolean
    /** DOWN+UP của một keycode (KEYCODE_DEL / DPAD_* / ENTER). */
    fun sendKey(keyCode: Int)
}

/** [EditorPort] thật trên InputConnection của hệ thống. */
class InputConnectionPort(private val ic: InputConnection) : EditorPort {
    override fun beginBatch() { ic.beginBatchEdit() }
    override fun endBatch() { ic.endBatchEdit() }
    override fun commitText(text: CharSequence) = ic.commitText(text, 1)
    override fun deleteBefore(utf16: Int) = ic.deleteSurroundingText(utf16, 0)
    override fun deleteCodePointsBefore(count: Int) = ic.deleteSurroundingTextInCodePoints(count, 0)
    override fun deleteSurrounding(before: Int, after: Int) = ic.deleteSurroundingText(before, after)
    override fun textBefore(n: Int): CharSequence? = ic.getTextBeforeCursor(n, 0)
    override fun textAfter(n: Int): CharSequence? = ic.getTextAfterCursor(n, 0)
    override fun selectedText(): CharSequence? = ic.getSelectedText(0)
    override fun setSelection(start: Int, end: Int) = ic.setSelection(start, end)
    override fun finishComposing() = ic.finishComposingText()
    override fun performEditorAction(actionId: Int) = ic.performEditorAction(actionId)
    override fun sendKey(keyCode: Int) {
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}

/**
 * Theo dõi vị trí con trỏ mà CHÍNH IME gây ra, để phân biệt onUpdateSelection do mình
 * (bỏ qua) với do người dùng chạm/dời con trỏ (phải reset engine, nếu không lần ⌫ kế sẽ
 * xoá nhầm chữ). Lưu danh sách vị trí kỳ vọng (mỗi edit một mốc) vì hệ thống có thể báo
 * từng bước hoặc gộp sau endBatchEdit.
 */
class SelectionTracker {
    /** Vị trí con trỏ (UTF-16) đã biết; -1 = chưa biết. */
    var cursor = -1; private set
    private val expected = ArrayDeque<Int>()

    fun reset(selStart: Int, selEnd: Int) {
        cursor = if (selStart == selEnd) selEnd else -1
        expected.clear()
    }

    fun inserted(utf16: Int) = shift(utf16)
    fun deleted(utf16: Int) = shift(-utf16)
    fun movedTo(pos: Int) { if (pos >= 0) { cursor = pos; expected.addLast(pos); trim() } }

    private fun shift(d: Int) {
        if (cursor < 0) return
        cursor = (cursor + d).coerceAtLeast(0)
        expected.addLast(cursor); trim()
    }

    private fun trim() { while (expected.size > 64) expected.removeFirst() }

    /**
     * onUpdateSelection(newSelStart, newSelEnd). true = do chính mình (bỏ qua);
     * false = thay đổi từ bên ngoài (đã cập nhật [cursor]).
     */
    fun onUpdate(selStart: Int, selEnd: Int): Boolean {
        if (selStart == selEnd) {
            val i = expected.indexOf(selEnd)
            if (i >= 0) {
                repeat(i + 1) { expected.removeFirst() }
                if (expected.isEmpty()) cursor = selEnd
                return true
            }
        }
        expected.clear()
        cursor = if (selStart == selEnd) selEnd else -1
        return false
    }

    val pendingCount: Int get() = expected.size
}

/**
 * [TextProxy] trên một [EditorPort]: engine ghi thẳng bằng commitText + deleteSurroundingText
 * (diff), luôn trong một batch edit do caller mở bằng [begin]/[end]. Không dùng composing span
 * (không gạch chân) ⇒ ổn định trong Chrome/Brave omnibox, Messenger, ô mật khẩu.
 *
 * - ⌫ khi không soạn: xoá đúng grapheme cuối (emoji ZWJ, dấu kết hợp) bằng deleteSurroundingText;
 *   KEYCODE_DEL chỉ khi không đọc được chữ (TYPE_NULL, ô trống — app tự xử lý, ví dụ xoá chip).
 * - Có selection ⇒ ⌫ = commitText("") (xoá vùng chọn).
 */
class InputBridge(private val portOf: () -> EditorPort?) : TextProxy {
    var config: FieldConfig = FieldConfig.DEFAULT
        private set
    val tracker = SelectionTracker()
    private var port: EditorPort? = null
    private var depth = 0
    /** Lần ghi gần nhất thất bại (InputConnection chết / app từ chối) ⇒ IME nên reset engine. */
    var failed = false
        private set

    fun startInput(config: FieldConfig, selStart: Int, selEnd: Int) {
        this.config = config
        tracker.reset(selStart, selEnd)
        failed = false
    }

    /** Mở batch edit (lồng được). false = không có InputConnection. */
    fun begin(): Boolean {
        if (depth == 0) {
            port = portOf() ?: return false
            port?.beginBatch()
        }
        depth++
        return true
    }

    fun end() {
        if (depth == 0) return
        depth--
        if (depth == 0) { port?.endBatch(); port = null }
    }

    inline fun <T> edit(block: () -> T): T? {
        if (!begin()) return null
        try { return block() } finally { end() }
    }

    fun takeFailure(): Boolean { val f = failed; failed = false; return f }

    private fun conn(): EditorPort? = port ?: portOf()

    override val isSecure: Boolean get() = config.isSecure

    override fun insertText(text: String) {
        val c = conn() ?: run { failed = true; return }
        if (text.isEmpty()) return
        if (config.rawKeys && text == "\n") { c.sendKey(KeyEvent.KEYCODE_ENTER); return }
        if (!c.commitText(text)) { failed = true; return }
        tracker.inserted(text.length)
    }

    override fun insertLineBreak() {
        val c = conn() ?: return
        if (c.commitText("\n")) tracker.inserted(1) else failed = true
    }

    override fun deleteCodePoints(count: Int) {
        if (count <= 0) return
        val c = conn() ?: run { failed = true; return }
        // Đo trước số UTF-16 để cập nhật tracker chính xác (chữ ngoài BMP chiếm 2 unit).
        val before = c.textBefore(count * 2)?.toString()
        if (!c.deleteCodePointsBefore(count)) { failed = true; return }
        val units = if (before != null) utf16Of(before, count) else count
        tracker.deleted(units)
    }

    override fun deleteBackward() {
        val c = conn() ?: return
        if (hasSelection) {
            if (c.commitText("")) tracker.reset(-1, -1) else failed = true
            return
        }
        val before = if (config.rawKeys) null else c.textBefore(32)?.toString()
        if (before.isNullOrEmpty()) { c.sendKey(KeyEvent.KEYCODE_DEL); tracker.reset(-1, -1); return }
        val n = lastGraphemeLength(before)
        if (c.deleteBefore(n)) tracker.deleted(n) else failed = true
    }

    override fun contextBeforeInput(): String? =
        if (config.rawKeys) null else conn()?.textBefore(CONTEXT_CHARS)?.toString()

    override fun contextAfterInput(): String? = if (config.rawKeys) null else conn()?.textAfter(4)?.toString()

    override fun confirmTail(expected: String): Boolean {
        if (expected.isEmpty()) return true
        val before = contextBeforeInput() ?: return true
        return before.endsWith(expected)
    }

    override val canReEdit: Boolean get() = config.canReEdit

    override val hasSelection: Boolean
        get() = !config.rawKeys && (conn()?.selectedText()?.isNotEmpty() == true)

    override fun selectedText(): String? = conn()?.selectedText()?.toString()?.takeIf { it.isNotEmpty() }

    override fun clearAll() {
        val c = conn() ?: return
        c.commitText("")
        c.deleteSurrounding(20_000, 20_000)
        tracker.reset(0, 0)
    }

    /** Phím Enter: action của ô (Go/Search/Send…) hoặc xuống dòng. */
    fun enter() {
        val c = conn() ?: return
        val f = config
        if (f.actionId != 0 && f.enterAction != EnterAction.NEWLINE) { c.performEditorAction(f.actionId); return }
        if (f.rawKeys || !f.multiLine && f.kind != FieldKind.TEXT) { c.sendKey(KeyEvent.KEYCODE_ENTER); return }
        insertText("\n")
    }

    /**
     * Trackpad: dời con trỏ [dx] ký tự (âm = trái) và [dy] dòng. Ngang dùng setSelection khi
     * biết vị trí (đồng bộ, không trôi), dọc / không biết vị trí ⇒ phím DPAD.
     */
    fun moveCursor(dx: Int, dy: Int) {
        val c = conn() ?: return
        if (dx != 0) {
            val cur = tracker.cursor
            if (!config.rawKeys && cur >= 0) {
                val target = if (dx < 0) {
                    val before = c.textBefore(-dx * 2 + 2)?.toString().orEmpty()
                    cur - utf16Of(before, -dx).coerceAtMost(cur)
                } else {
                    val after = c.textAfter(dx * 2 + 2)?.toString().orEmpty()
                    cur + utf16Forward(after, dx)
                }
                if (target != cur && c.setSelection(target, target)) tracker.movedTo(target)
            } else {
                repeat(kotlin.math.abs(dx)) { c.sendKey(if (dx < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT) }
                tracker.reset(-1, -1)
            }
        }
        if (dy != 0) {
            repeat(kotlin.math.abs(dy)) { c.sendKey(if (dy < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN) }
            tracker.reset(-1, -1)
        }
    }

    /** Xoá đúng [text] ngay trước con trỏ nếu đuôi khớp (vuốt ⌫ xoá theo từ). */
    fun deleteExact(text: String): Boolean {
        if (text.isEmpty()) return true
        if (!confirmTail(text)) return false
        val c = conn() ?: return false
        if (!c.deleteBefore(text.length)) { failed = true; return false }
        tracker.deleted(text.length)
        return true
    }

    companion object {
        const val CONTEXT_CHARS = 256

        /** Số UTF-16 unit của [count] code point CUỐI của [s]. */
        fun utf16Of(s: String, count: Int): Int {
            var end = s.length; var k = count
            while (k > 0 && end > 0) { end = s.offsetByCodePoints(end, -1); k-- }
            return s.length - end + k   // k > 0: đọc thiếu, coi mỗi code point còn lại = 1 unit
        }

        private fun utf16Forward(s: String, count: Int): Int {
            var i = 0; var k = count
            while (k > 0 && i < s.length) { i = s.offsetByCodePoints(i, 1); k-- }
            return i
        }

        /** Độ dài UTF-16 của grapheme cuối (BreakIterator xử lý emoji ZWJ, cờ, dấu kết hợp). */
        fun lastGraphemeLength(s: String): Int {
            if (s.isEmpty()) return 0
            val it = BreakIterator.getCharacterInstance()
            it.setText(s)
            val end = it.last()
            var start = it.previous()
            if (start == BreakIterator.DONE) return 1
            // BreakIterator cũ (JVM < 20, vài ROM) tách tông da / ZWJ / VS16 khỏi emoji gốc: gộp lại.
            while (start > 0) {
                val first = s.codePointAt(start)
                val prev = s.codePointBefore(start)
                val glue = first in 0x1F3FB..0x1F3FF || first == 0xFE0F || first == 0x200D || prev == 0x200D
                if (!glue) break
                val p = it.preceding(start)
                if (p == BreakIterator.DONE) break
                start = p
            }
            return (end - start).coerceAtLeast(1)
        }
    }
}
