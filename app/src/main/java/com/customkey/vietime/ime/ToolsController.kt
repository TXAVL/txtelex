package com.customkey.vietime.ime

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import com.customkey.vietime.tools.ClipItem
import com.customkey.vietime.tools.ClipboardVault
import com.customkey.vietime.tools.MathEvaluator
import com.customkey.vietime.tools.ToneRestorer
import com.customkey.vietime.tools.ToolsRepo
import com.customkey.vietime.tools.Translator
import com.customkey.vietime.ui.keyboard.KeyboardPanel
import com.customkey.vietime.ui.keyboard.ToolsPaneView

/**
 * Phần "công cụ" của IME: lịch sử clipboard + chip dán nhanh (OTP/link…), chip kết quả phép
 * tính sau "=", chip thêm dấu cả câu, bảng Clipboard / Mẫu câu, thêm dấu cho vùng chọn, dịch.
 */
class ToolsController(
    private val ctx: Context,
    private val proxy: InputBridge,
    private val session: TypingSession,
    private val panel: () -> KeyboardPanel?,
    private val main: Handler,
    private val worker: Handler,
    /** Chạy [block] trong batch edit của IME (reset engine nếu ghi lỗi). */
    private val edit: (() -> Unit) -> Unit,
    private val refresh: () -> Unit,
    private val onCommitImage: ((android.net.Uri, String) -> Boolean)? = null,
) : ToolsPaneView.Listener {
    private val prefs = ImePrefs.of(ctx)
    private val cm = ctx.getSystemService(ClipboardManager::class.java)

    /** Lần đổi clipboard gần nhất (uptime) và nội dung đã mời dán (để chỉ mời một lần). */
    private var clipChangedAt = 0L
    private var offeredClip: String? = null
    private var offeredImage: Pair<String, String>? = null
    private var pasteUsed = false

    /** Chip thêm dấu tính nền: kế hoạch hiện hành + generation lúc tính. */
    @Volatile private var tonesPlan: ToneRestorer.Edit? = null
    private var tonesGen = -1

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onClipChanged() }
    private val repoListener: () -> Unit = { main.post { if (panel()?.toolsShown == true) fillPane() } }

    fun start() {
        cm?.addPrimaryClipChangedListener(clipListener)
        ToolsRepo.listeners += repoListener
    }

    fun stop() {
        cm?.removePrimaryClipChangedListener(clipListener)
        ToolsRepo.listeners -= repoListener
    }

    private fun on(key: String) = prefs.getBoolean(key, true)

    private fun onClipChanged() {
        val clip = runCatching { cm?.primaryClip }.getOrNull() ?: return
        val img = clipImage(clip)
        val text = clipText(clip)
        if (img == null && text == null) return
        clipChangedAt = SystemClock.uptimeMillis()
        pasteUsed = false
        offeredClip = text
        offeredImage = img
        if (on(ToolsRepo.KEY_CLIP_HISTORY) && !isSensitive(clip.description)) {
            ToolsRepo.editClipboard(ctx) { vault ->
                if (img != null) {
                    vault.addImage(img.first, img.second, System.currentTimeMillis())
                } else if (text != null) {
                    vault.add(text, System.currentTimeMillis())
                }
                true
            }
        }
        main.post(refresh)
    }

    private fun clipText(c: ClipData): String? =
        if (c.itemCount == 0) null else c.getItemAt(0).coerceToText(ctx)?.toString()?.takeIf { it.isNotBlank() }

    private fun clipImage(c: ClipData): Pair<String, String>? {
        if (c.itemCount == 0) return null
        val item = c.getItemAt(0)
        val uri = item.uri ?: return null
        val desc = c.description
        var mime: String? = null
        if (desc != null) {
            for (i in 0 until desc.mimeTypeCount) {
                val m = desc.getMimeType(i)
                if (m.startsWith("image/")) {
                    mime = m
                    break
                }
            }
        }
        val uriStr = uri.toString()
        if (mime == null && (uriStr.endsWith(".png") || uriStr.endsWith(".jpg") || uriStr.endsWith(".jpeg") || uriStr.endsWith(".webp") || uriStr.endsWith(".gif"))) {
            mime = "image/png"
        }
        return if (mime != null) uriStr to mime else null
    }

    /** Android 13+: app đánh dấu nội dung nhạy cảm (mật khẩu) ⇒ không lưu lịch sử. */
    private fun isSensitive(d: ClipDescription?): Boolean =
        Build.VERSION.SDK_INT >= 33 && d?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true

    // MARK: chip đặc biệt

    /** Chip đặc biệt (nếu có) đứng trước gợi ý thường. */
    fun specialChip(): Suggestion? {
        val f = proxy.config
        if (f.isSecure || f.rawKeys) return null
        if (session.isComposing) return null
        val before = proxy.contextBeforeInput() ?: return null
        if (on(ToolsRepo.KEY_MATH_CHIP) && before.endsWith("=")) {
            MathEvaluator.chip(before)?.let { return Suggestion("= $it", Suggestion.Kind.MATH, it) }
        }
        tonesPlan?.takeIf { tonesGen == session.generation && before.endsWith(it.original) }?.let { p ->
            // Kế hoạch chỉ gồm phần khác biệt ("om nay…") — ghép lại phần đầu từ để hiện đủ ("hôm nay…").
            val head = before.dropLast(p.original.length).takeLastWhile { !it.isWhitespace() }
            val shown = (head + p.replacement).trim()
            return Suggestion("✨ " + (if (shown.length > 26) "…" + shown.takeLast(25) else shown), Suggestion.Kind.TONES, p.replacement)
        }
        pasteImageChip(before)?.let { return it }
        pasteChip(before)?.let { return it }
        return null
    }

    private fun pasteImageChip(before: String): Suggestion? {
        if (!on(ToolsRepo.KEY_PASTE_CHIP) || pasteUsed) return null
        if (SystemClock.uptimeMillis() - clipChangedAt > PASTE_WINDOW_MS || clipChangedAt == 0L) return null
        if (before.isNotEmpty() && !before.last().isWhitespace()) return null
        val img = offeredImage ?: runCatching { cm?.primaryClip?.let { clipImage(it) } }.getOrNull() ?: return null
        offeredImage = img
        return Suggestion("🖼️ Dán ảnh", Suggestion.Kind.PASTE_IMAGE, img.first)
    }

    private fun pasteChip(before: String): Suggestion? {
        if (!on(ToolsRepo.KEY_PASTE_CHIP) || pasteUsed) return null
        if (SystemClock.uptimeMillis() - clipChangedAt > PASTE_WINDOW_MS || clipChangedAt == 0L) return null
        if (before.isNotEmpty() && !before.last().isWhitespace()) return null
        val clip = runCatching { cm?.primaryClip }.getOrNull() ?: return null
        val text = clipText(clip) ?: return null
        val m = ClipboardVault.detect(text)
        offeredClip = text
        return Suggestion(ClipboardVault.chipLabel(m), Suggestion.Kind.PASTE, m.value)
    }

    /** Sau dấu cách / dấu câu: tính nền xem câu trước con trỏ có cần thêm dấu không. */
    fun scheduleTones() {
        tonesPlan = null
        if (!on(ToolsRepo.KEY_TONES_CHIP) || session.englishMode) return
        val f = proxy.config
        if (f.isSecure || f.passthrough || !f.suggestionsAllowed) return
        val before = proxy.contextBeforeInput() ?: return
        if (!ToneRestorer.looksUntoned(before)) return
        val gen = session.generation
        worker.post {
            val plan = runCatching { ToneRestorer.planBefore(before, session.lm) }.getOrNull()
            main.post {
                if (plan != null && gen == session.generation) { tonesPlan = plan; tonesGen = gen; refresh() }
            }
        }
    }

    /** Chạm chip đặc biệt. true = đã xử lý. */
    fun accept(s: Suggestion): Boolean {
        val payload = s.payload ?: return false
        when (s.kind) {
            Suggestion.Kind.MATH -> edit { proxy.insertText(payload) }
            Suggestion.Kind.PASTE -> { pasteUsed = true; edit { proxy.insertText(payload) } }
            Suggestion.Kind.PASTE_IMAGE -> {
                pasteUsed = true
                val uri = android.net.Uri.parse(payload)
                val mime = offeredImage?.second ?: "image/png"
                val ok = onCommitImage?.invoke(uri, mime) ?: false
                if (!ok) {
                    panel()?.showToast("Ứng dụng này không hỗ trợ chèn ảnh trực tiếp từ bàn phím. Hãy chạm giữ ô nhập để Dán ảnh.", "🖼️")
                }
            }
            Suggestion.Kind.TONES -> {
                val p = tonesPlan ?: return true
                tonesPlan = null
                edit {
                    if (proxy.confirmTail(p.original)) {
                        proxy.deleteCodePoints(p.original.codePointCount(0, p.original.length))
                        proxy.insertText(p.replacement)
                    }
                }
            }
            else -> return false
        }
        session.externalSelectionChange()
        return true
    }

    // MARK: bảng công cụ

    fun toggle() {
        val p = panel() ?: return
        if (p.toolsShown) { p.showTools(false); return }
        fillPane()
        p.showTools(true)
    }

    fun close() { panel()?.showTools(false) }

    private fun fillPane() {
        val p = panel() ?: return
        p.tools.listener = this
        p.tools.setData(ToolsRepo.clipboard(ctx).items, ToolsRepo.snippets(ctx).items,
            on(ToolsRepo.KEY_CLIP_HISTORY), Translator.isAvailable(ctx))
    }

    override fun onPasteText(text: String) {
        edit { session.commitBeforeEnter(proxy); proxy.insertText(text) }
        session.externalSelectionChange()
        close(); refresh()
    }

    override fun onPasteImage(uri: String, mimeType: String) {
        val ok = onCommitImage?.invoke(android.net.Uri.parse(uri), mimeType) ?: false
        if (ok) {
            close(); refresh()
        } else {
            panel()?.showToast("Ứng dụng này không hỗ trợ chèn ảnh trực tiếp từ bàn phím. Hãy chạm giữ ô nhập để Dán ảnh.", "🖼️")
        }
    }

    override fun onPin(item: ClipItem, pinned: Boolean) { ToolsRepo.editClipboard(ctx) { it.setPinned(item.id, pinned) } }
    override fun onDeleteClip(item: ClipItem) { ToolsRepo.editClipboard(ctx) { it.delete(item.id) } }
    override fun onClearUnpinned() { ToolsRepo.editClipboard(ctx) { it.clearUnpinned() } }
    override fun onClearAll() { ToolsRepo.wipeClipboard(ctx); fillPane() }
    override fun onClose() { close() }

    /** Thêm dấu: vùng chọn ⇒ thay vùng chọn; không thì câu trước con trỏ; không thì nội dung clipboard. */
    override fun onAddTones() {
        val p = panel()
        val sel = proxy.selectedText()
        if (sel != null) {
            val out = ToneRestorer.restore(sel, session.lm)
            if (out != null) edit { proxy.insertText(out) } else p?.showMessage("Vùng chọn đã có dấu")
            session.externalSelectionChange(); close(); return
        }
        val before = proxy.contextBeforeInput().orEmpty()
        val plan = ToneRestorer.planBefore(before, session.lm)
        if (plan != null) {
            edit {
                if (proxy.confirmTail(plan.original)) {
                    proxy.deleteCodePoints(plan.original.codePointCount(0, plan.original.length))
                    proxy.insertText(plan.replacement)
                }
            }
            session.externalSelectionChange(); close(); return
        }
        val clip = runCatching { cm?.primaryClip?.let(::clipText) }.getOrNull()
        val out = clip?.let { ToneRestorer.restore(it, session.lm) }
        if (out != null) {
            edit { session.commitBeforeEnter(proxy); proxy.insertText(out) }
            session.externalSelectionChange(); close()
        } else p?.showMessage("Chọn đoạn không dấu (hoặc copy nó) rồi bấm Thêm dấu", icon = "✨")
    }

    /** Dịch vùng chọn / câu trước con trỏ sang tiếng Anh (hoặc ngược lại) — module trên máy. */
    override fun onTranslate() {
        val p = panel()
        if (!Translator.isAvailable(ctx)) {
            Translator.requestInstall(ctx)
            p?.showMessage("Dịch trên máy: tải về từ Google Play khi cài bản Play", icon = "🌐")
            return
        }
        val sel = proxy.selectedText()
        val src = sel ?: proxy.contextBeforeInput()?.let { ToneRestorerSentence.last(it) }
        if (src.isNullOrBlank()) { p?.showMessage("Chọn đoạn cần dịch"); return }
        p?.showMessage("Đang dịch…")
        Translator.translate(ctx, src) { out ->
            main.post {
                p?.showMessage(null)
                if (out == null) { p?.showMessage("Không dịch được"); return@post }
                edit {
                    if (sel != null) proxy.insertText(out)
                    else if (proxy.confirmTail(src)) { proxy.deleteCodePoints(src.codePointCount(0, src.length)); proxy.insertText(out) }
                }
                session.externalSelectionChange(); close()
            }
        }
    }

    private companion object { const val PASTE_WINDOW_MS = 3 * 60 * 1000L }
}

/** Câu cuối trước con trỏ (đến dấu kết câu / xuống dòng trước đó). */
internal object ToneRestorerSentence {
    fun last(before: String): String {
        var i = before.length
        while (i > 0 && before[i - 1] !in ".!?\n") i--
        return before.substring(i).trimStart()
    }
}
