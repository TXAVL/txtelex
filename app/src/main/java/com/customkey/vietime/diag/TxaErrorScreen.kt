package com.customkey.vietime.diag

import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.customkey.vietime.ui.MainActivity
import com.customkey.vietime.ui.theme.GhostButton
import com.customkey.vietime.ui.theme.GlassCard
import com.customkey.vietime.ui.theme.GradientButton
import com.customkey.vietime.ui.theme.SectionLabel
import com.customkey.vietime.ui.theme.Tx
import com.customkey.vietime.ui.theme.TxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

/**
 * Màn báo lỗi của TxTelex — chạy ở tiến trình riêng `:txa_crash` nên vẫn mở được khi tiến trình
 * chính vừa chết. Giao diện chỉ hiện: loại lỗi, vân tay, chẩn đoán, hộp đen, stack. Thông tin
 * thiết bị CHỈ được ghép vào khi bấm Sao chép / Chia sẻ ([TxaDeviceReport.fullReport]).
 */
class TxaErrorScreen : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(AColor.TRANSPARENT), SystemBarStyle.dark(AColor.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_REPORT)
        val file = path?.let { File(it) }?.takeIf { it.exists() }
        val text = file?.let { runCatching { it.readText() }.getOrNull() }
        val crash = text?.let { TxaCrash.decode(it) }
        val viewOnly = intent.getBooleanExtra(EXTRA_VIEW_ONLY, false)
        setContent {
            TxTheme {
                Box(Modifier.fillMaxSize().background(Tx.Night).background(Tx.background)) {
                    Scanlines()
                    if (crash == null) Text("Không đọc được báo cáo.", Modifier.align(Alignment.Center), color = Tx.TextDim)
                    else ErrorContent(crash, text, viewOnly,
                        onRestart = { startActivity(Intent(this@TxaErrorScreen, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)); finishAndRemoveTask() },
                        onClose = { finishAndRemoveTask() })
                }
            }
        }
    }

    companion object {
        const val EXTRA_REPORT = "txa.report"
        const val EXTRA_VIEW_ONLY = "txa.viewOnly"
    }
}

/** Lớp sọc quét mờ kiểu màn CRT — điểm nhận diện của màn lỗi TxTelex. */
@Composable
private fun Scanlines() {
    val t = rememberInfiniteTransition(label = "scan")
    val y by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3800, easing = LinearEasing)), label = "y")
    Canvas(Modifier.fillMaxSize()) {
        var yy = 0f
        while (yy < size.height) { drawLine(Color(0x0CFFFFFF), Offset(0f, yy), Offset(size.width, yy), 1f); yy += 6f }
        val by = y * size.height
        drawRect(Color(0x1400E5FF), topLeft = Offset(0f, by), size = androidx.compose.ui.geometry.Size(size.width, 90f))
    }
}

/** Tiêu đề "glitch": ba lớp chữ lệch màu rung ngẫu nhiên quanh lớp chính. */
@Composable
private fun GlitchTitle(text: String) {
    val t = rememberInfiniteTransition(label = "glitch")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(140, easing = LinearEasing), RepeatMode.Restart), label = "p")
    val seed = (phase * 1000).toInt()
    val r = remember(seed) { Random(seed) }
    val burst = r.nextInt(9) == 0
    val dx = if (burst) r.nextInt(-5, 6).toFloat() else r.nextInt(-1, 2).toFloat()
    val style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black, fontSize = 30.sp, letterSpacing = 1.sp)
    Box {
        Text(text, Modifier.offset(x = (dx).dp, y = 0.dp), style = style, color = Color(0xAAFF2E88))
        Text(text, Modifier.offset(x = (-dx).dp, y = (if (burst) 1 else 0).dp), style = style, color = Color(0xAA00E5FF))
        Text(text, style = style, color = Tx.Text)
    }
}

