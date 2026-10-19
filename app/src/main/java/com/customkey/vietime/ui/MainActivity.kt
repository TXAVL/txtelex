package com.customkey.vietime.ui

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import com.customkey.vietime.tools.BackupManager
import com.customkey.vietime.ui.settings.DefaultShortcuts
import com.customkey.vietime.ui.settings.ShortcutsScreen
import androidx.activity.compose.rememberLauncherForActivityResult
import com.customkey.vietime.ime.AssetBlobs
import com.customkey.vietime.ime.ImePrefs
import com.customkey.vietime.ui.onboarding.SetupCard
import com.customkey.vietime.ui.onboarding.SwipePracticeScreen
import com.customkey.vietime.ui.onboarding.TypingSandbox
import com.customkey.vietime.ui.onboarding.rememberImeStatus
import com.customkey.vietime.ui.settings.LayoutScreen
import com.customkey.vietime.ui.settings.PlusCard
import com.customkey.vietime.ui.settings.ToolsScreen
import com.customkey.vietime.ui.settings.SoundHapticsScreen
import com.customkey.vietime.ui.settings.ThemeStudioScreen
import com.customkey.vietime.ui.settings.UserDictScreen
import com.customkey.vietime.ui.theme.GhostButton
import com.customkey.vietime.ui.theme.GlassCard
import com.customkey.vietime.ui.theme.SectionLabel
import com.customkey.vietime.ui.theme.Tx
import com.customkey.vietime.ui.theme.TxBackground
import com.customkey.vietime.ui.theme.TxSwitchRow
import com.customkey.vietime.ui.theme.TxTheme

class MainActivity : ComponentActivity() {
    /** Tăng mỗi lần cửa sổ lấy lại focus (sau hộp thoại chọn bàn phím). */
    private val focusTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
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

private enum class Screen { HOME, PRACTICE, ABOUT, THEME, SOUND, LAYOUT, TOOLS, DICT, LOGS, SHORTCUTS }

@Composable
private fun AppRoot(focusTick: State<Int>, onOpenImeSettings: () -> Unit, onPickIme: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
    val status by rememberImeStatus(focusTick)
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        when (screen) {
            Screen.HOME -> Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header()
                Hero()
                SetupCard(status, onOpenImeSettings, onPickIme)
                if (status.selected) TypingSandbox()
                SectionLabel("Tuỳ biến")
                CustomizeTile("🎨", "Theme Studio", "Ảnh nền, màu, độ trong suốt, bo góc, viền phím") { screen = Screen.THEME }
                CustomizeTile("🔊", "Âm thanh & Rung", "Phím cơ Blue/Brown/Red, máy đánh chữ, giọt nước, âm riêng") { screen = Screen.SOUND }
                CustomizeTile("📐", "Bố cục", "Hàng số, chiều cao, tách đôi, thời gian giữ phím") { screen = Screen.LAYOUT }
                SectionLabel("Tính năng")
                CustomizeTile("🧰", "Công cụ", "Thêm dấu cả câu, máy tính, clipboard thông minh, mẫu câu") { screen = Screen.TOOLS }
                CustomizeTile("⚡", "Gõ tắt", "ko → không, dc → được… thêm, sửa, nhập / xuất file") { screen = Screen.SHORTCUTS }
                CustomizeTile("📖", "Từ điển cá nhân", "Quản lý từ đã học, thêm tên riêng & thuật ngữ để gợi ý ngay") { screen = Screen.DICT }
                FeatureTile(R.drawable.img_swipe_practice, "Luyện gõ vuốt",
                    "Vuốt qua các chữ để gõ cả từ — chấm điểm từng nét.", "Bắt đầu") { screen = Screen.PRACTICE }
                FeatureTile(R.drawable.img_privacy_safe, "Riêng tư tuyệt đối",
                    "Chạy 100% trên máy. Không thu thập phím gõ, không gửi gì lên mạng.", null) {}
                PlusCard()
                SectionLabel("Kiểu gõ")
                TypingModeCard()
                BackupCard()
                Footer { screen = Screen.ABOUT }
            }
            // Luyện vuốt KHÔNG nằm trong verticalScroll: nét vuốt dọc sẽ bị cuộn trang nuốt mất.
            Screen.PRACTICE -> SwipePracticeScreen(onBack = { screen = Screen.HOME })
            Screen.THEME -> ThemeStudioScreen(onBack = { screen = Screen.HOME })
            Screen.SOUND -> SoundHapticsScreen(onBack = { screen = Screen.HOME })
            Screen.LAYOUT -> LayoutScreen(onBack = { screen = Screen.HOME })
            Screen.TOOLS -> ToolsScreen(onBack = { screen = Screen.HOME })
            Screen.DICT -> UserDictScreen(onBack = { screen = Screen.HOME })
            Screen.SHORTCUTS -> ShortcutsScreen(onBack = { screen = Screen.HOME })
            Screen.ABOUT -> Column(Modifier.verticalScroll(rememberScrollState())) { AboutScreen(onBack = { screen = Screen.HOME }, onLogs = { screen = Screen.LOGS }) }
            Screen.LOGS -> Column(Modifier.verticalScroll(rememberScrollState())) { TxaLogCenter(onBack = { screen = Screen.ABOUT }) }
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_txtelex_logo), "TxTelex", Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("TxTelex", style = MaterialTheme.typography.titleLarge, color = Tx.Text)
            Text("Vietnamese Smart Keyboard", style = MaterialTheme.typography.bodySmall, color = Tx.CyanSoft)
        }
        Text("v${BuildConfig.VERSION_NAME}", Modifier.background(Tx.Glass, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
            color = Tx.TextDim, fontSize = 12.sp)
    }
}

