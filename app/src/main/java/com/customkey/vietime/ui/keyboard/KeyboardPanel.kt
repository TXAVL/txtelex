package com.customkey.vietime.ui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.widget.LinearLayout

/**
 * Khung bàn phím = thanh gợi ý + vùng phím, vẽ CHUNG một nền (màu + ảnh nền cắt giữa) để ảnh
 * phủ liền cả hai. Dùng cho IME và bản xem trước trong app (Theme Studio, Luyện vuốt).
 */
@SuppressLint("ViewConstructor")
class KeyboardPanel(context: Context) : LinearLayout(context) {
    val strip = SuggestionStripView(context).also { it.drawsBackground = false }
    val keyboard = KeyboardView(context).also { it.drawsBackground = false }

    var style: KeyboardStyle = KeyboardStyle.neon
        set(v) { field = v; strip.style = v; keyboard.style = v; invalidate() }

    private val src = Rect()
    private val dst = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(keyboard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** Ẩn/hiện thanh gợi ý (bản xem trước luyện vuốt không cần). */
    fun showStrip(show: Boolean) { strip.visibility = if (show) VISIBLE else GONE }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(style.background)
        val bmp = style.wallpaper ?: return
        if (width == 0 || height == 0) return
        // center-crop ảnh vào khung hiện tại
        val vw = width.toFloat(); val vh = height.toFloat()
        val scale = maxOf(vw / bmp.width, vh / bmp.height)
        val sw = (vw / scale).toInt().coerceAtMost(bmp.width)
        val sh = (vh / scale).toInt().coerceAtMost(bmp.height)
        val sx = (bmp.width - sw) / 2; val sy = (bmp.height - sh) / 2
        src.set(sx, sy, sx + sw, sy + sh)
        dst.set(0f, 0f, vw, vh)
        canvas.drawBitmap(bmp, src, dst, paint)
    }
}
