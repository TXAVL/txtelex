package com.customkey.vietime.ui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Khung bàn phím = thanh gợi ý + vùng phím, vẽ CHUNG một nền (màu + ảnh nền cắt giữa) để ảnh
 * phủ liền cả hai. Dùng cho IME và bản xem trước trong app (Theme Studio, Luyện vuốt).
 */
@SuppressLint("ViewConstructor")
class KeyboardPanel(context: Context) : FrameLayout(context) {
    val strip = SuggestionStripView(context).also { it.drawsBackground = false }
    val keyboard = KeyboardView(context).also { it.drawsBackground = false }
    /** Bảng công cụ (Clipboard / Mẫu câu…) — đè lên vùng phím, giữ nguyên chiều cao. */
    val tools = ToolsPaneView(context).also { it.visibility = GONE }
    /** Bảng emoji / kaomoji đầy đủ theo ref VietTelex. */
    val emoji = EmojiPaneView(context).also { it.visibility = GONE }
    val toast = TxaToastView(context)

    private val mainContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val body = FrameLayout(context)

    var style: KeyboardStyle = KeyboardStyle.neon
        set(v) {
            field = v
            strip.style = v
            keyboard.style = v
            tools.style = v
            emoji.style = v
            toast.applyStyle(v)
            invalidate()
        }

    val toolsShown: Boolean get() = tools.visibility == VISIBLE
    val emojiShown: Boolean get() = emoji.visibility == VISIBLE

    fun showTools(show: Boolean) {
        if (show) {
            emoji.visibility = GONE
            tools.layoutParams = (tools.layoutParams as FrameLayout.LayoutParams).apply {
                height = keyboard.height.takeIf { it > 0 } ?: keyboard.desiredHeight()
            }
        }
        tools.visibility = if (show) VISIBLE else GONE
        keyboard.visibility = if (show) INVISIBLE else VISIBLE
        strip.toolsOpen = show
        if (show) keyboard.cancelAllTouches()
    }

    fun showEmoji(show: Boolean) {
        if (show) {
            tools.visibility = GONE
            strip.toolsOpen = false
            emoji.layoutParams = (emoji.layoutParams as FrameLayout.LayoutParams).apply {
                height = keyboard.height.takeIf { it > 0 } ?: keyboard.desiredHeight()
            }
            emoji.open()
        }
        emoji.visibility = if (show) VISIBLE else GONE
        keyboard.visibility = if (show) INVISIBLE else VISIBLE
        if (show) keyboard.cancelAllTouches()
    }

    /**
     * Hiển thị thông báo:
     * - Thông báo ngắn (<= 28 ký tự): giữ nguyên vị trí trên thanh gợi ý (strip).
     * - Thông báo dài (> 28 ký tự, ví dụ thông báo tính năng dịch Play): tự động chuyển thành TxaToast nổi dạng card kính trên bàn phím.
     */
    fun showMessage(msg: String?, icon: String? = null) {
        if (msg == null) {
            strip.showMessage(null)
            return
        }
        if (TxaToast.isLong(msg)) {
            strip.showMessage(null)
            showToast(msg, icon)
        } else {
            strip.showMessage(msg)
        }
    }

    fun showToast(msg: String, icon: String? = null, durationMs: Long = 3500L) {
        toast.show(msg, icon, durationMs)
    }

    fun hideToast() {
        toast.hide()
    }

    private val src = Rect()
    private val dst = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setWillNotDraw(false)
        val d = resources.displayMetrics.density
        val stripH = (42 * d).toInt()
        mainContainer.addView(strip, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, stripH))
        body.addView(keyboard, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        body.addView(tools, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        body.addView(emoji, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        mainContainer.addView(body, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(mainContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val toastLp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL).apply {
            leftMargin = (12 * d).toInt()
            rightMargin = (12 * d).toInt()
            topMargin = (6 * d).toInt()
        }
        addView(toast, toastLp)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val stripH = if (strip.visibility != GONE) (42 * resources.displayMetrics.density).toInt() else 0
        val kbH = if (toolsShown) {
            tools.layoutParams = (tools.layoutParams as FrameLayout.LayoutParams).apply {
                height = keyboard.desiredHeight()
            }
            keyboard.desiredHeight()
        } else if (emojiShown) {
            emoji.layoutParams = (emoji.layoutParams as FrameLayout.LayoutParams).apply {
                height = keyboard.desiredHeight()
            }
            keyboard.desiredHeight()
        } else {
            keyboard.desiredHeight()
        }
        val targetH = stripH + kbH
        val rawWidth = MeasureSpec.getSize(widthMeasureSpec)
        val width = if (rawWidth > 0) rawWidth else resources.displayMetrics.widthPixels
        mainContainer.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(targetH, MeasureSpec.EXACTLY)
        )
        if (toast.visibility != GONE) {
            val d = resources.displayMetrics.density
            val availW = (width - 24 * d).toInt().coerceAtLeast(0)
            toast.measure(
                MeasureSpec.makeMeasureSpec(availW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(targetH, MeasureSpec.AT_MOST)
            )
        }
        setMeasuredDimension(width, targetH)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        val targetH = mainContainer.measuredHeight
        val topPos = (h - targetH).coerceAtLeast(0)
        mainContainer.layout(0, topPos, w, topPos + targetH)
        if (toast.visibility != GONE) {
            val toastW = toast.measuredWidth
            val toastH = toast.measuredHeight
            val toastL = (w - toastW) / 2
            val toastT = topPos + (6 * resources.displayMetrics.density).toInt()
            toast.layout(toastL, toastT, toastL + toastW, toastT + toastH)
        }
    }

    /** Ẩn/hiện thanh gợi ý (bản xem trước luyện vuốt không cần). */
    fun showStrip(show: Boolean) { strip.visibility = if (show) VISIBLE else GONE }

    override fun onDraw(canvas: Canvas) {
        val top = mainContainer.top.toFloat()
        val bottom = height.toFloat()
        if (bottom <= top) return
        canvas.save()
        canvas.clipRect(0f, top, width.toFloat(), bottom)
        canvas.drawColor(style.background)
        val bmp = style.wallpaper
        if (bmp != null && width > 0 && height > 0) {
            val vw = width.toFloat()
            val vh = bottom - top
            val scale = maxOf(vw / bmp.width, vh / bmp.height)
            val sw = (vw / scale).toInt().coerceAtMost(bmp.width)
            val sh = (vh / scale).toInt().coerceAtMost(bmp.height)
            val sx = (bmp.width - sw) / 2
            val sy = (bmp.height - sh) / 2
            src.set(sx, sy, sx + sw, sy + sh)
            dst.set(0f, top, vw, bottom)
            canvas.drawBitmap(bmp, src, dst, paint)
        }
        canvas.restore()
    }
}
