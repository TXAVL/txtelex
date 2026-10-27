package com.customkey.vietime.ui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.customkey.vietime.tools.ClipItem
import com.customkey.vietime.tools.ClipboardVault
import com.customkey.vietime.tools.Snippet
import com.customkey.vietime.tools.TextTransform

/**
 * Bảng công cụ thay chỗ vùng phím: tab Clipboard (lịch sử, ghim, xoá) / Mẫu câu, cùng các nút
 * hành động: Thêm dấu, Dịch. Dựng bằng View thường (không RecyclerView — ≤ 50 mục).
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class ToolsPaneView(context: Context) : LinearLayout(context) {
    interface Listener {
        fun onPasteText(text: String)
        fun onPasteImage(uri: String, mimeType: String) {}
        fun onPin(item: ClipItem, pinned: Boolean)
        fun onDeleteClip(item: ClipItem)
        fun onClearUnpinned()
        fun onClearAll() {}
        fun onAddTones()
        fun onTranslate()
        fun onClose()
        /** Bảng sửa văn bản: điều hướng / chọn / copy / cắt / dán. */
        fun onEdit(action: EditAction) {}
        /** Đổi kiểu chữ / bỏ dấu cho vùng chọn (hoặc câu vừa gõ). */
        fun onTransform(t: TextTransform) {}
    }

    enum class Tab { CLIPBOARD, SNIPPETS, EDIT }

    /** Nút của bảng sửa văn bản. */
    enum class EditAction { LEFT, RIGHT, UP, DOWN, HOME, END, SELECT_ALL, COPY, CUT, PASTE, UNDO, DELETE_WORD }

    /** Chế độ chọn: mũi tên mở rộng vùng chọn (giữ Shift). */
    var selecting = false
        private set

    var listener: Listener? = null
    var style: KeyboardStyle = KeyboardStyle.neon
        set(v) { field = v; render() }
    var tab = Tab.CLIPBOARD
        private set

    private val d = resources.displayMetrics.density
    private var clips: List<ClipItem> = emptyList()
    private var snippets: List<Snippet> = emptyList()
    private var historyEnabled = true
    private var translateAvailable = false
    private var filterQuery = ""

    private val bar = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val barScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(bar) }
    private val body = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll = ScrollView(context).apply { addView(body); isVerticalScrollBarEnabled = false }

    init {
        orientation = VERTICAL
        addView(barScroll, LayoutParams(LayoutParams.MATCH_PARENT, (48 * d).toInt()))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
    }

    fun setData(clips: List<ClipItem>, snippets: List<Snippet>, historyEnabled: Boolean, translateAvailable: Boolean) {
        this.clips = clips; this.snippets = snippets
        this.historyEnabled = historyEnabled; this.translateAvailable = translateAvailable
        render()
    }

    fun select(t: Tab) { tab = t; filterQuery = ""; render() }

    private fun render() {
        bar.removeAllViews()
        bar.addView(pill("📋 Clipboard", tab == Tab.CLIPBOARD) { select(Tab.CLIPBOARD) })
        bar.addView(pill("💬 Mẫu câu", tab == Tab.SNIPPETS) { select(Tab.SNIPPETS) })
        bar.addView(pill("✂ Sửa", tab == Tab.EDIT) { select(Tab.EDIT) })
        bar.addView(pill("✨ Thêm dấu", false) { listener?.onAddTones() })
        bar.addView(pill(if (translateAvailable) "🌐 Dịch" else "🌐 Dịch (Play)", false) { listener?.onTranslate() })
        bar.addView(pill("⌨", false) { listener?.onClose() })

        body.removeAllViews()
        when (tab) {
            Tab.CLIPBOARD -> {
                if (!historyEnabled) {
                    body.addView(hint("Lịch sử clipboard đang tắt — bật trong app TxTelex › Công cụ."))
                    return
                }
                if (clips.isEmpty()) {
                    body.addView(hint("Chưa có gì. Copy văn bản hoặc ảnh ở bất kỳ đâu, nó sẽ hiện ở đây."))
                    return
                }

                // Thanh tìm kiếm nhanh nội dung clipboard
                val searchBox = android.widget.EditText(context).apply {
                    hint = "🔍 Tìm kiếm trong clipboard..."
                    setHintTextColor(withAlpha(style.label, 0.45f))
                    setTextColor(style.label)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    background = rounded(withAlpha(style.keyFill, 0.4f), 8f)
                    setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
                    maxLines = 1
                    isSingleLine = true
                    if (filterQuery.isNotEmpty()) {
                        setText(filterQuery)
                        setSelection(filterQuery.length)
                    }
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            val q = s?.toString() ?: ""
                            if (filterQuery != q) {
                                filterQuery = q
                                updateClipList()
                            }
                        }
                        override fun afterTextChanged(s: android.text.Editable?) {}
                    })
                }
                body.addView(searchBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = (8 * d).toInt()
                })

                val listContainer = LinearLayout(context).apply {
                    tag = "clip_list_container"
                    orientation = VERTICAL
                }
                body.addView(listContainer)
                populateClips(listContainer)

                // Hàng nút thao tác xoá
                val actionsRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
                }
                if (clips.any { !it.pinned }) {
                    actionsRow.addView(link("Xoá mục chưa ghim") { listener?.onClearUnpinned() })
                }
                actionsRow.addView(TextView(context).apply {
                    text = "  •  "
                    setTextColor(withAlpha(style.label, 0.3f))
                })
                actionsRow.addView(link("Xoá tất cả") { listener?.onClearAll() })
                body.addView(actionsRow)
            }
            Tab.EDIT -> renderEdit()
            Tab.SNIPPETS -> {
                if (snippets.isEmpty()) body.addView(hint("Chưa có mẫu câu. Thêm trong app TxTelex › Công cụ."))
                for (s in snippets) body.addView(row(s.text, null, null) { listener?.onPasteText(s.text) })
            }
        }
    }

    private fun updateClipList() {
        val listContainer = body.findViewWithTag<LinearLayout>("clip_list_container") ?: return
        listContainer.removeAllViews()
        populateClips(listContainer)
    }

    private fun populateClips(container: LinearLayout) {
        val filtered = if (filterQuery.isBlank()) clips else clips.filter {
            it.text.contains(filterQuery, ignoreCase = true)
        }
        if (filtered.isEmpty()) {
            container.addView(hint("Không tìm thấy kết quả phù hợp."))
            return
        }
        for (c in filtered) container.addView(clipRow(c))
    }

    private fun clipRow(c: ClipItem): View {
        if (c.isImage) {
            val label = "🖼️ [Hình ảnh] • Chạm để dán ảnh"
            return row(label, if (c.pinned) "📌" else "📍", "✕",
                onAux = { listener?.onPin(c, !c.pinned) },
                onAux2 = { listener?.onDeleteClip(c) }) {
                c.imageUri?.let { uri -> listener?.onPasteImage(uri, c.mimeType ?: "image/png") }
            }
        }
        val m = ClipboardVault.detect(c.text)
        val tag = if (m.kind != com.customkey.vietime.tools.ClipKind.TEXT) m.kind.icon + " " else ""
        return row(tag + c.text.replace('\n', ' '), if (c.pinned) "📌" else "📍", "✕",
            onAux = { listener?.onPin(c, !c.pinned) }, onAux2 = { listener?.onDeleteClip(c) }) { listener?.onPasteText(c.text) }
    }

    private fun row(text: String, aux: String?, aux2: String?, onAux: (() -> Unit)? = null, onAux2: (() -> Unit)? = null, onTap: () -> Unit): View {
        val s = style
        val r = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(withAlpha(s.keyFill, s.keyOpacity.coerceAtLeast(0.6f)), 10f)
            setPadding((12 * d).toInt(), (10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt())
            setOnClickListener { onTap() }
        }
        r.addView(TextView(context).apply {
            this.text = text; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setTextColor(s.label); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (aux != null) r.addView(icon(aux) { onAux?.invoke() })
        if (aux2 != null) r.addView(icon(aux2) { onAux2?.invoke() })
        return r.also {
            it.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = (6 * d).toInt() }
        }
    }

    private fun icon(t: String, onClick: () -> Unit) = TextView(context).apply {
        text = t; gravity = Gravity.CENTER; setTextColor(style.modifierLabel); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
        setOnClickListener { onClick() }
    }

    /** Lưới nút sửa văn bản: điều hướng (trái) + thao tác (phải) + hàng đổi kiểu chữ. */
    private fun renderEdit() {
        fun e(a: EditAction) = { listener?.onEdit(a); Unit }
        grid(listOf(
            listOf("⇤" to e(EditAction.HOME), "▲" to e(EditAction.UP), "⇥" to e(EditAction.END), "Chọn tất cả" to e(EditAction.SELECT_ALL)),
            listOf("◀" to e(EditAction.LEFT), (if (selecting) "◉ Đang chọn" else "◎ Chọn") to { selecting = !selecting; render() },
                "▶" to e(EditAction.RIGHT), "⧉ Copy" to e(EditAction.COPY)),
            listOf("⌫ từ" to e(EditAction.DELETE_WORD), "▼" to e(EditAction.DOWN), "↶ Hoàn tác" to e(EditAction.UNDO), "✂ Cắt" to e(EditAction.CUT)),
        ), accentIndex = if (selecting) 5 else -1)
        body.addView(grid(listOf(listOf("📋 Dán" to e(EditAction.PASTE)) +
            TextTransform.entries.map { t -> t.label to { listener?.onTransform(t); Unit } }), small = true, attach = false))
        body.addView(hint("Đổi kiểu chữ áp cho vùng đang chọn — chưa chọn thì cho câu vừa gõ."))
    }

    private fun grid(rows: List<List<Pair<String, () -> Unit>>>, accentIndex: Int = -1, small: Boolean = false, attach: Boolean = true): View {
        val wrap = LinearLayout(context).apply { orientation = VERTICAL }
        var idx = 0
        for (row in rows) {
            val r = LinearLayout(context).apply { orientation = HORIZONTAL }
            for ((label, action) in row) {
                val on = idx == accentIndex
                r.addView(TextView(context).apply {
                    text = label; gravity = Gravity.CENTER; maxLines = 1
                    setTextColor(if (on) com.customkey.vietime.customization.Colors.onColor(style.accent) else style.label)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 12.5f else 15f)
                    background = rounded(if (on) style.accent else withAlpha(style.keyFill, style.keyOpacity.coerceAtLeast(0.7f)), 10f)
                    setPadding((4 * d).toInt(), ((if (small) 9 else 12) * d).toInt(), (4 * d).toInt(), ((if (small) 9 else 12) * d).toInt())
                    setOnClickListener { action() }
                }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { setMargins((3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt()) })
                idx++
            }
            wrap.addView(r, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        if (attach) body.addView(wrap)
        return wrap
    }

    private fun pill(t: String, selected: Boolean, onClick: () -> Unit) = TextView(context).apply {
        val s = style
        text = t; gravity = Gravity.CENTER
        setTextColor(if (selected) com.customkey.vietime.customization.Colors.onColor(s.accent) else s.label)
        setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        background = rounded(if (selected) s.accent else s.modifierFill, 18f)
        setPadding((14 * d).toInt(), (7 * d).toInt(), (14 * d).toInt(), (7 * d).toInt())
        setOnClickListener { onClick() }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = (6 * d).toInt() }
    }

    private fun hint(t: String) = TextView(context).apply {
        text = t; setTextColor(withAlpha(style.label, 0.6f)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding((8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt()); gravity = Gravity.CENTER
    }

    private fun link(t: String, onClick: () -> Unit) = TextView(context).apply {
        text = t; setTextColor(style.accent); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); gravity = Gravity.CENTER
        setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt()); setOnClickListener { onClick() }
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radiusDp * d }
    private fun withAlpha(c: Int, a: Float) = (c and 0x00FFFFFF) or ((255 * a.coerceIn(0f, 1f)).toInt() shl 24)
}
