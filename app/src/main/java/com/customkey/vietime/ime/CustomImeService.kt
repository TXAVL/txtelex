package com.customkey.vietime.ime

import android.content.Context
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
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
import com.customkey.vietime.ui.keyboard.KeyboardPlane
import com.customkey.vietime.ui.keyboard.KeyboardStyle
import com.customkey.vietime.ui.keyboard.KeyboardView
import com.customkey.vietime.ui.keyboard.LayoutOptions
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

    private var keyboard: KeyboardView? = null
    private var strip: SuggestionStripView? = null

    private var swipeDecoder: SwipeDecoder? = null
    private val swipeLock = Any()
    @Volatile private var swipeReady = false

    private var soundOn = true
    private var hapticOn = true
    private var flickLanguage = true

    override fun onCreate() {
        val t0 = SystemClock.elapsedRealtime()
        super.onCreate()
        AssetBlobs.ensureInstalled(this)
        prefs = ImePrefs.of(this)
        val lm = UserLangModel(File(filesDir, Keys.USERLM_FILE), main)
        session = TypingSession(lm)
        lm.onReady = { refreshBar() }
        worker.post { session.seedLanguageModel() }
        prefs.registerOnSharedPreferenceChangeListener(this)
        readPrefs()
        Log.d(TAG, "onCreate ${SystemClock.elapsedRealtime() - t0} ms")
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        runCatching { session.lm.close() }
        worker.looper.quitSafely()
        super.onDestroy()
    }

    private fun readPrefs() {
        soundOn = prefs.getBoolean(ImePrefs.KEY_SOUND, true)
        hapticOn = prefs.getBoolean(ImePrefs.HAPTIC, true)
        flickLanguage = prefs.getBoolean(ImePrefs.SPACE_FLICK_LANGUAGE, true)
        keyboard?.longPressDelayMs = prefs.getInt(ImePrefs.LONG_PRESS_MS, 300).toLong()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) { readPrefs() }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val style = KeyboardStyle.neon
        val s = SuggestionStripView(this).also { it.listener = this; it.style = style }
        val k = KeyboardView(this).also {
            it.listener = this; it.style = style
            it.longPressDelayMs = prefs.getInt(ImePrefs.LONG_PRESS_MS, 300).toLong()
        }
        strip = s; keyboard = k
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(style.background)
            addView(s, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(k, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val cfg = FieldClassifier.classify(info)
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
            options = LayoutOptions(numberRow = prefs.getBoolean(Keys.NUMBER_ROW, false))
            spaceLabel = spaceLabelFor()
            swipeTyping = prefs.getBoolean(ImePrefs.SWIPE_TYPING, true) && !cfg.passthrough && !cfg.isSecure
        }
        strip?.showUndo(false)
        strip?.showMessage(null)
        applyAutoShift()
        refreshBar()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        keyboard?.cancelAllTouches()
        session.finishInput()
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

    private inline fun edit(block: () -> Unit) {
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
        val list = runCatching { session.suggestions(proxy) }.getOrElse { emptyList() }
        s.show(list)
    }

    // MARK: KeyboardView.Listener

    override fun onLetter(ch: Char) { edit { session.letter(ch, proxy) }; strip?.showUndo(false); afterKey() }

    override fun onText(text: String) {
        edit { session.text(text, proxy) }
        strip?.showUndo(false)
        afterKey()
    }

    override fun onSpace() {
        edit { session.space(proxy) }
        strip?.showUndo(false)
        // Đang ở plane ký hiệu: dấu cách đưa về bàn chữ (như iOS).
        keyboard?.let { if (it.plane == KeyboardPlane.SYMBOLS || it.plane == KeyboardPlane.SYMBOLS2) it.plane = KeyboardPlane.LETTERS }
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

    override fun onFeedback(kind: KeyboardView.FeedbackKind) {
        val v = keyboard ?: return
        if (hapticOn) {
            val c = when (kind) {
                KeyboardView.FeedbackKind.TRACKPAD_TICK -> HapticFeedbackConstants.CLOCK_TICK
                else -> HapticFeedbackConstants.KEYBOARD_TAP
            }
            v.performHapticFeedback(c)
        }
        if (soundOn && kind != KeyboardView.FeedbackKind.TRACKPAD_TICK) {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val fx = when (kind) {
                KeyboardView.FeedbackKind.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                KeyboardView.FeedbackKind.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyboardView.FeedbackKind.MODIFIER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            am.playSoundEffect(fx, -1f)
        }
    }

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
            swipeReady = true
        }
    }

    override fun onSwipeTypingStart(): Boolean =
        swipeReady && !proxy.config.passthrough && !proxy.config.isSecure && keyboard?.plane == KeyboardPlane.LETTERS

    override fun onSwipeTypingEnd(path: SwipePath, case: SwipeSuggest.Case) {
        val dec = swipeDecoder ?: return
        val ctx = session.swipeContext()
        val cands = synchronized(swipeLock) { dec.decode(path, SwipeSuggest.TOP_K, ctx.folded, ctx.english, null) }
        val choice = session.resolveSwipe(cands, ctx, case)
        if (choice != null) edit { session.commitSwipe(choice, proxy) }
        strip?.showUndo(false)
        applyAutoShift()
        refreshBar()
    }

    // MARK: SuggestionStripView.Listener

    override fun onSuggestion(s: Suggestion) {
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
