package com.customkey.vietime.ime

import com.customkey.vietime.engine.AdjacentKeyFixer
import com.customkey.vietime.engine.AutoCorrect
import com.customkey.vietime.engine.EngineBridge
import com.customkey.vietime.engine.EngineSettings
import com.customkey.vietime.engine.FieldTraits
import com.customkey.vietime.engine.SeedData
import com.customkey.vietime.engine.SuggestRank
import com.customkey.vietime.engine.SuggestionFill
import com.customkey.vietime.engine.SwipeCandidate
import com.customkey.vietime.engine.SwipeChoice
import com.customkey.vietime.engine.SwipeEnglish
import com.customkey.vietime.engine.SwipeSuggest
import com.customkey.vietime.engine.TextProxy
import com.customkey.vietime.engine.TypingHeuristics
import com.customkey.vietime.engine.TypingTimings
import com.customkey.vietime.engine.UserLangModel
import com.customkey.vietime.engine.VNSuggest

/** Một ô trên thanh gợi ý. [payload] = chữ chèn khi khác chữ hiển thị (chip dán, thêm dấu…). */
data class Suggestion(val text: String, val kind: Kind, val payload: String? = null) {
    /** Chip đặc biệt (phép tính / thêm dấu / dán nhanh text & ảnh) — tô nổi, IME tự xử lý khi chạm. */
    val special: Boolean get() = kind == Kind.MATH || kind == Kind.TONES || kind == Kind.PASTE || kind == Kind.PASTE_IMAGE

    enum class Kind {
        /** Kết quả phép tính sau "=". */
        MATH,
        /** Thêm dấu cho câu không dấu trước con trỏ. */
        TONES,
        /** Dán nhanh nội dung text vừa copy (OTP, link, STK…). */
        PASTE,
        /** Dán nhanh hình ảnh vừa copy từ clipboard/ảnh chụp màn hình. */
        PASTE_IMAGE,
        /** "↩ chữ gốc" — hoàn tác lần tự sửa vừa rồi. */
        UNDO_CORRECTION,
        /** Đúng chữ đang gõ (giữ nguyên). */
        LITERAL,
        /** Hoàn tất từ đang gõ. */
        COMPLETION,
        /** Từ kế tiếp sau dấu cách. */
        NEXT,
        /** Phương án khác của từ vừa vuốt. */
        SWIPE_ALT,
    }
}

/**
 * Bộ điều phối gõ phím — thuần Kotlin (không Android) để test trên JVM. Nối [EngineBridge]
 * (Telex/VNI, khôi phục tiếng Anh, gõ tắt, sửa dấu từ đã gõ) với học từ ([UserLangModel]),
 * gợi ý, gõ vuốt, xoá theo từ + hoàn tác và chế độ VI/EN.
 *
 * Mọi hàm sửa chữ nhận [TextProxy] đã nằm trong batch edit của caller.
 */
