package com.customkey.vietime.ui

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.customkey.vietime.BuildConfig
import com.customkey.vietime.R
import com.customkey.vietime.diag.TxaLogCenter
import com.customkey.vietime.diag.showPendingCrash
import com.customkey.vietime.engine.Keys
import com.customkey.vietime.ime.AssetBlobs
import com.customkey.vietime.ime.ImePrefs
import com.customkey.vietime.tools.BackupManager
import com.customkey.vietime.ui.onboarding.GestureTips
import com.customkey.vietime.ui.onboarding.SetupCard
import com.customkey.vietime.ui.onboarding.SwipePracticeScreen
import com.customkey.vietime.ui.onboarding.TypingSandbox
import com.customkey.vietime.ui.onboarding.rememberImeStatus
import com.customkey.vietime.ui.settings.DefaultShortcuts
import com.customkey.vietime.ui.settings.LayoutScreen
import com.customkey.vietime.ui.settings.PlusCard
import com.customkey.vietime.ui.settings.ShortcutsScreen
import com.customkey.vietime.ui.settings.SoundHapticsScreen
import com.customkey.vietime.ui.settings.ThemeStudioScreen
import com.customkey.vietime.ui.settings.ToolsScreen
import com.customkey.vietime.ui.settings.UserDictScreen
import com.customkey.vietime.ui.theme.Glyph
import com.customkey.vietime.ui.theme.LocalTx
import com.customkey.vietime.ui.theme.TxBackground
import com.customkey.vietime.ui.theme.TxDivider
import com.customkey.vietime.ui.theme.TxFloatingTabBar
import com.customkey.vietime.ui.theme.TxPage
import com.customkey.vietime.ui.theme.TxRow
import com.customkey.vietime.ui.theme.TxSection
import com.customkey.vietime.ui.theme.TxSwitchRow
import com.customkey.vietime.ui.theme.TxTabSpec
import com.customkey.vietime.ui.theme.TxTheme

