package com.customkey.vietime.ui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View

/**
 * Vệt sáng gõ vuốt (Swipe Trail): Overlay phủ lên vùng phím, không nhận touch.
 * Chỉ giữ lại các điểm trong [TAIL_MS] gần nhất; khi nhấc tay thì vệt mờ dần [FADE_MS].
 * Toàn bộ mảng điểm, Path và Paint được cấp phát trước — onDraw không cấp phát bộ nhớ.
 */
@SuppressLint("ViewConstructor")
class SwipeTrailView(context: Context) : View(context) {
    private val xs = FloatArray(CAP)
    private val ys = FloatArray(CAP)
    private val ts = LongArray(CAP)
    private var head = 0
    private var size = 0
    private var active = false
    private var fadeStart = 0L
    private val path = Path()
    private val density = resources.displayMetrics.density

    var trailColor: Int = 0xFF00E5FF.toInt()
        set(value) {
            field = value
            paint.color = value
            baseAlpha = (Color.alpha(value) * 0.85f).toInt()
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 4.5f * density
        color = trailColor
    }
    private var baseAlpha = (Color.alpha(trailColor) * 0.85f).toInt()

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    override fun onTouchEvent(event: android.view.MotionEvent?) = false

    fun begin() {
        size = 0
        head = 0
        active = true
        fadeStart = 0L
        postInvalidateOnAnimation()
    }

    fun add(x: Float, y: Float, tMs: Long = SystemClock.uptimeMillis()) {
        if (!active) return
        xs[head] = x
        ys[head] = y
        ts[head] = tMs
        head = (head + 1) % CAP
        if (size < CAP) size++
        postInvalidateOnAnimation()
    }

    /** Nhấc tay (mờ dần) hoặc huỷ ([fade] = false: xoá ngay lập tức). */
    fun end(fade: Boolean) {
        if (!active && size == 0) return
        active = false
        if (fade && size > 1) {
            fadeStart = SystemClock.uptimeMillis()
            postInvalidateOnAnimation()
        } else {
            size = 0
            invalidate()
        }
    }

    override fun onDraw(c: Canvas) {
        if (size < 2) return
        val now = SystemClock.uptimeMillis()
        var alpha = baseAlpha
        if (!active) {
            val f = (now - fadeStart).toFloat() / FADE_MS
            if (f >= 1f) {
                size = 0
                return
            }
            alpha = (baseAlpha * (1f - f)).toInt()
        }

        val cutoff = (if (active) now else fadeStart) - TAIL_MS
        path.reset()
        var n = 0
        for (i in 0 until size) {
            val k = (head - size + i + CAP) % CAP
            if (ts[k] < cutoff) continue
            if (n == 0) path.moveTo(xs[k], ys[k]) else path.lineTo(xs[k], ys[k])
            n++
        }
        if (n >= 2) {
            paint.alpha = alpha
            c.drawPath(path, paint)
        }
        if (!active || n >= 2) postInvalidateOnAnimation()
    }

    companion object {
        private const val CAP = 128
        const val TAIL_MS = 300L
        const val FADE_MS = 220L
    }
}

