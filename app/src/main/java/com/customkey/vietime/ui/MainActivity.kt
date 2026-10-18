package com.customkey.vietime.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.customkey.vietime.engine.Keys
import com.customkey.vietime.ime.AssetBlobs
import com.customkey.vietime.ime.ImePrefs
import com.customkey.vietime.ui.onboarding.SetupCard
import com.customkey.vietime.ui.onboarding.SwipePracticeScreen
import com.customkey.vietime.ui.onboarding.TypingSandbox
import com.customkey.vietime.ui.onboarding.rememberImeStatus

class MainActivity : ComponentActivity() {
    /** Tăng mỗi lần cửa sổ lấy lại focus (sau hộp thoại chọn bàn phím). */
    private val focusTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AssetBlobs.ensureInstalled(this)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    AppRoot(
                        focusTick = focusTick,
                        onOpenImeSettings = { runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) } },
                        onPickIme = { getSystemService(InputMethodManager::class.java).showInputMethodPicker() },
                    )
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) focusTick.intValue++
    }
}

private enum class Screen { HOME, PRACTICE, LICENSES }

@Composable
private fun AppRoot(focusTick: androidx.compose.runtime.State<Int>, onOpenImeSettings: () -> Unit, onPickIme: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
    val status by rememberImeStatus(focusTick)
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        when (screen) {
            Screen.HOME -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("TxTelex", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                SetupCard(status, onOpenImeSettings, onPickIme)
                if (status.selected) TypingSandbox()
                TypingModeCard()
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Luyện gõ vuốt", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Vuốt qua các chữ để gõ cả từ — luyện trên bàn phím mẫu, chấm điểm từng từ.",
                            style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { screen = Screen.PRACTICE }) { Text("Bắt đầu luyện") }
                    }
                }
                TextButton(onClick = { screen = Screen.LICENSES }) { Text("Giấy phép mã nguồn mở") }
            }
            Screen.PRACTICE -> SwipePracticeScreen(onBack = { screen = Screen.HOME })
            // Luyện vuốt KHÔNG nằm trong verticalScroll: nét vuốt dọc sẽ bị cuộn trang nuốt mất.
            Screen.LICENSES -> Column(Modifier.verticalScroll(rememberScrollState())) { LicensesScreen(onBack = { screen = Screen.HOME }) }
        }
    }
}

/** Kiểu gõ & phản hồi cơ bản (GĐ3 mở rộng thành màn Cài đặt đầy đủ). */
@Composable
private fun TypingModeCard() {
    val ctx = LocalContext.current
    val prefs = remember { ImePrefs.of(ctx) }
    @Composable
    fun Toggle(title: String, key: String, def: Boolean) {
        var v by remember { mutableStateOf(prefs.getBoolean(key, def)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(v, { v = it; prefs.edit().putBoolean(key, it).apply() })
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Kiểu gõ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Toggle("Gõ VNI (tắt = Telex)", Keys.VNI_MODE, false)
            Toggle("Bỏ dấu tự do", Keys.FREE_MARKING, true)
            Toggle("Tự khôi phục từ tiếng Anh", Keys.AUTO_RESTORE, true)
            Toggle("Sửa dấu từ đã gõ", Keys.RE_EDIT_WORDS, true)
            Toggle("Gõ vuốt", ImePrefs.SWIPE_TYPING, true)
            Toggle("Vuốt phím cách đổi VI/EN", ImePrefs.SPACE_FLICK_LANGUAGE, true)
            Toggle("Âm thanh phím", ImePrefs.KEY_SOUND, true)
            Toggle("Rung khi gõ", ImePrefs.HAPTIC, true)
            Toggle("Hàng phím số", Keys.NUMBER_ROW, false)
        }
    }
}

/** Giấy phép: MIT của viettelex (lõi bộ gõ) + FUTO Swipe (mô hình gõ vuốt). */
@Composable
private fun LicensesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val texts = remember {
        listOf("viettelex-LICENSE.txt" to "Lõi bộ gõ — ptrinh/viettelex (MIT)", "futoswipe-LICENSE.md" to "Powered by FUTO Swipe")
            .map { (f, title) -> title to runCatching { ctx.assets.open("licenses/$f").bufferedReader().readText() }.getOrDefault("") }
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Quay lại") }
            Text("Giấy phép mã nguồn mở", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Text("Lõi bộ gõ tiếng Việt (Telex/VNI, kiểm tra chính tả, khôi phục tiếng Anh, gợi ý, gõ vuốt) trích từ " +
            "dự án mã nguồn mở viettelex — Copyright (c) 2026 Phil Trinh, giấy phép MIT. Gõ vuốt: Powered by FUTO Swipe.",
            style = MaterialTheme.typography.bodyMedium)
        for ((title, body) in texts) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(body, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }
}