@Composable
private fun ErrorContent(c: TxaCrash, raw: String, viewOnly: Boolean, onRestart: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var showStack by remember { mutableStateOf(false) }
    val count = remember { TxaCrashHub.occurrences(ctx, c.fingerprint) }

    fun export(share: Boolean) {
        busy = if (share) "Đang đóng gói báo cáo…" else "Đang sao chép…"
        scope.launch {
            val full = withContext(Dispatchers.IO) { TxaDeviceReport.fullReport(ctx, c, raw) }
            if (share) TxaDeviceReport.share(ctx, full, "txtelex-${c.fingerprint}.txt")
            else { TxaDeviceReport.copy(ctx, full); Toast.makeText(ctx, "Đã sao chép báo cáo đầy đủ (kèm thông tin máy)", Toast.LENGTH_SHORT).show() }
            busy = null
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.kind.badge, Modifier.background(Tx.Red.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                .border(1.dp, Tx.Red.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                color = Tx.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text(c.timeText, color = Tx.TextFaint, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        GlitchTitle(if (viewOnly) "NHẬT KÝ SỰ CỐ" else "TXTELEX VẤP NGÃ")
        Text(if (viewOnly) c.kind.title else "${c.kind.title}. Bàn phím đã tự dừng để không làm mất chữ của bạn — mở lại là dùng tiếp được.",
            color = Tx.TextDim, style = MaterialTheme.typography.bodyMedium)

        GlassCard(glow = true) {
            Text("VÂN TAY LỖI", color = Tx.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.fingerprint, Modifier.weight(1f), color = Tx.Cyan, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(if (count > 1) "gặp $count lần" else "lần đầu", color = if (count > 1) Tx.Red else Tx.Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            Text("🩺 ${c.hint}", color = Tx.Text, style = MaterialTheme.typography.bodyMedium)
        }

        if (c.breadcrumbs.isNotEmpty()) {
            SectionLabel("Hộp đen — những gì xảy ra trước đó")
            GlassCard {
                val crumbs = c.breadcrumbs.takeLast(10)
                for ((i, line) in crumbs.withIndex()) Row(verticalAlignment = Alignment.Top) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(9.dp).background(if (i == crumbs.lastIndex) Tx.Red else Tx.Cyan, CircleShape))
                        if (i < crumbs.lastIndex) Box(Modifier.width(1.5.dp).height(22.dp).background(Tx.Stroke))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(line, color = if (i == crumbs.lastIndex) Tx.Text else Tx.TextDim, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.offset(y = (-3).dp))
                }
            }
        }

        GlassCard(onClick = { showStack = !showStack }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Chi tiết kỹ thuật", Modifier.weight(1f), color = Tx.Text, style = MaterialTheme.typography.titleSmall)
                Text(if (showStack) "▲" else "▼", color = Tx.TextDim)
            }
            Text(c.title, color = Tx.CyanSoft, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp))
            if (showStack) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.horizontalScroll(rememberScrollState())) {
                    Text(c.stack.lines().take(60).joinToString("\n"), color = Tx.TextFaint, fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace, lineHeight = 14.sp, softWrap = false)
                }
            }
        }

        busy?.let { Text(it, color = Tx.CyanSoft, fontSize = 13.sp) }
        if (!viewOnly) GradientButton("Mở lại TxTelex", Modifier.fillMaxWidth(), onClick = onRestart)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("⧉ Sao chép báo cáo", Modifier.weight(1f)) { export(share = false) }
            GhostButton("↗ Chia sẻ báo cáo", Modifier.weight(1f)) { export(share = true) }
        }
        Text("Báo cáo chia sẻ có kèm cấu hình máy để TXA tìm lỗi nhanh hơn. Không chứa chữ bạn đã gõ.",
            color = Tx.TextFaint, fontSize = 11.5.sp)
        Text(if (viewOnly) "Quay lại" else "Đóng", Modifier.align(Alignment.CenterHorizontally).clickable(onClick = onClose).padding(10.dp),
            color = Tx.TextDim, fontSize = 14.sp)
    }
}

/**
 * Trung tâm nhật ký (trong màn Giới thiệu): danh sách sự cố, xuất / sao chép log đầy đủ, xoá, và
 * "phòng thử nghiệm" cố ý gây lỗi để kiểm tra bộ bắt.
 */
