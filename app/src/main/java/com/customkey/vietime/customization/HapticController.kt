package com.customkey.vietime.customization

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Loại thao tác cần rung — mỗi loại một lực riêng. */
enum class HapticKind { KEY, DELETE, CURSOR }

/** Lực rung 0…100 (0 = tắt) cho phím thường, phím xoá, trượt con trỏ. */
data class HapticConfig(val key: Int = 35, val delete: Int = 50, val cursor: Int = 20) {
    fun strength(k: HapticKind) = when (k) { HapticKind.KEY -> key; HapticKind.DELETE -> delete; HapticKind.CURSOR -> cursor }.coerceIn(0, 100)

    companion object {
        const val KEY_KEY = "haptic.key"
        const val KEY_DELETE = "haptic.delete"
        const val KEY_CURSOR = "haptic.cursor"

        fun load(get: (String) -> Any?): HapticConfig {
            val d = HapticConfig()
            fun i(k: String, def: Int) = ((get(k) as? Int) ?: def).coerceIn(0, 100)
            return HapticConfig(i(KEY_KEY, d.key), i(KEY_DELETE, d.delete), i(KEY_CURSOR, d.cursor))
        }

        /**
         * Lực → (thời lượng ms, biên độ 1…255). Máy có điều khiển biên độ: thời lượng ngắn cố
         * định, biên độ tăng theo lực; máy không có: chỉ kéo dài thời lượng.
         */
        fun pulse(kind: HapticKind, strength: Int, amplitudeControl: Boolean): Pair<Long, Int>? {
            if (strength <= 0) return null
            val s = strength.coerceIn(1, 100) / 100f
            val base = when (kind) { HapticKind.KEY -> 14L; HapticKind.DELETE -> 20L; HapticKind.CURSOR -> 7L }
            return if (amplitudeControl) base to (20 + 235 * s).toInt().coerceIn(1, 255)
            else (3 + base * s).toLong().coerceAtLeast(3) to VibrationEffect.DEFAULT_AMPLITUDE
        }
    }
}

/** Rung qua Vibrator (bỏ qua cài đặt "rung khi chạm" của hệ thống — người dùng đã chỉnh riêng). */
class HapticController(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

    private val amplitudeControl = vibrator?.hasAmplitudeControl() == true
    var config = HapticConfig()

    fun perform(kind: HapticKind) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val (ms, amp) = HapticConfig.pulse(kind, config.strength(kind), amplitudeControl) ?: return
        runCatching { v.vibrate(VibrationEffect.createOneShot(ms, amp)) }
    }

    fun cancel() { runCatching { vibrator?.cancel() } }
}
