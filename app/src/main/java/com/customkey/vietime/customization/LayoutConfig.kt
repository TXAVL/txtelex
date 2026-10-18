package com.customkey.vietime.customization

import com.customkey.vietime.engine.Keys
import com.customkey.vietime.ui.keyboard.LayoutOptions

/** Bàn phím tách đôi khi nào. */
enum class SplitMode(val id: String, val title: String) {
    OFF("off", "Không"),
    LANDSCAPE("landscape", "Khi xoay ngang / máy tính bảng"),
    ALWAYS("always", "Luôn luôn");

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: LANDSCAPE }
}

/**
 * Tuỳ chỉnh bố cục: hàng số, chiều cao (%), độ rộng (%), tách đôi, thời gian giữ phím.
 * Thuần dữ liệu — [load] đọc qua hàm getter (SharedPreferences.all) để test được.
 */
data class LayoutConfig(
    val numberRow: Boolean = false,
    /** 70…140 % chiều cao chuẩn. */
    val heightPercent: Int = 100,
    /** 75…100 % chiều ngang màn hình (căn giữa). */
    val widthPercent: Int = 100,
    val split: SplitMode = SplitMode.LANDSCAPE,
    /** 150…800 ms. */
    val longPressMs: Int = 300,
) {
    fun rowHeightDp(base: Float = BASE_ROW_DP): Float = base * heightPercent.coerceIn(MIN_HEIGHT, MAX_HEIGHT) / 100f

    /** [wide] = màn ngang hoặc ≥ 600dp. */
    fun options(wide: Boolean): LayoutOptions = LayoutOptions(
        numberRow = numberRow,
        splitGap = if (split == SplitMode.ALWAYS || (split == SplitMode.LANDSCAPE && wide)) SPLIT_GAP else 0f,
        widthFraction = widthPercent.coerceIn(MIN_WIDTH, 100) / 100f,
    )

    companion object {
        const val BASE_ROW_DP = 54f
        const val SPLIT_GAP = 3f
        const val MIN_HEIGHT = 70; const val MAX_HEIGHT = 140
        const val MIN_WIDTH = 75
        const val MIN_LONG = 150; const val MAX_LONG = 800

        const val KEY_HEIGHT = "layout.heightPercent"
        const val KEY_WIDTH = "layout.widthPercent"
        const val KEY_SPLIT = "layout.split"
        const val KEY_LONG_PRESS = "layout.longPressMs"

        fun load(get: (String) -> Any?): LayoutConfig = LayoutConfig(
            numberRow = (get(Keys.NUMBER_ROW) as? Boolean) ?: false,
            heightPercent = ((get(KEY_HEIGHT) as? Int) ?: 100).coerceIn(MIN_HEIGHT, MAX_HEIGHT),
            widthPercent = ((get(KEY_WIDTH) as? Int) ?: 100).coerceIn(MIN_WIDTH, 100),
            split = SplitMode.of(get(KEY_SPLIT) as? String),
            longPressMs = ((get(KEY_LONG_PRESS) as? Int) ?: 300).coerceIn(MIN_LONG, MAX_LONG),
        )
    }
}