@Composable
fun TxaLogCenter(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    val reports = remember(tick) { TxaCrashHub.list(ctx) }
    var busy by remember { mutableStateOf<String?>(null) }

    fun export(share: Boolean) {
        busy = "Đang gom nhật ký…"
        scope.launch {
            val full = withContext(Dispatchers.IO) { TxaDeviceReport.fullReport(ctx, null, null) }
            if (share) TxaDeviceReport.share(ctx, full, "txtelex-nhatky-${TxaLogger.session}.txt")
            else { TxaDeviceReport.copy(ctx, full); Toast.makeText(ctx, "Đã sao chép nhật ký (kèm thông tin máy)", Toast.LENGTH_SHORT).show() }
            busy = null
        }
    }

    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("‹ Quay lại", Modifier.clickable(onClick = onBack).padding(vertical = 6.dp), color = Tx.CyanSoft, fontWeight = FontWeight.SemiBold)
        Text("Nhật ký & báo lỗi", style = MaterialTheme.typography.titleLarge, color = Tx.Text)
        Text("TxTelex tự ghi lại sự cố (cả lỗi tầng native và treo máy) — chỉ lưu trong máy. Bấm xuất khi cần gửi cho TXA.",
            color = Tx.TextDim, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("⧉ Sao chép nhật ký", Modifier.weight(1f)) { export(false) }
            GhostButton("↗ Xuất file", Modifier.weight(1f)) { export(true) }
        }
        busy?.let { Text(it, color = Tx.CyanSoft, fontSize = 13.sp) }

        SectionLabel("Sự cố đã ghi (${reports.size})")
        if (reports.isEmpty()) GlassCard { Text("Chưa có sự cố nào. Tuyệt vời! ✨", color = Tx.TextDim) }
        for ((f, c) in reports) GlassCard(padding = androidx.compose.foundation.layout.PaddingValues(14.dp),
            onClick = { TxaCrashHub.showScreen(ctx, f, viewOnly = true) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.kind.badge, color = if (c.kind == TxaCrashKind.FREEZE) Tx.CyanSoft else Tx.Red, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.width(56.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.title, color = Tx.Text, fontSize = 13.sp, maxLines = 1, fontFamily = FontFamily.Monospace)
                    Text("${c.fingerprint} · ${c.timeText}", color = Tx.TextFaint, fontSize = 11.sp)
                }
                Text("›", color = Tx.Cyan, fontSize = 20.sp)
            }
        }
        if (reports.isNotEmpty()) GhostButton("Xoá toàn bộ sự cố & nhật ký") {
            TxaCrashHub.clearAll(ctx); TxaLogger.clear(); tick++
        }

        SectionLabel("Phòng thử nghiệm")
        GlassCard {
            Text("Cố ý gây lỗi để kiểm tra bộ bắt. Ứng dụng sẽ đóng ngay rồi hiện màn báo lỗi.",
                color = Tx.TextDim, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Crash Java", Modifier.weight(1f)) {
                    TxaLogger.crumb("Phòng thử nghiệm: crash Java")
                    throw IllegalStateException("Crash thử nghiệm từ Phòng thử nghiệm TxTelex")
                }
                GhostButton("Crash native", Modifier.weight(1f)) {
                    TxaLogger.crumb("Phòng thử nghiệm: crash native SIGSEGV"); TxaLogger.flushSync()
                    TxaNativeGuard.crashForTest(0)
                }
            }
            Spacer(Modifier.height(8.dp))
            GhostButton("Treo luồng chính 6 giây", Modifier.fillMaxWidth()) {
                TxaLogger.crumb("Phòng thử nghiệm: treo 6s"); Thread.sleep(6000)
            }
            if (!TxaNativeGuard.active) Text("Bộ bắt native chưa nạp trên máy này.", color = Tx.Red, fontSize = 12.sp)
        }
    }
}

/** Mở màn lỗi cho báo cáo chưa xem (gọi từ MainActivity). */
fun showPendingCrash(ctx: Context): Boolean {
    val f = TxaCrashHub.takePending(ctx) ?: return false
    TxaCrashHub.showScreen(ctx, f, viewOnly = false)
    return true
}
