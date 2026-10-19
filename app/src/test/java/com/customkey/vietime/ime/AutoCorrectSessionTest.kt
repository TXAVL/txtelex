package com.customkey.vietime.ime

import com.customkey.vietime.engine.EngineSettings
import com.customkey.vietime.engine.TestAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tự sửa lỗi chạm trượt: sửa lúc gõ dấu cách, ⌫ / chip ↩ hoàn tác, từ đã hoàn tác không sửa lại. */
class AutoCorrectSessionTest {
    @Before fun setUp() = TestAssets.install()

    private class Rig(settings: EngineSettings = EngineSettings()) {
        val ed = FakeEditor()
        val proxy = InputBridge { ed }.also { it.startInput(FieldConfig.DEFAULT, 0, 0) }
        val s = TypingSession().also { it.startInput(settings, FieldConfig.DEFAULT.toTraits()) }
        var saved = 0
        init { s.onRejectedChanged = { saved++ } }

        /** Gõ [word] kèm điểm chạm; phím thứ [slip] lệch [dx] (âm = về bên trái). */
        fun type(word: String, slip: Int = -1, dx: Float = -0.4f, touch: Boolean = true) {
            for ((i, c) in word.withIndex()) proxy.edit {
                if (touch) s.noteLetterTouch(if (i == slip) dx else 0f, 0f)
                s.letter(c, proxy)
            }
        }
        fun space() = proxy.edit { s.space(proxy) }
        fun backspace() = proxy.edit { s.backspace(proxy) }
    }

    @Test fun correctsAtSpaceAndBackspaceRestores() {
        val r = Rig()
        r.type("tpoi", slip = 1)          // 'p' chạm lệch về phía 'o'
        r.space()
        assertEquals("tôi ", r.ed.text)
        assertEquals("tpoi", r.s.autoCorrectUndo?.first)
        r.backspace()
        assertEquals("tpoi ", r.ed.text)  // trả chữ gốc, giữ dấu cách
        assertEquals(1, r.saved)
        r.type("tpoi", slip = 1); r.space()
        assertEquals("tpoi tpoi ", r.ed.text)   // đã từng hoàn tác ⇒ không sửa lại
    }

    @Test fun undoChipLeadsTheBar() {
        val r = Rig()
        r.type("tpoi", slip = 1); r.space()
        val bar = r.s.suggestions(r.proxy)
        assertEquals(Suggestion.Kind.UNDO_CORRECTION, bar.first().kind)
        assertEquals("↩ tpoi", bar.first().text)
        r.proxy.edit { r.s.accept(bar.first(), r.proxy) }
        assertEquals("tpoi ", r.ed.text)
        assertNull(r.s.autoCorrectUndo)
    }

    @Test fun offOrWithoutTouchesDoesNothing() {
        val off = Rig(EngineSettings(autoCorrect = false))
        off.type("tpoi", slip = 1); off.space()
        assertEquals("tpoi ", off.ed.text)
        val noTouch = Rig()
        noTouch.type("tpoi", touch = false); noTouch.space()
        assertEquals("tpoi ", noTouch.ed.text)
        val vni = Rig(EngineSettings(vniMode = true))
        vni.type("tpoi", slip = 1); vni.space()
        assertEquals("tpoi ", vni.ed.text)
    }

    @Test fun validAndEnglishWordsLeftAlone() {
        val r = Rig()
        r.type("google", slip = 2); r.space()
        r.type("tooi", slip = 1, dx = 0.1f); r.space()
        assertEquals("google tôi ", r.ed.text)
    }

    @Test fun suggestsCorrectionWhenNoCompletion() {
        val r = Rig()
        r.type("tpoi", touch = false)
        val bar = r.s.suggestions(r.proxy)
        assertTrue("phải gợi ý bản sửa: $bar", bar.any { it.text == "tôi" })
    }

    @Test fun accentedLetterFromLongPressJoinsTheWord() {
        val r = Rig()
        r.type("vi"); r.proxy.edit { r.s.letter('ệ', r.proxy) }; r.type("t"); r.space()
        assertEquals("việt ", r.ed.text)
    }
}
