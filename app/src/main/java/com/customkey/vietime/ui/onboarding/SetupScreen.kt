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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.customkey.vietime.ui.theme.GradientButton
import com.customkey.vietime.ui.theme.Glyph
import com.customkey.vietime.ui.theme.GlyphIcon
import com.customkey.vietime.ui.theme.LocalTx
import com.customkey.vietime.ui.theme.TxDivider
import com.customkey.vietime.ui.theme.TxRow
import com.customkey.vietime.ui.theme.TxSection

/**
 * Thẻ trạng thái bàn phím. Chưa sẵn sàng ⇒ 2 bước: bật TxTelex trong Cài đặt hệ thống, rồi chọn làm bàn phím
 * đang dùng. Dấu tích cập nhật ngay nhờ [rememberImeStatus].
 */
@Composable
fun SetupCard(status: ImeStatus, onOpenImeSettings: () -> Unit, onPickIme: () -> Unit) {
    val p = LocalTx.current
    if (status.selected) {
        TxSection {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(p.green, CircleShape), contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.Check, Color.White, 24.dp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Bàn phím đã bật", color = p.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("Thử gõ ngay bên dưới.", color = p.secondary, fontSize = 14.sp)
                }
                Text("Đổi", Modifier.padding(start = 8.dp), color = p.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    } else {
        TxSection(
            header = "Kích hoạt bàn phím",
            footer = "Hai bước, chưa tới 10 giây. Xong là gõ được tiếng Việt ở mọi ứng dụng.",
        ) {
            StepRow(1, "Bật TxTelex", "Trong danh sách bàn phím của hệ thống", status.enabled, "Mở cài đặt", true, onOpenImeSettings)
            TxDivider()
            StepRow(2, "Chọn TxTelex", "Làm bàn phím đang dùng", status.selected, "Chọn", status.enabled, onPickIme)
        }
    }
}

@Composable
private fun StepRow(n: Int, title: String, sub: String, done: Boolean, action: String, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalTx.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp).background(if (done) p.green else if (enabled) p.accent.copy(alpha = 0.14f) else p.fill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) GlyphIcon(Glyph.Check, Color.White, 18.dp)
            else Text("$n", color = if (enabled) p.accent else p.tertiary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = if (enabled || done) p.label else p.tertiary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = p.secondary, fontSize = 12.5.sp)
        }
        if (!done) GradientButton(action, enabled = enabled, onClick = onClick, modifier = Modifier.padding(0.dp))
    }
}

/** Khu "Thử gõ": ô thường, ô URL (như thanh địa chỉ), ô số, ô mật khẩu (bàn phím phải KHÔNG bỏ dấu). */
@Composable
fun TypingSandbox() {
    val p = LocalTx.current
    TxSection(
        header = "Thử gõ",
        footer = "Gõ yeeu txa → yêu txa. Ô mật khẩu cố ý không bỏ dấu.",
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            var normal by rememberSaveable { mutableStateOf("") }
            Field(normal, { normal = it }, "Ô thường", "yeeu txa, ddaauf tieen…", minLines = 2,
                options = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            var url by rememberSaveable { mutableStateOf("") }
            Field(url, { url = it }, "Ô URL (như thanh địa chỉ)", "github.com hoặc tìm kiếm tiếng Việt",
                options = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                var num by rememberSaveable { mutableStateOf("") }
                Field(num, { num = it }, "Ô số", "123", Modifier.weight(1f), options = KeyboardOptions(keyboardType = KeyboardType.Number))
                var pw by rememberSaveable { mutableStateOf("") }
                Field(pw, { pw = it }, "Mật khẩu", "không bỏ dấu", Modifier.weight(1f), password = true,
                    options = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
        }
        Text(
            "yeeu txa  →  yêu txa", Modifier.padding(start = 16.dp, bottom = 14.dp),
            color = p.accent, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
        )
    }
}

@Composable
private fun Field(
    value: String, onChange: (String) -> Unit, label: String, placeholder: String,
    modifier: Modifier = Modifier.fillMaxWidth(), minLines: Int = 1, password: Boolean = false,
    options: KeyboardOptions,
) {
    val p = LocalTx.current
    OutlinedTextField(
        value, onChange, modifier.fillMaxWidth(), singleLine = minLines == 1, minLines = minLines,
        label = { Text(label) }, placeholder = { Text(placeholder, color = p.tertiary) },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = options, shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = p.accent, unfocusedBorderColor = p.separator,
            focusedLabelColor = p.accent, unfocusedLabelColor = p.secondary, cursorColor = p.accent,
            focusedContainerColor = p.bg, unfocusedContainerColor = p.bg,
        ),
    )
}

/** Mẹo cử chỉ — hiện trong tab Kiểu gõ. */
@Composable
fun GestureTips() {
    TxSection(header = "Cử chỉ") {
        val tips = listOf(
            Triple(Glyph.Layout, "Giữ phím Cách rồi kéo", "Di chuyển con trỏ như trackpad"),
            Triple(Glyph.Swipe, "Vuốt qua các chữ", "Gõ nguyên từ không cần nhấc tay"),
            Triple(Glyph.Keyboard, "Vuốt nhanh phím Cách", "Đổi Tiếng Việt ↔ English"),
            Triple(Glyph.Back, "Vuốt trái phím xoá", "Xoá cả từ, có Hoàn tác"),
        )
        tips.forEachIndexed { i, (g, t, s) ->
            if (i > 0) TxDivider(62.dp)
            TxRow(t, s, glyph = g, tint = LocalTx.current.accent)
        }
    }
}
