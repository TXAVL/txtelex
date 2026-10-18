package com.customkey.vietime.ui.settings

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.customkey.vietime.ime.ImePrefs
import com.customkey.vietime.tools.BillingRepository
import com.customkey.vietime.tools.ClipboardVault
import com.customkey.vietime.tools.MathEvaluator
import com.customkey.vietime.tools.PlusFeature
import com.customkey.vietime.tools.PlusGate
import com.customkey.vietime.tools.ToneRestorer
import com.customkey.vietime.tools.ToolsRepo
import com.customkey.vietime.ui.theme.GhostButton
import com.customkey.vietime.ui.theme.GlassCard
import com.customkey.vietime.ui.theme.GradientButton
import com.customkey.vietime.ui.theme.SectionLabel
import com.customkey.vietime.ui.theme.Tx
import com.customkey.vietime.ui.theme.TxSwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Màn Công cụ: thêm dấu cả câu, máy tính, clipboard thông minh, mẫu câu. */
@Composable
fun ToolsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ImePrefs.of(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { tick++ }
        ToolsRepo.listeners += l
        onDispose { ToolsRepo.listeners -= l }
    }

    CustomizeScaffold("Công cụ", onBack, preview = false) {
        AddTonesCard()
        MathCard()

        SectionLabel("Thanh gợi ý")
        GlassCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
            @Composable
            fun Toggle(title: String, sub: String, key: String) {
                var v by remember { mutableStateOf(prefs.getBoolean(key, true)) }
                TxSwitchRow(title, sub, v) { v = it; prefs.edit().putBoolean(key, it).apply() }
            }
            Toggle("Chip thêm dấu", "Gõ không dấu cả câu ⇒ chip ✨ thêm dấu", ToolsRepo.KEY_TONES_CHIP)
            Toggle("Kết quả phép tính", "Gõ 12*3= ⇒ chip 36", ToolsRepo.KEY_MATH_CHIP)
            Toggle("Dán nhanh", "Vừa copy OTP / link / SĐT ⇒ chip dán một chạm", ToolsRepo.KEY_PASTE_CHIP)
            Toggle("Lưu lịch sử clipboard", "Chỉ lưu trên máy; bỏ qua nội dung app đánh dấu nhạy cảm", ToolsRepo.KEY_CLIP_HISTORY)
        }

        SectionLabel("Lịch sử clipboard")
        ClipboardList(tick)

        SectionLabel("Mẫu câu")
        SnippetEditor(tick)
    }
}

@Composable
private fun AddTonesCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("hom nay troi dep qua, minh di cafe nhe") }
    var output by remember { mutableStateOf<String?>(null) }
    GlassCard(glow = true) {
        Text("✨ Thêm dấu cả câu", style = MaterialTheme.typography.titleMedium, color = Tx.Text)
        Text("Trên bàn phím: chạm chip ✨ sau khi gõ, hoặc ✦ › Thêm dấu cho vùng chọn / nội dung đã copy.",
            style = MaterialTheme.typography.bodySmall, color = Tx.TextDim)
        Spacer(Modifier.height(10.dp))
        TxField(input, { input = it; output = null }, "Gõ câu không dấu")
        Spacer(Modifier.height(10.dp))
        GradientButton("Thêm dấu", Modifier.fillMaxWidth()) {
            scope.launch { output = withContext(Dispatchers.Default) { ToneRestorer.restore(input) ?: input } }
        }
        output?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = Tx.CyanSoft, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        if (!PlusGate.allows(ctx, PlusFeature.ADD_TONES)) Text("Tính năng Plus", color = Tx.TextFaint, fontSize = 12.sp)
    }
}

