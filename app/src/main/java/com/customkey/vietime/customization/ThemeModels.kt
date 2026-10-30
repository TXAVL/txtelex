package com.customkey.vietime.customization

import android.graphics.Bitmap
import com.customkey.vietime.ui.keyboard.KeyboardStyle

/**
 * Cấu hình giao diện bàn phím (Theme Studio). Thuần dữ liệu — mã hoá thành chuỗi `k=v;…` để lưu
 * SharedPreferences và test được trên JVM. [toStyle] chuyển sang [KeyboardStyle] để vẽ.
 */
data class ThemeConfig(
    val presetId: String = SYSTEM_ID,
    val background: Int = 0xFFE7EAF2.toInt(),
    val keyColor: Int = 0xFFFFFFFF.toInt(),
    val modifierColor: Int = 0xFFD2DAEF.toInt(),
    val labelColor: Int = 0xFF14171F.toInt(),
    val accentColor: Int = 0xFF1668F0.toInt(),
    /** Độ đục nền phím 0…1 (thấp = lộ ảnh nền). */
    val keyOpacity: Float = 1f,
    /** Độ đục chữ trên phím 0…1. */
    val labelOpacity: Float = 1f,
    val cornerRadiusDp: Float = 10f,
    val borderWidthDp: Float = 0f,
    val borderColor: Int = 0x331668F0,
    /** Có dùng ảnh nền riêng không (ảnh đã xử lý lưu ở [ThemeStore]). */
    val wallpaper: Boolean = false,
    /** Bán kính làm mờ ảnh nền 0…25. */
    val blur: Int = 8,
    /** Lớp phủ tối lên ảnh nền 0…0.85. */
    val dim: Float = 0.35f,
) {
    /** Bộ "Hệ thống" đổi màu theo chế độ sáng/tối của máy; các bộ khác giữ nguyên màu đã lưu. */
    fun resolved(night: Boolean): ThemeConfig =
        if (presetId != SYSTEM_ID) this
        else (if (night) SYSTEM_DARK else this.copy(
            background = 0xFFE7EAF2.toInt(), keyColor = 0xFFFFFFFF.toInt(), modifierColor = 0xFFD2DAEF.toInt(),
            labelColor = 0xFF14171F.toInt(), accentColor = 0xFF1668F0.toInt(), borderColor = 0x331668F0,
        )).copy(
            presetId = SYSTEM_ID, keyOpacity = keyOpacity, labelOpacity = labelOpacity, cornerRadiusDp = cornerRadiusDp,
            borderWidthDp = borderWidthDp, wallpaper = wallpaper, blur = blur, dim = dim,
        )

    fun toStyle(wallpaperBitmap: Bitmap?, night: Boolean = true): KeyboardStyle {
        val c = resolved(night)
        // Nền sáng: bóng phím nhẹ hơn, phím nhấn đậm hơn để thấy rõ khi chạm.
        val light = Colors.luminance(c.keyColor) > 0.6f
        return KeyboardStyle(
            background = c.background,
            keyFill = c.keyColor,
            modifierFill = c.modifierColor,
            pressedFill = Colors.mix(c.keyColor, c.accentColor, if (light) 0.22f else 0.35f),
            label = c.labelColor,
            modifierLabel = Colors.mix(c.labelColor, c.accentColor, 0.35f),
            accent = c.accentColor,
            stripBackground = c.background,
            stripText = c.labelColor,
            divider = Colors.withAlpha(c.labelColor, 0.2f),
            keyShadow = if (light) 0x26000000 else 0x55000000,
            cornerRadiusDp = c.cornerRadiusDp.coerceIn(0f, 24f),
            borderWidthDp = c.borderWidthDp.coerceIn(0f, 3f),
            borderColor = c.borderColor,
            keyOpacity = c.keyOpacity.coerceIn(0f, 1f),
            labelOpacity = c.labelOpacity.coerceIn(0.2f, 1f),
            wallpaper = if (c.wallpaper) wallpaperBitmap else null,
        )
    }

    fun encode(): String = listOf(
        "preset" to presetId,
        "bg" to hex(background), "key" to hex(keyColor), "mod" to hex(modifierColor),
        "label" to hex(labelColor), "accent" to hex(accentColor),
        "keyOp" to keyOpacity.toString(), "labelOp" to labelOpacity.toString(),
        "radius" to cornerRadiusDp.toString(), "border" to borderWidthDp.toString(), "borderColor" to hex(borderColor),
        "wall" to wallpaper.toString(), "blur" to blur.toString(), "dim" to dim.toString(),
    ).joinToString(";") { (k, v) -> "$k=$v" }

    companion object {
        const val SYSTEM_ID = "system"

        private val SYSTEM_DARK = ThemeConfig(
            SYSTEM_ID, 0xFF14161C.toInt(), 0xFF3A3E4A.toInt(), 0xFF262932.toInt(),
            0xFFF1F3F9.toInt(), 0xFF5B9CFF.toInt(), borderColor = 0x335B9CFF,
        )

        private fun hex(c: Int) = String.format("%08X", c)

        /** Giải mã; khoá thiếu/hỏng ⇒ giữ mặc định của [base]. */
        fun decode(s: String?, base: ThemeConfig = ThemeConfig()): ThemeConfig {
            if (s.isNullOrBlank()) return base
            val m = s.split(';').mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it) to p.substring(it + 1) } }.toMap()
            fun c(k: String, d: Int) = m[k]?.let { runCatching { java.lang.Long.parseLong(it, 16).toInt() }.getOrNull() } ?: d
            fun f(k: String, d: Float) = m[k]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: d
            return ThemeConfig(
                presetId = m["preset"] ?: base.presetId,
                background = c("bg", base.background), keyColor = c("key", base.keyColor),
                modifierColor = c("mod", base.modifierColor), labelColor = c("label", base.labelColor),
                accentColor = c("accent", base.accentColor),
                keyOpacity = f("keyOp", base.keyOpacity).coerceIn(0f, 1f),
                labelOpacity = f("labelOp", base.labelOpacity).coerceIn(0.2f, 1f),
                cornerRadiusDp = f("radius", base.cornerRadiusDp).coerceIn(0f, 24f),
                borderWidthDp = f("border", base.borderWidthDp).coerceIn(0f, 3f),
                borderColor = c("borderColor", base.borderColor),
                wallpaper = m["wall"]?.toBooleanStrictOrNull() ?: base.wallpaper,
                blur = (m["blur"]?.toIntOrNull() ?: base.blur).coerceIn(0, 25),
                dim = f("dim", base.dim).coerceIn(0f, 0.85f),
            )
        }
    }
}

