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

/**
 * Bảng công cụ thay chỗ vùng phím: tab Clipboard (lịch sử, ghim, xoá) / Mẫu câu, cùng các nút
 * hành động: Thêm dấu, Dịch. Dựng bằng View thường (không RecyclerView — ≤ 50 mục).
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class ToolsPaneView(context: Context) : LinearLayout(context) {
    interface Listener {
        fun onPasteText(text: String)
        fun onPin(item: ClipItem, pinned: Boolean)
        fun onDeleteClip(item: ClipItem)
        fun onClearUnpinned()
        fun onAddTones()
        fun onTranslate()
        fun onClose()
    }

    enum class Tab { CLIPBOARD, SNIPPETS }

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

    fun select(t: Tab) { tab = t; render() }

    private fun render() {
        bar.removeAllViews()
        bar.addView(pill("📋 Clipboard", tab == Tab.CLIPBOARD) { select(Tab.CLIPBOARD) })
        bar.addView(pill("💬 Mẫu câu", tab == Tab.SNIPPETS) { select(Tab.SNIPPETS) })
        bar.addView(pill("✨ Thêm dấu", false) { listener?.onAddTones() })
        bar.addView(pill(if (translateAvailable) "🌐 Dịch" else "🌐 Dịch (Play)", false) { listener?.onTranslate() })
        bar.addView(pill("⌨", false) { listener?.onClose() })

        body.removeAllViews()
        when (tab) {
            Tab.CLIPBOARD -> {
                if (!historyEnabled) body.addView(hint("Lịch sử clipboard đang tắt — bật trong app TxTelex › Công cụ."))
                else if (clips.isEmpty()) body.addView(hint("Chưa có gì. Copy chữ ở bất kỳ đâu, nó sẽ hiện ở đây."))
                for (c in clips) body.addView(clipRow(c))
                if (clips.any { !it.pinned }) body.addView(link("Xoá các mục chưa ghim") { listener?.onClearUnpinned() })
            }
            Tab.SNIPPETS -> {
                if (snippets.isEmpty()) body.addView(hint("Chưa có mẫu câu. Thêm trong app TxTelex › Công cụ."))
                for (s in snippets) body.addView(row(s.text, null, null) { listener?.onPasteText(s.text) })
            }
        }
    }

    private fun clipRow(c: ClipItem): View {
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

    private fun pill(t: String, selected: Boolean, onClick: () -> Unit) = TextView(context).apply {
        val s = style
        text = t; gravity = Gravity.CENTER
        setTextColor(if (selected) 0xFF0A1024.toInt() else s.label)
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
        text = t; setTextColor(style.accent); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); gravity = Gravity.CENTER
        setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt()); setOnClickListener { onClick() }
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radiusDp * d }
    private fun withAlpha(c: Int, a: Float) = (c and 0x00FFFFFF) or ((255 * a.coerceIn(0f, 1f)).toInt() shl 24)
}
