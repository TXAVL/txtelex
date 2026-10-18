package com.customkey.vietime.diag

import android.content.Context
import android.os.Process
import android.util.Log
import com.customkey.vietime.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * TxaLogger — Hệ thống ghi nhật ký an toàn của TxTelex.
 *
 * - Ghi log chi tiết ra file (filesDir/txa_logs/), xoay vòng 3 × 512 KB, bằng MỘT luồng nền:
 *   gọi log trên luồng bàn phím chỉ là đẩy vào hàng đợi (vài µs) — không chặn phím.
 * - Giữ [RING_SIZE] dòng gần nhất + [CRUMB_SIZE] "breadcrumb" (dấu chân sự kiện) trong RAM để
 *   chụp vào báo cáo lỗi ngay cả khi file chưa kịp ghi.
 * - QUY TẮC RIÊNG TƯ: KHÔNG BAO GIỜ ghi chữ người dùng gõ, nội dung clipboard, mật khẩu.
 *   Chỉ ghi sự kiện, loại ô nhập, thời gian, lỗi. Thông tin thiết bị chỉ chèn lúc XUẤT báo cáo.
 */
object TxaLogger {
    enum class Level(val mark: Char, val prio: Int) { V('V', Log.VERBOSE), D('D', Log.DEBUG), I('I', Log.INFO), W('W', Log.WARN), E('E', Log.ERROR), F('F', Log.ASSERT) }

    const val RING_SIZE = 600
    const val CRUMB_SIZE = 40
    private const val MAX_FILE = 512 * 1024L
    private const val KEEP_FILES = 3

    @Volatile private var dir: File? = null
    @Volatile var processTag: String = "main"; private set
    /** Mã phiên ngắn (đổi mỗi lần process khởi động) — ghép log của cùng một lần chạy. */
    val session: String = java.lang.Long.toString(System.currentTimeMillis() xor (Process.myPid().toLong() shl 20), 36).takeLast(6).uppercase()
    private val started = System.currentTimeMillis()

    private val queue = LinkedBlockingQueue<String>(4096)
    private val ring = ArrayDeque<String>(RING_SIZE)
    private val crumbs = ArrayDeque<String>(CRUMB_SIZE)
    private val stamp = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
    @Volatile private var writer: Thread? = null

    fun init(ctx: Context, process: String) {
        if (dir != null) return
        processTag = process
        dir = File(ctx.applicationContext.filesDir, "txa_logs").apply { mkdirs() }
        writer = Thread({ drainLoop() }, "txa-logger").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
        i("TxaLogger", "══ phiên $session · tiến trình $processTag · pid ${Process.myPid()} · v${BuildConfig.VERSION_NAME} ══")
    }

    fun v(tag: String, msg: String) = log(Level.V, tag, msg, null)
    fun d(tag: String, msg: String) = log(Level.D, tag, msg, null)
    fun i(tag: String, msg: String) = log(Level.I, tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(Level.W, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(Level.E, tag, msg, t)

    /** Dấu chân sự kiện (mở app, bật bàn phím, đổi theme…) — hiện trong nhật ký thao tác của màn lỗi. */
    fun crumb(event: String) {
        val line = "${clock()}  $event"
        synchronized(crumbs) { if (crumbs.size >= CRUMB_SIZE) crumbs.removeFirst(); crumbs.addLast(line) }
        log(Level.I, "crumb", event, null)
    }

    /** Chạy [block]; lỗi thì ghi lại (không ném) và trả [fallback]. */
    inline fun <T> guard(tag: String, what: String, fallback: T, block: () -> T): T =
        try { block() } catch (t: Throwable) { e(tag, "$what thất bại", t); fallback }

    fun log(level: Level, tag: String, msg: String, t: Throwable?) {
        val th = Thread.currentThread()
        val sb = StringBuilder(96 + msg.length)
            .append(stamp.get()!!.format(Date())).append(" | ").append(level.mark).append(" | ")
            .append(th.name).append('#').append(th.id).append(" | ").append(tag).append(" | ").append(msg)
        if (t != null) sb.append('\n').append(Log.getStackTraceString(t).trimEnd())
        val line = sb.toString()
        synchronized(ring) { if (ring.size >= RING_SIZE) ring.removeFirst(); ring.addLast(line) }
        if (BuildConfig.DEBUG || level >= Level.W) Log.println(level.prio, "TXA/$tag", if (t != null) "$msg\n${Log.getStackTraceString(t)}" else msg)
        if (dir != null) queue.offer(line)
    }

    fun recent(n: Int = RING_SIZE): List<String> = synchronized(ring) { ring.toList().takeLast(n) }
    fun breadcrumbs(): List<String> = synchronized(crumbs) { crumbs.toList() }

    /** Thời gian chạy của tiến trình, dạng "+12.345s". */
    fun clock(): String = "+%.3fs".format(Locale.US, (System.currentTimeMillis() - started) / 1000.0)
    fun uptimeMs(): Long = System.currentTimeMillis() - started

    /** Ghi ngay mọi dòng đang chờ (gọi trong bộ bắt crash, trước khi process chết). */
    fun flushSync() {
        val d = dir ?: return
        val batch = ArrayList<String>()
        queue.drainTo(batch)
        if (batch.isNotEmpty()) runCatching { append(d, batch) }
    }

    fun files(): List<File> {
        val d = dir ?: return emptyList()
        return (KEEP_FILES - 1 downTo 1).map { File(d, "txa_$it.log") }.filter { it.exists() } + listOf(File(d, "txa_0.log")).filter { it.exists() }
    }

    /** Toàn bộ log trên đĩa (cũ → mới), tối đa [maxBytes] cuối. */
    fun readAll(maxBytes: Int = 900 * 1024): String {
        flushSync()
        val all = files().joinToString("") { runCatching { it.readText() }.getOrDefault("") }
        return if (all.length > maxBytes) "…(cắt bớt phần cũ)…\n" + all.takeLast(maxBytes) else all
    }

    fun clear() {
        queue.clear(); synchronized(ring) { ring.clear() }
        dir?.listFiles()?.forEach { it.delete() }
        i("TxaLogger", "đã xoá nhật ký")
    }

    private fun drainLoop() {
        val batch = ArrayList<String>(64)
        while (true) {
            try {
                val first = queue.poll(2, TimeUnit.SECONDS) ?: continue
                batch.add(first)
                queue.drainTo(batch, 255)
                dir?.let { append(it, batch) }
            } catch (_: InterruptedException) {
                return
            } catch (t: Throwable) {
                Log.w("TXA/TxaLogger", "ghi log lỗi", t)
            } finally {
                batch.clear()
            }
        }
    }

    @Synchronized
    private fun append(d: File, lines: List<String>) {
        val f = File(d, "txa_0.log")
        if (f.length() > MAX_FILE) rotate(d)
        FileOutputStream(f, true).bufferedWriter().use { w -> for (l in lines) { w.write("[$session/$processTag] "); w.write(l); w.write("\n") } }
    }

    private fun rotate(d: File) {
        File(d, "txa_${KEEP_FILES - 1}.log").delete()
        for (k in KEEP_FILES - 2 downTo 0) File(d, "txa_$k.log").takeIf { it.exists() }?.renameTo(File(d, "txa_${k + 1}.log"))
    }
}
