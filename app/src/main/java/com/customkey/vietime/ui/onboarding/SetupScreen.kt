package com.customkey.vietime.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.customkey.vietime.ui.theme.GhostButton
import com.customkey.vietime.ui.theme.GlassCard
import com.customkey.vietime.ui.theme.GradientButton
import com.customkey.vietime.ui.theme.Tx

/**
 * Trình Setup 2 bước: Bước 1 mở Cài đặt hệ thống để BẬT bàn phím, Bước 2 mở hộp thoại CHỌN
 * bàn phím. Dấu tích cập nhật ngay nhờ [rememberImeStatus].
 */
@Composable
fun SetupCard(status: ImeStatus, onOpenImeSettings: () -> Unit, onPickIme: () -> Unit) {
    GlassCard(glow = !status.selected) {
        if (status.selected) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepDot(done = true, label = "✓")
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("TxTelex đã sẵn sàng", style = MaterialTheme.typography.titleMedium, color = Tx.Text)
                    Text("Gõ thử ngay bên dưới", style = MaterialTheme.typography.bodySmall, color = Tx.TextDim)
                }
                GhostButton("Đổi", onClick = onPickIme)
            }
        } else {
            Text("Kích hoạt bàn phím", style = MaterialTheme.typography.titleLarge, color = Tx.Text)
            Text("Hai bước, chưa tới 10 giây.", style = MaterialTheme.typography.bodyMedium, color = Tx.TextDim)
            Spacer(Modifier.height(16.dp))
            Step(1, "Bật TxTelex", "Trong danh sách bàn phím của hệ thống", status.enabled,
                "Mở cài đặt", enabled = true, onClick = onOpenImeSettings)
            Box(Modifier.padding(start = 17.dp).width(2.dp).height(18.dp).background(if (status.enabled) Tx.Cyan else Tx.Stroke))
            Step(2, "Chọn TxTelex", "Làm bàn phím đang dùng", status.selected,
                "Chọn bàn phím", enabled = status.enabled, onClick = onPickIme)
        }
    }
}

@Composable
private fun Step(n: Int, title: String, sub: String, done: Boolean, action: String, enabled: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepDot(done, if (done) "✓" else n.toString(), active = enabled)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled || done) Tx.Text else Tx.TextFaint)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Tx.TextDim)
        }
        if (!done) GradientButton(action, enabled = enabled, onClick = onClick)
    }
}

@Composable
private fun StepDot(done: Boolean, label: String, active: Boolean = true) {
    val m = Modifier.size(36.dp)
    Box(
        if (done) m.background(Tx.accent, CircleShape)
        else m.border(1.5.dp, if (active) Tx.Cyan else Tx.Stroke, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (done) Tx.Night else if (active) Tx.Cyan else Tx.TextFaint,
            fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
    }
}

/**
 * Khu "Gõ thử tại đây": ô thường, ô URL (như thanh địa chỉ), ô số, ô mật khẩu (bàn phím phải
 * KHÔNG bỏ dấu). Mẹo cử chỉ ngay dưới.
 */
@Composable
fun TypingSandbox() {
    GlassCard {
        Text("Gõ thử tại đây", style = MaterialTheme.typography.titleMedium, color = Tx.Text)
        Row(Modifier.padding(top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Thử gõ ", style = MaterialTheme.typography.bodyMedium, color = Tx.TextDim)
            Chip("yeeu txa")
            Text("  →  ", color = Tx.Cyan, fontWeight = FontWeight.Bold)
            Chip("yêu txa", highlight = true)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            var normal by rememberSaveable { mutableStateOf("") }
            Field(normal, { normal = it }, "Ô thường", "yeeu txa, ddaauf tieen…", minLines = 2,
                options = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            var url by rememberSaveable { mutableStateOf("") }
            Field(url, { url = it }, "Ô URL — như thanh địa chỉ", "github.com hoặc tìm kiếm tiếng Việt",
                options = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                var num by rememberSaveable { mutableStateOf("") }
                Field(num, { num = it }, "Ô số", "123", Modifier.weight(1f),
                    options = KeyboardOptions(keyboardType = KeyboardType.Number))
                var pw by rememberSaveable { mutableStateOf("") }
                Field(pw, { pw = it }, "Mật khẩu", "không bỏ dấu", Modifier.weight(1f), password = true,
                    options = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
        }
        Spacer(Modifier.height(14.dp))
        Tips()
    }
}

@Composable
private fun Chip(text: String, highlight: Boolean = false) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text,
        Modifier.background(if (highlight) Tx.accentSoft else Brush.linearGradient(listOf(Tx.Glass, Tx.Glass)), shape)
            .border(1.dp, if (highlight) Tx.Cyan else Tx.Stroke, shape).padding(horizontal = 8.dp, vertical = 3.dp),
        color = if (highlight) Tx.CyanSoft else Tx.Text, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
    )
}

@Composable
private fun Field(
    value: String, onChange: (String) -> Unit, label: String, placeholder: String,
    modifier: Modifier = Modifier.fillMaxWidth(), minLines: Int = 1, password: Boolean = false,
    options: KeyboardOptions,
) {
    OutlinedTextField(
        value, onChange, modifier, singleLine = minLines == 1, minLines = minLines,
        label = { Text(label) }, placeholder = { Text(placeholder, color = Tx.TextFaint) },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = options, shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Tx.Cyan, unfocusedBorderColor = Tx.Stroke,
            focusedLabelColor = Tx.Cyan, unfocusedLabelColor = Tx.TextDim, cursorColor = Tx.Cyan,
            focusedContainerColor = Tx.Glass, unfocusedContainerColor = Tx.Glass,
        ),
    )
}

@Composable
private fun Tips() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((icon, t) in listOf(
            "⇆" to "Giữ phím Cách rồi kéo để di chuyển con trỏ",
            "🌐" to "Vuốt nhanh phím Cách để đổi Tiếng Việt ↔ English",
            "⌫" to "Vuốt trái phím xoá để xoá cả từ, có Hoàn tác",
            "✎" to "Gõ xong vẫn sửa dấu: thay ␣ ⌫ s → tháy",
            "〰" to "Vuốt qua các chữ để gõ nguyên từ",
        )) Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).background(Tx.Glass, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Text(icon, color = Tx.Cyan, fontSize = 13.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text(t, style = MaterialTheme.typography.bodySmall, color = Tx.TextDim)
        }
    }
}
