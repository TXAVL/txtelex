package com.customkey.vietime.diag

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.customkey.vietime.BuildConfig
import java.io.File
import kotlin.system.exitProcess

/**
 * TxaCrashHub — bắt MỌI loại sự cố của TxTelex:
 *
 * 1. **Java/Kotlin**: `Thread.setDefaultUncaughtExceptionHandler` → ghi báo cáo đồng bộ, mở
 *    [TxaErrorScreen] ở tiến trình riêng `:txa_crash` (vẫn sống khi tiến trình chính chết).
 * 2. **Native (C/C++)**: [TxaNativeGuard] cài signal handler (SIGSEGV/SIGABRT/SIGBUS/SIGFPE/
 *    SIGILL/SIGTRAP/SIGSYS) ghi dump trước khi chết; lần mở sau [ingest] chuyển thành báo cáo.
 * 3. **Treo (FREEZE)**: "nhịp tim" — luồng nền gõ cửa luồng chính mỗi giây; im lặng ≥ 5 s ⇒
 *    chụp stack luồng chính (chỉ ghi log + báo cáo, không làm phiền người dùng).
 * 4. **Hệ thống (Android 11+)**: `ApplicationExitInfo` — ANR thật, crash native (tombstone),
 *    bị kill vì thiếu RAM… mà app không kịp tự ghi.
 */
object TxaCrashHub {
    private const val TAG = "TxaCrashHub"
    private const val PREFS = "txa_diag"
    private const val KEY_PENDING = "pending"
    private const val KEY_EXIT_SEEN = "exitSeen"
    private const val MAX_REPORTS = 30
    const val CRASH_PROCESS_SUFFIX = ":txa_crash"
    private const val FREEZE_MS = 5000L

    @Volatile private var installed = false
    private lateinit var app: Context

    fun reportsDir(ctx: Context) = File(ctx.applicationContext.filesDir, "txa_crash/reports").apply { mkdirs() }
    fun nativeDir(ctx: Context) = File(ctx.applicationContext.filesDir, "txa_crash/native").apply { mkdirs() }

    fun processName(ctx: Context): String =
        if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
        else runCatching { File("/proc/self/cmdline").readText().trim('\u0000', ' ') }.getOrDefault(ctx.packageName)

    fun isCrashProcess(ctx: Context) = processName(ctx).endsWith(CRASH_PROCESS_SUFFIX)

    /** Gọi sớm nhất có thể (Application.onCreate). */
    fun install(application: Application) {
        if (installed) return
        installed = true
        app = application.applicationContext
        val proc = processName(app)
        val short = proc.substringAfter(':', "main")
        TxaLogger.init(app, short)
        if (isCrashProcess(app)) return   // màn lỗi không tự bắt chính nó (tránh vòng lặp)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                onJavaCrash(thread, error)
            } catch (t: Throwable) {
                Log.e("TXA/$TAG", "bộ bắt lỗi tự lỗi", t)
                previous?.uncaughtException(thread, error)
            } finally {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
        TxaNativeGuard.install(nativeDir(app))
        startHeartbeat()
        TxaLogger.crumb("Khởi động ${short} · native guard ${if (TxaNativeGuard.active) "BẬT" else "tắt"}")
    }

    // MARK: Java

    private fun onJavaCrash(thread: Thread, error: Throwable) {
        val root = generateSequence(error) { it.cause }.last()
        val c = TxaCrash(
            kind = TxaCrashKind.JAVA, time = System.currentTimeMillis(),
            thread = "${thread.name}#${thread.id}",
            title = root.javaClass.name + (root.message?.let { ": " + it.take(160) } ?: ""),
            message = error.toString().take(400),
            stack = Log.getStackTraceString(error),
            breadcrumbs = TxaLogger.breadcrumbs(),
            process = TxaLogger.processTag, session = TxaLogger.session,
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            uptimeMs = TxaLogger.uptimeMs(), logTail = TxaLogger.recent(80),
        )
        TxaLogger.e(TAG, "CRASH ${c.fingerprint} trên ${c.thread}", error)
        TxaLogger.flushSync()
        val f = save(app, c)
        markPending(app, f)
        showScreen(app, f)
    }

    // MARK: lưu / đọc

    fun save(ctx: Context, c: TxaCrash): File {
        val dir = reportsDir(ctx)
        val f = File(dir, "${c.time}_${c.kind.name}_${c.fingerprint}.txt")
        f.writeText(c.encode())
        dir.listFiles()?.sortedByDescending { it.name }?.drop(MAX_REPORTS)?.forEach { it.delete() }
        return f
    }

    fun list(ctx: Context): List<Pair<File, TxaCrash>> =
        reportsDir(ctx).listFiles()?.sortedByDescending { it.name }
            ?.mapNotNull { f -> runCatching { TxaCrash.decode(f.readText()) }.getOrNull()?.let { f to it } }.orEmpty()

    /** Số lần đã gặp cùng vân tay. */
    fun occurrences(ctx: Context, fingerprint: String): Int =
        reportsDir(ctx).listFiles()?.count { it.name.endsWith("_$fingerprint.txt") } ?: 0

    fun clearAll(ctx: Context) { reportsDir(ctx).listFiles()?.forEach { it.delete() }; nativeDir(ctx).listFiles()?.forEach { it.delete() } }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun markPending(ctx: Context, f: File) { prefs(ctx).edit().putString(KEY_PENDING, f.name).commit() }

