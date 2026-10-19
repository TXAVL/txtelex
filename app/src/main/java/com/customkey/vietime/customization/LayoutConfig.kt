package com.customkey.vietime.customization

import com.customkey.vietime.engine.Keys
import com.customkey.vietime.ui.keyboard.LayoutOptions

/** Chế độ một tay (màn dọc): dồn bàn phím về phía ngón cái. */
enum class OneHandMode(val id: String, val title: String, val align: Int) {
    OFF("off", "Tắt", 0), LEFT("left", "Tay trái", -1), RIGHT("right", "Tay phải", 1);

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: OFF }
}

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
    val oneHand: OneHandMode = OneHandMode.OFF,
    /** Cỡ chữ trên phím 80…130 %. */
    val labelScalePercent: Int = 100,
    /** Bong bóng phóng to chữ khi bấm. */
    val keyPreview: Boolean = true,
) {
    fun rowHeightDp(base: Float = BASE_ROW_DP): Float = base * heightPercent.coerceIn(MIN_HEIGHT, MAX_HEIGHT) / 100f

    /** [wide] = màn ngang hoặc ≥ 600dp. */
    fun options(wide: Boolean): LayoutOptions {
        val split = split == SplitMode.ALWAYS || (split == SplitMode.LANDSCAPE && wide)
        // Một tay chỉ ở màn dọc, không cùng lúc với tách đôi.
        val oneHanded = oneHand != OneHandMode.OFF && !wide && !split
        return LayoutOptions(
            numberRow = numberRow,
            splitGap = if (split) SPLIT_GAP else 0f,
            widthFraction = (if (oneHanded) minOf(widthPercent, ONE_HAND_WIDTH) else widthPercent).coerceIn(MIN_WIDTH, 100) / 100f,
            align = if (oneHanded) oneHand.align else 0,
        )
    }

    val labelScale: Float get() = labelScalePercent.coerceIn(80, 130) / 100f

    companion object {
        const val BASE_ROW_DP = 54f
        const val SPLIT_GAP = 3f
        const val MIN_HEIGHT = 70; const val MAX_HEIGHT = 140
        const val MIN_WIDTH = 75
        const val ONE_HAND_WIDTH = 82
        const val MIN_LONG = 150; const val MAX_LONG = 800

        const val KEY_HEIGHT = "layout.heightPercent"
        const val KEY_WIDTH = "layout.widthPercent"
        const val KEY_SPLIT = "layout.split"
        const val KEY_LONG_PRESS = "layout.longPressMs"
        const val KEY_ONE_HAND = "layout.oneHand"
        const val KEY_LABEL_SCALE = "layout.labelScale"
        val KEYS = setOf(Keys.NUMBER_ROW, KEY_HEIGHT, KEY_WIDTH, KEY_SPLIT, KEY_LONG_PRESS, KEY_ONE_HAND, KEY_LABEL_SCALE, Keys.KEY_PREVIEW)

        fun load(get: (String) -> Any?): LayoutConfig = LayoutConfig(
            numberRow = (get(Keys.NUMBER_ROW) as? Boolean) ?: false,
            heightPercent = ((get(KEY_HEIGHT) as? Int) ?: 100).coerceIn(MIN_HEIGHT, MAX_HEIGHT),
            widthPercent = ((get(KEY_WIDTH) as? Int) ?: 100).coerceIn(MIN_WIDTH, 100),
            split = SplitMode.of(get(KEY_SPLIT) as? String),
            longPressMs = ((get(KEY_LONG_PRESS) as? Int) ?: 300).coerceIn(MIN_LONG, MAX_LONG),
            oneHand = OneHandMode.of(get(KEY_ONE_HAND) as? String),
            labelScalePercent = ((get(KEY_LABEL_SCALE) as? Int) ?: 100).coerceIn(80, 130),
            keyPreview = (get(Keys.KEY_PREVIEW) as? Boolean) ?: true,
        )
    }
}
