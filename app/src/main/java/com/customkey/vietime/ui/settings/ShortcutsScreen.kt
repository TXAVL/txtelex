package com.customkey.vietime.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.customkey.vietime.engine.Keys
import com.customkey.vietime.engine.ShortcutFile
import com.customkey.vietime.engine.ShortcutTable
import com.customkey.vietime.ime.ImePrefs
import com.customkey.vietime.ui.theme.GhostButton
import com.customkey.vietime.ui.theme.GlassCard
import com.customkey.vietime.ui.theme.GradientButton
import com.customkey.vietime.ui.theme.SectionLabel
import com.customkey.vietime.ui.theme.Tx
import com.customkey.vietime.ui.theme.TxSwitchRow

/** Bảng gõ tắt mặc định khi người dùng chưa từng chỉnh. */
object DefaultShortcuts {
    val table: Map<String, String> = linkedMapOf(
        "ko" to "không", "k" to "không", "dc" to "được", "đc" to "được", "vs" to "với", "ntn" to "như thế nào",
        "bn" to "bao nhiêu", "mn" to "mọi người", "cty" to "công ty", "stk" to "số tài khoản", "sdt" to "số điện thoại",
        "tks" to "cảm ơn", "j" to "gì", "ng" to "người", "đt" to "điện thoại", "hn" to "Hà Nội", "hcm" to "TP. Hồ Chí Minh",
    )

    /** Ghi bảng mặc định một lần (lần đầu cài) để gõ tắt dùng được ngay. */
    fun seedIfAbsent(ctx: android.content.Context) {
        val p = ImePrefs.of(ctx)
        if (!p.contains(Keys.SHORTCUTS)) p.edit().putString(Keys.SHORTCUTS, ShortcutFile.exportYAML(table)).apply()
    }
}

/** Gõ tắt: "ko" + dấu cách ⇒ "không". Thêm / sửa / xoá, bật-tắt, nhập-xuất file YAML. */
@Composable
fun ShortcutsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ImePrefs.of(ctx) }
    var table by remember { mutableStateOf(ShortcutFile.parse(prefs.getString(Keys.SHORTCUTS, null)).toSortedMap()) }
    var enabled by remember { mutableStateOf(prefs.getBoolean(Keys.SHORTCUTS_ENABLED, true)) }
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }

    fun save(t: Map<String, String>) {
        table = t.toSortedMap()
        prefs.edit().putString(Keys.SHORTCUTS, ShortcutFile.exportYAML(t)).apply()
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/yaml")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(ShortcutFile.exportYAML(table).toByteArray()) } }
            .onSuccess { Toast.makeText(ctx, "Đã xuất ${table.size} gõ tắt", Toast.LENGTH_SHORT).show() }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
        val imported = ShortcutFile.parse(text)
        if (imported.isEmpty()) { Toast.makeText(ctx, "File không có gõ tắt hợp lệ", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
        val (merged, added, updated) = ShortcutFile.merge(table, imported)
        save(merged)
        Toast.makeText(ctx, "Thêm $added, cập nhật $updated gõ tắt", Toast.LENGTH_SHORT).show()
    }

    CustomizeScaffold("Gõ tắt", onBack, preview = false) {
        GlassCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
            TxSwitchRow("Bật gõ tắt", "Gõ khoá rồi dấu cách / dấu câu ⇒ bung nội dung; ⌫ ngay sau = trả lại khoá", enabled) {
                enabled = it; prefs.edit().putBoolean(Keys.SHORTCUTS_ENABLED, it).apply()
            }
        }

        SectionLabel(if (editing != null) "Sửa gõ tắt" else "Thêm gõ tắt")
        GlassCard {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShortcutField(key, { key = it.trim() }, "Khoá (vd: ko)", Modifier.weight(0.38f))
                ShortcutField(value, { value = it }, "Nội dung (vd: không)", Modifier.weight(0.62f))
            }
            Spacer(Modifier.height(10.dp))
            val validKey = key.isNotEmpty() && ShortcutTable.isValidKey(key)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GradientButton(if (editing != null) "Lưu" else "Thêm", Modifier.weight(1f), enabled = validKey && value.isNotBlank()) {
                    val t = LinkedHashMap(table)
                    editing?.let { if (it != key) t.remove(it) }
                    t[key] = value
                    save(t); key = ""; value = ""; editing = null
                }
                if (editing != null) GhostButton("Huỷ") { key = ""; value = ""; editing = null }
            }
            if (key.isNotEmpty() && !validKey) Text("Khoá chỉ gồm chữ, số hoặc ký hiệu, không có dấu cách.", color = Tx.Red, fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp))
        }

        SectionLabel("Bảng gõ tắt (${table.size})")
        GlassCard {
            if (table.isEmpty()) Text("Chưa có gõ tắt nào.", color = Tx.TextDim)
            for ((k, v) in table) Row(Modifier.fillMaxWidth().clickable { key = k; value = v; editing = k }.padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(k, Modifier.width(82.dp), color = Tx.Cyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("→  " + v.replace("\n", " ⏎ "), Modifier.weight(1f), color = Tx.Text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("✕", Modifier.clickable { save(table - k) }.padding(8.dp), color = Tx.TextDim)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("⬇ Nhập file", Modifier.weight(1f)) { importer.launch(arrayOf("*/*")) }
            GhostButton("⬆ Xuất file", Modifier.weight(1f)) { exporter.launch("txtelex-go-tat.yml") }
        }
        GhostButton("Khôi phục bảng mặc định", Modifier.fillMaxWidth()) { save(table + DefaultShortcuts.table) }
    }
}

@Composable
private fun ShortcutField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(
        value, onChange, modifier, label = { Text(label, fontSize = 12.sp) }, singleLine = true, shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Tx.Cyan, unfocusedBorderColor = Tx.Stroke, focusedLabelColor = Tx.Cyan,
            unfocusedLabelColor = Tx.TextDim, cursorColor = Tx.Cyan, focusedContainerColor = Tx.Glass, unfocusedContainerColor = Tx.Glass,
        ),
    )
}
