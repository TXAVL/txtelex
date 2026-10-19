package com.customkey.vietime.ime

import com.customkey.vietime.customization.LayoutConfig
import com.customkey.vietime.customization.OneHandMode
import com.customkey.vietime.engine.EngineSettings
import com.customkey.vietime.engine.TestAssets
import com.customkey.vietime.ui.keyboard.KeyAction
import com.customkey.vietime.ui.keyboard.KeyboardLayout
import com.customkey.vietime.ui.keyboard.KeyboardPlane
import com.customkey.vietime.ui.keyboard.LayoutOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AutoSpaceTest {
    @Before fun setUp() = TestAssets.install()

    private fun rig(on: Boolean = true): Triple<FakeEditor, InputBridge, TypingSession> {
        val ed = FakeEditor()
        val proxy = InputBridge { ed }.also { it.startInput(FieldConfig.DEFAULT, 0, 0) }
        val s = TypingSession().also { it.startInput(EngineSettings(), FieldConfig.DEFAULT.toTraits()); it.autoSpaceAfterPunct = on }
        return Triple(ed, proxy, s)
    }

    private fun type(keys: String, r: Triple<FakeEditor, InputBridge, TypingSession>): String {
        val (ed, p, s) = r
        for (c in keys) p.edit {
            when {
                c == '⌫' -> s.backspace(p)
                c == ' ' -> s.space(p)
                c.isLetter() -> s.letter(c, p)
                else -> s.text(c.toString(), p)
            }
        }
        return ed.text
    }

    @Test fun addsSpaceAfterPunctuation() {
        assertEquals("chào, bạn. ", type("chaof,banj.", rig()))
    }

    @Test fun typedSpaceIsSwallowed() {
        assertEquals("chào, bạn", type("chaof, banj", rig()))
    }

    @Test fun backspaceRemovesOnlyTheAutoSpace() {
        assertEquals("chào,", type("chaof,⌫", rig()))
    }

    @Test fun secondPunctuationReplacesTheSpace() {
        assertEquals("thật?! ", type("thaatj?!", rig()))
    }

    @Test fun numbersAndUrlFieldsUntouched() {
        assertEquals("3.5", type("3.5", rig()))
        assertEquals("10:30", type("10:30", rig()))
        // Ô URL: không bao giờ tự thêm dấu cách (lúc gõ "google." chưa biết đó là tên miền).
        val ed = FakeEditor()
        val url = FieldClassifier.classify(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI, 0)
        val p = InputBridge { ed }.also { it.startInput(url, 0, 0) }
        val s = TypingSession().also { it.startInput(EngineSettings(), url.toTraits()); it.autoSpaceAfterPunct = true }
        type("google.com", Triple(ed, p, s))
        assertEquals("google.com", ed.text)
    }

    @Test fun offByDefault() {
        assertEquals("chào,bạn", type("chaof,banj", rig(on = false)))
    }
}

class KeyboardLayoutUpgradeTest {
    @Test fun symbolsPlaneHasAngleBracketsLikeReference() {
        val all = KeyboardLayout.rows(KeyboardPlane.SYMBOLS2, FieldKind.TEXT, EnterAction.NEWLINE).flatten()
            .mapNotNull { (it.action as? KeyAction.Text)?.text }
        for (c in listOf("<", ">", "[", "]", "{", "}", "%", "©", "®", "™", "✓", "$", "€", "£", "¥", "\\", "~", "`", "|", "^", "°", "="))
            assertTrue("thiếu $c", c in all)
    }

    @Test fun vowelsLongPressGiveVietnameseLetters() {
        val keys = KeyboardLayout.rows(KeyboardPlane.LETTERS, FieldKind.TEXT, EnterAction.NEWLINE).flatten()
        fun alts(c: Char) = keys.first { (it.action as? KeyAction.Letter)?.ch == c }.alternates
        assertTrue(alts('a').containsAll(listOf("á", "à", "ả", "ã", "ạ", "â", "ă")))
        assertTrue(alts('o').containsAll(listOf("ô", "ơ")))
        assertTrue(alts('u').contains("ư"))
        assertEquals("3", alts('e').first())            // hàng trên: số vẫn là lựa chọn đầu
        assertTrue(alts('d').contains("đ"))
    }

    @Test fun oneHandShiftsKeysToThumbSide() {
        val opts = LayoutConfig(oneHand = OneHandMode.RIGHT).options(wide = false)
        assertEquals(1, opts.align)
        val keys = KeyboardLayout.place(KeyboardLayout.rows(KeyboardPlane.LETTERS, FieldKind.TEXT, EnterAction.NEWLINE, opts), 1000f, 100f, 0f, opts)
        val q = keys.first { (it.def.action as? KeyAction.Letter)?.ch == 'q' }
        val p = keys.first { (it.def.action as? KeyAction.Letter)?.ch == 'p' }
        assertTrue("q phải bị đẩy sang phải: ${q.left}", q.left > 150f)
        assertEquals(1000f, p.right, 0.5f)
        // màn ngang / tách đôi ⇒ không một tay
        assertEquals(0, LayoutConfig(oneHand = OneHandMode.LEFT).options(wide = true).align)
        assertEquals(LayoutOptions().align, 0)
    }
}
