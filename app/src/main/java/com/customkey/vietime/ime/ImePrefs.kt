package com.customkey.vietime.ime

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import com.customkey.vietime.engine.KeyboardData
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * SharedPreferences dùng chung app cài đặt ⇄ bàn phím (cùng process). Key của engine lấy từ
 * [com.customkey.vietime.engine.Keys]; key riêng của app ở đây.
 */
object ImePrefs {
    const val NAME = "vietime"

    const val SWIPE_TYPING = "swipeTypingEnabled"
    const val KEY_SOUND = "keySoundEnabled"
    const val HAPTIC = "hapticEnabled"
    const val LONG_PRESS_MS = "longPressDelayMs"
    const val SPACE_FLICK_LANGUAGE = "spaceFlickLanguage"
    const val ENGLISH_MODE = "englishMode"

    fun of(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}

/** Nạp blob từ điển/LM từ assets — mmap khi file không nén (.bin), đọc hết khi nén (seed.tsv). */
object AssetBlobs {
    fun provider(assets: AssetManager): (String) -> ByteBuffer = { name ->
        runCatching {
            assets.openFd(name).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { ch ->
                    ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length)
                }
            }
        }.getOrElse { ByteBuffer.wrap(assets.open(name).use { it.readBytes() }) }
    }

    fun ensureInstalled(ctx: Context) {
        if (!KeyboardData.installed) KeyboardData.install(provider(ctx.applicationContext.assets))
    }
}