/** Bộ theme dựng sẵn. */
data class ThemePreset(val id: String, val name: String, val config: ThemeConfig)

object ThemePresets {
    val all: List<ThemePreset> = listOf(
        ThemePreset("system", "Hệ thống", ThemeConfig()),
        ThemePreset("neon", "Cyber Neon", ThemeConfig("neon", 0xFF0A1024.toInt(), 0xFF1A2448.toInt(), 0xFF111936.toInt(),
            0xFFEFF4FF.toInt(), 0xFF00E5FF.toInt(), cornerRadiusDp = 9f, borderWidthDp = 1f, borderColor = 0x3338E1FF)),
        ThemePreset("amoled", "AMOLED Black", ThemeConfig("amoled", 0xFF000000.toInt(), 0xFF141418.toInt(), 0xFF0C0C0E.toInt(),
            0xFFF8FAFC.toInt(), 0xFF38BDF8.toInt(), borderWidthDp = 0.8f, borderColor = 0x2238BDF8, cornerRadiusDp = 8f)),
        ThemePreset("midnight", "Midnight", ThemeConfig("midnight", 0xFF000000.toInt(), 0xFF1C1C1E.toInt(), 0xFF121214.toInt(),
            0xFFFFFFFF.toInt(), 0xFF0A84FF.toInt(), borderWidthDp = 0f, cornerRadiusDp = 7f)),
        ThemePreset("titanium", "Titanium", ThemeConfig("titanium", 0xFF181A20.toInt(), 0xFF282C37.toInt(), 0xFF1F222B.toInt(),
            0xFFF1F3F9.toInt(), 0xFF6366F1.toInt(), borderWidthDp = 0.8f, borderColor = 0x336366F1, cornerRadiusDp = 9f)),
        ThemePreset("aurora", "Aurora", ThemeConfig("aurora", 0xFF12052B.toInt(), 0xFF2A1458.toInt(), 0xFF1D0E40.toInt(),
            0xFFF6EEFF.toInt(), 0xFFB388FF.toInt(), borderColor = 0x55B388FF, cornerRadiusDp = 12f)),
        ThemePreset("ocean", "Ocean", ThemeConfig("ocean", 0xFF03263A.toInt(), 0xFF0B3D5C.toInt(), 0xFF072F47.toInt(),
            0xFFE6FBFF.toInt(), 0xFF2EE5C5.toInt(), borderColor = 0x442EE5C5)),
        ThemePreset("sunset", "Sunset Amber", ThemeConfig("sunset", 0xFF18100C.toInt(), 0xFF2C1E17.toInt(), 0xFF20150F.toInt(),
            0xFFFFF7ED.toInt(), 0xFFF97316.toInt(), borderWidthDp = 0.8f, borderColor = 0x33F97316, cornerRadiusDp = 9f)),
        ThemePreset("sakura", "Sakura", ThemeConfig("sakura", 0xFFFCE4EC.toInt(), 0xFFFFFFFF.toInt(), 0xFFF8BBD0.toInt(),
            0xFF4A1430.toInt(), 0xFFE91E63.toInt(), borderWidthDp = 0f, cornerRadiusDp = 12f)),
        ThemePreset("lavender", "Lavender Dream", ThemeConfig("lavender", 0xFFF5EEFD.toInt(), 0xFFFFFFFF.toInt(), 0xFFE9D5FF.toInt(),
            0xFF3B0764.toInt(), 0xFF9333EA.toInt(), borderWidthDp = 0f, cornerRadiusDp = 12f)),
        ThemePreset("matcha", "Matcha", ThemeConfig("matcha", 0xFFE8F0DA.toInt(), 0xFFFBFDF6.toInt(), 0xFFC9D8B0.toInt(),
            0xFF223018.toInt(), 0xFF5B8C2A.toInt(), borderWidthDp = 0f)),
        ThemePreset("paper", "Giấy trắng", ThemeConfig("paper", 0xFFD3D6DD.toInt(), 0xFFFFFFFF.toInt(), 0xFFADB3BC.toInt(),
            0xFF111111.toInt(), 0xFF0A66FF.toInt(), borderWidthDp = 0f, cornerRadiusDp = 6f)),
        ThemePreset("retro", "Retro", ThemeConfig("retro", 0xFF2B2420.toInt(), 0xFFE9DFC9.toInt(), 0xFFC2B59B.toInt(),
            0xFF2B2420.toInt(), 0xFFE0702E.toInt(), borderWidthDp = 1.5f, borderColor = 0x662B2420, cornerRadiusDp = 4f)),
    )

