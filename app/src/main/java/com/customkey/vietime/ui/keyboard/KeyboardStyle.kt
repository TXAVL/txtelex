package com.customkey.vietime.ui.keyboard

import android.graphics.Bitmap

/**
 * Mọi thứ KeyboardView cần để vẽ — màu ARGB, kích thước dp. GĐ3 (Theme Studio) sinh object này
 * từ ThemeModels; GĐ2 dùng [light]/[dark] mặc định.
 */
data class KeyboardStyle(
    val background: Int,
    val keyFill: Int,
    val modifierFill: Int,
    val pressedFill: Int,
    val label: Int,
    val modifierLabel: Int,
    val accent: Int,
    val stripBackground: Int,
    val stripText: Int,
    val divider: Int,
    val keyShadow: Int,
    val cornerRadiusDp: Float = 6f,
    val keyGapDp: Float = 3f,
    val rowGapDp: Float = 5f,
    val borderWidthDp: Float = 0f,
    val borderColor: Int = 0,
    /** 0…1 — áp lên nền phím (để lộ ảnh nền). */
    val keyOpacity: Float = 1f,
    /** 0…1 — áp lên chữ trên phím. */
    val labelOpacity: Float = 1f,
    val labelSizeSp: Float = 22f,
    /** Ảnh nền đã xử lý (blur/overlay) hoặc null. */
    val wallpaper: Bitmap? = null,
) {
    companion object {
        val light = KeyboardStyle(
            background = 0xFFD3D6DD.toInt(), keyFill = 0xFFFFFFFF.toInt(), modifierFill = 0xFFADB3BC.toInt(),
            pressedFill = 0xFFB8BEC8.toInt(), label = 0xFF111111.toInt(), modifierLabel = 0xFF111111.toInt(),
            accent = 0xFF0A66FF.toInt(), stripBackground = 0xFFD3D6DD.toInt(), stripText = 0xFF111111.toInt(),
            divider = 0x33000000, keyShadow = 0x40000000,
        )
        val dark = KeyboardStyle(
            background = 0xFF1B1B1D.toInt(), keyFill = 0xFF3A3A3D.toInt(), modifierFill = 0xFF2A2A2D.toInt(),
            pressedFill = 0xFF55555A.toInt(), label = 0xFFF2F2F2.toInt(), modifierLabel = 0xFFF2F2F2.toInt(),
            accent = 0xFF4C8DFF.toInt(), stripBackground = 0xFF1B1B1D.toInt(), stripText = 0xFFF2F2F2.toInt(),
            divider = 0x33FFFFFF, keyShadow = 0x60000000,
        )
    }
}
