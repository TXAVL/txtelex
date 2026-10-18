package com.customkey.vietime.ime

/** EditorPort giả: một ô chữ với con trỏ/selection, ghi lại phím gửi đi. */
class FakeEditor(initial: String = "") : EditorPort {
    val sb = StringBuilder(initial)
    var selStart = initial.length
    var selEnd = initial.length
    val keys = ArrayList<Int>()
    val actions = ArrayList<Int>()
    var batchDepth = 0
    /** Mô phỏng onUpdateSelection: mỗi lần con trỏ đổi. */
    val selectionEvents = ArrayList<Pair<Int, Int>>()

    val text: String get() = sb.toString()

    private fun moved() { selectionEvents += selStart to selEnd }

    override fun beginBatch() { batchDepth++ }
    override fun endBatch() { batchDepth-- }

    override fun commitText(text: CharSequence): Boolean {
        sb.replace(selStart, selEnd, text.toString())
        selStart += text.length; selEnd = selStart
        moved(); return true
    }

    override fun deleteBefore(utf16: Int): Boolean {
        val n = minOf(utf16, selStart)
        sb.delete(selStart - n, selStart)
        selStart -= n; selEnd -= n
        moved(); return true
    }

    override fun deleteCodePointsBefore(count: Int): Boolean {
        var start = selStart; var k = count
        while (k > 0 && start > 0) { start = sb.offsetByCodePoints(start, -1); k-- }
        return deleteBefore(selStart - start)
    }

    override fun deleteSurrounding(before: Int, after: Int): Boolean {
        val a = minOf(after, sb.length - selEnd)
        sb.delete(selEnd, selEnd + a)
        return deleteBefore(before)
    }

    override fun textBefore(n: Int): CharSequence = sb.substring(maxOf(0, selStart - n), selStart)
    override fun textAfter(n: Int): CharSequence = sb.substring(selEnd, minOf(sb.length, selEnd + n))
    override fun selectedText(): CharSequence? = if (selStart == selEnd) null else sb.substring(selStart, selEnd)
    override fun setSelection(start: Int, end: Int): Boolean { selStart = start; selEnd = end; moved(); return true }
    override fun finishComposing() = true
    override fun performEditorAction(actionId: Int): Boolean { actions += actionId; return true }
    override fun sendKey(keyCode: Int) { keys += keyCode }
}
