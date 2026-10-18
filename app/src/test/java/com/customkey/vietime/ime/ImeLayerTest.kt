package com.customkey.vietime.ime

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import com.customkey.vietime.engine.EngineSettings
import com.customkey.vietime.engine.TestAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val TEXT = InputType.TYPE_CLASS_TEXT

class FieldClassifierTest {
    @Test fun passwordsAreSecureAndNeverCompose() {
        for (v in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            val c = FieldClassifier.classify(TEXT or v, 0)
            assertTrue("variation $v", c.isSecure)
            assertTrue(c.passthrough)
            assertFalse(c.suggestionsAllowed)
            assertTrue(c.toTraits().isSecure && c.toTraits().noLearning)
        }
        assertTrue(FieldClassifier.classify(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0).isSecure)
    }

    @Test fun chatFakeVisiblePasswordIsNotSecure() {
        val c = FieldClassifier.classify(TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 0)
        assertFalse(c.isSecure)
        assertFalse(c.passthrough)
        assertFalse(c.suggestionsAllowed)   // app muốn tắt gợi ý
    }

    @Test fun browserAddressBar() {
        val c = FieldClassifier.classify(TEXT or InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_GO, "com.android.chrome")
        assertEquals(FieldKind.URL, c.kind)
        assertFalse("Telex vẫn gõ được để tìm kiếm tiếng Việt", c.passthrough)
        assertTrue(c.addressOrSearch)
        assertFalse(c.canReEdit)
        assertEquals(EnterAction.GO, c.enterAction)
        val t = c.toTraits()
        assertTrue(t.urlField && t.lowercaseSuggestions && !t.capSentences)
    }

    @Test fun numberEmailAsciiArePassthrough() {
        assertTrue(FieldClassifier.classify(InputType.TYPE_CLASS_NUMBER, 0).passthrough)
        assertTrue(FieldClassifier.classify(InputType.TYPE_CLASS_PHONE, 0).let { it.passthrough && it.kind == FieldKind.PHONE })
        assertTrue(FieldClassifier.classify(TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0).passthrough)
        assertTrue(FieldClassifier.classify(TEXT, EditorInfo.IME_FLAG_FORCE_ASCII).passthrough)
        assertTrue(FieldClassifier.classify(InputType.TYPE_NULL, 0).rawKeys)
    }

    @Test fun otpByHint() {
        val c = FieldClassifier.classify(InputType.TYPE_CLASS_NUMBER, 0, hintText = "Nhập mã OTP")
        assertTrue(c.otp)
        assertTrue(c.toTraits().passthrough && !c.toTraits().suggestionsAllowed)
        assertTrue(FieldClassifier.classify(TEXT, 0, autofillHints = arrayOf("smsOTPCode")).otp)
    }

    @Test fun enterActions() {
        assertEquals(EnterAction.SEARCH, FieldClassifier.classify(TEXT, EditorInfo.IME_ACTION_SEARCH).enterAction)
        assertEquals(EnterAction.SEND, FieldClassifier.classify(TEXT, EditorInfo.IME_ACTION_SEND).enterAction)
        assertEquals(EnterAction.NEWLINE, FieldClassifier.classify(TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_SEND).enterAction)
        assertEquals(EnterAction.NEWLINE, FieldClassifier.classify(TEXT, EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION).enterAction)
    }
}

class SelectionTrackerTest {
    @Test fun ownEditsIgnoredExternalDetected() {
        val t = SelectionTracker()
        t.reset(0, 0)
        t.inserted(3); t.deleted(1); t.inserted(2)
        assertTrue(t.onUpdate(4, 4))          // báo gộp sau batch
        assertEquals(4, t.cursor)
        assertFalse(t.onUpdate(1, 1))         // người dùng chạm vào giữa chữ
        assertEquals(1, t.cursor)
        t.inserted(1)
        assertTrue(t.onUpdate(2, 2))
        assertFalse(t.onUpdate(0, 2))         // bôi đen
        assertEquals(-1, t.cursor)
    }

    @Test fun intermediateReportsAccepted() {
        val t = SelectionTracker()
        t.reset(5, 5)
        t.deleted(1); t.inserted(2)
        assertTrue(t.onUpdate(4, 4))
        assertTrue(t.onUpdate(6, 6))
        assertEquals(0, t.pendingCount)
    }
}

class InputBridgeTest {
    private fun bridge(ed: FakeEditor, cfg: FieldConfig = FieldConfig.DEFAULT) =
        InputBridge { ed }.also { it.startInput(cfg, ed.selStart, ed.selEnd) }

    @Test fun deleteBackwardRemovesWholeGrapheme() {
        val ed = FakeEditor("ok 👍🏽")
        val b = bridge(ed)
        b.edit { b.deleteBackward() }
        assertEquals("ok ", ed.text)
        val ed2 = FakeEditor("á")   // a + dấu sắc kết hợp
        val b2 = bridge(ed2)
        b2.edit { b2.deleteBackward() }
        assertEquals("", ed2.text)
    }

