package com.customkey.vietime.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.customkey.vietime.engine.ImmediateMainThread
import com.customkey.vietime.engine.Keys
import com.customkey.vietime.engine.UserLangModel
import com.customkey.vietime.ime.ImePrefs
import com.customkey.vietime.ui.theme.GhostButton
import com.customkey.vietime.ui.theme.GlassCard
import com.customkey.vietime.ui.theme.GradientButton
import com.customkey.vietime.ui.theme.Tx
import java.io.File
import java.util.concurrent.Executor

/**
 * Màn hình Từ điển cá nhân:
 * Xem danh sách các từ bàn phím đã tự học qua thói quen gõ,
 * thêm tên riêng / biệt danh / thuật ngữ riêng để bàn phím ưu tiên gợi ý ngay,
 * hoặc xoá các từ gõ nhầm.
 */
@Composable
fun UserDictScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ImePrefs.of(ctx) }
    val directExecutor = remember { Executor { it.run() } }
    val userlmFile = remember { File(ctx.filesDir, Keys.USERLM_FILE) }

    val model = remember {
        UserLangModel(userlmFile, ImmediateMainThread(), directExecutor)
    }

    var rev by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var newWordDraft by remember { mutableStateOf("") }
    var showConfirmDeleteAll by remember { mutableStateOf(false) }

    val entries = remember(rev, searchQuery) {
        model.entries(searchQuery)
    }
    val totalCount = remember(rev) { model.entries().size }

    fun notifyChanged() {
        model.save()
        prefs.edit().putLong(Keys.USERLM_RESET_AT, System.currentTimeMillis()).apply()
        rev++
    }

    CustomizeScaffold(title = "Từ điển cá nhân", onBack = onBack, preview = false) {
        // Card giới thiệu + thống kê
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bộ nhớ học máy", style = MaterialTheme.typography.titleMedium, color = Tx.Text)
                    Text(
                        "Đã ghi nhận $totalCount từ theo thói quen gõ của bạn. Toàn bộ dữ liệu được mã hoá cục bộ trên thiết bị.",
                        style = MaterialTheme.typography.bodySmall, color = Tx.TextDim,
                    )
                }
                Box(
                    Modifier
                        .size(46.dp)
                        .background(Tx.accentSoft, RoundedCornerShape(14.dp))
                        .border(1.dp, Tx.Stroke, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("📖", fontSize = 22.sp)
                }
            }
        }

        // Card thêm từ mới
        GlassCard {
            Text("Thêm từ hoặc tên riêng", style = MaterialTheme.typography.titleSmall, color = Tx.Text)
            Text(
                "Tên người, địa danh, thuật ngữ kỹ thuật (vd: Kubernetes, Antigravity, GenAI…). Từ thêm tay sẽ được ưu tiên gợi ý ngay khi bạn gõ các chữ đầu.",
                style = MaterialTheme.typography.bodySmall, color = Tx.TextDim,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TxField(
                    value = newWordDraft,
                    onChange = { newWordDraft = it },
                    label = "VD: Antigravity",
                    modifier = Modifier.weight(1f),
                )
                GradientButton("Thêm", enabled = newWordDraft.isNotBlank()) {
                    val w = newWordDraft.trim()
                    if (model.addWord(w)) {
                        notifyChanged()
                        Toast.makeText(ctx, "Đã thêm “$w” vào từ điển", Toast.LENGTH_SHORT).show()
                        newWordDraft = ""
                    } else {
                        Toast.makeText(ctx, "Từ không hợp lệ (chỉ gồm chữ cái, tối đa 32 ký tự)", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // Card tìm kiếm và danh sách
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Danh sách từ đã học", style = MaterialTheme.typography.titleSmall, color = Tx.Text, modifier = Modifier.weight(1f))
                if (totalCount > 0) {
                    Text(
                        "Xoá tất cả",
                        Modifier.clickable { showConfirmDeleteAll = true },
                        color = Tx.Red,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            TxField(
                value = searchQuery,
                onChange = { searchQuery = it },
                label = "Tìm kiếm từ (vd: việt, tech…)",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            if (entries.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (searchQuery.isBlank()) "Chưa có từ nào được ghi nhận." else "Không tìm thấy từ “$searchQuery”.",
                        color = Tx.TextDim,
                        fontSize = 14.sp,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    entries.take(100).forEach { item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Tx.Glass)
                                .border(1.dp, Tx.Stroke, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(item.word, color = Tx.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                    if (item.manual) {
                                        Box(
                                            Modifier
                                                .background(Tx.accentSoft, RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        ) {
                                            Text("Thủ công", color = Tx.CyanSoft, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                Text("Tần suất gõ: ${item.count} lần", color = Tx.TextFaint, fontSize = 11.sp)
                            }
                            Text(
                                "✕",
                                Modifier
                                    .clickable {
                                        if (model.removeWord(item.word)) {
                                            notifyChanged()
                                            Toast.makeText(ctx, "Đã xoá “${item.word}”", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .padding(8.dp),
                                color = Tx.TextDim,
                                fontSize = 16.sp,
                            )
                        }
                    }
                    if (entries.size > 100) {
                        Text(
                            "Đang hiện 100 / ${entries.size} kết quả đầu tiên.",
                            color = Tx.TextFaint,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }

    if (showConfirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { showConfirmDeleteAll = false },
            title = { Text("Xoá toàn bộ từ đã học?") },
            text = { Text("Hành động này sẽ thiết lập lại toàn bộ mô hình ngôn ngữ và danh sách từ cá nhân về mặc định ban đầu.") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDeleteAll = false
                    model.eraseAll()
                    notifyChanged()
                    Toast.makeText(ctx, "Đã xoá toàn bộ dữ liệu từ đã học", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Xoá sạch", color = Tx.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDeleteAll = false }) {
                    Text("Huỷ", color = Tx.Text)
                }
            },
        )
    }
}