class MainActivity : ComponentActivity() {
    /** Tăng mỗi lần cửa sổ lấy lại focus (sau hộp thoại chọn bàn phím). */
    private val focusTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        AssetBlobs.ensureInstalled(this)
        DefaultShortcuts.seedIfAbsent(this)
        setContent {
            TxTheme {
                TxBackground {
                    AppRoot(
                        focusTick = focusTick,
                        onOpenImeSettings = { runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) } },
                        onPickIme = { getSystemService(InputMethodManager::class.java).showInputMethodPicker() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Sự cố lần trước (bàn phím / native / ANR) chưa được xem ⇒ mở màn báo lỗi.
        showPendingCrash(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) focusTick.intValue++
    }
}

/** Màn con mở toàn màn hình (ẩn thanh tab). */
private enum class Sub { NONE, PRACTICE, THEME, SOUND, LAYOUT, TOOLS, DICT, SHORTCUTS, LOGS }

private val Tabs = listOf(
    TxTabSpec("Kiểu gõ", Glyph.Keyboard),
    TxTabSpec("Giao diện", Glyph.Palette),
    TxTabSpec("Công cụ", Glyph.Grid),
    TxTabSpec("Giới thiệu", Glyph.Info),
)

@Composable
private fun AppRoot(focusTick: State<Int>, onOpenImeSettings: () -> Unit, onPickIme: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sub by rememberSaveable { mutableStateOf(Sub.NONE) }
    BackHandler(enabled = sub != Sub.NONE) { sub = Sub.NONE }
    val status by rememberImeStatus(focusTick)
    val back = { sub = Sub.NONE }

    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        when (sub) {
            // Luyện vuốt KHÔNG nằm trong cuộn dọc: nét vuốt dọc sẽ bị trang nuốt mất.
            Sub.PRACTICE -> SwipePracticeScreen(onBack = back)
            Sub.THEME -> ThemeStudioScreen(onBack = back)
            Sub.SOUND -> SoundHapticsScreen(onBack = back)
            Sub.LAYOUT -> LayoutScreen(onBack = back)
            Sub.TOOLS -> ToolsScreen(onBack = back)
            Sub.DICT -> UserDictScreen(onBack = back)
            Sub.SHORTCUTS -> ShortcutsScreen(onBack = back)
            Sub.LOGS -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) { TxaLogCenter(onBack = back) }
            Sub.NONE -> {
                when (tab) {
                    0 -> TypingTab(status, onOpenImeSettings, onPickIme)
                    1 -> LookTab { sub = it }
                    2 -> ToolsTab { sub = it }
                    else -> AboutTab { sub = Sub.LOGS }
                }
                TxFloatingTabBar(Tabs, tab, collapsed = false, onSelect = { tab = it }, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

private val TabBottom = 92.dp

// ───────────────────────────── Tab 1: Kiểu gõ ─────────────────────────────

@Composable
private fun TypingTab(status: com.customkey.vietime.ui.onboarding.ImeStatus, onOpenImeSettings: () -> Unit, onPickIme: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ImePrefs.of(ctx) }
    @Composable
    fun Toggle(title: String, sub: String?, key: String, def: Boolean) {
        var v by remember { mutableStateOf(prefs.getBoolean(key, def)) }
        TxSwitchRow(title, sub, v) { v = it; prefs.edit().putBoolean(key, it).apply() }
    }
    TxPage("TxTelex", bottomSpace = TabBottom) {
        SetupCard(status, onOpenImeSettings, onPickIme)
        if (status.selected) TypingSandbox()

        TxSection("Kiểu gõ") {
            Toggle("Gõ VNI", "Tắt = Telex", Keys.VNI_MODE, false); TxDivider()
            Toggle("Simple Telex", "w đứng riêng không thành ư; [ ] không thành ơ ư", Keys.SIMPLE_TELEX, true); TxDivider()
            Toggle("Bỏ dấu tự do", "Gõ dấu ở cuối hay giữa từ đều được", Keys.FREE_MARKING, true); TxDivider()
            Toggle("Gõ nhanh (Quick Telex)", "cc→ch, nn→ng, tt→th, gg→gh…", Keys.QUICK_TELEX, false); TxDivider()
            Toggle("Bỏ dấu kiểu mới", "hoà, thuý thay vì hòa, thúy", Keys.MODERN_TONE, false); TxDivider()
            Toggle("Chính tả Teencode", "wá, zui zẻ, kó, bíe, thík…", Keys.TEENCODE, false)
        }
        TxSection("Chính tả") {
            Toggle("Tự sửa lỗi chính tả", "Chạm trượt phím (tpoi → tôi) tự sửa khi gõ dấu cách; ⌫ để hoàn tác", Keys.AUTO_CORRECT, true); TxDivider()
            Toggle("Tự khôi phục tiếng Anh", "google, github, he is… giữ nguyên", Keys.AUTO_RESTORE, true); TxDivider()
            Toggle("Kiểm tra chính tả khi gõ", "Chỉ bỏ dấu khi thành âm tiết tiếng Việt hợp lệ (mp3, h2o giữ nguyên)", Keys.LIVE_SPELL_CHECK, true); TxDivider()
            Toggle("Hiểu ngữ cảnh tiếng Anh", "\"he is\" giữ tiếng Anh, \"hè í\" thì không", Keys.CONTEXTUAL_ENGLISH, true); TxDivider()
            Toggle("Sửa dấu từ đã gõ", "Gõ xong vẫn sửa được dấu: ⌫ rồi gõ lại phím dấu", Keys.RE_EDIT_WORDS, true)
        }
        TxSection("Gợi ý & vuốt", footer = "Mọi dữ liệu học từ bạn chỉ nằm trên máy.") {
            Toggle("Gõ vuốt", "Vuốt qua các chữ để gõ cả từ", ImePrefs.SWIPE_TYPING, true); TxDivider()
            Toggle("Vuốt ra từ tiếng Anh", "Gõ vuốt hiểu cả từ tiếng Anh theo ngữ cảnh", Keys.SWIPE_ENGLISH, true); TxDivider()
            Toggle("Vuốt phím Cách đổi VI/EN", null, ImePrefs.SPACE_FLICK_LANGUAGE, true); TxDivider()
            Toggle("Chạm thông minh", "Chạm giữa hai phím ⇒ chọn phím hợp với từ đang gõ", Keys.SMART_TOUCH, true); TxDivider()
            Toggle("Tự thêm dấu cách sau dấu câu", "Gõ , . ? ! tự cách — gõ cách nữa không bị đôi", Keys.AUTO_SPACE_AFTER_PUNCT, false); TxDivider()
            Toggle("Gõ tắt", "Bung từ viết tắt (ko → không, dc → được)", Keys.SHORTCUTS_ENABLED, true); TxDivider()
            Toggle("Lọc từ nhạy cảm", "Không gợi ý từ tục (vẫn gõ được bình thường)", Keys.FILTER_SENSITIVE, true); TxDivider()
            SuggestionCountRow(prefs); TxDivider()
            TxRow("Xoá từ đã học", destructive = true, onClick = {
                val f = java.io.File(ctx.filesDir, Keys.USERLM_FILE)
                if (f.exists()) f.delete()
                android.widget.Toast.makeText(ctx, "Đã xoá bộ nhớ từ đã học", android.widget.Toast.LENGTH_SHORT).show()
            })
        }
        GestureTips()
    }
}

/** Chọn số ô gợi ý 3–5 bằng nút phân đoạn. */
@Composable
private fun SuggestionCountRow(prefs: android.content.SharedPreferences) {
    val p = LocalTx.current
    var n by remember { mutableStateOf(prefs.getInt(Keys.SUGGESTION_COUNT, 3).coerceIn(3, 5)) }
    TxRow("Số ô gợi ý", "Trên thanh gợi ý của bàn phím") {
        Row(Modifier.clip(RoundedCornerShape(9.dp)).background(p.fill).padding(2.dp)) {
            for (k in 3..5) Box(
                Modifier.clip(RoundedCornerShape(7.dp)).background(if (k == n) p.card else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { n = k; prefs.edit().putInt(Keys.SUGGESTION_COUNT, k).apply() }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) { Text("$k", color = if (k == n) p.label else p.secondary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        }
    }
}

// ───────────────────────────── Tab 2: Giao diện ─────────────────────────────

@Composable
private fun LookTab(open: (Sub) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ImePrefs.of(ctx) }
    @Composable
    fun Toggle(title: String, sub: String?, key: String, def: Boolean) {
        var v by remember { mutableStateOf(prefs.getBoolean(key, def)) }
        TxSwitchRow(title, sub, v) { v = it; prefs.edit().putBoolean(key, it).apply() }
    }
    val p = LocalTx.current
    TxPage("Giao diện", bottomSpace = TabBottom) {
        TxSection("Tuỳ biến bàn phím", footer = "Mọi thay đổi hiện ngay trên bàn phím mẫu ở cuối mỗi màn.") {
            TxRow("Theme Studio", "Ảnh nền, màu, độ trong suốt, bo góc, viền phím", Glyph.Palette, tint = androidx.compose.ui.graphics.Color(0xFF8B5CF6), chevron = true, onClick = { open(Sub.THEME) }); TxDivider(64.dp)
            TxRow("Âm thanh & Rung", "Phím cơ Blue/Brown/Red, máy đánh chữ, giọt nước, âm riêng", Glyph.Speaker, tint = androidx.compose.ui.graphics.Color(0xFFF2711C), chevron = true, onClick = { open(Sub.SOUND) }); TxDivider(64.dp)
            TxRow("Bố cục", "Hàng số, chiều cao, tách đôi, thời gian giữ phím", Glyph.Layout, tint = p.accent, chevron = true, onClick = { open(Sub.LAYOUT) })
        }
        TxSection("Phản hồi khi gõ") {
            Toggle("Âm bàn phím", "Phát âm thanh khi bấm", Keys.KEY_SOUND, true); TxDivider()
            Toggle("Rung khi bấm phím", "Rung nhẹ mỗi lần chạm", Keys.HAPTIC_FEEDBACK, true)
        }
    }
}

// ───────────────────────────── Tab 3: Công cụ ─────────────────────────────

@Composable
private fun ToolsTab(open: (Sub) -> Unit) {
    val p = LocalTx.current
    TxPage("Công cụ", bottomSpace = TabBottom) {
        TxSection("Soạn thảo") {
            TxRow("Công cụ", "Thêm dấu cả câu, máy tính, clipboard thông minh, mẫu câu", Glyph.Grid, tint = p.accent, chevron = true, onClick = { open(Sub.TOOLS) }); TxDivider(64.dp)
            TxRow("Gõ tắt", "ko → không, dc → được… thêm, sửa, nhập / xuất file", Glyph.Bolt, tint = androidx.compose.ui.graphics.Color(0xFFF2A81C), chevron = true, onClick = { open(Sub.SHORTCUTS) }); TxDivider(64.dp)
            TxRow("Từ điển cá nhân", "Từ đã học, tên riêng và thuật ngữ để gợi ý ngay", Glyph.Book, tint = androidx.compose.ui.graphics.Color(0xFF2FB964), chevron = true, onClick = { open(Sub.DICT) })
        }
        TxSection("Luyện tập") {
            TxRow("Luyện gõ vuốt", "Vuốt qua các chữ để gõ cả từ — chấm điểm từng nét", Glyph.Swipe, tint = androidx.compose.ui.graphics.Color(0xFFE5384B), chevron = true, onClick = { open(Sub.PRACTICE) })
        }
        PlusCard()
        BackupSection()
    }
}

/** Sao lưu toàn bộ cài đặt + gõ tắt + mẫu câu ra file JSON, hoặc khôi phục từ file. */
@Composable
private fun BackupSection() {
    val ctx = LocalContext.current
    val p = LocalTx.current
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(BackupManager.export(ctx).toByteArray()) } }
            .onSuccess { android.widget.Toast.makeText(ctx, "Đã sao lưu", android.widget.Toast.LENGTH_SHORT).show() }
            .onFailure { android.widget.Toast.makeText(ctx, "Không ghi được file", android.widget.Toast.LENGTH_SHORT).show() }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val msg = runCatching {
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("Không đọc được file")
            val r = BackupManager.import(ctx, text)
            "Đã khôi phục ${r.settings} cài đặt, ${r.snippets} mẫu câu, ${r.pinned} mục ghim"
        }.getOrElse { it.message ?: "File không hợp lệ" }
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
    }
    TxSection("Sao lưu", footer = "Cài đặt, theme, âm thanh, gõ tắt, mẫu câu và mục clipboard đã ghim — chuyển sang máy mới chỉ một file.") {
        TxRow("Sao lưu ra file", glyph = Glyph.Cloud, tint = p.accent, onClick = { exporter.launch("txtelex-saoluu.json") }); TxDivider(64.dp)
        TxRow("Khôi phục từ file", glyph = Glyph.Cloud, tint = p.green, onClick = { importer.launch(arrayOf("application/json", "*/*")) })
    }
}

// ───────────────────────────── Tab 4: Giới thiệu ─────────────────────────────

/**
 * Giới thiệu: bản quyền TxTelex thuộc TXA. Mục "Thành phần mã nguồn mở" là BẮT BUỘC theo giấy phép MIT
 * của lõi bộ gõ (phải kèm thông báo bản quyền gốc trong mọi bản phân phối).
 */
@Composable
private fun AboutTab(onLogs: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalTx.current
    var showOss by remember { mutableStateOf(false) }
    val mit = remember { runCatching { ctx.assets.open("licenses/viettelex-LICENSE.txt").bufferedReader().readText() }.getOrDefault("") }
    TxPage("Giới thiệu", bottomSpace = TabBottom) {
        Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Image(painterResource(R.drawable.ic_txtelex_logo), "TxTelex", Modifier.size(88.dp).clip(RoundedCornerShape(22.dp)))
            Spacer(Modifier.height(4.dp))
            Text("TxTelex", color = p.label, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Vietnamese Smart Keyboard · ${BuildConfig.VERSION_NAME}", color = p.secondary, fontSize = 13.sp)
        }
        TxSection {
            TxRow("Nhật ký & báo lỗi", "Sự cố đã ghi, xuất nhật ký gửi TXA", Glyph.Bug, tint = p.accent, chevron = true, onClick = onLogs); TxDivider(64.dp)
            TxRow("Riêng tư", "Chạy 100% trên máy, không gửi phím gõ lên mạng", Glyph.Lock, tint = p.green)
        }
        TxSection(footer = "© 2026 TXA. Tên, logo, giao diện và hệ thống tuỳ biến TxTelex thuộc về TXA.") {
            TxRow("Thành phần mã nguồn mở", chevron = !showOss, onClick = { showOss = !showOss })
            if (showOss) {
                Text("Bộ xử lý Telex/VNI dùng mã nguồn viettelex theo giấy phép MIT:", Modifier.padding(horizontal = 16.dp), color = p.secondary, fontSize = 12.5.sp)
                Text(mit, Modifier.padding(16.dp), fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, lineHeight = 14.sp, color = p.tertiary)
            }
        }
    }
}
