package com.customkey.vietime.diag

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import androidx.core.content.FileProvider
import com.customkey.vietime.BuildConfig
import com.customkey.vietime.ime.ImePrefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Thông tin thiết bị CHI TIẾT — chỉ được ghép vào lúc người dùng bấm Sao chép / Chia sẻ báo cáo
 * (KHÔNG hiện trên giao diện). Không chứa chữ người dùng gõ, clipboard, danh bạ hay định danh
 * quảng cáo; chỉ cấu hình máy + trạng thái app.
 */
object TxaDeviceReport {
    private const val BANNER = """
╔═══════════════════════════════════════════════════════════╗
║    _____  __  __  _____   _____   _       _____  __  __   ║
║   |_   _| \ \/ / |_   _| | ____| | |     | ____| \ \/ /   ║
║     | |    \  /    | |   |  _|   | |     |  _|    \  /    ║
║     | |    /  \    | |   | |___  | |___  | |___   /  \    ║
║     |_|   /_/\_\   |_|   |_____| |_____| |_____| /_/\_\   ║
║                                                           ║
║          TXTELEX · BÁO CÁO HỆ THỐNG · TXA STUDIO          ║
╚═══════════════════════════════════════════════════════════╝"""

    fun build(ctx: Context): String = buildString {
        appendLine(BANNER.trimStart('\n'))
        appendLine("Tạo lúc: ${fmt(System.currentTimeMillis())}  ·  phiên ${TxaLogger.session}")
        section("ỨNG DỤNG") { app(ctx) }
        section("THIẾT BỊ") { device() }
        section("MÀN HÌNH") { display(ctx) }
        section("BỘ NHỚ") { memory(ctx) }
        section("PIN & NHIỆT") { power(ctx) }
        section("MẠNG") { network(ctx) }
        section("NGÔN NGỮ & THỜI GIAN") { locale(ctx) }
        section("BÀN PHÍM HỆ THỐNG") { imes(ctx) }
        section("THÀNH PHẦN HỆ THỐNG") { components(ctx) }
        section("CÀI ĐẶT TXTELEX") { settings(ctx) }
    }

    private inline fun StringBuilder.section(title: String, body: StringBuilder.() -> Unit) {
        appendLine()
        appendLine("┌─ $title " + "─".repeat((48 - title.length).coerceAtLeast(2)))
        runCatching { body() }.onFailure { appendLine("│ (không đọc được: ${it.javaClass.simpleName})") }
    }

    private fun StringBuilder.kv(k: String, v: Any?) { appendLine("│ ${k.padEnd(22)} $v") }

    private fun fmt(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(t))
    private fun mb(b: Long) = "%.1f MB".format(Locale.US, b / 1048576.0)

    private fun StringBuilder.app(ctx: Context) {
        val pm = ctx.packageManager
        val pi = pm.getPackageInfo(ctx.packageName, 0)
        kv("Gói", ctx.packageName)
        kv("Phiên bản", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}")
        kv("Cài lần đầu", fmt(pi.firstInstallTime))
        kv("Cập nhật", fmt(pi.lastUpdateTime))
        kv("Nguồn cài", runCatching {
            if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(ctx.packageName).installingPackageName
            else @Suppress("DEPRECATION") pm.getInstallerPackageName(ctx.packageName)
        }.getOrNull() ?: "APK cài tay")
        kv("targetSdk", ctx.applicationInfo.targetSdkVersion)
        kv("Tiến trình", "${TxaCrashHub.processName(ctx)} · pid ${Process.myPid()} · uid ${Process.myUid()}")
        kv("Chạy được", TxaLogger.clock())
        kv("Bộ bắt native", if (TxaNativeGuard.active) "BẬT" else "tắt")
        kv("Số luồng", Thread.activeCount())
        kv("FD đang mở", File("/proc/self/fd").list()?.size ?: "?")
        kv("Debug", BuildConfig.DEBUG)
    }

