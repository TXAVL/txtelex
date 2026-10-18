package com.customkey.vietime.customization

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.customkey.vietime.ime.ImePrefs
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Lưu [ThemeConfig] + ảnh nền. Ảnh gốc (đã thu nhỏ, cắt theo tỉ lệ bàn phím) giữ ở
 * `wallpaper_src.png` để chỉnh lại độ mờ/tối bất cứ lúc nào; ảnh đã xử lý ở `wallpaper.png` —
 * IME chỉ việc decode, không tốn CPU lúc mở bàn phím. Gọi các hàm xử lý ảnh NGOÀI main thread.
 */
object ThemeStore {
    const val KEY_THEME = "theme.config"
    /** Tăng mỗi khi ảnh nền đổi ⇒ IME (listener prefs) nạp lại bitmap. */
    const val KEY_WALL_VERSION = "theme.wallVersion"

    private fun src(ctx: Context) = File(ctx.filesDir, "wallpaper_src.png")
    private fun out(ctx: Context) = File(ctx.filesDir, "wallpaper.png")

    fun load(ctx: Context): ThemeConfig = ThemeConfig.decode(ImePrefs.of(ctx).getString(KEY_THEME, null))

    fun save(ctx: Context, cfg: ThemeConfig) { ImePrefs.of(ctx).edit().putString(KEY_THEME, cfg.encode()).apply() }

    fun hasSource(ctx: Context) = src(ctx).exists()

    /** Ảnh nền đã xử lý (null nếu chưa có). */
    fun loadWallpaper(ctx: Context): Bitmap? =
        out(ctx).takeIf { it.exists() }?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }

    /**
     * Nhập ảnh từ thư viện: thu nhỏ + cắt giữa theo tỉ lệ [targetW]:[targetH] rồi xử lý theo [cfg].
     */
    fun importWallpaper(ctx: Context, uri: Uri, targetW: Int, targetH: Int, cfg: ThemeConfig): Boolean {
        val bmp = decodeScaled(ctx, uri, targetW) ?: return false
        val cropped = centerCrop(bmp, targetW.toFloat() / targetH)
        src(ctx).outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return process(ctx, cfg)
    }

    /** Áp độ mờ / lớp phủ tối lên ảnh gốc đã lưu. */
    fun process(ctx: Context, cfg: ThemeConfig): Boolean {
        val s = runCatching { BitmapFactory.decodeFile(src(ctx).absolutePath) }.getOrNull() ?: return false
        // Xử lý ở nửa độ phân giải: ảnh đã mờ nên phóng lại không thấy khác, nhanh gấp 4.
        val w = max(1, s.width / 2); val h = max(1, s.height / 2)
        val small = Bitmap.createScaledBitmap(s, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        ImageFx.blur(px, w, h, (cfg.blur / 2f).roundToInt())
        ImageFx.dim(px, cfg.dim)
        val res = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        out(ctx).outputStream().use { res.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bumpVersion(ctx)
        return true
    }

    fun removeWallpaper(ctx: Context) {
        src(ctx).delete(); out(ctx).delete(); bumpVersion(ctx)
    }

    private fun bumpVersion(ctx: Context) {
        val p = ImePrefs.of(ctx)
        p.edit().putInt(KEY_WALL_VERSION, p.getInt(KEY_WALL_VERSION, 0) + 1).apply()
    }

    private fun decodeScaled(ctx: Context, uri: Uri, maxW: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(ctx.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { dec, info, _ ->
                val s = info.size
                val scale = (maxW * 1.5f / s.width).coerceAtMost(1f)
                dec.setTargetSize(max(1, (s.width * scale).toInt()), max(1, (s.height * scale).toInt()))
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxW) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    }.getOrNull()

    private fun centerCrop(b: Bitmap, aspect: Float): Bitmap {
        val cur = b.width.toFloat() / b.height
        return if (cur > aspect) {
            val w = (b.height * aspect).toInt().coerceIn(1, b.width)
            Bitmap.createBitmap(b, (b.width - w) / 2, 0, w, b.height)
        } else {
            val h = (b.width / aspect).toInt().coerceIn(1, b.height)
            Bitmap.createBitmap(b, 0, (b.height - h) / 2, b.width, h)
        }
    }
}
