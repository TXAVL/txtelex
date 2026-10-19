package com.customkey.vietime.ime

import android.content.Context
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.customkey.vietime.engine.Cancellable
import com.customkey.vietime.engine.EngineSettings
import com.customkey.vietime.engine.Keys
import com.customkey.vietime.engine.MainThread
import com.customkey.vietime.engine.SwipeDecoder
import com.customkey.vietime.engine.SwipeLayout
import com.customkey.vietime.engine.SwipePath
import com.customkey.vietime.engine.SwipeSuggest
import com.customkey.vietime.engine.SyllableLM
import com.customkey.vietime.engine.UserLangModel
import com.customkey.vietime.customization.KeyboardCustomizer
import com.customkey.vietime.diag.TxaLogger
import com.customkey.vietime.ui.keyboard.EmojiPaneView
import com.customkey.vietime.ui.keyboard.KeyboardPanel
import com.customkey.vietime.ui.keyboard.KeyboardPlane
import com.customkey.vietime.ui.keyboard.KeyboardView
import com.customkey.vietime.ui.keyboard.SuggestionStripView
import java.io.File

/**
 * InputMethodService: dựng View, phân loại ô, nối phím → [TypingSession] qua [InputBridge].
 * Khởi động nhẹ: blob mmap lười, mô hình người dùng nạp nền, decoder vuốt dựng nền khi cần.
 */
