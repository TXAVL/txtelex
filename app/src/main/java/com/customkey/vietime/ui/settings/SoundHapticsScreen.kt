package com.customkey.vietime.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.customkey.vietime.customization.HapticConfig
import com.customkey.vietime.customization.HapticController
import com.customkey.vietime.customization.HapticKind
import com.customkey.vietime.customization.SoundEffectPool
import com.customkey.vietime.customization.SoundKind
import com.customkey.vietime.customization.SoundProfile
import com.customkey.vietime.ime.ImePrefs
import com.customkey.vietime.ui.theme.GhostButton
import com.customkey.vietime.ui.theme.GlassCard
import com.customkey.vietime.ui.theme.SectionLabel
import com.customkey.vietime.ui.theme.Tx
import com.customkey.vietime.ui.theme.TxSwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

import com.customkey.vietime.engine.Keys

/**
 * Âm thanh & Rung: kiểu âm phím (phím cơ Blue/Brown/Red, máy đánh chữ, giọt nước, âm riêng),
 * âm lượng; lực rung riêng cho phím thường / phím xoá / trượt con trỏ. Chạm một kiểu = nghe thử.
 */
@Composable
fun SoundHapticsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ImePrefs.of(ctx) }
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf(SoundProfile.of(prefs.getString(SoundEffectPool.KEY_PROFILE, null))) }
    var soundEnabled by remember { mutableStateOf(prefs.getBoolean(Keys.KEY_SOUND, profile != SoundProfile.OFF)) }
    var volume by remember { mutableIntStateOf(prefs.getInt(SoundEffectPool.KEY_VOLUME, 60)) }
    var haptic by remember { mutableStateOf(HapticConfig.load { prefs.all[it] }) }
    var hapticEnabled by remember { mutableStateOf(prefs.getBoolean(Keys.HAPTIC_FEEDBACK, true)) }
    var hasCustom by remember { mutableStateOf(SoundEffectPool.customFile(ctx).exists()) }

    // Bộ phát nghe thử riêng của màn này.
    val tester = remember { SoundEffectPool(ctx) }
    val vib = remember { HapticController(ctx) }
    DisposableEffect(Unit) { onDispose { tester.release() } }

    fun audition(p: SoundProfile, kind: SoundKind = SoundKind.KEY) {
        if (!soundEnabled && p != SoundProfile.OFF) return
        tester.volume = volume / 100f
        scope.launch {
            withContext(Dispatchers.Default) { tester.setProfile(p, force = p == SoundProfile.CUSTOM) }
            // SoundPool nạp bất đồng bộ — đợi một nhịp rồi phát.
            kotlinx.coroutines.delay(120)
            tester.play(kind)
        }
    }

    fun choose(p: SoundProfile) {
        profile = p
        prefs.edit().putString(SoundEffectPool.KEY_PROFILE, p.id).apply()
        audition(p)
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching false
                    if (bytes.size > SoundEffectPool.MAX_CUSTOM_BYTES) return@runCatching false
                    SoundEffectPool.customFile(ctx).writeBytes(bytes); true
                }.getOrDefault(false)
            }
            if (!ok) { Toast.makeText(ctx, "File phải là âm thanh dưới 2 MB", Toast.LENGTH_SHORT).show(); return@launch }
            hasCustom = true
            prefs.edit().putInt(SoundEffectPool.KEY_CUSTOM_VERSION, prefs.getInt(SoundEffectPool.KEY_CUSTOM_VERSION, 0) + 1).apply()
            choose(SoundProfile.CUSTOM)
        }
    }

    CustomizeScaffold("Âm thanh & Rung", onBack) {
        SectionLabel("Âm bàn phím")
        GlassCard {
            TxSwitchRow("Bật âm bàn phím", "Phát âm thanh phản hồi khi gõ phím", soundEnabled) { on ->
                soundEnabled = on
                prefs.edit().putBoolean(Keys.KEY_SOUND, on).apply()
                if (on) {
                    val p = if (profile == SoundProfile.OFF) SoundProfile.BLUE else profile
                    choose(p)
                }
            }
        }

        if (soundEnabled) {
            GlassCard {
                TxSlider("Âm lượng bàn phím", volume.toFloat(), 0f..100f, { "${it.roundToInt()}%" },
                    onDone = { prefs.edit().putInt(SoundEffectPool.KEY_VOLUME, volume).apply(); audition(profile) }) {
                    volume = it.roundToInt()
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, k) in listOf("Phím" to SoundKind.KEY, "Cách" to SoundKind.SPACE, "Xoá" to SoundKind.DELETE, "Enter" to SoundKind.ENTER))
                        GhostButton(label, Modifier.weight(1f)) { audition(profile, k) }
                }
            }

            SectionLabel("Kiểu âm thanh")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in SoundProfile.entries) {
                    if (p == SoundProfile.OFF) continue
                    if (p == SoundProfile.CUSTOM && !hasCustom) continue
                    ChoiceRow(p.title, p.subtitle, selected = p == profile,
                        trailing = if (p.synthesized || p == SoundProfile.CUSTOM) ({
                            Text("▶", Modifier.padding(start = 8.dp), color = Tx.Cyan, fontSize = 16.sp)
                        }) else null) { choose(p) }
                }
                GhostButton(if (hasCustom) "Đổi file âm thanh của bạn…" else "+ Nạp âm thanh riêng (.wav / .mp3)", Modifier.fillMaxWidth()) {
                    importer.launch(arrayOf("audio/*"))
                }
            }
        }

        SectionLabel("Rung phản hồi")
        GlassCard {
            TxSwitchRow("Bật rung khi chạm phím", "Phản hồi xúc giác khi gõ hoặc chạm phím", hapticEnabled) { on ->
                hapticEnabled = on
                prefs.edit().putBoolean(Keys.HAPTIC_FEEDBACK, on).apply()
                if (on) {
                    vib.config = haptic
                    vib.perform(HapticKind.KEY)
                }
            }
        }

        if (hapticEnabled) {
            GlassCard {
                fun save(h: HapticConfig) {
                    haptic = h
                    prefs.edit().putInt(HapticConfig.KEY_KEY, h.key).putInt(HapticConfig.KEY_DELETE, h.delete)
                        .putInt(HapticConfig.KEY_CURSOR, h.cursor).apply()
                }
                fun pct(v: Float) = if (v.roundToInt() == 0) "Tắt" else "${v.roundToInt()}%"
                TxSlider("Phím thường", haptic.key.toFloat(), 0f..100f, ::pct,
                    onDone = { save(haptic); vib.config = haptic; vib.perform(HapticKind.KEY) }) { haptic = haptic.copy(key = it.roundToInt()) }
                TxSlider("Phím xoá", haptic.delete.toFloat(), 0f..100f, ::pct,
                    onDone = { save(haptic); vib.config = haptic; vib.perform(HapticKind.DELETE) }) { haptic = haptic.copy(delete = it.roundToInt()) }
                TxSlider("Trượt con trỏ", haptic.cursor.toFloat(), 0f..100f, ::pct,
                    onDone = { save(haptic); vib.config = haptic; vib.perform(HapticKind.CURSOR) }) { haptic = haptic.copy(cursor = it.roundToInt()) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    GhostButton("Thử rung phím", Modifier.weight(1f)) {
                        vib.config = haptic
                        vib.perform(HapticKind.KEY)
                    }
                    GhostButton("Thử rung xoá", Modifier.weight(1f)) {
                        vib.config = haptic
                        vib.perform(HapticKind.DELETE)
                    }
                }
                Text("Giữ phím Cách rồi kéo trên bàn phím mẫu để thử rung con trỏ.", color = Tx.TextDim, fontSize = 12.sp)
            }
        }
    }
}
