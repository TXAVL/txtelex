package com.customkey.vietime.ui.settings

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.ViewGroup
import com.customkey.vietime.customization.KeyboardCustomizer
import com.customkey.vietime.customization.LayoutConfig
import com.customkey.vietime.engine.EngineSettings
import com.customkey.vietime.engine.Keys
import com.customkey.vietime.engine.TextProxy
import com.customkey.vietime.ime.FieldConfig
import com.customkey.vietime.ime.ImePrefs
import com.customkey.vietime.ime.InputBridge
import com.customkey.vietime.ime.Suggestion
import com.customkey.vietime.ime.TypingSession
import com.customkey.vietime.ui.keyboard.EmojiPaneView
import com.customkey.vietime.ui.keyboard.KeyboardPanel
import com.customkey.vietime.ui.keyboard.KeyboardPlane
import com.customkey.vietime.ui.keyboard.KeyboardView
import com.customkey.vietime.ui.keyboard.SuggestionStripView
import com.customkey.vietime.ui.theme.Tx

/** Ô chữ trong bộ nhớ cho bàn phím xem trước. */
private class MemoryText : TextProxy {
    val sb = StringBuilder()
    override fun insertText(text: String) { sb.append(text) }
    override fun deleteCodePoints(count: Int) {
        var k = count
        while (k-- > 0 && sb.isNotEmpty()) sb.setLength(sb.offsetByCodePoints(sb.length, -1))
    }
    override fun deleteBackward() {
        if (sb.isEmpty()) return
        sb.setLength(sb.length - InputBridge.lastGraphemeLength(sb.toString()))
    }
    override val isSecure: Boolean get() = false
    override fun contextBeforeInput(): String = sb.toString()
    override val canReEdit: Boolean get() = true
}

/**
 * Bàn phím mẫu gõ được ngay trong app: cùng KeyboardPanel + cùng tuỳ biến với IME thật, chữ hiện ở
 * ô phía trên. Mọi thay đổi trong Theme Studio / Âm thanh / Bố cục áp ngay (nghe prefs).
 */
@Composable
fun KeyboardPreview(modifier: Modifier = Modifier, hint: String = "Gõ thử trên bàn phím mẫu…") {
    val ctx = LocalContext.current
    val prefs = remember { ImePrefs.of(ctx) }
    var layoutCfg by remember { mutableStateOf(LayoutConfig.load { prefs.all[it] }) }
    var text by remember { mutableStateOf("") }
    val holder = remember { arrayOfNulls<KeyboardPanel>(1) }
    val custom = remember {
        lateinit var c: KeyboardCustomizer
        c = KeyboardCustomizer(ctx) { holder[0]?.let { p -> c.applyTo(p, c.isWide(ctx)) } }
        c
    }
    val mem = remember { MemoryText() }
    val session = remember {
        TypingSession().also {
            it.startInput(EngineSettings.load { k -> prefs.all[k] }, FieldConfig.DEFAULT.toTraits())
            it.seedLanguageModel()
        }
    }

    DisposableEffect(Unit) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Keys.NUMBER_ROW || key == LayoutConfig.KEY_HEIGHT || key == LayoutConfig.KEY_WIDTH) {
                layoutCfg = LayoutConfig.load { prefs.all[it] }
            }
            if (!custom.onPrefChanged(key)) session.startInput(EngineSettings.load { k -> prefs.all[k] }, FieldConfig.DEFAULT.toTraits())
        }
        prefs.registerOnSharedPreferenceChangeListener(l)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(l)
            holder[0] = null
            custom.release()
        }
    }

    val rows = if (layoutCfg.numberRow) 5 else 4
    val panelHeightDp = (42 + rows * layoutCfg.rowHeightDp() + 4).dp

    Column(modifier.fillMaxWidth().wrapContentHeight()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).heightIn(min = 42.dp)
                .background(Tx.Glass, RoundedCornerShape(14.dp)).border(1.dp, Tx.Stroke, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (text.isEmpty()) Text(hint, color = Tx.TextFaint, fontSize = 15.sp)
            else Text(text.takeLast(80) + "▏", color = Tx.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(panelHeightDp),
            factory = { c ->
                KeyboardPanel(c).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }.also { panel ->
                    holder[0] = panel
                    fun sync() {
                        text = mem.sb.toString()
                        panel.keyboard.let { k -> if (k.plane == KeyboardPlane.LETTERS) session.autoShift(mem)?.let { k.setAutoShift(it) } }
                        panel.strip.show(session.suggestions(mem))
                    }
                    panel.keyboard.spaceLabel = "TxTelex"
                    panel.strip.showToolsButton = false
                    panel.keyboard.listener = object : KeyboardView.Listener {
                        override fun onLetter(ch: Char) { session.letter(ch, mem); sync() }
                        override fun onText(text: String) { session.text(text, mem); sync() }
                        override fun onSpace() { session.space(mem); sync() }
                        override fun onBackspace() { session.backspace(mem); sync() }
                        override fun onEnter() { session.commitBeforeEnter(mem); mem.sb.setLength(0); sync() }
                        override fun onGlobe(longPress: Boolean) {}
                        override fun onEmoji() { panel.showEmoji(true) }
                        override fun onFeedback(kind: KeyboardView.FeedbackKind) = custom.feedback(kind)
                        override fun onLanguageFlick() {
                            val en = session.toggleLanguage(mem)
                            panel.keyboard.spaceLabel = if (en) "English" else "TxTelex"
                            sync()
                        }
                        override fun onDeleteWords(words: Int) { session.deleteWords(mem, words); panel.strip.showMessage(null); sync() }
                        override fun onDeleteWordsPreview(words: Int) {
                            panel.strip.showMessage(if (words > 0) "Xoá: " + session.wordsBefore(mem, words).trimEnd() else null)
                        }
                    }
                    panel.emoji.listener = object : EmojiPaneView.Listener {
                        override fun onEmojiPicked(emoji: String) {
                            session.text(emoji, mem)
                            sync()
                            custom.feedback(KeyboardView.FeedbackKind.KEY)
                        }
                        override fun onBackspace() {
                            session.backspace(mem)
                            sync()
                            custom.feedback(KeyboardView.FeedbackKind.DELETE)
                        }
                        override fun onClose() {
                            panel.showEmoji(false)
                        }
                    }
                    panel.strip.listener = object : SuggestionStripView.Listener {
                        override fun onSuggestion(s: Suggestion) { session.accept(s, mem); sync() }
                        override fun onUndoDelete() {}
                    }
                    custom.reloadAll()
                }
            },
            update = { panel ->
                holder[0] = panel
            },
        )
    }
}