class CustomImeService : InputMethodService(), KeyboardView.Listener, SuggestionStripView.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val handler = Handler(Looper.getMainLooper())
    private val main = object : MainThread {
        override fun post(r: Runnable) { handler.post(r) }
        override fun postDelayed(delayMs: Long, r: Runnable): Cancellable {
            handler.postDelayed(r, delayMs); return Cancellable { handler.removeCallbacks(r) }
        }
    }
    private val worker by lazy { HandlerThread("vietime-worker").apply { start() }.let { Handler(it.looper) } }

    private lateinit var prefs: SharedPreferences
    private lateinit var session: TypingSession
    private val proxy = InputBridge { currentInputConnection?.let { InputConnectionPort(it) } }

    private var panel: KeyboardPanel? = null
    private val keyboard: KeyboardView? get() = panel?.keyboard
    private val strip: SuggestionStripView? get() = panel?.strip
    private lateinit var custom: KeyboardCustomizer
    private lateinit var tools: ToolsController

    private var swipeDecoder: SwipeDecoder? = null
    private val swipeLock = Any()
    @Volatile private var swipeReady = false

    private var flickLanguage = true

    override fun onCreate() {
        val t0 = SystemClock.elapsedRealtime()
        super.onCreate()
        AssetBlobs.ensureInstalled(this)
        prefs = ImePrefs.of(this)
        val lm = UserLangModel(File(filesDir, Keys.USERLM_FILE), main)
        session = TypingSession(lm)
        session.autoCorrectRejected = com.customkey.vietime.engine.AutoCorrect.Rejected.decode(prefs.getString(Keys.AUTO_CORRECT_REJECTED, null))
        session.onRejectedChanged = { prefs.edit().putString(Keys.AUTO_CORRECT_REJECTED, session.autoCorrectRejected.encode()).apply() }
        lm.onReady = { refreshBar() }
        worker.post { session.seedLanguageModel() }
        custom = KeyboardCustomizer(this) { applyCustomization() }
        custom.reloadAll()
        tools = ToolsController(this, proxy, session, { panel }, handler, worker, { b -> edit(b) }, { refreshBar() }) { uri, mime ->
            pasteImage(uri, mime)
        }
        tools.start()
        prefs.registerOnSharedPreferenceChangeListener(this)
        readPrefs()
        TxaLogger.crumb("Bàn phím khởi động ${SystemClock.elapsedRealtime() - t0} ms")
    }

    private fun pasteImage(uri: android.net.Uri, mimeType: String): Boolean {
        val ic = currentInputConnection ?: return false
        val ei = currentInputEditorInfo ?: return false
        return try {
            val contentInfo = androidx.core.view.inputmethod.InputContentInfoCompat(
                uri,
                android.content.ClipDescription("Image", arrayOf(mimeType)),
                null
            )
            val flags = androidx.core.view.inputmethod.InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
            androidx.core.view.inputmethod.InputConnectionCompat.commitContent(ic, ei, contentInfo, flags, null)
        } catch (e: Exception) {
            false
        }
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        runCatching { session.lm.close() }
        custom.release()
        tools.stop()
        worker.looper.quitSafely()
        super.onDestroy()
    }

    private fun readPrefs() {
        flickLanguage = prefs.getBoolean(ImePrefs.SPACE_FLICK_LANGUAGE, true)
        session.autoSpaceAfterPunct = prefs.getBoolean(Keys.AUTO_SPACE_AFTER_PUNCT, false)
        session.swipeEnglishEnabled = prefs.getBoolean(Keys.SWIPE_ENGLISH, true)
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        if (!custom.onPrefChanged(key)) readPrefs()
        if (key in Keys.ENGINE_KEYS) {
            val s = EngineSettings.load { prefs.all[it] }
            session.applySettings(s)
            refreshBar()
        }
    }

    private fun applyCustomization() { panel?.let { custom.applyTo(it, custom.isWide(this)) } }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyCustomization()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val p = KeyboardPanel(this)
        p.keyboard.listener = this
        p.keyboard.letterPrior = ::smartTouchPrior
        p.strip.listener = this
        p.tools.listener = tools
        p.emoji.listener = object : EmojiPaneView.Listener {
            override fun onEmojiPicked(emoji: String) {
                edit { session.commitBeforeEnter(proxy) }
                proxy.insertText(emoji)
                custom.feedback(KeyboardView.FeedbackKind.KEY)
            }
            override fun onBackspace() {
                edit { session.backspace(proxy) }
                custom.feedback(KeyboardView.FeedbackKind.DELETE)
            }
            override fun onClose() {
                panel?.showEmoji(false)
            }
        }
        panel = p
        applyCustomization()
        return p
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val cfg = FieldClassifier.classify(info)
        // Chỉ ghi LOẠI ô + app chủ — không bao giờ ghi nội dung ô.
        TxaLogger.crumb("Gõ vào ${info?.packageName ?: "?"} · ô ${cfg.kind}${if (cfg.isSecure) " (mật khẩu)" else ""}${if (cfg.otp) " (OTP)" else ""}")
        proxy.startInput(cfg, info?.initialSelStart ?: -1, info?.initialSelEnd ?: -1)
        val settings = EngineSettings.load { key -> prefs.all[key] }
        session.startInput(settings, cfg.toTraits())
        if (prefs.getBoolean(ImePrefs.ENGLISH_MODE, false) != session.englishMode && !cfg.passthrough)
            proxy.edit { session.setEnglish(prefs.getBoolean(ImePrefs.ENGLISH_MODE, false), proxy) }
        keyboard?.apply {
            cancelAllTouches()
            fieldKind = cfg.kind
            enterAction = cfg.enterAction
            plane = when (cfg.kind) {
                FieldKind.NUMBER, FieldKind.DATETIME -> KeyboardPlane.NUMBER
                FieldKind.PHONE -> KeyboardPlane.PHONE
                else -> KeyboardPlane.LETTERS
            }
            spaceLabel = spaceLabelFor()
            swipeTyping = prefs.getBoolean(ImePrefs.SWIPE_TYPING, true) && !cfg.passthrough && !cfg.isSecure
        }
        applyCustomization()
        panel?.showTools(false)
        strip?.showToolsButton = !cfg.isSecure && !cfg.rawKeys
        strip?.showUndo(false)
        strip?.showMessage(null)
        applyAutoShift()
        refreshBar()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        keyboard?.cancelAllTouches()
        session.finishInput()
        TxaLogger.d(TAG, "finishInputView")
        handler.removeCallbacks(refreshRun)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                                   candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (!proxy.tracker.onUpdate(newSelStart, newSelEnd)) {
            session.externalSelectionChange()
            strip?.showUndo(false)
            applyAutoShift()
            refreshBar()
        }
    }

    private fun spaceLabelFor(): String = when {
        proxy.config.passthrough -> ""
        session.englishMode -> "English"
        else -> "Tiếng Việt"
    }

    // MARK: sửa chữ

    private fun edit(block: () -> Unit) {
        if (!proxy.begin()) return
        try { block() } finally { proxy.end() }
        if (proxy.takeFailure()) session.externalSelectionChange()
    }

    private fun afterKey() {
        applyAutoShift()
        scheduleRefresh()
    }

    private fun applyAutoShift() {
        val k = keyboard ?: return
        if (k.plane != KeyboardPlane.LETTERS) return
        session.autoShift(proxy)?.let { k.setAutoShift(it) }
    }

    private val refreshRun = Runnable { refreshBar() }
    private fun scheduleRefresh() { handler.removeCallbacks(refreshRun); handler.postDelayed(refreshRun, 30) }

    private fun refreshBar() {
        val s = strip ?: return
        val list = TxaLogger.guard(TAG, "gợi ý", emptyList()) { session.suggestions(proxy) }
        val special = TxaLogger.guard(TAG, "chip công cụ", null) { tools.specialChip() }
        s.show(if (special != null) (listOf(special) + list).take(3) else list)
    }

    // MARK: KeyboardView.Listener

    override fun onLetterTouch(dx: Float, dy: Float) = session.noteLetterTouch(dx, dy)

    /** Prior chạm thông minh: chỉ ô chữ thường, Telex, khi trie đã nạp nền (không chặn phím). */
    private fun smartTouchPrior(): ((Char) -> Float?)? {
        if (!prefs.getBoolean(Keys.SMART_TOUCH, true) || session.englishMode) return null
        val f = proxy.config
        if (f.isSecure || f.passthrough || f.kind == FieldKind.URL) return null
        val kp = com.customkey.vietime.engine.TelexKeyPrior.sharedIfReady
            ?: run { worker.post { runCatching { com.customkey.vietime.engine.TelexKeyPrior.warmUp() } }; return null }
        return kp.forTyping(session.rawWord, session.vniMode)
    }

    override fun onLetter(ch: Char) { edit { session.letter(ch, proxy) }; strip?.showUndo(false); afterKey() }

    override fun onText(text: String) {
        edit { session.text(text, proxy) }
        strip?.showUndo(false)
        if (text.any { it in ".,!?;:" }) tools.scheduleTones()
        afterKey()
    }

    override fun onSpace() {
        edit { session.space(proxy) }
        strip?.showUndo(false)
        // Đang ở plane ký hiệu: dấu cách đưa về bàn chữ (như iOS).
        keyboard?.let { if (it.plane == KeyboardPlane.SYMBOLS || it.plane == KeyboardPlane.SYMBOLS2) it.plane = KeyboardPlane.LETTERS }
        tools.scheduleTones()
        afterKey()
    }

    override fun onBackspace() { edit { session.backspace(proxy) }; strip?.showUndo(false); afterKey() }

    override fun onEnter() {
        edit { session.commitBeforeEnter(proxy); proxy.enter() }
        afterKey()
    }

    override fun onGlobe(longPress: Boolean) {
        val imm = getSystemService(InputMethodManager::class.java)
        if (longPress) imm.showInputMethodPicker() else switchToNextInputMethod(false)
    }

    override fun onEmoji() {
        panel?.showEmoji(true)
    }

    override fun onFeedback(kind: KeyboardView.FeedbackKind) = custom.feedback(kind)

    override fun onTrackpadStart() {
        edit { session.commitBeforeEnter(proxy) }
        session.externalSelectionChange()
        strip?.showMessage("◀ ▶  Di chuyển con trỏ")
    }

    override fun onTrackpadMove(dx: Int, dy: Int) { edit { proxy.moveCursor(dx, dy) } }

    override fun onTrackpadEnd() {
        strip?.showMessage(null)
        session.externalSelectionChange()
        applyAutoShift(); refreshBar()
    }

    override fun onLanguageFlick() {
        if (!flickLanguage || proxy.config.passthrough) { onSpace(); return }
        var english = false
        edit { english = session.toggleLanguage(proxy) }
        prefs.edit().putBoolean(ImePrefs.ENGLISH_MODE, english).apply()
        keyboard?.spaceLabel = spaceLabelFor()
        strip?.showMessage(if (english) "English" else "Tiếng Việt")
        handler.postDelayed({ strip?.showMessage(null); refreshBar() }, 700)
    }

    override fun onDeleteWordsPreview(words: Int) {
        val s = strip ?: return
        if (words <= 0) { s.showMessage(null); return }
        val t = session.wordsBefore(proxy, words).trimEnd()
        s.showMessage(if (t.isEmpty()) null else "Xoá: $t")
    }

    override fun onDeleteWords(words: Int) {
        var deleted = ""
        edit { deleted = session.deleteWords(proxy, words) }
        strip?.showMessage(null)
        strip?.showUndo(deleted.isNotEmpty())
        afterKey()
    }

    override fun onSwipeLayout(layout: SwipeLayout) {
        val dec = swipeDecoder ?: SwipeDecoder().also { swipeDecoder = it }
        swipeReady = false
        worker.post {
            synchronized(swipeLock) { dec.setLayout(layout); dec.prepare() }
            SyllableLM.shared
            if (session.swipeEnglishEnabled) runCatching { com.customkey.vietime.engine.SwipeEnglish.lexicon }
            swipeReady = true
        }
    }

    override fun onSwipeTypingStart(): Boolean =
        swipeReady && !proxy.config.passthrough && !proxy.config.isSecure && keyboard?.plane == KeyboardPlane.LETTERS

    override fun onSwipeTypingEnd(path: SwipePath, case: SwipeSuggest.Case) {
        val dec = swipeDecoder ?: return
        val ctx = session.swipeContext()
        val cands = TxaLogger.guard(TAG, "giải mã nét vuốt (${path.count} điểm)", emptyList()) {
            synchronized(swipeLock) { dec.decode(path, SwipeSuggest.TOP_K, ctx.folded, ctx.english, if (ctx.english != null) ctx.englishWord else null) }
        }
        val choice = session.resolveSwipe(cands, ctx, case)
        if (choice != null) edit { session.commitSwipe(choice, proxy) }
        strip?.showUndo(false)
        applyAutoShift()
        refreshBar()
    }

    // MARK: SuggestionStripView.Listener

    override fun onToolsButton() = tools.toggle()

    override fun onSuggestion(s: Suggestion) {
        if (s.special) { tools.accept(s); onFeedback(KeyboardView.FeedbackKind.KEY); afterKey(); return }
        edit { session.accept(s, proxy) }
        onFeedback(KeyboardView.FeedbackKind.KEY)
        afterKey()
    }

    override fun onUndoDelete() {
        edit { session.undoDelete(proxy) }
        strip?.showUndo(false)
        afterKey()
    }

    private companion object { const val TAG = "CustomIme" }
}