    @Test fun emptyFieldSendsKeyDel() {
        val ed = FakeEditor("")
        val b = bridge(ed)
        b.edit { b.deleteBackward() }
        assertEquals(listOf(KeyEvent.KEYCODE_DEL), ed.keys)
    }

    @Test fun selectionDeletedByBackspace() {
        val ed = FakeEditor("xin chào")
        ed.selStart = 0; ed.selEnd = 3
        val b = bridge(ed)
        b.edit { b.deleteBackward() }
        assertEquals(" chào", ed.text)
    }

    @Test fun batchBalanced() {
        val ed = FakeEditor()
        val b = bridge(ed)
        b.edit { b.insertText("a"); b.edit { b.insertText("b") } }
        assertEquals(0, ed.batchDepth)
        assertEquals("ab", ed.text)
    }

    @Test fun cursorMovesWithSetSelectionWhenKnown() {
        val ed = FakeEditor("tiếng việt")
        val b = bridge(ed)
        b.edit { b.moveCursor(-4, 0) }
        assertEquals(ed.text.length - 4, ed.selStart)
        b.edit { b.moveCursor(2, 0) }
        assertEquals(ed.text.length - 2, ed.selStart)
        b.edit { b.moveCursor(0, -1) }
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_UP), ed.keys)
    }

    @Test fun enterUsesEditorAction() {
        val ed = FakeEditor()
        val cfg = FieldClassifier.classify(TEXT, EditorInfo.IME_ACTION_SEARCH)
        val b = bridge(ed, cfg)
        b.edit { b.enter() }
        assertEquals(listOf(EditorInfo.IME_ACTION_SEARCH), ed.actions)
        val ed2 = FakeEditor()
        val b2 = bridge(ed2, FieldClassifier.classify(TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 0))
        b2.edit { b2.enter() }
        assertEquals("\n", ed2.text)
    }
}

/** Luồng gõ thật: TypingSession → InputBridge → FakeEditor. */
class TypingSessionTest {
    @Before fun setUp() = TestAssets.install()

    private class Rig(cfg: FieldConfig = FieldConfig.DEFAULT, settings: EngineSettings = EngineSettings(), initial: String = "") {
        val ed = FakeEditor(initial)
        val proxy = InputBridge { ed }.also { it.startInput(cfg, ed.selStart, ed.selEnd) }
        val s = TypingSession().also { it.startInput(settings, cfg.toTraits()) }

        fun type(keys: String): String {
            for (c in keys) proxy.edit {
                when {
                    c == '⌫' -> s.backspace(proxy)
                    c == ' ' -> s.space(proxy)
                    c.isLetter() -> s.letter(c, proxy)
                    else -> s.text(c.toString(), proxy)
                }
            }
            // Mỗi edit của IME phải được nhận ra là "của mình".
            for ((a, b) in ed.selectionEvents) assertTrue("selection ($a,$b) phải là của IME", proxy.tracker.onUpdate(a, b))
            ed.selectionEvents.clear()
            return ed.text
        }
    }

    @Test fun telexThroughRealBridge() {
        assertEquals("Tiếng Việt ", Rig().type("Tieengs Vieejt "))
        assertEquals("google github ", Rig().type("google github "))
    }

    @Test fun passwordFieldTypesLiterally() {
        val pw = FieldClassifier.classify(TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0)
        val r = Rig(pw)
        assertEquals("vieejt ddaay", r.type("vieejt ddaay"))
        assertTrue(r.s.suggestions(r.proxy).isEmpty())
    }

    @Test fun vniThroughRealBridge() {
        assertEquals("việt nam ", Rig(settings = EngineSettings(vniMode = true)).type("vie6t5 nam "))
    }

    @Test fun reEditFinishedWord() {
        assertEquals("tháy", Rig().type("thay ⌫s"))
    }

    @Test fun fastTypingKeepsEveryKey() {
        val r = Rig()
        val words = "xin chaof cacs banj, hoom nay trowif ddepj quas! "
        assertEquals("xin chào các bạn, hôm nay trời đẹp quá! ", r.type(words))
    }

    @Test fun externalCursorMoveResetsEngine() {
        val r = Rig()
        r.type("vie")
        r.ed.setSelection(0, 0); r.ed.selectionEvents.clear()
        assertFalse(r.proxy.tracker.onUpdate(0, 0))
        r.s.externalSelectionChange()
        r.proxy.edit { r.s.letter('j', r.proxy) }
        assertEquals("jvie", r.ed.text)
    }

