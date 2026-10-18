package com.customkey.vietime.ui.keyboard

import com.customkey.vietime.ime.EnterAction
import com.customkey.vietime.ime.FieldKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutTest {
    private fun laid(opts: LayoutOptions = LayoutOptions(), kind: FieldKind = FieldKind.TEXT) =
        KeyboardLayout.place(KeyboardLayout.rows(KeyboardPlane.LETTERS, kind, EnterAction.NEWLINE, opts), 1000f, 100f, 0f, opts)

    @Test fun lettersEqualWidthAndEdgeHitAreas() {
        val keys = laid()
        val letters = keys.filter { it.def.action is KeyAction.Letter }
        assertEquals(26, letters.size)
        assertTrue(letters.all { kotlin.math.abs(it.width - 100f) < 0.01f })
        val a = letters.first { (it.def.action as KeyAction.Letter).ch == 'a' }
        assertEquals(50f, a.left, 0.01f)      // hàng giữa thụt nửa phím
        assertTrue(a.contains(5f, 150f))     // vùng chạm mở tới mép trái
    }

    @Test fun everyPointMapsToOneKey() {
        val keys = laid()
        for (x in 0 until 1000 step 7) for (y in 0 until 400 step 9)
            assertEquals("($x,$y)", 1, keys.count { it.contains(x.toFloat(), y.toFloat()) })
    }

    @Test fun numberRowAndUrlKeys() {
        assertEquals(5, KeyboardLayout.rows(KeyboardPlane.LETTERS, FieldKind.TEXT, EnterAction.NEWLINE, LayoutOptions(numberRow = true)).size)
        val url = KeyboardLayout.rows(KeyboardPlane.LETTERS, FieldKind.URL, EnterAction.GO).last()
        assertTrue(url.any { it.label == "/" })
        assertEquals("Đi", url.last().label)
    }

    @Test fun splitLeavesGapInMiddle() {
        val opts = LayoutOptions(splitGap = 2f)
        val top = laid(opts).filter { it.row == 0 }
        val t = top.first { (it.def.action as KeyAction.Letter).ch == 't' }
        val y = top.first { (it.def.action as KeyAction.Letter).ch == 'y' }
        assertTrue(y.left - t.right > 100f)
    }

    @Test fun swipeCentersCoverAlphabet() {
        assertEquals(26, KeyboardLayout.letterCenters(laid()).size)
    }
}
