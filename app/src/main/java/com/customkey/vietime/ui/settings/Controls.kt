package com.customkey.vietime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.customkey.vietime.customization.Colors
import com.customkey.vietime.ui.theme.Glyph
import com.customkey.vietime.ui.theme.GlyphIcon
import com.customkey.vietime.ui.theme.LocalTx
import com.customkey.vietime.ui.theme.Tx

/** Khung màn tuỳ biến: nút quay lại + tiêu đề lớn, phần chỉnh cuộn được, bàn phím mẫu ghim ở đáy. */
@Composable
fun CustomizeScaffold(title: String, onBack: () -> Unit, preview: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalTx.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.clickable(remember { MutableInteractionSource() }, null, onClick = onBack).padding(start = 12.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(Glyph.Back, p.accent, 22.dp)
            Spacer(Modifier.width(2.dp))
            Text("Quay lại", color = p.accent, fontSize = 16.sp)
        }
        Text(title, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = p.label, style = MaterialTheme.typography.headlineMedium)
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
            Spacer(Modifier.size(16.dp))
        }
        if (preview) KeyboardPreview()
    }
}

/** Thanh trượt có nhãn + giá trị. [onDone] gọi khi nhả tay (việc nặng như xử lý ảnh). */
@Composable
fun TxSlider(
    title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: (Float) -> String,
    steps: Int = 0, onDone: (() -> Unit)? = null, onChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), color = Tx.Text, style = MaterialTheme.typography.bodyLarge)
            Text(label(value), color = Tx.CyanSoft, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps,
            onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Tx.Cyan, inactiveTrackColor = Tx.Glass,
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
        )
    }
}

/** Hàng ô màu chọn nhanh. */
@Composable
fun ColorRow(title: String, selected: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), color = Tx.Text, style = MaterialTheme.typography.bodyLarge)
            Box(Modifier.size(20.dp).background(Color(selected), CircleShape).border(1.dp, Tx.Stroke, CircleShape))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Colors.palette) { c ->
                val on = c == selected
                Box(
                    Modifier.size(34.dp).background(Color(c), CircleShape)
                        .border(if (on) 2.5.dp else 1.dp, if (on) Tx.Cyan else Tx.Stroke, CircleShape)
                        .clickable { onPick(c) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) Text("✓", color = if (Colors.luminance(c) > 0.55f) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Dòng lựa chọn một trong nhiều: nền thẻ, dấu tích ở bên phải khi được chọn. */
@Composable
fun ChoiceRow(title: String, subtitle: String?, selected: Boolean, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val p = LocalTx.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().background(if (selected) p.accent.copy(alpha = 0.12f) else p.card, shape)
            .border(1.5.dp, if (selected) p.accent else Color.Transparent, shape).clip(shape).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.label, fontSize = 16.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            if (subtitle != null) Text(subtitle, color = p.secondary, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
        trailing?.invoke()
        if (selected) { Spacer(Modifier.width(8.dp)); GlyphIcon(Glyph.Check, p.accent, 20.dp) }
    }
}

/** Ô nhập chữ nền thẻ. */
@Composable
fun TxField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        label = { Text(label) },
        shape = RoundedCornerShape(12.dp),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Tx.Cyan,
            unfocusedBorderColor = Color.Transparent,
            focusedLabelColor = Tx.Cyan,
            unfocusedLabelColor = Tx.TextDim,
            cursorColor = Tx.Cyan,
            focusedContainerColor = LocalTx.current.card,
            unfocusedContainerColor = LocalTx.current.card,
        ),
    )
}