@Composable
private fun MathCard() {
    var expr by remember { mutableStateOf("26,160*2,500") }
    val res = MathEvaluator.evaluate(expr)?.text
    GlassCard {
        Text("🧮 Máy tính trên thanh gợi ý", style = MaterialTheme.typography.titleMedium, color = Tx.Text)
        Text("Gõ phép tính kèm dấu = trong bất kỳ ô nào, kết quả hiện trên thanh gợi ý.",
            style = MaterialTheme.typography.bodySmall, color = Tx.TextDim)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TxField(expr, { expr = it }, "Phép tính", Modifier.weight(1f))
            Text("  =  ${res ?: "—"}", color = Tx.Cyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ClipboardList(tick: Int) {
    val ctx = LocalContext.current
    val items = remember(tick) { ToolsRepo.clipboard(ctx).items }
    GlassCard {
        if (items.isEmpty()) Text("Chưa có mục nào. Copy chữ ở bất kỳ đâu khi đang dùng TxTelex.", color = Tx.TextDim, fontSize = 14.sp)
        for (c in items) {
            val m = ClipboardVault.detect(c.text)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m.kind.icon, Modifier.width(28.dp))
                Text(c.text.replace('\n', ' '), Modifier.weight(1f), color = Tx.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                Text(if (c.pinned) "📌" else "📍", Modifier.clickable { ToolsRepo.editClipboard(ctx) { it.setPinned(c.id, !c.pinned) } }.padding(8.dp))
                Text("✕", Modifier.clickable { ToolsRepo.editClipboard(ctx) { it.delete(c.id) } }.padding(8.dp), color = Tx.TextDim)
            }
        }
        if (items.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
            GhostButton("Xoá mục chưa ghim") { ToolsRepo.editClipboard(ctx) { it.clearUnpinned() } }
            GhostButton("Xoá tất cả") { ToolsRepo.wipeClipboard(ctx) }
        }
    }
}

@Composable
private fun SnippetEditor(tick: Int) {
    val ctx = LocalContext.current
    val items = remember(tick) { ToolsRepo.snippets(ctx).items }
    var draft by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Long?>(null) }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TxField(draft, { draft = it }, if (editing != null) "Sửa mẫu câu" else "Thêm mẫu câu mới", Modifier.weight(1f))
            GradientButton(if (editing != null) "Lưu" else "Thêm", enabled = draft.isNotBlank()) {
                val id = editing
                if (id != null) ToolsRepo.editSnippets(ctx) { it.update(id, draft) } else ToolsRepo.editSnippets(ctx) { it.add(draft) != null }
                draft = ""; editing = null
            }
        }
        Spacer(Modifier.height(8.dp))
        for (s in items) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.text, Modifier.weight(1f).clickable { draft = s.text; editing = s.id }, color = Tx.Text, fontSize = 14.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("↑", Modifier.clickable { ToolsRepo.editSnippets(ctx) { it.move(s.id, -1) } }.padding(8.dp), color = Tx.TextDim)
            Text("↓", Modifier.clickable { ToolsRepo.editSnippets(ctx) { it.move(s.id, 1) } }.padding(8.dp), color = Tx.TextDim)
            Text("✕", Modifier.clickable { ToolsRepo.editSnippets(ctx) { it.delete(s.id) } }.padding(8.dp), color = Tx.TextDim)
        }
        Text("Trên bàn phím: ✦ › Mẫu câu, chạm để chèn.", color = Tx.TextFaint, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun TxField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        value, onChange, modifier, label = { Text(label) }, shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Tx.Cyan, unfocusedBorderColor = Tx.Stroke, focusedLabelColor = Tx.Cyan,
            unfocusedLabelColor = Tx.TextDim, cursorColor = Tx.Cyan, focusedContainerColor = Tx.Glass, unfocusedContainerColor = Tx.Glass,
        ),
    )
}

/** Thẻ TxTelex Plus trên màn chính. */
@Composable
fun PlusCard() {
    val ctx = LocalContext.current
    var price by remember { mutableStateOf<String?>(null) }
    var owned by remember { mutableStateOf(PlusGate.unlocked(ctx)) }
    val billing = remember { BillingRepository(ctx) { o, p -> owned = o || !PlusGate.PAYWALL_ENABLED; price = p } }
    DisposableEffect(Unit) { billing.connect(); onDispose { billing.release() } }
    GlassCard(glow = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TxTelex Plus", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Tx.Text)
            Text(if (!PlusGate.PAYWALL_ENABLED) "Miễn phí" else if (owned) "Đã mở khoá ★" else price ?: "",
                color = Tx.Cyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(Modifier.height(8.dp))
        for (f in PlusFeature.entries) Text("✓ ${f.title} — ${f.detail}", color = Tx.TextDim, fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 2.dp))
        if (PlusGate.PAYWALL_ENABLED && !owned) {
            Spacer(Modifier.height(10.dp))
            GradientButton("Mở khoá Plus", Modifier.fillMaxWidth()) { (ctx as? Activity)?.let { billing.purchase(it) } }
        }
    }
}
