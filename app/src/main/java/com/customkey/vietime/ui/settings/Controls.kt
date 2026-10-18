package com.customkey.vietime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.customkey.vietime.customization.Colors
import com.customkey.vietime.ui.theme.Tx

/** Khung màn tuỳ biến: tiêu đề + phần chỉnh cuộn được + bàn phím mẫu ghim ở đáy. */
@Composable
fun CustomizeScaffold(title: String, onBack: () -> Unit, preview: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", Modifier.clickable(onClick = onBack).padding(end = 12.dp), color = Tx.CyanSoft, fontSize = 30.sp)
            Text(title, style = MaterialTheme.typography.titleLarge, color = Tx.Text)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            content()
            Spacer(Modifier.size(12.dp))
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
            colors = SliderDefaults.colors(thumbColor = Tx.Cyan, activeTrackColor = Tx.Cyan, inactiveTrackColor = Tx.Stroke,
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

/** Thẻ lựa chọn một trong nhiều (radio). */
@Composable
fun ChoiceRow(title: String, subtitle: String?, selected: Boolean, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().background(if (selected) Tx.accentSoft else androidx.compose.ui.graphics.Brush.linearGradient(listOf(Tx.Glass, Tx.Glass)), shape)
            .border(1.dp, if (selected) Tx.Cyan else Tx.Stroke, shape).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp).border(2.dp, if (selected) Tx.Cyan else Tx.TextFaint, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).background(Tx.Cyan, CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Tx.Text, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, color = Tx.TextDim, style = MaterialTheme.typography.bodySmall)
        }
        trailing?.invoke()
    }
}

/** Hộp nhập text giao diện Glassmorphism / Cyberpunk chuẩn. */
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
        shape = RoundedCornerShape(14.dp),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Tx.Cyan,
            unfocusedBorderColor = Tx.Stroke,
            focusedLabelColor = Tx.Cyan,
            unfocusedLabelColor = Tx.TextDim,
            cursorColor = Tx.Cyan,
            focusedContainerColor = Tx.Glass,
            unfocusedContainerColor = Tx.Glass,
        ),
    )
}