class TypingSession(
    val lm: UserLangModel = UserLangModel(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var settings = EngineSettings(); private set
    var traits = FieldTraits(); private set
    private var bridge = EngineBridge(settings)

    /** Từ đã chốt gần nhất (ngữ cảnh gợi ý + học bigram/trigram). */
    var lastWord: String? = null; private set
    var lastWord2: String? = null; private set

    private var lastSpaceAt = 0L
    private var lastInsertWasSpace = false

    /** Từ vừa vuốt còn mở + các phương án (thanh gợi ý hiện để đổi). */
    private var swipeWord: String? = null
    private var swipeAlts: List<String> = emptyList()
    private var swipeEnglish = false

    /** Chữ vừa xoá bằng vuốt ⌫ — nút Hoàn tác chèn lại. */
    var deletedForUndo: String? = null; private set

    /** Tăng mỗi lần trạng thái gõ đổi — IME bỏ kết quả gợi ý của lượt cũ. */
    var generation = 0; private set

    init {
        lm.isKnownWord = { VNSuggest.contains(it) }
    }

    /** Gọi sau khi KeyboardData đã install (seed cần asset). */
    fun seedLanguageModel() = runCatching { lm.seedIfEmpty(SeedData::load) }

    val englishMode: Boolean get() = bridge.englishMode
    val isComposing: Boolean get() = bridge.isComposing
    val composingWord: String get() = bridge.composedWord

    fun startInput(settings: EngineSettings, traits: FieldTraits) {
        val english = bridge.englishMode
        this.settings = settings
        this.traits = traits
        bridge = EngineBridge(settings).also {
            it.passthrough = traits.passthrough
            it.shortcutsAllowed = !traits.urlField && !traits.passthrough
            if (english) it.setEnglish(true, NoText)
        }
        armAutoCorrect()
        lastWord = null; lastWord2 = null
        clearSwipe(); deletedForUndo = null
        lastInsertWasSpace = false
        generation++
    }

    /** Cập nhật cài đặt bộ gõ tức thời khi người dùng đổi trong Cài đặt mà bàn phím đang mở. */
    fun applySettings(s: EngineSettings) {
        settings = s
        bridge.applySettings(s)
        armAutoCorrect()
        generation++
    }

    // MARK: tự sửa lỗi chạm trượt

    /** Từ người dùng từng hoàn tác tự sửa — không bao giờ sửa lại (IME nạp/lưu qua [onRejectedChanged]). */
    var autoCorrectRejected = AutoCorrect.Rejected()
    var onRejectedChanged: (() -> Unit)? = null
    private val wordTouches = ArrayList<AutoCorrect.Touch>(12)
    private var wordTouchesOk = false
    private var pendingTouch: AutoCorrect.Touch? = null
    /** Tự sửa đang bật ở ô này (IME chỉ cần gửi điểm chạm khi true). */
    var autoCorrectOn = false; private set

    private fun armAutoCorrect() {
        autoCorrectOn = settings.autoCorrect && !settings.vniMode && AutoCorrect.fieldAllows(traits) && !traits.appNoSuggestions
        wordTouches.clear(); wordTouchesOk = false
        bridge.autoCorrector = if (autoCorrectOn) ::autoCorrection else null
    }

    /** IME gọi ngay trước [letter]: điểm chạm lệch so với tâm phím (theo cỡ phím). */
    fun noteLetterTouch(dx: Float, dy: Float) { if (autoCorrectOn) pendingTouch = AutoCorrect.Touch(dx, dy) }

    private fun noteTouch(fresh: Boolean) {
        val t = pendingTouch; pendingTouch = null
        if (!autoCorrectOn) return
        if (fresh) { wordTouches.clear(); wordTouchesOk = true }
        if (t == null) wordTouchesOk = false else if (wordTouchesOk) wordTouches.add(t)
        if (wordTouches.size != bridge.rawWord.length) wordTouchesOk = false
    }

    /** Chỉ sửa khi có đủ điểm chạm cho đúng các phím của từ (từ nạp lại / vuốt ⇒ không sửa). */
    private fun autoCorrection(raw: String): String? {
        if (!wordTouchesOk || wordTouches.size != raw.length) return null
        val b = bridge
        return AutoCorrect.correction(raw, wordTouches, compose = { b.composeTrial(it) },
            frequency = { VNSuggest.frequency(it) },
            hasCompletion = { VNSuggest.matches(it, poolLimit = 1).isNotEmpty() },
            isKnown = { w -> w in autoCorrectRejected || lm.isUserWord(w) || AutoCorrect.isEnglish(w) })
    }

    /** Vừa trả lại chữ gốc (⌫ / chip ↩): nhớ để không sửa lại. */
    private fun takeRevert() {
        val orig = bridge.revertedAutoCorrect ?: return
        bridge.revertedAutoCorrect = null
        if (autoCorrectRejected.add(orig)) onRejectedChanged?.invoke()
        lastWord = null; lastWord2 = null
    }

    /** (chữ gốc, chữ đã sửa) nếu vừa tự sửa và chưa gõ gì thêm. */
    val autoCorrectUndo: Pair<String, String>? get() = bridge.autoCorrectUndo

    fun finishInput() { bridge.reset(); clearSwipe(); runCatching { lm.save() } }

    /** Con trỏ dời từ bên ngoài (chạm vào ô, app tự sửa chữ): quên từ đang soạn. */
    fun externalSelectionChange() {
        bridge.reset(); bridge.forgetLastCommit()
        lastWord = null; lastWord2 = null
        clearSwipe(); lastInsertWasSpace = false
        generation++
    }

    // MARK: phím

    fun letter(ch: Char, p: TextProxy) {
        settleSwipeIfEnglish(p)
        clearSwipe(); deletedForUndo = null
        val fresh = !bridge.isComposing
        bridge.letter(ch, p)
        noteTouch(fresh)
        lastInsertWasSpace = false
        generation++
    }

    /** Số / ký hiệu / dấu câu. VNI: số trong lúc soạn từ mang dấu. */
    fun text(s: String, p: TextProxy) {
        deletedForUndo = null
        val d = s.singleOrNull()
        if (swipeWord == null && d != null && d in '0'..'9' && bridge.vniDigit(d, p)) {
            generation++; return
        }
        settleSwipeIfEnglish(p)
        clearSwipe()
        commitAndLearn(bridge.boundary(s, p))
        // Hết câu ⇒ không nối ngữ cảnh bigram qua dấu câu.
        if (s.any { it in ".!?\n" }) { lastWord = null; lastWord2 = null }
        lastInsertWasSpace = false
        generation++
    }

    fun space(p: TextProxy) {
        deletedForUndo = null
        val now = clock()
        val ctx = p.contextBeforeInput()
        val quick = lastInsertWasSpace && now - lastSpaceAt < (TypingTimings.DOUBLE_SPACE_S * 1000).toLong()
        if (quick && !bridge.isComposing && ctx != null && !traits.passthrough &&
            TypingHeuristics.doubleSpaceMakesPeriod(ctx, true)) {
            p.deleteCodePoints(1)
            p.insertText(". ")
            lastWord = null; lastWord2 = null
            lastInsertWasSpace = false
            generation++
            return
        }
        settleSwipeIfEnglish(p)
        clearSwipe()
        commitAndLearn(bridge.boundary(" ", p))
        lastInsertWasSpace = true
        lastSpaceAt = now
        generation++
    }

    /** Enter: chốt từ (không chèn gì) — IME tự gửi action / "\n" sau đó. */
    fun commitBeforeEnter(p: TextProxy) {
        settleSwipeIfEnglish(p)
        clearSwipe()
        commitAndLearn(bridge.boundary("", p, expand = true))
        lastWord = null; lastWord2 = null
        lastInsertWasSpace = false
        generation++
    }

    fun backspace(p: TextProxy) {
        deletedForUndo = null
        clearSwipe()
        // EngineBridge tự xử lý cả hai trường hợp: đang soạn (diff) và không soạn (deleteBackward,
        // hoặc mở lại từ vừa chốt để sửa dấu).
        bridge.backspace(p)
        takeRevert()
        wordTouchesOk = false
        lastInsertWasSpace = false
        generation++
    }

    /** Vuốt phím cách: đổi VI ↔ EN. Trả true nếu đang ở EN sau khi đổi. */
    fun toggleLanguage(p: TextProxy): Boolean {
        val committed = bridge.setEnglish(!bridge.englishMode, p)
        commitAndLearn(committed)
        clearSwipe()
        generation++
        return bridge.englishMode
    }

    fun setEnglish(on: Boolean, p: TextProxy) { if (on != bridge.englishMode) toggleLanguage(p) }

    // MARK: viết hoa tự động

    /** Shift tự động cho phím kế (null = ô không tự viết hoa). */
    fun autoShift(p: TextProxy): Boolean? {
        if (traits.capCharacters) return true
        if (!traits.capSentences && !traits.capWords) return null
        if (bridge.isComposing) return false
        val ctx = p.contextBeforeInput() ?: return traits.initialCaps
        if (traits.capWords) return ctx.isEmpty() || ctx.last().isWhitespace()
        val t = ctx.trimEnd(' ', ' ')
        if (t.isEmpty()) return true
        if (t.length < ctx.length && t.last() in ".!?…") return true
        return t.last() == '\n'
    }

    // MARK: gợi ý

    /** Thanh gợi ý cho trạng thái hiện tại (đồng bộ, vài trăm µs). Rỗng = không hiện gì. */
    fun suggestions(p: TextProxy, max: Int = 3): List<Suggestion> {
        if (!traits.suggestionsAllowed || traits.passthrough || traits.isSecure) return emptyList()
        // Vừa tự sửa ⇒ ô đầu là "↩ chữ gốc" (chạm = hoàn tác, như ⌫ ngay sau dấu cách).
        val undo = if (!bridge.isComposing && swipeWord == null) bridge.autoCorrectUndo?.let {
            Suggestion("↩ ${it.first}", Suggestion.Kind.UNDO_CORRECTION, it.first)
        } else null
        val base = baseSuggestions(p, max)
        return if (undo != null) (listOf(undo) + base).take(max) else base
    }

    private fun baseSuggestions(p: TextProxy, max: Int): List<Suggestion> {
        swipeWord?.let { w ->
            return (listOf(Suggestion(w, Suggestion.Kind.LITERAL)) +
                swipeAlts.filter { it != w }.map { Suggestion(it, Suggestion.Kind.SWIPE_ALT) }).take(max)
        }
        if (bridge.isComposing) {
            val typed = bridge.composedWord
            if (bridge.englishMode) return listOf(Suggestion(typed, Suggestion.Kind.LITERAL))
            val pool = VNSuggest.matches(typed, poolLimit = 24, excluding = typed.lowercase())
            val pmi = SuggestRank.inlinePmi(pool, lastWord, prev2 = lastWord2)
            val ctx = lastWord?.let { lm.nextWords(it, lastWord2, 12).toSet() } ?: emptySet()
            val ranked = SuggestRank.rankInline(pool, pmi, typed.codePointCount(0, typed.length), lm::count, ctx)
            val predicted = bridge.predictedCommit.ifEmpty { typed }
            // Không có từ nào khớp ⇒ đề xuất bản sửa lỗi chạm trượt / gõ nhầm phím dấu (ô giữa).
            val fix = if (pool.isEmpty() && settings.autoFixAdjacent && !settings.vniMode) {
                val raw = bridge.rawWord
                runCatching { AdjacentKeyFixer.lexiconCorrection(raw, bridge) ?: AdjacentKeyFixer.lexiconToneSlip(raw, bridge) }.getOrNull()
            } else null
            val words = (listOfNotNull(fix) + lm.manualCompletions(typed, 1) + ranked)
                .map { matchCase(it, typed) }.filter { it != predicted }.distinct().toMutableList()

            // 1. Phím tắt gõ tắt (e.g. ko -> không, dc -> được)
            val preview = bridge.shortcutPreview
            if (preview != null && !preview.contains('\n') && preview != predicted) {
                words.add(0, preview)
            }

            // 2. Gợi ý emoji thông minh theo nghĩa từ
            var emojis: List<String> = emptyList()
            val cLow = typed.lowercase()
            lastWord?.let { emojis = runCatching { com.customkey.vietime.engine.EmojiSuggest.emojis(it.lowercase() + " " + cLow) }.getOrDefault(emptyList()) }
            if (emojis.isEmpty()) emojis = runCatching { com.customkey.vietime.engine.EmojiSuggest.emojis(predicted) }.getOrDefault(emptyList())
            if (emojis.isEmpty()) emojis = runCatching { com.customkey.vietime.engine.EmojiSuggest.emojis(typed) }.getOrDefault(emptyList())

            val result = mutableListOf(Suggestion(predicted, Suggestion.Kind.LITERAL))
            val wordSlots = if (emojis.isNotEmpty() && max >= 3) max - 2 else max - 1
            result.addAll(words.take(wordSlots).map { Suggestion(it, Suggestion.Kind.COMPLETION) })
            if (emojis.isNotEmpty() && max >= 3) {
                result.add(Suggestion(emojis.first(), Suggestion.Kind.COMPLETION))
            }
            return result
        }
        val before = p.contextBeforeInput()
        val (ctxPrev, ctxPrev2) = extractContextWords(before)
        val prev = lastWord ?: ctxPrev
        val prev2 = lastWord2 ?: ctxPrev2
        val isSentenceStart = isStartOfSentence(before)

        // 1. Nếu có từ ngữ cảnh trước con trỏ (sau dấu cách)
        if (prev != null && before != null && (before.isEmpty() || before.last().isWhitespace())) {
            val personal = if (bridge.englishMode) {
                lm.nextWords(prev, prev2, 12).filter { runCatching { SwipeEnglish.containsIfLoaded(it.lowercase()) }.getOrDefault(true) }
            } else {
                lm.nextWords(prev, prev2, 6)
            }
            val next = if (bridge.englishMode) {
                SuggestionFill.pad(personal, defaultEnglishNext(prev), max)
            } else {
                val filled = SuggestRank.nextFill(prev, prev2, personal,
                    { w -> prev2?.let { lm.trigramCount(it, prev, w) } ?: 0 }, need = max)
                SuggestionFill.pad(filled, lm.topWords(max + 6), max)
            }
            val formatted = next.map { w ->
                if (isSentenceStart) w.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } else w
            }
            val emojis = runCatching { com.customkey.vietime.engine.EmojiSuggest.emojis(prev) }.getOrDefault(emptyList())
            if (emojis.isNotEmpty() && max >= 3 && formatted.isNotEmpty()) {
                val res = formatted.take(max - 1).map { Suggestion(it, Suggestion.Kind.NEXT) }.toMutableList()
                res.add(Suggestion(emojis.first(), Suggestion.Kind.NEXT))
                return res
            }
            if (formatted.isNotEmpty()) {
                return formatted.take(max).map { Suggestion(it, Suggestion.Kind.NEXT) }
            }
        }

        // 2. Khi chưa gõ gì (ô rỗng, hoặc chưa có từ trước): Dải auto-complete từ mở đầu câu
        val topList = if (bridge.englishMode) {
            val fromLm = lm.topWords(max + 6).filter { runCatching { SwipeEnglish.containsIfLoaded(it.lowercase()) }.getOrDefault(true) }
            SuggestionFill.pad(fromLm, DEFAULT_EN_STARTERS, max)
        } else {
            val fromLm = lm.topWords(max + 6)
            SuggestionFill.pad(fromLm, DEFAULT_VI_STARTERS, max)
        }

        val formattedTop = topList.map { w ->
            if (isSentenceStart) w.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } else w
        }
        return formattedTop.take(max).map { Suggestion(it, Suggestion.Kind.NEXT) }
    }

    /** Chạm một ô gợi ý: thay từ đang soạn / từ vừa vuốt bằng [s], thêm dấu cách nếu là từ. */
    fun accept(s: Suggestion, p: TextProxy) {
        deletedForUndo = null
        if (s.kind == Suggestion.Kind.UNDO_CORRECTION) {
            if (bridge.revertAutoCorrect(p)) takeRevert()
            generation++
            return
        }
        when {
            swipeWord != null -> {
                val cur = swipeWord!!
                if (p.confirmTail(cur)) { p.deleteCodePoints(cp(cur)); p.insertText(s.text) }
                bridge.reset()
                clearSwipe()
                commitAndLearn(s.text, accepted = true)
                p.insertText(" ")
            }
            bridge.isComposing -> {
                val typed = bridge.composedWord
                if (s.kind == Suggestion.Kind.LITERAL) {
                    // Chốt như dấu cách (engine tự khôi phục tiếng Anh nếu cần).
                    commitAndLearn(bridge.boundary(" ", p, expand = false), accepted = true)
                } else {
                    val isWord = s.text.isNotEmpty() && Character.isLetter(s.text.codePointAt(0))
                    if (p.confirmTail(typed)) { p.deleteCodePoints(cp(typed)); p.insertText(s.text) }
                    bridge.reset()
                    if (isWord) {
                        commitAndLearn(s.text, accepted = true)
                        p.insertText(" ")
                    } else {
                        lastWord = null; lastWord2 = null
                    }
                }
            }
            else -> {
                val isWord = s.text.isNotEmpty() && Character.isLetter(s.text.codePointAt(0))
                p.insertText(if (isWord) s.text + " " else s.text)
                if (isWord) commitAndLearn(s.text, accepted = true) else { lastWord = null; lastWord2 = null }
            }
        }
        lastInsertWasSpace = true
        lastSpaceAt = clock()
        generation++
    }

    // MARK: gõ vuốt

    /** Ngữ cảnh cho decoder (từ trước + mô hình người dùng). */
    fun swipeContext(): SwipeSuggest.Context {
        val composing = bridge.isComposing
        val pending = if (composing) bridge.predictedCommit.takeIf { UserLangModel.learnable(it) } else null
        val p1 = if (composing) pending else lastWord
        val p2 = if (composing) (if (pending != null) lastWord else null) else lastWord2
        return SwipeSuggest.context(lm, p1, p2, null, properNouns = !traits.lowercaseSuggestions)
    }

    /** Chọn từ từ ứng viên decoder (chế độ EN chỉ giữ từ tiếng Anh). */
    fun resolveSwipe(cands: List<SwipeCandidate>, ctx: SwipeSuggest.Context, case: SwipeSuggest.Case): SwipeChoice? {
        val list = if (bridge.englishMode) SwipeSuggest.englishOnly(cands) else cands
        return SwipeSuggest.choose(list, ctx.word, case, ctx.lambdaFreq, ctx.proper)
    }

    /**
     * Nhấc tay khỏi nét vuốt: chốt từ đang soạn (kèm dấu cách), thêm dấu cách nếu liền trước
     * là chữ, chèn từ rồi seed engine ⇒ phím dấu Telex vẫn sửa được từ vừa vuốt.
     */
    fun commitSwipe(choice: SwipeChoice, p: TextProxy) {
        deletedForUndo = null
        val prevSwipe = swipeWord
        if (prevSwipe != null) {
            settleSwipe(prevSwipe)
            bridge.reset()
            bridge.boundary(" ", p, expand = false)
        } else if (bridge.isComposing) {
            commitAndLearn(bridge.boundary(" ", p, expand = false))
        } else if (SwipeSuggest.needsLeadingSpace(p.contextBeforeInput())) {
            bridge.boundary(" ", p, expand = false)
        }
        clearSwipe()
        p.insertText(choice.word)
        if (choice.english) bridge.adoptLiteral() else if (!bridge.adoptWord(choice.word)) bridge.adoptLiteral()
        swipeWord = choice.word
        swipeAlts = choice.alternatives
        swipeEnglish = choice.english
        lastInsertWasSpace = false
        generation++
    }

    private fun settleSwipe(w: String) { commitAndLearn(w) }

    /** Từ vuốt nguyên văn (EN) đang mở: phím kế chốt + học nó. */
    private fun settleSwipeIfEnglish(p: TextProxy) {
        val w = swipeWord ?: return
        if (swipeEnglish) { settleSwipe(w); bridge.reset() }
    }

    private fun clearSwipe() { swipeWord = null; swipeAlts = emptyList(); swipeEnglish = false }

    val openSwipeWord: String? get() = swipeWord

    // MARK: vuốt ⌫ xoá theo từ + hoàn tác

    /** Chữ sẽ bị xoá nếu xoá [words] từ trước con trỏ (xem trước khi đang vuốt). */
    fun wordsBefore(p: TextProxy, words: Int): String {
        val ctx = p.contextBeforeInput() ?: return ""
        return WordSpan.lastWords(ctx, words)
    }

    /** Xoá [words] từ trước con trỏ; lưu lại để [undoDelete]. Trả chữ đã xoá (rỗng = không xoá). */
    fun deleteWords(p: TextProxy, words: Int): String {
        if (words <= 0) return ""
        val target = wordsBefore(p, words)
        if (target.isEmpty()) return ""
        bridge.reset(); bridge.forgetLastCommit(); clearSwipe()
        p.deleteCodePoints(cp(target))
        deletedForUndo = target
        lastWord = null; lastWord2 = null
        lastInsertWasSpace = false
        generation++
        return target
    }

    fun undoDelete(p: TextProxy): Boolean {
        val t = deletedForUndo ?: return false
        deletedForUndo = null
        bridge.reset()
        p.insertText(t)
        generation++
        return true
    }

    // MARK: học từ

    private fun commitAndLearn(word: String, accepted: Boolean = false) {
        if (word.isEmpty()) return
        val parts = word.split(' ', '\n').filter { it.isNotEmpty() }
        if (parts.size > 1) { parts.forEach { commitAndLearn(it, accepted) }; return }
        val learn = !traits.noLearning && !traits.isSecure && traits.suggestionsAllowed && !traits.passthrough
        if (learn) lm.record(word, lastWord, lastWord2, if (accepted) 2 else 1)
        if (UserLangModel.learnable(word)) { lastWord2 = lastWord; lastWord = word }
        else { lastWord = null; lastWord2 = null }
    }

    companion object {
        fun cp(s: String) = s.codePointCount(0, s.length)

        /** Gợi ý theo kiểu chữ đang gõ: "Vie" → "Việt", "VIE" → "VIỆT". */
        fun matchCase(word: String, typed: String): String = when {
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercaseChar() }
            else -> word
        }

        private val DEFAULT_VI_STARTERS = listOf("Tôi", "Anh", "Em", "Dạ", "Xin", "Mình", "Chào", "Bạn", "Hôm nay", "Cảm ơn")
        private val DEFAULT_EN_STARTERS = listOf("I", "The", "You", "We", "Hello", "Thanks", "Please", "How", "Yes", "Good")

        private fun defaultEnglishNext(prev: String): List<String> = when (prev.lowercase()) {
            "i" -> listOf("am", "have", "will", "would", "think", "can")
            "you" -> listOf("are", "can", "have", "will", "know", "want")
            "the" -> listOf("same", "first", "best", "new", "way", "time")
            "we" -> listOf("are", "have", "will", "can", "need", "should")
            "hello" -> listOf("there", "everyone", "how", "friend")
            "thank", "thanks" -> listOf("you", "so", "a", "very")
            else -> listOf("and", "the", "to", "is", "for", "in")
        }

        private fun extractContextWords(before: String?): Pair<String?, String?> {
            if (before.isNullOrEmpty()) return null to null
            val trimmed = before.trimEnd()
            if (trimmed.isEmpty()) return null to null
            val tokens = trimmed.split(Regex("[\\s,;!?\\n]+")).filter { it.isNotEmpty() }
            val p1 = tokens.lastOrNull()?.filter { Character.isLetterOrDigit(it) }?.takeIf { it.isNotEmpty() }
            val p2 = if (tokens.size >= 2) tokens[tokens.size - 2].filter { Character.isLetterOrDigit(it) }.takeIf { it.isNotEmpty() } else null
            return p1 to p2
        }

        private fun isStartOfSentence(before: String?): Boolean {
            if (before.isNullOrBlank()) return true
            val trimmed = before.trimEnd()
            if (trimmed.isEmpty()) return true
            val lastChar = trimmed.last()
            return lastChar in ".!?\n"
        }
    }
}

