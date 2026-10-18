// MIT License — Copyright (c) 2026 Phil Trinh
// Ported from ptrinh/viettelex tests; modified for com.customkey.vietime.

package com.customkey.vietime.engine

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/** Nạp assets thật (android/app/src/main/assets) bằng mmap — như IME. */
object TestAssets {
    private val dir: File by lazy {
        listOf(File("src/main/assets"), File("app/src/main/assets"))
            .first { File(it, Keys.ASSET_LEXICON).exists() }
    }
    @Volatile private var installed = false
    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            // VT_LM=đường-dẫn: đo một vnlm.bin ứng viên (Scripts/gen-syllable-lm.py build --out …)
            // mà không chép đè asset — LMDataEvalTests / lưới trọng số nguồn dữ liệu.
            val lmOverride = System.getenv("VT_LM")?.let { File(it) }
            KeyboardData.install { name ->
                val file = if (name == Keys.ASSET_LM && lmOverride != null) lmOverride else File(dir, name)
                RandomAccessFile(file, "r").use { f ->
                    f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length())
                }
            }
            installed = true
        }
    }
}

/** Mock proxy ghi lại luồng xoá/chèn (≈ iOS MockProxy). */
class MockProxy(var isSecureField: Boolean = false) : TextProxy {
    val sb = StringBuilder()
    val text: String get() = sb.toString()
    override val isSecure: Boolean get() = isSecureField
    override fun insertText(text: String) { sb.append(text) }
    override fun deleteCodePoints(count: Int) {
        repeat(count) { if (sb.isNotEmpty()) sb.setLength(sb.offsetByCodePoints(sb.length, -1)) }
    }
    override fun deleteBackward() = deleteCodePoints(1)
    /** Số lần đọc chữ trước con trỏ (≈ IPC getTextBeforeCursor trên máy thật). */
    var contextReads = 0
    override fun contextBeforeInput(): String { contextReads++; return text }
    override fun clearAll() { sb.setLength(0) }
    override fun confirmTail(expected: String): Boolean = text.endsWith(expected)
    /** Sửa lại từ đã chốt (IcProxy: ô COMMIT thường). */
    var reEdit = true
    /** Chữ SAU con trỏ; null = không đọc được. */
    var after: String? = ""
    var selection = false
    override val canReEdit: Boolean get() = reEdit
    override val hasSelection: Boolean get() = selection
    override fun contextAfterInput(): String? = after
}
