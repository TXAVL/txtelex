package com.customkey.vietime.diag

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Loại sự cố. */
enum class TxaCrashKind(val title: String, val badge: String) {
    JAVA("Lỗi ứng dụng", "JVM"),
    NATIVE("Lỗi tầng native", "NATIVE"),
    ANR("Ứng dụng bị treo", "ANR"),
    FREEZE("Luồng chính bị kẹt", "FREEZE"),
    SYSTEM("Hệ thống dừng ứng dụng", "SYS");

    companion object { fun of(s: String?) = entries.firstOrNull { it.name == s } ?: JAVA }
}

/**
 * Một báo cáo sự cố. Thuần dữ liệu — mã hoá dạng văn bản có header `Khoá: giá trị` + các khối
 * `=== TÊN ===`, đọc được bằng mắt và test được trên JVM.
 *
 * [fingerprint]: "vân tay" ổn định của lỗi (loại + ngoại lệ + 6 khung stack đầu thuộc code của
 * app/thư viện, bỏ số dòng) ⇒ cùng một bug luôn ra cùng mã TXA-XXXX-XXXX, đếm được số lần gặp.
 */
data class TxaCrash(
    val kind: TxaCrashKind,
    val time: Long,
    val thread: String,
    val title: String,
    val message: String,
    val stack: String,
    val breadcrumbs: List<String> = emptyList(),
    val process: String = "main",
    val session: String = "",
    val appVersion: String = "",
    val uptimeMs: Long = 0,
    /** Đuôi log lúc xảy ra lỗi (đã lọc, không có chữ người dùng gõ). */
    val logTail: List<String> = emptyList(),
) {
    val fingerprint: String get() = fingerprintOf(kind, title, stack)

    /** Gợi ý nguyên nhân thân thiện (tiếng Việt) dựa trên loại ngoại lệ / tín hiệu. */
    val hint: String get() = hintFor(kind, title, message, stack)

    val timeText: String get() = SimpleDateFormat("HH:mm:ss · dd/MM/yyyy", Locale("vi")).format(Date(time))

    fun encode(): String = buildString {
        appendLine("TXA-CRASH v1")
        appendLine("Kind: ${kind.name}")
        appendLine("Time: $time")
        appendLine("Thread: ${one(thread)}")
        appendLine("Title: ${one(title)}")
        appendLine("Message: ${one(message)}")
        appendLine("Process: ${one(process)}")
        appendLine("Session: ${one(session)}")
        appendLine("Version: ${one(appVersion)}")
        appendLine("Uptime: $uptimeMs")
        appendLine("=== STACK ===")
        appendLine(stack.trimEnd())
        appendLine("=== CRUMBS ===")
        breadcrumbs.forEach { appendLine(one(it)) }
        appendLine("=== LOG ===")
        logTail.forEach { appendLine(it.replace("\n", "\n  ")) }
    }

    companion object {
        private fun one(s: String) = s.replace('\n', ' ').replace('\r', ' ')

        fun decode(text: String): TxaCrash? {
            val lines = text.lines()
            if (lines.firstOrNull() != "TXA-CRASH v1") return null
            val head = HashMap<String, String>()
            var i = 1
            while (i < lines.size && !lines[i].startsWith("=== ")) {
                val l = lines[i]; val k = l.indexOf(": ")
                if (k > 0) head[l.substring(0, k)] = l.substring(k + 2)
                i++
            }
            val blocks = HashMap<String, MutableList<String>>()
            var cur: MutableList<String>? = null
            while (i < lines.size) {
                val l = lines[i]
                if (l.startsWith("=== ") && l.endsWith(" ===")) cur = blocks.getOrPut(l.removeSurrounding("=== ", " ===")) { ArrayList() }
                else cur?.add(l)
                i++
            }
            fun block(n: String) = blocks[n].orEmpty().dropLastWhile { it.isEmpty() }
            return TxaCrash(
                kind = TxaCrashKind.of(head["Kind"]),
                time = head["Time"]?.toLongOrNull() ?: return null,
                thread = head["Thread"].orEmpty(),
                title = head["Title"].orEmpty(),
                message = head["Message"].orEmpty(),
                stack = block("STACK").joinToString("\n"),
                breadcrumbs = block("CRUMBS"),
                process = head["Process"] ?: "main",
                session = head["Session"].orEmpty(),
                appVersion = head["Version"].orEmpty(),
                uptimeMs = head["Uptime"]?.toLongOrNull() ?: 0,
                logTail = block("LOG"),
            )
        }

        private val LINE_NO = Regex(":\\d+\\)")
        private val ADDR = Regex("0x[0-9a-fA-F]+|\\+\\d+")
        private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

        /** Vân tay ổn định: bỏ số dòng / địa chỉ, chỉ giữ 6 khung có nghĩa đầu tiên. */
        fun fingerprintOf(kind: TxaCrashKind, title: String, stack: String): String {
            val frames = stack.lines().map { it.trim() }
                .filter { it.startsWith("at ") || it.startsWith("#") }
                .filterNot { it.contains("java.lang.reflect") || it.contains("dalvik.system") || it.contains("com.android.internal.os") }
                .take(6)
                .map { it.replace(LINE_NO, ")").replace(ADDR, "") }
            val seed = kind.name + "|" + title.substringBefore(':') + "|" + frames.joinToString("|")
            var h = -0x340d631b7bdddcdbL   // FNV-1a 64
            for (b in seed.toByteArray()) { h = h xor (b.toLong() and 0xFF); h *= 0x100000001b3L }
            val sb = StringBuilder("TXA-")
            var x = h
            for (k in 0 until 8) {
                if (k == 4) sb.append('-')
                sb.append(ALPHABET[((x ushr 1) % ALPHABET.length).toInt()]); x = x ushr 5
            }
            return sb.toString()
        }

        fun hintFor(kind: TxaCrashKind, title: String, message: String, stack: String): String {
            val t = "$title $message"
            return when {
                kind == TxaCrashKind.ANR || kind == TxaCrashKind.FREEZE -> "Luồng giao diện bị chặn quá lâu — thường do việc nặng chạy nhầm trên luồng chính."
                "OutOfMemory" in t -> "Máy hết bộ nhớ RAM — thử đóng bớt ứng dụng hoặc dùng ảnh nền nhỏ hơn."
                "ClassNotFound" in t || "NoClassDefFound" in t -> "Thiếu một thành phần của ứng dụng — cài lại bản mới nhất thường sẽ khắc phục."
                "SIGSEGV" in t || "SIGBUS" in t -> "Truy cập bộ nhớ không hợp lệ ở tầng native (thư viện hệ thống / đồ hoạ)."
                "SIGABRT" in t -> "Một thư viện native tự dừng vì phát hiện trạng thái sai."
                "DeadObject" in t || "DeadSystem" in t -> "Dịch vụ hệ thống vừa khởi động lại — thường tự hết."
                "BadTokenException" in t || "WindowManager" in t -> "Cửa sổ bàn phím bị hệ thống đóng giữa chừng."
                "IllegalState" in t -> "Ứng dụng rơi vào trạng thái không mong đợi."
                "NullPointer" in t -> "Một giá trị bị thiếu (null) ở chỗ không ngờ tới."
                "SecurityException" in t -> "Hệ thống từ chối một quyền ứng dụng cần."
                "IOException" in t || "FileNotFound" in t -> "Lỗi đọc/ghi bộ nhớ máy — kiểm tra dung lượng còn trống."
                stack.contains("customkey.vietime.engine") -> "Lỗi trong lõi bộ gõ tiếng Việt."
                else -> "Lỗi chưa rõ nguyên nhân — gửi báo cáo giúp TXA sửa nhanh hơn."
            }
        }
    }
}