    private fun StringBuilder.device() {
        kv("Hãng / Model", "${Build.MANUFACTURER} · ${Build.BRAND} · ${Build.MODEL}")
        kv("Mã máy", "${Build.DEVICE} / ${Build.PRODUCT} / ${Build.BOARD}")
        kv("Phần cứng", Build.HARDWARE)
        if (Build.VERSION.SDK_INT >= 31) kv("SoC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        kv("ABI", Build.SUPPORTED_ABIS.joinToString())
        kv("CPU lõi", Runtime.getRuntime().availableProcessors())
        kv("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · bản vá ${Build.VERSION.SECURITY_PATCH}")
        kv("Bản dựng", "${Build.ID} · ${Build.TYPE} · ${Build.TAGS}")
        kv("Incremental", Build.VERSION.INCREMENTAL)
        kv("Fingerprint", Build.FINGERPRINT)
        kv("Kernel", System.getProperty("os.version"))
        kv("VM", "${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}")
        kv("Bootloader", Build.BOOTLOADER)
        kv("Radio", runCatching { Build.getRadioVersion() }.getOrNull())
        kv("Máy ảo?", looksEmulator())
        kv("Dấu hiệu root", rootHints())
    }

    private fun looksEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone") ||
            Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") || Build.PRODUCT.contains("sdk")

    private fun rootHints(): String {
        val su = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/data/adb/magisk").filter { File(it).exists() }
        val tags = if (Build.TAGS?.contains("test-keys") == true) listOf("test-keys") else emptyList()
        return (su + tags).ifEmpty { listOf("không") }.joinToString()
    }

    private fun StringBuilder.display(ctx: Context) {
        val dm = ctx.resources.displayMetrics
        val c = ctx.resources.configuration
        kv("Độ phân giải", "${dm.widthPixels}×${dm.heightPixels} px · ${dm.densityDpi} dpi (×${dm.density})")
        kv("Cỡ dp", "${c.screenWidthDp}×${c.screenHeightDp} dp · sw${c.smallestScreenWidthDp}dp")
        kv("Cỡ chữ hệ thống", "×${c.fontScale}")
        kv("Hướng", if (c.orientation == Configuration.ORIENTATION_LANDSCAPE) "ngang" else "dọc")
        kv("Chế độ tối", (c.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
        val wm = ctx.getSystemService(WindowManager::class.java)
        @Suppress("DEPRECATION")
        kv("Tần số quét", runCatching { "%.0f Hz".format(Locale.US, wm.defaultDisplay.refreshRate) }.getOrDefault("?"))
    }

    private fun StringBuilder.memory(ctx: Context) {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        kv("RAM", "${mb(mi.availMem)} trống / ${mb(mi.totalMem)} · ngưỡng ${mb(mi.threshold)} · thiếu RAM=${mi.lowMemory}")
        kv("Lớp bộ nhớ app", "${am.memoryClass} MB (large ${am.largeMemoryClass} MB) · lowRam=${am.isLowRamDevice}")
        val rt = Runtime.getRuntime()
        kv("Heap Java", "${mb(rt.totalMemory() - rt.freeMemory())} dùng / ${mb(rt.maxMemory())} tối đa")
        kv("Heap native", "${mb(Debug.getNativeHeapAllocatedSize())} / ${mb(Debug.getNativeHeapSize())}")
        val st = StatFs(ctx.filesDir.absolutePath)
        kv("Bộ nhớ trong", "${mb(st.availableBytes)} trống / ${mb(st.totalBytes)}")
        kv("Ổ ngoài", Environment.getExternalStorageState())
    }

    private fun StringBuilder.power(ctx: Context) {
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (b != null) {
            val lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / b.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val status = when (b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "đang sạc"; BatteryManager.BATTERY_STATUS_FULL -> "đầy"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "đang xả"; else -> "không sạc"
            }
            kv("Pin", "$lvl% · $status · ${b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0}°C · ${b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)} mV")
        }
        val pm = ctx.getSystemService(PowerManager::class.java)
        kv("Tiết kiệm pin", pm.isPowerSaveMode)
        kv("Miễn tối ưu pin", pm.isIgnoringBatteryOptimizations(ctx.packageName))
        if (Build.VERSION.SDK_INT >= 29) kv("Nhiệt máy", when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "bình thường"; PowerManager.THERMAL_STATUS_LIGHT -> "ấm"
            PowerManager.THERMAL_STATUS_MODERATE -> "nóng vừa"; PowerManager.THERMAL_STATUS_SEVERE -> "nóng"
            else -> "rất nóng (${pm.currentThermalStatus})"
        })
        kv("Thời gian từ lúc bật máy", "%.1f giờ".format(Locale.US, SystemClock.elapsedRealtime() / 3_600_000.0))
    }

    private fun StringBuilder.network(ctx: Context) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        kv("Kết nối", when {
            caps == null -> "không có / không đọc được"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Di động"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "khác"
        })
        kv("Tính phí theo dung lượng", runCatching { cm.isActiveNetworkMetered }.getOrNull())
    }

    private fun StringBuilder.locale(ctx: Context) {
        kv("Ngôn ngữ", ctx.resources.configuration.locales.toLanguageTags())
        kv("Múi giờ", TimeZone.getDefault().id)
        kv("Giờ 24h", android.text.format.DateFormat.is24HourFormat(ctx))
        val a11y = ctx.getSystemService(AccessibilityManager::class.java)
        kv("TalkBack/khám phá chạm", a11y.isTouchExplorationEnabled)
    }

    private fun StringBuilder.imes(ctx: Context) {
        val imm = ctx.getSystemService(InputMethodManager::class.java)
        val cur = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        kv("Đang dùng", cur.substringBefore('/'))
        kv("Đã bật", imm.enabledInputMethodList.joinToString { it.packageName })
    }

    private fun StringBuilder.components(ctx: Context) {
        fun ver(pkg: String) = runCatching { val p = ctx.packageManager.getPackageInfo(pkg, 0); "${p.versionName}" }.getOrNull() ?: "không có"
        kv("WebView", runCatching { android.webkit.WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } }.getOrNull())
        kv("Google Play Services", ver("com.google.android.gms"))
        kv("Play Store", ver("com.android.vending"))
        kv("Quyền rung", ctx.checkSelfPermission(android.Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED)
    }

    /** Cài đặt của TxTelex (chỉ cấu hình, không có nội dung người dùng). */
    private fun StringBuilder.settings(ctx: Context) {
        val all = ImePrefs.of(ctx).all.toSortedMap()
        for ((k, v) in all) kv(k, v.toString().take(120))
    }

    // MARK: xuất

    /** Báo cáo đầy đủ để gửi: thông tin máy + sự cố + sự kiện gần nhất + log + logcat của chính app. */
    fun fullReport(ctx: Context, crash: TxaCrash?, crashText: String?): String = buildString {
        append(build(ctx))
        appendLine()
        if (crash != null) {
            appendLine("┌─ SỰ CỐ ${crash.fingerprint} " + "─".repeat(24))
            appendLine("│ Loại      ${crash.kind.title} [${crash.kind.badge}]")
            appendLine("│ Lúc       ${fmt(crash.time)}")
            appendLine("│ Gặp       ${TxaCrashHub.occurrences(ctx, crash.fingerprint)} lần")
            appendLine("│ Chẩn đoán ${crash.hint}")
            appendLine()
            appendLine(crashText ?: crash.encode())
        }
        appendLine("┌─ NHẬT KÝ TXTELEX " + "─".repeat(30))
        appendLine(TxaLogger.readAll(600 * 1024))
        appendLine("┌─ LOGCAT (chỉ tiến trình của TxTelex) " + "─".repeat(10))
        appendLine(logcat())
    }

    /** Logcat chỉ đọc được log của chính app (Android 4.1+) — 800 dòng cuối. */
    private fun logcat(): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "800").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.ifBlank { "(trống)" }
    }.getOrDefault("(không đọc được logcat)")

    fun copy(ctx: Context, text: String) {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        // Binder giới hạn ~1 MB — giữ phần đầu (thông tin máy + sự cố) và đuôi log.
        val safe = if (text.length > 350_000) text.take(250_000) + "\n…(lược bớt — dùng Chia sẻ để lấy bản đầy đủ)…\n" + text.takeLast(80_000) else text
        cm.setPrimaryClip(ClipData.newPlainText("TxTelex báo cáo lỗi", safe))
    }

    fun share(ctx: Context, text: String, name: String) {
        val dir = File(ctx.cacheDir, "txa_export").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, name).apply { writeText(text) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.txa.files", f)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Báo cáo lỗi TxTelex ${BuildConfig.VERSION_NAME}")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Gửi báo cáo lỗi").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
