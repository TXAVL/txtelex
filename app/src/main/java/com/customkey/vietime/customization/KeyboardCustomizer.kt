package com.customkey.vietime.customization

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.customkey.vietime.engine.Keys
import com.customkey.vietime.ime.ImePrefs
import com.customkey.vietime.ui.keyboard.KeyboardPanel
import com.customkey.vietime.ui.keyboard.KeyboardView
import java.util.concurrent.Executors

/**
 * Gom mọi tuỳ biến (theme, ảnh nền, âm thanh, rung, bố cục) cho một bàn phím — IME và bản xem
 * trước trong app dùng chung ⇒ chỉnh ở Theme Studio thấy ngay trên bàn phím thật.
 * IO (decode ảnh, tổng hợp âm) chạy ở luồng riêng; [onChanged] gọi lại trên main.
 */
class KeyboardCustomizer(context: Context, private val onChanged: () -> Unit) {
    private val ctx = context.applicationContext
    private val prefs = ImePrefs.of(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "txtelex-custom").apply { isDaemon = true } }

    val sound = SoundEffectPool(ctx)
    val haptic = HapticController(ctx)

    var theme: ThemeConfig = ThemeStore.load(ctx); private set
    var layout: LayoutConfig = LayoutConfig.load { prefs.all[it] }; private set
    @Volatile private var wallpaper: Bitmap? = null

    /** Nạp toàn bộ (lần đầu). */
    fun reloadAll() {
        theme = ThemeStore.load(ctx)
        layout = LayoutConfig.load { prefs.all[it] }
        haptic.config = HapticConfig.load { prefs.all[it] }
        sound.volume = prefs.getInt(SoundEffectPool.KEY_VOLUME, 60) / 100f
        val wantWall = theme.wallpaper
        io.execute {
            wallpaper = if (wantWall) ThemeStore.loadWallpaper(ctx) else null
            sound.setProfile(SoundProfile.of(prefs.getString(SoundEffectPool.KEY_PROFILE, null)))
            main.post(onChanged)
        }
        onChanged()
    }

    /** Một key prefs đổi — chỉ làm phần cần thiết. true nếu key thuộc tuỳ biến. */
    fun onPrefChanged(key: String?): Boolean {
        when (key) {
            ThemeStore.KEY_THEME -> {
                val old = theme
                theme = ThemeStore.load(ctx)
                if (theme.wallpaper != old.wallpaper) io.execute {
                    wallpaper = if (theme.wallpaper) ThemeStore.loadWallpaper(ctx) else null
                    main.post(onChanged)
                }
            }
            ThemeStore.KEY_WALL_VERSION -> io.execute {
                wallpaper = if (theme.wallpaper) ThemeStore.loadWallpaper(ctx) else null
                main.post(onChanged)
            }
            SoundEffectPool.KEY_PROFILE, SoundEffectPool.KEY_CUSTOM_VERSION -> {
                val p = SoundProfile.of(prefs.getString(SoundEffectPool.KEY_PROFILE, null))
                val force = key == SoundEffectPool.KEY_CUSTOM_VERSION
                io.execute { sound.setProfile(p, force) }
                return true
            }
            SoundEffectPool.KEY_VOLUME -> { sound.volume = prefs.getInt(key, 60) / 100f; return true }
            HapticConfig.KEY_KEY, HapticConfig.KEY_DELETE, HapticConfig.KEY_CURSOR -> {
                haptic.config = HapticConfig.load { prefs.all[it] }; return true
            }
            Keys.NUMBER_ROW, LayoutConfig.KEY_HEIGHT, LayoutConfig.KEY_WIDTH, LayoutConfig.KEY_SPLIT, LayoutConfig.KEY_LONG_PRESS ->
                layout = LayoutConfig.load { prefs.all[it] }
            else -> return false
        }
        onChanged()
        return true
    }

    fun isWide(c: Context): Boolean {
        val cfg = c.resources.configuration
        return cfg.orientation == Configuration.ORIENTATION_LANDSCAPE || cfg.smallestScreenWidthDp >= 600
    }

    /** Áp theme + bố cục lên khung bàn phím. */
    fun applyTo(panel: KeyboardPanel, wide: Boolean) {
        panel.style = theme.toStyle(wallpaper)
        val kb = panel.keyboard
        kb.rowHeightDp = layout.rowHeightDp()
        kb.options = layout.options(wide)
        kb.longPressDelayMs = layout.longPressMs.toLong()
        panel.requestLayout()
    }

    /** Âm thanh + rung cho một chạm. */
    fun feedback(kind: KeyboardView.FeedbackKind) {
        when (kind) {
            KeyboardView.FeedbackKind.KEY -> { sound.play(SoundKind.KEY); haptic.perform(HapticKind.KEY) }
            KeyboardView.FeedbackKind.MODIFIER -> { sound.play(SoundKind.ENTER); haptic.perform(HapticKind.KEY) }
            KeyboardView.FeedbackKind.SPACE -> { sound.play(SoundKind.SPACE); haptic.perform(HapticKind.KEY) }
            KeyboardView.FeedbackKind.DELETE -> { sound.play(SoundKind.DELETE); haptic.perform(HapticKind.DELETE) }
            KeyboardView.FeedbackKind.TRACKPAD_TICK -> haptic.perform(HapticKind.CURSOR)
        }
    }

    fun release() { sound.release(); io.shutdown() }
}