    @Test fun deleteWordsAndUndo() {
        val r = Rig()
        r.type("xin chaof banj ")
        val gone = r.proxy.edit { r.s.deleteWords(r.proxy, 2) }
        assertEquals("chào bạn ", gone)
        assertEquals("xin ", r.ed.text)
        r.proxy.edit { r.s.undoDelete(r.proxy) }
        assertEquals("xin chào bạn ", r.ed.text)
        assertNull(r.s.deletedForUndo)
    }

    @Test fun languageToggle() {
        val r = Rig()
        r.type("vieejt ")
        r.proxy.edit { r.s.toggleLanguage(r.proxy) }
        assertTrue(r.s.englishMode)
        assertEquals("việt tieengs ", r.type("tieengs "))
        r.proxy.edit { r.s.toggleLanguage(r.proxy) }
        assertEquals("việt tieengs tiếng ", r.type("tieengs "))
    }

    @Test fun suggestionsCompleteAndAccept() {
        val r = Rig()
        r.type("ngu")
        val sug = r.s.suggestions(r.proxy)
        assertTrue(sug.isNotEmpty())
        assertEquals(Suggestion.Kind.LITERAL, sug[0].kind)
        val pick = sug.firstOrNull { it.kind == Suggestion.Kind.COMPLETION } ?: return
        r.proxy.edit { r.s.accept(pick, r.proxy) }
        assertEquals(pick.text + " ", r.ed.text)
        assertFalse(r.s.isComposing)
    }

    @Test fun nextWordAfterSpace() {
        val r = Rig()
        r.type("xin ")
        val next = r.s.suggestions(r.proxy)
        assertTrue(next.all { it.kind == Suggestion.Kind.NEXT })
    }

    @Test fun autoShiftAtSentenceStart() {
        val r = Rig()
        assertEquals(true, r.s.autoShift(r.proxy))
        r.type("xin. ")
        assertEquals(true, r.s.autoShift(r.proxy))
        r.type("ab")
        assertEquals(false, r.s.autoShift(r.proxy))
    }

    @Test fun doubleSpacePeriod() {
        var now = 1_000L
        val cfg = FieldConfig.DEFAULT
        val ed = FakeEditor()
        val proxy = InputBridge { ed }.also { it.startInput(cfg, 0, 0) }
        val s = TypingSession(clock = { now }).also { it.startInput(EngineSettings(), cfg.toTraits()) }
        for (c in "chaof") proxy.edit { s.letter(c, proxy) }
        proxy.edit { s.space(proxy) }
        now += 200
        proxy.edit { s.space(proxy) }
        assertEquals("chào. ", ed.text)
    }

    @Test fun wordSpan() {
        assertEquals("bạn ", WordSpan.lastWords("xin chào bạn ", 1))
        assertEquals("chào bạn! ", WordSpan.lastWords("xin chào bạn! ", 2))
        assertEquals("xin chào", WordSpan.lastWords("xin chào", 5))
        assertEquals("\n", WordSpan.lastWords("dòng một\n", 1))
    }
}

class GestureTest {
    @Test fun spaceTapFlickTrackpad() {
        val g = SpaceGesture(SpaceGesture.Params(longPressMs = 300, flickMinPx = 100f, flickMaxMs = 350, stepPx = 20f))
        g.down(500f, 50f, 0); g.move(505f, 50f, 40)
        assertEquals(SpaceGesture.Result.TAP, g.up(505f, 80))

        g.down(500f, 50f, 0); g.move(560f, 50f, 60); g.move(640f, 52f, 120)
        assertEquals(SpaceGesture.Result.FLICK, g.up(650f, 150))
        assertEquals(1, g.flickDirection)

        g.down(500f, 50f, 0)
        assertTrue(g.longPressElapsed(500f, 50f))
        assertEquals(SpaceGesture.Steps(-2, 0), g.move(458f, 50f, 400))
        assertEquals(SpaceGesture.Steps(1, 0), g.move(480f, 50f, 450))
        assertEquals(SpaceGesture.Result.TRACKPAD_END, g.up(480f, 500))
    }

    @Test fun slowDragBecomesTrackpad() {
        val g = SpaceGesture(SpaceGesture.Params(longPressMs = 300, flickMaxMs = 350, slopPx = 10f))
        g.down(100f, 10f, 0)
        g.move(150f, 10f, 400)
        assertEquals(SpaceGesture.State.TRACKPAD, g.state)
    }

    @Test fun backspaceSwipeWords() {
        val b = BackspaceSwipe(BackspaceSwipe.Params(startPx = 40f, wordStepPx = 60f))
        b.down(1000f)
        assertFalse(b.move(980f))
        assertTrue(b.move(950f)); assertEquals(1, b.words)
        assertTrue(b.move(830f)); assertEquals(3, b.words)
        b.move(1000f); assertEquals(0, b.words)       // kéo về ⇒ huỷ
        assertEquals(0, b.up())
        b.down(1000f); b.move(900f)
        assertEquals(2, b.up())
    }
}
