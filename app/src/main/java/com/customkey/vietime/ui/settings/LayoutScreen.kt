package com.customkey.vietime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.customkey.vietime.customization.LayoutConfig
import com.customkey.vietime.customization.SplitMode
import com.customkey.vietime.engine.Keys
import com.customkey.vietime.ime.ImePrefs
import com.customkey.vietime.ui.theme.GlassCard
import com.customkey.vietime.ui.theme.SectionLabel
import com.customkey.vietime.ui.theme.Tx
import com.customkey.vietime.ui.theme.TxSwitchRow
import kotlin.math.roundToInt

/** Bố cục: hàng số, chiều cao %, độ rộng %, tách đôi, thời gian giữ phím. */
@Composable
fun LayoutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ImePrefs.of(ctx) }
    var cfg by remember { mutableStateOf(LayoutConfig.load { prefs.all[it] }) }
    fun save(c: LayoutConfig) {
        cfg = c
        prefs.edit().putBoolean(Keys.NUMBER_ROW, c.numberRow).putInt(LayoutConfig.KEY_HEIGHT, c.heightPercent)
            .putInt(LayoutConfig.KEY_WIDTH, c.widthPercent).putString(LayoutConfig.KEY_SPLIT, c.split.id)
            .putInt(LayoutConfig.KEY_LONG_PRESS, c.longPressMs).apply()
    }

    CustomizeScaffold("Bố cục bàn phím", onBack) {
        SectionLabel("Kích thước")
        GlassCard {
            TxSwitchRow("Hàng phím số riêng", "Thêm hàng 1–0 phía trên", cfg.numberRow) { save(cfg.copy(numberRow = it)) }
            TxSlider("Chiều cao", cfg.heightPercent.toFloat(), LayoutConfig.MIN_HEIGHT.toFloat()..LayoutConfig.MAX_HEIGHT.toFloat(),
                { "${it.roundToInt()}%" }, onDone = { save(cfg) }) { cfg = cfg.copy(heightPercent = (it / 5).roundToInt() * 5) }
            TxSlider("Độ rộng", cfg.widthPercent.toFloat(), LayoutConfig.MIN_WIDTH.toFloat()..100f,
                { "${it.roundToInt()}%" }, onDone = { save(cfg) }) { cfg = cfg.copy(widthPercent = it.roundToInt()) }
        }

        SectionLabel("Tách đôi bàn phím")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in SplitMode.entries) ChoiceRow(m.title, null, cfg.split == m) { save(cfg.copy(split = m)) }
            Text("Tách đôi giúp gõ bằng hai ngón cái trên màn hình ngang và máy tính bảng.", color = Tx.TextDim, fontSize = 12.sp)
        }

        SectionLabel("Giữ phím")
        GlassCard {
            TxSlider("Thời gian giữ phím", cfg.longPressMs.toFloat(), LayoutConfig.MIN_LONG.toFloat()..LayoutConfig.MAX_LONG.toFloat(),
                { "${it.roundToInt()} ms" }, onDone = { save(cfg) }) { cfg = cfg.copy(longPressMs = (it / 10).roundToInt() * 10) }
            Text("Giữ phím chữ để ra số/ký hiệu phụ, giữ phím Cách để vào trackpad.", color = Tx.TextDim, fontSize = 12.sp)
        }
    }
}
