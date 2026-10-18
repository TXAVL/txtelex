package com.customkey.vietime.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Bảng màu thương hiệu TxTelex — lấy từ logo: navy sâu, cyan neon, tím điện. */
object Tx {
    val Night = Color(0xFF070D1E)
    val Deep = Color(0xFF0D1530)
    val Navy = Color(0xFF1A2456)
    val Glass = Color(0x1AFFFFFF)
    val GlassStrong = Color(0x26FFFFFF)
    val Stroke = Color(0x2E7FE9FF)
    val Cyan = Color(0xFF00E5FF)
    val CyanSoft = Color(0xFF76EEFF)
    val Blue = Color(0xFF2F6BFF)
    val Violet = Color(0xFF7C4DFF)
    val Green = Color(0xFF2EE59D)
    val Red = Color(0xFFFF5470)
    val Text = Color(0xFFEFF4FF)
    val TextDim = Color(0xFF9AA8C7)
    val TextFaint = Color(0xFF66739A)

    val accent = Brush.linearGradient(listOf(Cyan, Blue, Violet))
    val accentSoft = Brush.linearGradient(listOf(Color(0x3300E5FF), Color(0x337C4DFF)))
    val background = Brush.verticalGradient(listOf(Navy, Night, Night))
    val border = Brush.linearGradient(listOf(Color(0x8000E5FF), Color(0x1AFFFFFF), Color(0x807C4DFF)))
}

private val TxColors = darkColorScheme(
    primary = Tx.Cyan, onPrimary = Tx.Night,
    secondary = Tx.Violet, onSecondary = Color.White,
    tertiary = Tx.Blue,
    background = Tx.Night, onBackground = Tx.Text,
    surface = Tx.Deep, onSurface = Tx.Text,
    surfaceVariant = Color(0xFF16204A), onSurfaceVariant = Tx.TextDim,
    outline = Tx.Stroke, error = Tx.Red,
)

private val TxType = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
)

/** Theme app — luôn tối theo nhận diện thương hiệu. */
@Composable
fun TxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TxColors, typography = TxType, content = content)
}

/** Nền gradient toàn màn. */
@Composable
fun TxBackground(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.background(Tx.Night).background(Tx.background), content = content)
}

/** Thẻ kính mờ viền gradient neon. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    glow: Boolean = false,
    padding: PaddingValues = PaddingValues(18.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(if (glow) Tx.accentSoft else Brush.linearGradient(listOf(Tx.GlassStrong, Tx.Glass)))
            .border(BorderStroke(1.dp, Tx.border), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Nút chính: nền gradient cyan → tím. */
@Composable
fun GradientButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.heightIn(min = 46.dp).clip(shape)
            .background(if (enabled) Tx.accent else Brush.linearGradient(listOf(Tx.GlassStrong, Tx.Glass)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Tx.Night else Tx.TextFaint, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

/** Nút phụ: viền neon, nền trong. */
@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.heightIn(min = 42.dp).clip(shape).border(1.dp, Tx.border, shape)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Tx.CyanSoft, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
}

/** Nhãn mục nhỏ in hoa. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier.padding(start = 6.dp, top = 6.dp), color = Tx.TextFaint,
        fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
}

/** Một dòng công tắc trong thẻ cài đặt. */
@Composable
fun TxSwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Tx.Text, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, color = Tx.TextDim, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Tx.Night, checkedTrackColor = Tx.Cyan, checkedBorderColor = Tx.Cyan,
                uncheckedThumbColor = Tx.TextDim, uncheckedTrackColor = Tx.Deep, uncheckedBorderColor = Tx.Stroke,
            ),
        )
    }
}