    fun byId(id: String?): ThemePreset = all.firstOrNull { it.id == id } ?: all[0]
}

/** Tiện ích màu ARGB thuần. */
object Colors {
    /** Máy đang ở chế độ tối? */
    fun isNight(ctx: android.content.Context): Boolean =
        (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    fun withAlpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((255 * a.coerceIn(0f, 1f)).toInt() shl 24)

    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        fun lerp(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return (lerp(ch(a, 24), ch(b, 24)) shl 24) or (lerp(ch(a, 16), ch(b, 16)) shl 16) or
            (lerp(ch(a, 8), ch(b, 8)) shl 8) or lerp(ch(a, 0), ch(b, 0))
    }

    /** Màu chữ (trắng/xanh đen) đọc rõ trên nền [bg]. */
    fun onColor(bg: Int): Int = if (luminance(bg) < 0.55f) 0xFFFFFFFF.toInt() else 0xFF0A1024.toInt()

    /** Độ sáng cảm nhận 0…1 — chọn màu chữ tương phản. */
    fun luminance(c: Int): Float {
        val r = (c shr 16 and 0xFF) / 255f; val g = (c shr 8 and 0xFF) / 255f; val b = (c and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /** Bảng màu chọn nhanh trong Theme Studio. */
    val palette: List<Int> = listOf(
        0xFF000000, 0xFF0A1024, 0xFF1A2448, 0xFF111936, 0xFF1C1C1E, 0xFF3A3A3D, 0xFF2A1458, 0xFF0B3D5C,
        0xFFFFFFFF, 0xFFEFF4FF, 0xFFD3D6DD, 0xFFFCE4EC, 0xFFE8F0DA, 0xFFE9DFC9,
        0xFF00E5FF, 0xFF2F6BFF, 0xFF7C4DFF, 0xFFB388FF, 0xFF2EE5C5, 0xFF2EE59D, 0xFFFFC53D, 0xFFE0702E,
        0xFFFF5470, 0xFFE91E63,
    ).map { it.toInt() }
}

/** Xử lý ảnh nền trên mảng pixel ARGB (thuần, test được): làm mờ + phủ tối. */
object ImageFx {
    /**
     * Box blur 3 lượt (xấp xỉ Gaussian), O(w·h) mỗi lượt bất kể bán kính. Sửa [px] tại chỗ.
     */
    fun blur(px: IntArray, w: Int, h: Int, radius: Int) {
        if (radius <= 0 || w <= 0 || h <= 0) return
        val tmp = IntArray(px.size)
        repeat(3) {
            boxH(px, tmp, w, h, radius)
            boxV(tmp, px, w, h, radius)
        }
    }

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                a += p ushr 24; rr += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                val out = src[row + (x - r).coerceIn(0, w - 1)]
                val inn = src[row + (x + r + 1).coerceIn(0, w - 1)]
                a += (inn ushr 24) - (out ushr 24)
                rr += (inn shr 16 and 0xFF) - (out shr 16 and 0xFF)
                g += (inn shr 8 and 0xFF) - (out shr 8 and 0xFF)
                b += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val p = src[i.coerceIn(0, h - 1) * w + x]
                a += p ushr 24; rr += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                val out = src[(y - r).coerceIn(0, h - 1) * w + x]
                val inn = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                a += (inn ushr 24) - (out ushr 24)
                rr += (inn shr 16 and 0xFF) - (out shr 16 and 0xFF)
                g += (inn shr 8 and 0xFF) - (out shr 8 and 0xFF)
                b += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }

    /** Phủ đen [amount] (0…1) lên mọi pixel. */
    fun dim(px: IntArray, amount: Float) {
        if (amount <= 0f) return
        val k = 1f - amount.coerceIn(0f, 1f)
        for (i in px.indices) {
            val p = px[i]
            val r = ((p shr 16 and 0xFF) * k).toInt(); val g = ((p shr 8 and 0xFF) * k).toInt(); val b = ((p and 0xFF) * k).toInt()
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}