    /** Báo cáo chưa được người dùng xem (crash ở bàn phím / native) — MainActivity mở màn lỗi. */
    fun takePending(ctx: Context): File? {
        val p = prefs(ctx)
        val name = p.getString(KEY_PENDING, null) ?: return null
        p.edit().remove(KEY_PENDING).apply()
        return File(reportsDir(ctx), name).takeIf { it.exists() }
    }

    fun showScreen(ctx: Context, report: File, viewOnly: Boolean = false) {
        runCatching {
            ctx.startActivity(Intent(ctx, TxaErrorScreen::class.java)
                .putExtra(TxaErrorScreen.EXTRA_REPORT, report.absolutePath)
                .putExtra(TxaErrorScreen.EXTRA_VIEW_ONLY, viewOnly)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }.onSuccess { if (!viewOnly) prefs(ctx).edit().remove(KEY_PENDING).commit() }
    }

    // MARK: lần mở sau — native dump + ApplicationExitInfo

    /** Gọi ở tiến trình chính sau khi khởi động (luồng nền): chuyển dump native / exit info thành báo cáo. */
    fun ingest(ctx: Context) {
        var newest: File? = null
        for (dump in nativeDir(ctx).listFiles()?.sortedBy { it.name }.orEmpty()) {
            val c = runCatching { TxaNativeGuard.parseDump(dump.readText()) }.getOrNull()
            dump.delete()
            if (c == null) continue
            TxaLogger.e(TAG, "Phát hiện crash native lần trước: ${c.title} (${c.fingerprint})")
            newest = save(ctx, c)
        }
        if (Build.VERSION.SDK_INT >= 30) newest = ingestExitInfo(ctx) ?: newest
        newest?.let { markPending(ctx, it) }
    }

    private fun ingestExitInfo(ctx: Context): File? {
        if (Build.VERSION.SDK_INT < 30) return null
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return null
        val p = prefs(ctx)
        val seen = p.getLong(KEY_EXIT_SEEN, 0L)
        val infos = runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 8) }.getOrNull().orEmpty()
        var newest = seen
        var show: File? = null
        for (info in infos.filter { it.timestamp > seen }.sortedBy { it.timestamp }) {
            newest = maxOf(newest, info.timestamp)
            val reason = when (info.reason) {
                ApplicationExitInfo.REASON_ANR -> TxaCrashKind.ANR
                ApplicationExitInfo.REASON_CRASH_NATIVE -> TxaCrashKind.NATIVE
                ApplicationExitInfo.REASON_LOW_MEMORY -> { TxaLogger.w(TAG, "Lần trước bị hệ thống tắt vì thiếu RAM"); null }
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> TxaCrashKind.SYSTEM
                else -> null
            } ?: continue
            // Crash native đã có dump riêng (chi tiết hơn) trong vòng 15 s ⇒ bỏ bản trùng.
            if (reason == TxaCrashKind.NATIVE && list(ctx).any { it.second.kind == TxaCrashKind.NATIVE && kotlin.math.abs(it.second.time - info.timestamp) < 15_000 }) continue
            val trace = if (reason == TxaCrashKind.ANR) runCatching {
                info.traceInputStream?.bufferedReader()?.useLines { s -> s.take(400).joinToString("\n") }
            }.getOrNull().orEmpty() else ""
            val c = TxaCrash(
                kind = reason, time = info.timestamp, thread = "pid ${info.pid}",
                title = when (reason) { TxaCrashKind.ANR -> "ANR — ứng dụng không phản hồi"; TxaCrashKind.NATIVE -> "Crash native (hệ thống ghi nhận)"; else -> "Hệ thống dừng ứng dụng" },
                message = (info.description ?: "") + " · importance=${info.importance} · pss=${info.pss / 1024}MB · rss=${info.rss / 1024}MB",
                stack = trace.ifEmpty { "(hệ thống không cung cấp stack — xem tombstone bằng adb bugreport)" },
                process = info.processName ?: "?", appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            )
            TxaLogger.w(TAG, "ApplicationExitInfo: ${c.title} lúc ${c.timeText}")
            show = save(ctx, c)
        }
        p.edit().putLong(KEY_EXIT_SEEN, newest).apply()
        return show
    }

    // MARK: nhịp tim luồng chính

    private fun startHeartbeat() {
        val main = Handler(Looper.getMainLooper())
        val pendingFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val lastBeatAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        Thread({
            var reported = false
            while (true) {
                if (pendingFlag.compareAndSet(false, true)) main.post { lastBeatAt.set(System.currentTimeMillis()); pendingFlag.set(false) }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return@Thread }
                val pending = pendingFlag.get()
                val stall = System.currentTimeMillis() - lastBeatAt.get()
                if (pending && stall >= FREEZE_MS && !reported && !android.os.Debug.isDebuggerConnected()) {
                    reported = true
                    val mt = Looper.getMainLooper().thread
                    val stack = mt.stackTrace.joinToString("\n") { "\tat $it" }
                    TxaLogger.w(TAG, "Luồng chính im lặng ${stall} ms — có thể treo\n$stack")
                    runCatching {
                        save(app, TxaCrash(TxaCrashKind.FREEZE, System.currentTimeMillis(), "${mt.name}#${mt.id}",
                            "Luồng chính kẹt ${stall / 1000}s", "Không phản hồi nhịp tim trong ${stall} ms", stack,
                            TxaLogger.breadcrumbs(), TxaLogger.processTag, TxaLogger.session,
                            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", TxaLogger.uptimeMs(), TxaLogger.recent(40)))
                    }
                }
                if (!pending) reported = false
            }
        }, "txa-heartbeat").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1; start() }
    }
}