/** Banner thương hiệu + slogan. */
@Composable
private fun Hero() {
    val shape = RoundedCornerShape(24.dp)
    Box(Modifier.fillMaxWidth().aspectRatio(1024f / 500f).clip(shape).border(1.dp, Tx.border, shape)) {
        Image(painterResource(R.drawable.img_feature_banner), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Tx.Night.copy(alpha = 0.85f)))))
        Text("Gõ tiếng Việt đỉnh cao — nhanh, đẹp, riêng tư.",
            Modifier.align(Alignment.BottomStart).padding(16.dp), color = Tx.Text,
            style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun FeatureTile(image: Int, title: String, body: String, action: String?, onClick: () -> Unit) {
    GlassCard(padding = androidx.compose.foundation.layout.PaddingValues(14.dp), onClick = if (action != null) onClick else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(image), null, Modifier.size(76.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Tx.Text)
                Text(body, style = MaterialTheme.typography.bodySmall, color = Tx.TextDim)
                if (action != null) Text("$action  →", color = Tx.Cyan, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Kiểu gõ & phản hồi cơ bản (GĐ3 mở rộng thành màn Cài đặt đầy đủ). */
@Composable
private fun TypingModeCard() {
    val ctx = LocalContext.current
    val prefs = remember { ImePrefs.of(ctx) }
    @Composable
    fun Toggle(title: String, sub: String?, key: String, def: Boolean) {
        var v by remember { mutableStateOf(prefs.getBoolean(key, def)) }
        TxSwitchRow(title, sub, v) { v = it; prefs.edit().putBoolean(key, it).apply() }
    }
    GlassCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
        Toggle("Gõ VNI", "Tắt = Telex", Keys.VNI_MODE, false)
        Toggle("Bỏ dấu tự do", "Gõ dấu ở cuối hay giữa từ đều được", Keys.FREE_MARKING, true)
        Toggle("Tự khôi phục tiếng Anh", "google, github, he is… giữ nguyên", Keys.AUTO_RESTORE, true)
        Toggle("Sửa dấu từ đã gõ", null, Keys.RE_EDIT_WORDS, true)
        Toggle("Gõ vuốt", null, ImePrefs.SWIPE_TYPING, true)
        Toggle("Vuốt phím Cách đổi VI/EN", null, ImePrefs.SPACE_FLICK_LANGUAGE, true)
        Toggle("Âm bàn phím", "Phát âm thanh phản hồi khi bấm", Keys.KEY_SOUND, true)
        Toggle("Rung khi bấm phím", "Rung nhẹ phản hồi xúc giác khi chạm", Keys.HAPTIC_FEEDBACK, true)
        Toggle("Gõ tắt (Shortcuts)", "Bung từ viết tắt (vd: ko → không, dc → được)", Keys.SHORTCUTS_ENABLED, true)
        Toggle("Tự sửa lỗi chính tả", "Chạm trượt phím (tpoi → tôi) tự sửa khi gõ dấu cách; ⌫ để hoàn tác", Keys.AUTO_CORRECT, true)
        Toggle("Kiểm tra chính tả khi gõ", "Chỉ bỏ dấu khi thành âm tiết tiếng Việt hợp lệ (mp3, h2o giữ nguyên)", Keys.LIVE_SPELL_CHECK, true)
        Toggle("Hiểu ngữ cảnh tiếng Anh", "\"he is\" giữ tiếng Anh, \"hè í\" thì không", Keys.CONTEXTUAL_ENGLISH, true)
        Toggle("Chạm thông minh", "Chạm giữa hai phím ⇒ chọn phím hợp với từ đang gõ", Keys.SMART_TOUCH, true)
        Toggle("Tự thêm dấu cách sau dấu câu", "Gõ , . ? ! tự cách — gõ cách nữa không bị đôi", Keys.AUTO_SPACE_AFTER_PUNCT, false)
        Toggle("Vuốt ra từ tiếng Anh", "Gõ vuốt hiểu cả từ tiếng Anh theo ngữ cảnh", Keys.SWIPE_ENGLISH, true)
        Toggle("Simple Telex", "w đứng riêng không thành ư; [ ] không thành ơ ư", Keys.SIMPLE_TELEX, true)
        Toggle("Lọc từ nhạy cảm", "Không gợi ý từ tục (vẫn gõ được bình thường)", Keys.FILTER_SENSITIVE, true)
        SuggestionCountRow(prefs)
        Toggle("Gõ nhanh (Quick Telex)", "cc→ch, nn→ng, tt→th, gg→gh...", Keys.QUICK_TELEX, false)
        Toggle("Bỏ dấu kiểu mới", "hoà, thuý thay vì hòa, thúy", Keys.MODERN_TONE, false)
        Toggle("Chính tả Teencode", "wá, zui zẻ, kó, bíe, thík...", Keys.TEENCODE, false)
        Spacer(Modifier.height(8.dp))
        GhostButton("Xoá dữ liệu từ đã học", Modifier.fillMaxWidth()) {
            val f = java.io.File(ctx.filesDir, Keys.USERLM_FILE)
            if (f.exists()) f.delete()
            android.widget.Toast.makeText(ctx, "Đã xoá bộ nhớ từ đã học", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun SuggestionCountRow(prefs: android.content.SharedPreferences) {
    var n by remember { mutableStateOf(prefs.getInt(Keys.SUGGESTION_COUNT, 3).coerceIn(3, 5)) }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Số ô gợi ý", color = Tx.Text, style = MaterialTheme.typography.bodyLarge)
            Text("Trên thanh gợi ý của bàn phím", color = Tx.TextDim, style = MaterialTheme.typography.bodySmall)
        }
        for (k in 3..5) Text("$k", Modifier.padding(start = 6.dp)
            .background(if (k == n) Tx.Cyan else Tx.Glass, RoundedCornerShape(10.dp))
            .clickable { n = k; prefs.edit().putInt(Keys.SUGGESTION_COUNT, k).apply() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
            color = if (k == n) Tx.Night else Tx.Text, fontWeight = FontWeight.Bold)
    }
}

/** Sao lưu toàn bộ cài đặt + gõ tắt + mẫu câu ra file JSON, hoặc khôi phục từ file. */
@Composable
private fun BackupCard() {
    val ctx = LocalContext.current
    val exporter = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(BackupManager.export(ctx).toByteArray()) } }
            .onSuccess { android.widget.Toast.makeText(ctx, "Đã sao lưu", android.widget.Toast.LENGTH_SHORT).show() }
            .onFailure { android.widget.Toast.makeText(ctx, "Không ghi được file", android.widget.Toast.LENGTH_SHORT).show() }
    }
    val importer = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val msg = runCatching {
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("Không đọc được file")
            val r = BackupManager.import(ctx, text)
            "Đã khôi phục ${r.settings} cài đặt, ${r.snippets} mẫu câu, ${r.pinned} mục ghim"
        }.getOrElse { it.message ?: "File không hợp lệ" }
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
    }
    GlassCard {
        Text("Sao lưu & khôi phục", style = MaterialTheme.typography.titleMedium, color = Tx.Text)
        Text("Cài đặt, theme, âm thanh, gõ tắt, mẫu câu và mục clipboard đã ghim — chuyển sang máy mới chỉ một file.",
            style = MaterialTheme.typography.bodySmall, color = Tx.TextDim)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("⬆ Sao lưu", Modifier.weight(1f)) { exporter.launch("txtelex-saoluu.json") }
            GhostButton("⬇ Khôi phục", Modifier.weight(1f)) { importer.launch(arrayOf("application/json", "*/*")) }
        }
    }
}

@Composable
private fun CustomizeTile(icon: String, title: String, body: String, onClick: () -> Unit) {
    GlassCard(padding = androidx.compose.foundation.layout.PaddingValues(16.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).background(Tx.accentSoft, RoundedCornerShape(14.dp)).border(1.dp, Tx.Stroke, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center) { Text(icon, fontSize = 22.sp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Tx.Text)
                Text(body, style = MaterialTheme.typography.bodySmall, color = Tx.TextDim)
            }
            Text("›", color = Tx.Cyan, fontSize = 26.sp)
        }
    }
}

