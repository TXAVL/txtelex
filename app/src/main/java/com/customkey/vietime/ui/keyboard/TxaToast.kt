package com.customkey.vietime.ui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * TxaToast — Toast nổi dạng card kính trên bàn phím.
 * Dùng cho các thông báo dài của app (như hướng dẫn, cảnh báo dịch máy / Play, thêm dấu…)
 * để không bị cắt chữ "......." trên thanh gợi ý hẹp.
 */
@SuppressLint("ViewConstructor")
class TxaToastView(context: Context) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())

    private val iconView = TextView(context)
    private val textView = TextView(context)
    private val closeView = TextView(context)

    private val hideRunnable = Runnable { hide() }

    init {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * d
            setColor(0xF00D152D.toInt()) // Kính tối navy đậm
            setStroke((1.2f * d).toInt(), 0x8800E5FF.toInt()) // Viền cyan neon dịu
        }
        background = bg
        elevation = 12f * d

        val contentRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), (9 * d).toInt(), (12 * d).toInt(), (9 * d).toInt())
        }

        iconView.apply {
            textSize = 17f
            setTextColor(0xFF00E5FF.toInt())
            setPadding(0, 0, (8 * d).toInt(), 0)
            gravity = Gravity.CENTER
        }

        textView.apply {
            textSize = 13f
            setTextColor(0xFFEFF4FF.toInt())
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(2f * d, 1.15f)
        }

        closeView.apply {
            text = "✕"
            textSize = 12f
            setTextColor(0x77EFF4FF.toInt())
            setPadding((10 * d).toInt(), 0, (2 * d).toInt(), 0)
            gravity = Gravity.CENTER
        }

        contentRow.addView(iconView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        contentRow.addView(textView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        contentRow.addView(closeView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        addView(contentRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        setOnClickListener { hide() }
        visibility = GONE
    }

    fun applyStyle(style: KeyboardStyle) {
        (background as? GradientDrawable)?.apply {
            setStroke((1.2f * d).toInt(), (style.accent and 0x00FFFFFF) or 0x88000000.toInt())
        }
        iconView.setTextColor(style.accent)
    }

    fun show(message: String, icon: String? = null, durationMs: Long = 3500L) {
        mainHandler.removeCallbacks(hideRunnable)

        val resolvedIcon = icon ?: if (message.contains("dịch", ignoreCase = true)) "🌐"
        else if (message.contains("dấu", ignoreCase = true)) "✨"
        else "✦"

        iconView.text = resolvedIcon
        textView.text = message

        visibility = VISIBLE
        alpha = 0f
        translationY = -18f * d
        scaleX = 0.94f
        scaleY = 0.94f

        animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(240)
            .setInterpolator(DecelerateInterpolator())
            .start()

        mainHandler.postDelayed(hideRunnable, durationMs)
    }

    fun hide() {
        mainHandler.removeCallbacks(hideRunnable)
        if (visibility != VISIBLE) return
        animate()
            .alpha(0f)
            .translationY(-14f * d)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(180)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { visibility = GONE }
            .start()
    }
}

object TxaToast {
    /** Ngưỡng độ dài ký tự: thông báo dài hơn mức này sẽ được chuyển thành TxaToast nổi. */
    const val LONG_TEXT_THRESHOLD = 28

    fun isLong(message: String?): Boolean {
        if (message == null) return false
        return message.length > LONG_TEXT_THRESHOLD || message.contains("\n")
    }
}
