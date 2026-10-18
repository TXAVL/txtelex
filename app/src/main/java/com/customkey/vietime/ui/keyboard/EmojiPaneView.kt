package com.customkey.vietime.ui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.customkey.vietime.engine.EmojiData
import com.customkey.vietime.engine.EmojiRecents
import com.customkey.vietime.engine.EmojiSuggest
import com.customkey.vietime.ime.ImePrefs

/**
 * Bảng Emoji đầy đủ (port theo ref VietTelex):
 * - Thanh tìm kiếm emoji theo tiếng Việt / tiếng Anh.
 * - Danh sách cuộn theo nhóm (Thường dùng, Mặt cười, Động vật, Đồ ăn, Hoạt động, Du lịch, Đồ vật, Biểu tượng, Cờ, Kaomoji).
 * - Thanh chuyển tab category và nút [ABC], [⌫] ở đáy.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class EmojiPaneView(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onEmojiPicked(emoji: String)
        fun onBackspace()
        fun onClose()
    }

    var listener: Listener? = null
    var style: KeyboardStyle = KeyboardStyle.neon
        set(v) { field = v; applyStyle() }

    private val d = resources.displayMetrics.density
    private val prefs = ImePrefs.of(context)
    private val handler = Handler(Looper.getMainLooper())

    private var currentTab = 0
    private var searchQuery = ""
    private var recents: List<String> = emptyList()

    private val searchBox = EditText(context).apply {
        hint = "🔍 Tìm emoji (cười, tim, mèo, xe…)"
        setSingleLine(true)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
    }

    private val contentScroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
    private val contentBody = LinearLayout(context).apply { orientation = VERTICAL }

    private val navBar = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val navScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(navBar) }

    init {
        orientation = VERTICAL
        // Thanh tìm kiếm
        val searchContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
            addView(searchBox, LayoutParams(0, (38 * d).toInt(), 1f))
        }
        addView(searchContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Vùng emoji cuộn
        contentScroll.addView(contentBody)
        addView(contentScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // Thanh điều hướng danh mục ở đáy
        addView(navScroll, LayoutParams(LayoutParams.MATCH_PARENT, (44 * d).toInt()))

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim() ?: ""
                renderContent()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadRecents()
        buildNavBar()
        applyStyle()
    }

    fun open() {
        loadRecents()
        searchBox.setText("")
        searchQuery = ""
        renderContent()
    }

    private fun loadRecents() {
        val raw = prefs.getString("emoji.recents", null)
        recents = EmojiRecents.decode(raw)
    }

    private fun saveRecent(e: String) {
        recents = EmojiRecents.noteUsed(recents, e)
        prefs.edit().putString("emoji.recents", EmojiRecents.encode(recents)).apply()
    }

    private fun applyStyle() {
        val s = style
        setBackgroundColor(s.background)
        searchBox.setTextColor(s.label)
        searchBox.setHintTextColor((s.label and 0x00FFFFFF) or 0x66000000.toInt())
        searchBox.background = GradientDrawable().apply {
            setColor(s.keyFill)
            cornerRadius = 14 * d
            setStroke(1, s.borderColor)
        }
        buildNavBar()
        renderContent()
    }

    private fun buildNavBar() {
        navBar.removeAllViews()

        // Nút [ABC] quay về bàn phím
        val abcBtn = TextView(context).apply {
            text = "ABC"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(style.accent)
            setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
            setOnClickListener { listener?.onClose() }
        }
        navBar.addView(abcBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))

        // Các icon danh mục
        val tabs = listOf(
            0 to "🕒",
            1 to "😀",
            2 to "🐻",
            3 to "🍔",
            4 to "⚽",
            5 to "🚗",
            6 to "💡",
            7 to "❤️",
            8 to "🚩",
            9 to "^‿^",
        )
        for ((idx, icon) in tabs) {
            val tv = TextView(context).apply {
                text = icon
                textSize = if (idx == 9) 13f else 18f
                gravity = Gravity.CENTER
                val pad = (9 * d).toInt()
                setPadding(pad, 0, pad, 0)
                alpha = if (currentTab == idx) 1f else 0.5f
                setOnClickListener {
                    currentTab = idx
                    searchBox.setText("")
                    searchQuery = ""
                    buildNavBar()
                    renderContent()
                }
            }
            navBar.addView(tv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }

        // Nút ⌫ xoá lùi
        val delBtn = TextView(context).apply {
            text = "⌫"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(style.label)
            setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
            setOnClickListener { listener?.onBackspace() }
        }
        navBar.addView(delBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    private fun renderContent() {
        contentBody.removeAllViews()

        if (searchQuery.isNotEmpty()) {
            renderSearch()
            return
        }

        if (currentTab == 0) {
            // Recents
            renderCategorySection("THƯỜNG DÙNG", if (recents.isNotEmpty()) recents else listOf("😀", "❤️", "👍", "🔥", "✨", "🎉"))
            return
        }

        if (currentTab == 9) {
            // Kaomoji
            renderKaomoji()
            return
        }

        // Category chuẩn từ EmojiData (1..8)
        val catIdx = currentTab - 1
        val cats = runCatching { EmojiData.categories }.getOrNull() ?: emptyList()
        if (catIdx in cats.indices) {
            val c = cats[catIdx]
            renderCategorySection(EmojiData.displayName(c.name), c.emoji)
        }
    }

    private fun renderSearch() {
        val q = searchQuery.lowercase()
        val results = ArrayList<String>()
        // 1. Thử qua EmojiSuggest
        val sug = EmojiSuggest.emojis(q)
        results.addAll(sug)

        // 2. Lọc trong EmojiData
        val allCats = runCatching { EmojiData.categories }.getOrNull() ?: emptyList()
        for (cat in allCats) {
            if (cat.name.contains(q)) {
                results.addAll(cat.emoji.take(16))
            }
        }
        val distinct = results.distinct()
        if (distinct.isNotEmpty()) {
            renderCategorySection("KẾT QUẢ CHO “$searchQuery”", distinct)
        } else {
            val emptyTv = TextView(context).apply {
                text = "Không tìm thấy emoji cho “$searchQuery”"
                textSize = 14f
                setTextColor((style.label and 0x00FFFFFF) or 0x88000000.toInt())
                gravity = Gravity.CENTER
                setPadding(0, (24 * d).toInt(), 0, 0)
            }
            contentBody.addView(emptyTv)
        }
    }

    private fun renderCategorySection(title: String, emojis: List<String>) {
        val titleTv = TextView(context).apply {
            text = title
            textSize = 12f
            setTextColor(style.accent)
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
        }
        contentBody.addView(titleTv)

        val cols = 7
        var row = LinearLayout(context).apply { orientation = HORIZONTAL }
        var inRow = 0

        for (e in emojis) {
            val tv = TextView(context).apply {
                text = e
                textSize = 26f
                gravity = Gravity.CENTER
                setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
                setOnClickListener {
                    saveRecent(e)
                    listener?.onEmojiPicked(e)
                }
                setOnLongClickListener {
                    val variants = EmojiData.toneVariants(e)
                    if (!variants.isNullOrEmpty()) {
                        showTonePopup(variants)
                        true
                    } else false
                }
            }
            row.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            inRow++
            if (inRow >= cols) {
                contentBody.addView(row)
                row = LinearLayout(context).apply { orientation = HORIZONTAL }
                inRow = 0
            }
        }
        if (inRow > 0) {
            while (inRow < cols) {
                row.addView(View(context), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                inRow++
            }
            contentBody.addView(row)
        }
    }

    private fun renderKaomoji() {
        val groups = runCatching { EmojiData.kaomoji }.getOrNull() ?: emptyList()
        for (g in groups) {
            val titleTv = TextView(context).apply {
                text = g.name.uppercase()
                textSize = 12f
                setTextColor(style.accent)
                setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
            }
            contentBody.addView(titleTv)

            var row = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding((8 * d).toInt(), 0, (8 * d).toInt(), (4 * d).toInt()) }
            var countInRow = 0

            for (item in g.items) {
                val chip = TextView(context).apply {
                    text = item
                    textSize = 14f
                    setTextColor(style.label)
                    gravity = Gravity.CENTER
                    val bg = GradientDrawable().apply {
                        setColor(style.keyFill)
                        cornerRadius = 10 * d
                        setStroke(1, style.borderColor)
                    }
                    background = bg
                    setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
                    setOnClickListener { listener?.onEmojiPicked(item) }
                }
                val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = (6 * d).toInt()
                    bottomMargin = (6 * d).toInt()
                }
                row.addView(chip, lp)
                countInRow++
                if (countInRow >= 3) {
                    contentBody.addView(row)
                    row = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding((8 * d).toInt(), 0, (8 * d).toInt(), (4 * d).toInt()) }
                    countInRow = 0
                }
            }
            if (countInRow > 0) contentBody.addView(row)
        }
    }

    private fun showTonePopup(variants: List<String>) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = GradientDrawable().apply {
                setColor(style.background)
                cornerRadius = 16 * d
                setStroke(2, style.accent)
            }
            setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
        }
        for (v in variants) {
            val tv = TextView(context).apply {
                text = v
                textSize = 28f
                setPadding((6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt())
                setOnClickListener {
                    saveRecent(v)
                    listener?.onEmojiPicked(v)
                    renderContent()
                }
            }
            row.addView(tv)
        }
        contentBody.addView(row, 0)
    }
}
