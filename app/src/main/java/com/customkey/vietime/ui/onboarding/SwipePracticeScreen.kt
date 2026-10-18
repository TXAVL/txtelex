package com.customkey.vietime.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.customkey.vietime.engine.SwipeDecoder
import com.customkey.vietime.engine.SwipeLayout
import com.customkey.vietime.engine.SwipePath
import com.customkey.vietime.engine.SwipeSuggest
import com.customkey.vietime.ui.keyboard.KeyboardStyle
import com.customkey.vietime.ui.keyboard.KeyboardView
import com.customkey.vietime.ui.theme.GhostButton
import com.customkey.vietime.ui.theme.GlassCard
import com.customkey.vietime.ui.theme.Tx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Kho từ luyện vuốt: từ thông dụng (≥ 3 phím khác nhau liên tiếp — vuốt mới có nghĩa). */
object PracticeWords {
    val words = listOf(
        "việt", "người", "không", "những", "được", "trong", "chúng", "nhiều", "thành", "nhưng",
        "thì", "này", "của", "cho", "với", "các", "một", "còn", "theo", "đến", "nước", "làm",
        "năm", "ngày", "tiếng", "chào", "cảm", "ơn", "bạn", "mình", "hôm", "nay", "mai", "đẹp",
        "trời", "điện", "thoại", "bàn", "phím", "nhà", "trường", "học", "sinh", "viên", "công",
        "ty", "thời", "gian", "biết", "muốn", "thích", "thương", "nhớ", "yêu", "vui", "buồn",
        "xin", "lỗi", "tháng", "giờ", "phút", "sáng", "chiều", "tối", "đêm", "ăn", "uống", "ngủ",
        "đường", "phố", "chợ", "tiền", "việc", "hỏi", "trả", "lời", "nói", "viết", "đọc", "nghe",
    ).filter { distinctKeys(SwipeSuggest.fold(it)) >= 2 }

    fun distinctKeys(f: String): Int { var n = 0; var prev = ' '; for (c in f) { if (c != prev) n++; prev = c }; return n }

    fun at(seed: Long, i: Int): String {
        var z = seed + i * -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return words[((z ushr 1) % words.size).toInt()]
    }
}

/**
 * Luyện gõ vuốt: vuốt từ mục tiêu trên bản sao bàn phím (cùng KeyboardView + cùng decoder như
 * IME), chấm đúng/sai theo dạng không dấu, đếm độ chính xác phiên.
 */
@Composable
fun SwipePracticeScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val seed = remember { System.currentTimeMillis() }
    var index by remember { mutableIntStateOf(0) }
    val target = remember(index) { PracticeWords.at(seed, index) }
    var tries by remember { mutableIntStateOf(0) }
    var hits by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var ready by remember { mutableStateOf(false) }
    val decoder = remember { SwipeDecoder() }

    fun onSwipe(path: SwipePath) {
        // Đọc index hiện tại (listener của View được tạo một lần — không giữ `target` cũ).
        val want = SwipeSuggest.fold(PracticeWords.at(seed, index))
        val copy = SwipePath(path.minDistance).also { c -> for (i in 0 until path.count) c.add(path.xs[i], path.ys[i], path.ts[i], force = true) }
        scope.launch {
            val (top, shown) = withContext(Dispatchers.Default) {
                synchronized(decoder) {
                    val cands = decoder.decode(copy, SwipeSuggest.TOP_K, null)
                    val shown = SwipeSuggest.choose(cands, null)?.word
                    cands.map { it.folded } to shown
                }
            }
            val ok = top.firstOrNull() == want
            tries++; if (ok) hits++
            result = ok to (shown ?: top.firstOrNull() ?: "—")
            if (ok) index++
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹", Modifier.clickable(onClick = onBack).padding(end = 12.dp), color = Tx.CyanSoft, fontSize = 30.sp)
                Text("Luyện gõ vuốt", style = MaterialTheme.typography.titleLarge, color = Tx.Text)
            }
            GlassCard(glow = true) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("VUỐT TỪ", color = Tx.TextFaint, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text(target, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = Tx.Text)
                    Text(SwipeSuggest.fold(target).toCharArray().joinToString("  ›  "), color = Tx.CyanSoft, fontSize = 13.sp)
                    result?.let { (ok, got) ->
                        Text(if (ok) "✓ Chính xác: $got" else "✗ Ra “$got” — thử lại",
                            color = if (ok) Tx.Green else Tx.Red, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val pct = if (tries == 0) 0 else hits * 100 / tries
                Column(Modifier.weight(1f)) {
                    Text("$hits/$tries đúng · $pct%", color = Tx.Text, style = MaterialTheme.typography.titleSmall)
                    Text(if (ready) "Sẵn sàng — vuốt trên bàn phím bên dưới" else "Đang chuẩn bị từ điển vuốt…",
                        color = Tx.TextDim, style = MaterialTheme.typography.bodySmall)
                }
                GhostButton("Bỏ qua") { index++; result = null }
            }
        }
        Spacer(Modifier.weight(1f))
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                KeyboardView(ctx).apply {
                    style = KeyboardStyle.neon
                    spaceLabel = "Luyện vuốt"
                    listener = object : KeyboardView.Listener {
                        override fun onLetter(ch: Char) {}
                        override fun onText(text: String) {}
                        override fun onSpace() {}
                        override fun onBackspace() {}
                        override fun onEnter() {}
                        override fun onGlobe(longPress: Boolean) {}
                        override fun onSwipeLayout(layout: SwipeLayout) {
                            ready = false
                            scope.launch {
                                withContext(Dispatchers.Default) { synchronized(decoder) { decoder.setLayout(layout); decoder.prepare() } }
                                ready = true
                            }
                        }
                        override fun onSwipeTypingStart(): Boolean = ready
                        override fun onSwipeTypingEnd(path: SwipePath, case: SwipeSuggest.Case) = onSwipe(path)
                    }
                    swipeTyping = true
                }
            },
        )
    }
}
