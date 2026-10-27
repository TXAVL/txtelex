package com.customkey.vietime.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.customkey.vietime.customization.LayoutConfig
import com.customkey.vietime.customization.ThemeConfig
import com.customkey.vietime.customization.ThemePreset
import com.customkey.vietime.customization.ThemePresets
import com.customkey.vietime.customization.ThemeStore
import com.customkey.vietime.ime.ImePrefs
import com.customkey.vietime.ui.theme.GhostButton
import com.customkey.vietime.ui.theme.GlassCard
import com.customkey.vietime.ui.theme.GradientButton
import com.customkey.vietime.ui.theme.SectionLabel
import com.customkey.vietime.ui.theme.Tx
import com.customkey.vietime.ui.theme.TxSwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Theme Studio: bộ theme dựng sẵn, ảnh nền riêng (làm mờ + phủ tối), độ trong suốt phím/chữ,
 * bo góc, viền, màu. Mỗi thay đổi lưu ngay ⇒ bàn phím mẫu bên dưới VÀ bàn phím thật cập nhật.
 */
@Composable
fun ThemeStudioScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cfg by remember { mutableStateOf(ThemeStore.load(ctx)) }
    var busy by remember { mutableStateOf(false) }
    fun update(c: ThemeConfig) { cfg = c; ThemeStore.save(ctx, c) }

    fun reprocess() {
        ThemeStore.save(ctx, cfg)
        if (!cfg.wallpaper || !ThemeStore.hasSource(ctx)) return
        val c = cfg
        busy = true
        scope.launch { withContext(Dispatchers.Default) { ThemeStore.process(ctx, c) }; busy = false }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val dm = ctx.resources.displayMetrics
        val layout = LayoutConfig.load { ImePrefs.of(ctx).all[it] }
        val rows = if (layout.numberRow) 5 else 4
        val h = ((42 + rows * layout.rowHeightDp() + 4) * dm.density).toInt()
        busy = true
        val next = cfg.copy(wallpaper = true)
        scope.launch {
            val ok = withContext(Dispatchers.Default) { ThemeStore.importWallpaper(ctx, uri, dm.widthPixels, h, next) }
            busy = false
            if (ok) update(next) else Toast.makeText(ctx, "Không đọc được ảnh này", Toast.LENGTH_SHORT).show()
        }
    }

    CustomizeScaffold("Theme Studio", onBack) {
        SectionLabel("Bộ giao diện")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(ThemePresets.all) { p -> PresetCard(p, selected = p.id == cfg.presetId) {
                update(p.config.copy(wallpaper = cfg.wallpaper, blur = cfg.blur, dim = cfg.dim))
            } }
        }

        SectionLabel("Ảnh nền")
        GlassCard {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                GradientButton(if (cfg.wallpaper) "Đổi ảnh" else "Chọn ảnh từ máy", Modifier.weight(1f)) {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                if (cfg.wallpaper) GhostButton("Gỡ") {
                    ThemeStore.removeWallpaper(ctx); update(cfg.copy(wallpaper = false))
                }
            }
            if (busy) Text("Đang xử lý ảnh…", Modifier.padding(top = 8.dp), color = Tx.CyanSoft, fontSize = 13.sp)
            if (cfg.wallpaper) {
                Spacer(Modifier.height(10.dp))
                TxSlider("Làm mờ (Blur)", cfg.blur.toFloat(), 0f..25f, { "${it.roundToInt()}" }, steps = 24, onDone = ::reprocess) {
                    cfg = cfg.copy(blur = it.roundToInt())
                }
                TxSlider("Độ tối (Overlay)", cfg.dim, 0f..0.85f, { "${(it * 100).roundToInt()}%" }, onDone = { ThemeStore.save(ctx, cfg); reprocess() }) {
                    cfg = cfg.copy(dim = it)
                }
            }
        }

        SectionLabel("Phím")
        GlassCard {
            TxSlider("Độ đục nền phím", cfg.keyOpacity, 0f..1f, { "${(it * 100).roundToInt()}%" }, onDone = { ThemeStore.save(ctx, cfg) }) {
                cfg = cfg.copy(keyOpacity = it)
            }
            TxSlider("Độ đục chữ", cfg.labelOpacity, 0.2f..1f, { "${(it * 100).roundToInt()}%" }, onDone = { ThemeStore.save(ctx, cfg) }) {
                cfg = cfg.copy(labelOpacity = it)
            }
            TxSlider("Bo góc phím", cfg.cornerRadiusDp, 0f..24f, { "${it.roundToInt()} dp" }, onDone = { ThemeStore.save(ctx, cfg) }) {
                cfg = cfg.copy(cornerRadiusDp = it.roundToInt().toFloat())
            }
            TxSwitchRow("Viền phím", null, cfg.borderWidthDp > 0f) { on -> update(cfg.copy(borderWidthDp = if (on) 1f else 0f)) }
            if (cfg.borderWidthDp > 0f) TxSlider("Độ dày viền", cfg.borderWidthDp, 0.5f..3f, { "%.1f dp".format(it) }, onDone = { ThemeStore.save(ctx, cfg) }) {
                cfg = cfg.copy(borderWidthDp = (it * 2).roundToInt() / 2f)
            }
        }

        SectionLabel("Màu sắc")
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ColorRow("Màu nhấn", cfg.accentColor) { update(cfg.copy(presetId = "custom", accentColor = it, borderColor = (it and 0x00FFFFFF) or 0x44000000)) }
                ColorRow("Chữ trên phím", cfg.labelColor) { update(cfg.copy(presetId = "custom", labelColor = it)) }
                ColorRow("Phím chữ", cfg.keyColor) { update(cfg.copy(presetId = "custom", keyColor = it)) }
                ColorRow("Phím chức năng", cfg.modifierColor) { update(cfg.copy(presetId = "custom", modifierColor = it)) }
                ColorRow("Nền bàn phím", cfg.background) { update(cfg.copy(presetId = "custom", background = it)) }
            }
        }
    }
}

@Composable
private fun PresetCard(p: ThemePreset, selected: Boolean, onClick: () -> Unit) {
    val c = p.config
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.width(96.dp).border(if (selected) 2.dp else 1.dp, if (selected) Tx.Cyan else Tx.Stroke, shape)
            .background(Color(c.background), shape).clickable(onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // mini bàn phím 3 hàng
        for (r in 0 until 3) Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(vertical = 1.5.dp)) {
            repeat(if (r == 2) 3 else 5) { i ->
                val fill = if (r == 2 && i != 1) c.modifierColor else c.keyColor
                Box(Modifier.size(width = if (r == 2 && i == 1) 26.dp else 11.dp, height = 11.dp)
                    .background(Color(fill), RoundedCornerShape((c.cornerRadiusDp / 3).dp)))
            }
        }
        Box(Modifier.padding(top = 6.dp).size(width = 24.dp, height = 3.dp).background(Color(c.accentColor), RoundedCornerShape(2.dp)))
        Text(p.name, Modifier.padding(top = 6.dp), color = Color(c.labelColor), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
