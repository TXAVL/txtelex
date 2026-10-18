package com.customkey.vietime.diag

import java.io.File

/**
 * Cầu nối tới `libtxaguard.so` (txa_guard.c): cài signal handler bắt crash tầng native trên mọi
 * luồng của tiến trình, ghi dump vào [dir]. [parseDump] (thuần, test được) đọc dump lần mở sau.
 */
object TxaNativeGuard {
    @Volatile var active = false; private set

    fun install(dir: File) {
        active = runCatching {
            System.loadLibrary("txaguard")
            nativeInstall(dir.absolutePath)
        }.onFailure { TxaLogger.w("NativeGuard", "Không nạp được bộ bắt native", it) }.getOrDefault(false)
    }

    /** Phòng thử nghiệm: 0 = SIGSEGV, 1 = SIGABRT, 2 = SIGFPE. Gọi là app CHẾT ngay. */
    fun crashForTest(kind: Int) { if (active) nativeCrashTest(kind) }

    @JvmStatic private external fun nativeInstall(dir: String): Boolean
    @JvmStatic private external fun nativeCrashTest(kind: Int)

    /** Đọc file dump "TXA-NATIVE v1" → báo cáo. null nếu không đúng định dạng. */
    fun parseDump(text: String): TxaCrash? {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != "TXA-NATIVE v1") return null
        val kv = HashMap<String, String>()
        val frames = ArrayList<String>()
        var inFrames = false
        for (l in lines.drop(1)) {
            if (l == "--- frames ---") { inFrames = true; continue }
            if (inFrames) { if (l.isNotBlank()) frames += l } else l.indexOf('=').takeIf { it > 0 }?.let { kv[l.substring(0, it)] = l.substring(it + 1) }
        }
        val sig = kv["signal"]?.substringAfter(' ') ?: return null
        val code = kv["code"]?.substringAfter(' ').orEmpty()
        val addr = kv["addr"].orEmpty()
        val thread = kv["thread"].orEmpty()
        return TxaCrash(
            kind = TxaCrashKind.NATIVE,
            time = kv["time"]?.toLongOrNull() ?: System.currentTimeMillis(),
            thread = "$thread (tid ${kv["tid"].orEmpty()})",
            title = "$sig ($code)",
            message = "Địa chỉ lỗi $addr · ${kv["abi"].orEmpty()} · ${kv["regs"].orEmpty()}",
            stack = frames.joinToString("\n"),
            process = "pid ${kv["pid"].orEmpty()}",
        )
    }
}