@Composable
private fun Footer(onAbout: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("© 2026 TXA · TxTelex", color = Tx.TextFaint, fontSize = 12.sp)
        Text("Giới thiệu", Modifier.clickable(onClick = onAbout).padding(8.dp), color = Tx.CyanSoft, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Giới thiệu: bản quyền TxTelex thuộc TXA. Mục "Thành phần mã nguồn mở" ở cuối là BẮT BUỘC theo
 * giấy phép MIT của lõi bộ gõ (phải kèm thông báo bản quyền gốc trong mọi bản phân phối).
 */
@Composable
private fun AboutScreen(onBack: () -> Unit, onLogs: () -> Unit) {
    val ctx = LocalContext.current
    var showOss by remember { mutableStateOf(false) }
    val mit = remember { runCatching { ctx.assets.open("licenses/viettelex-LICENSE.txt").bufferedReader().readText() }.getOrDefault("") }
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("‹ Quay lại", Modifier.clickable(onClick = onBack).padding(vertical = 6.dp), color = Tx.CyanSoft, fontWeight = FontWeight.SemiBold)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(painterResource(R.drawable.ic_txtelex_logo), "TxTelex", Modifier.size(96.dp).clip(RoundedCornerShape(26.dp)))
            Text("TxTelex", style = MaterialTheme.typography.headlineMedium, color = Tx.Text)
            Text("Phiên bản ${BuildConfig.VERSION_NAME}", color = Tx.TextDim, fontSize = 13.sp)
        }
        GlassCard {
            Text("© 2026 TXA. Bảo lưu mọi quyền.", style = MaterialTheme.typography.titleSmall, color = Tx.Text)
            Spacer(Modifier.height(6.dp))
            Text("Tên, logo, giao diện và hệ thống tuỳ biến TxTelex thuộc về TXA.",
                style = MaterialTheme.typography.bodySmall, color = Tx.TextDim)
        }
        GlassCard(onClick = onLogs) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Nhật ký & báo lỗi", style = MaterialTheme.typography.titleSmall, color = Tx.Text)
                    Text("Sự cố đã ghi, xuất nhật ký gửi TXA", style = MaterialTheme.typography.bodySmall, color = Tx.TextDim)
                }
                Text("›", color = Tx.Cyan, fontSize = 22.sp)
            }
        }
        GlassCard(onClick = { showOss = !showOss }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Thành phần mã nguồn mở", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Tx.Text)
                Text(if (showOss) "▲" else "▼", color = Tx.TextDim)
            }
            if (showOss) {
                Spacer(Modifier.height(10.dp))
                Text("Bộ xử lý Telex/VNI dùng mã nguồn viettelex theo giấy phép MIT:",
                    style = MaterialTheme.typography.bodySmall, color = Tx.TextDim)
                Spacer(Modifier.height(8.dp))
                Text(mit, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, lineHeight = 14.sp, color = Tx.TextFaint)
            }
        }
    }
}
