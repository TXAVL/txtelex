package com.customkey.vietime.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private val Green = Color(0xFF2EB85C)

/**
 * Trình Setup 2 bước (giữ luồng của bản gốc): Bước 1 mở Cài đặt hệ thống để BẬT bàn phím,
 * Bước 2 mở hộp thoại CHỌN bàn phím. Dấu tích xanh cập nhật ngay nhờ [rememberImeStatus].
 */
@Composable
fun SetupCard(status: ImeStatus, onOpenImeSettings: () -> Unit, onPickIme: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (status.selected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CheckDot(true, "✓")
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Bàn phím đã sẵn sàng", style = MaterialTheme.typography.titleMedium)
                        Text("Gõ thử ngay bên dưới: vieejt → việt", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedButton(onClick = onPickIme) { Text("Đổi bàn phím…") }
            } else {
                Text("Cài đặt bàn phím", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Step(1, "Bật “TxTelex” trong Cài đặt", done = status.enabled,
                    action = "Mở Cài đặt", enabled = true, onClick = onOpenImeSettings)
                Step(2, "Chọn “TxTelex” làm bàn phím đang dùng", done = status.selected,
                    action = "Chọn bàn phím", enabled = status.enabled, onClick = onPickIme)
            }
        }
    }
}

@Composable
private fun Step(n: Int, title: String, done: Boolean, action: String, enabled: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CheckDot(done, if (done) "✓" else n.toString())
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!done) Button(onClick = onClick, enabled = enabled, modifier = Modifier.padding(top = 6.dp)) { Text(action) }
        }
    }
}

@Composable
private fun CheckDot(done: Boolean, label: String) {
    Box(
        Modifier.size(30.dp).background(if (done) Green else MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (done) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold) }
}

/**
 * Khu "Gõ thử tại đây": ô thường (gợi ý vieejt → việt), ô URL (thanh địa chỉ), ô số, ô mật khẩu
 * (bàn phím phải KHÔNG bỏ dấu). Mẹo cử chỉ ngay dưới.
 */
@Composable
fun TypingSandbox() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Gõ thử tại đây", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            var normal by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(normal, { normal = it }, Modifier.fillMaxWidth(), minLines = 2,
                label = { Text("Ô thường") }, placeholder = { Text("Thử: vieejt → việt, ddaauf → đầu") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            var url by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Ô URL (như thanh địa chỉ)") }, placeholder = { Text("github.com hoặc tìm kiếm tiếng Việt") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go))
            var num by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(num, { num = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Ô số") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            var pw by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Ô mật khẩu (không bỏ dấu)") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Tips()
        }
    }
}

@Composable
private fun Tips() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (t in listOf(
            "• Giữ phím Cách rồi kéo: di chuyển con trỏ (4 chiều).",
            "• Vuốt nhanh ngang phím Cách: đổi Tiếng Việt ↔ English.",
            "• Vuốt trái phím ⌫: xoá theo từ, có nút “Hoàn tác”.",
            "• Gõ xong từ vẫn sửa dấu được: “thay” + cách + ⌫ + s → “tháy”.",
            "• Vuốt qua các chữ để gõ cả từ (gõ vuốt).",
        )) Text(t, style = MaterialTheme.typography.bodySmall)
    }
}