/** Proxy rỗng — chỉ để đổi chế độ engine khi chưa có ô (không soạn ⇒ không ghi gì). */
private object NoText : TextProxy {
    override fun insertText(text: String) {}
    override fun deleteCodePoints(count: Int) {}
    override fun deleteBackward() {}
    override val isSecure: Boolean get() = false
}

/** Ranh giới từ cho xoá theo từ. */
object WordSpan {
    /**
     * Đuôi của [before] gồm [n] từ (kèm khoảng trắng/dấu câu theo sau mỗi từ), như Gboard:
     * "xin chào bạn " + n=1 → "bạn ", n=2 → "chào bạn ".
     */
    fun lastWords(before: String, n: Int): String {
        var i = before.length
        repeat(n) {
            // khoảng trắng (không gồm xuống dòng) + dấu câu đuôi
            while (i > 0 && before[i - 1] != '\n' && (before[i - 1].isWhitespace() || isPunct(before[i - 1]))) i--
            if (i == 0) return before
            if (before[i - 1] == '\n') { i--; return@repeat }   // mỗi dòng trống = một "từ"
            // thân từ: mọi ký tự không phải khoảng trắng / dấu câu (chữ có dấu, số, emoji)
            while (i > 0 && !before[i - 1].isWhitespace() && !isPunct(before[i - 1])) i--
        }
        return before.substring(i)
    }

    private fun isPunct(c: Char) = c in ".,;:!?…()[]{}\"“”«»"
}
