package com.customkey.vietime.tools

import android.content.Context
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Kho dữ liệu công cụ dùng chung app ⇄ bàn phím (cùng process): lịch sử clipboard, mẫu câu.
 * Lưu file nhỏ trong filesDir (không sao lưu lên mạng). Đổi gì cũng báo [listeners] trên luồng gọi.
 */
object ToolsRepo {
    const val KEY_CLIP_HISTORY = "tools.clipHistory"
    const val KEY_PASTE_CHIP = "tools.pasteChip"
    const val KEY_MATH_CHIP = "tools.mathChip"
    const val KEY_TONES_CHIP = "tools.addTonesChip"

    private var clip: ClipboardVault? = null
    private var snip: SnippetStore? = null
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    private fun clipFile(ctx: Context) = File(ctx.applicationContext.filesDir, "clipboard_history.txt")
    private fun snipFile(ctx: Context) = File(ctx.applicationContext.filesDir, "snippets.txt")

    @Synchronized
    fun clipboard(ctx: Context): ClipboardVault =
        clip ?: ClipboardVault.decode(clipFile(ctx).takeIf { it.exists() }?.readText()).also { clip = it }

    @Synchronized
    fun snippets(ctx: Context): SnippetStore =
        snip ?: SnippetStore.decode(snipFile(ctx).takeIf { it.exists() }?.readText()).also { snip = it }

    /** Sửa clipboard rồi lưu + báo. */
    fun editClipboard(ctx: Context, block: (ClipboardVault) -> Boolean) {
        val changed = synchronized(this) { block(clipboard(ctx)).also { if (it) clipFile(ctx).writeText(clip!!.encode()) } }
        if (changed) notifyListeners()
    }

    fun editSnippets(ctx: Context, block: (SnippetStore) -> Boolean) {
        val changed = synchronized(this) { block(snippets(ctx)).also { if (it) snipFile(ctx).writeText(snip!!.encode()) } }
        if (changed) notifyListeners()
    }

    /** Xoá sạch lịch sử (kể cả mục ghim). */
    fun wipeClipboard(ctx: Context) {
        synchronized(this) { clip = ClipboardVault(); clipFile(ctx).delete() }
        notifyListeners()
    }

    private fun notifyListeners() { for (l in listeners) runCatching { l() } }
}
