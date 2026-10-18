package com.customkey.vietime.ime

import com.customkey.vietime.engine.EngineBridge
import com.customkey.vietime.engine.EngineSettings
import com.customkey.vietime.engine.FieldTraits
import com.customkey.vietime.engine.SeedData
import com.customkey.vietime.engine.SuggestRank
import com.customkey.vietime.engine.SuggestionFill
import com.customkey.vietime.engine.SwipeCandidate
import com.customkey.vietime.engine.SwipeChoice
import com.customkey.vietime.engine.SwipeSuggest
import com.customkey.vietime.engine.TextProxy
import com.customkey.vietime.engine.TypingHeuristics
import com.customkey.vietime.engine.TypingTimings
import com.customkey.vietime.engine.UserLangModel
import com.customkey.vietime.engine.VNSuggest

/** Một ô trên thanh gợi ý. [payload] = chữ chèn khi khác chữ hiển thị (chip dán, thêm dấu…). */
data class Suggestion(val text: String, val kind: Kind, val payload: String? = null) {
    /** Chip đặc biệt (phép tính / thêm dấu / dán nhanh) — tô nổi, IME tự xử lý khi chạm. */
    val special: Boolean get() = kind == Kind.MATH || kind == Kind.TONES || kind == Kind.PASTE

    enum class Kind {
        /** Kết quả phép tính sau "=". */
        MATH,
        /** Thêm dấu cho câu không dấu trước con trỏ. */
        TONES,
        /** Dán nhanh nội dung vừa copy (OTP, link…). */
        PASTE,
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
        lastWord = null; lastWord2 = null
        clearSwipe(); deletedForUndo = null
        lastInsertWasSpace = false
        generation++
    }

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
        bridge.letter(ch, p)
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
            val words = (lm.manualCompletions(typed, 1) + ranked)
                .map { matchCase(it, typed) }.filter { it != predicted }.distinct()
            return (listOf(Suggestion(predicted, Suggestion.Kind.LITERAL)) +
                words.take(max - 1).map { Suggestion(it, Suggestion.Kind.COMPLETION) })
        }
        val prev = lastWord ?: return emptyList()
        if (bridge.englishMode) return emptyList()
        val before = p.contextBeforeInput() ?: return emptyList()
        if (before.isEmpty() || !before.last().isWhitespace()) return emptyList()
        val personal = lm.nextWords(prev, lastWord2, 6)
        val next = SuggestRank.nextFill(prev, lastWord2, personal,
            { w -> lastWord2?.let { lm.trigramCount(it, prev, w) } ?: 0 }, need = max)
        val padded = SuggestionFill.pad(next, emptyList(), max)
        return padded.take(max).map { Suggestion(it, Suggestion.Kind.NEXT) }
    }

    /** Chạm một ô gợi ý: thay từ đang soạn / từ vừa vuốt bằng [s], thêm dấu cách. */
    fun accept(s: Suggestion, p: TextProxy) {
        deletedForUndo = null
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
                    if (p.confirmTail(typed)) { p.deleteCodePoints(cp(typed)); p.insertText(s.text) }
                    bridge.reset()
                    commitAndLearn(s.text, accepted = true)
                    p.insertText(" ")
                }
            }
            else -> {
                p.insertText(s.text + " ")
                commitAndLearn(s.text, accepted = true)
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
