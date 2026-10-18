package com.customkey.vietime.tools

import com.customkey.vietime.engine.TestAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

private fun fixture(name: String): File =
    listOf(File("src/test/fixtures/$name"), File("app/src/test/fixtures/$name")).first { it.exists() }

/** Bộ ca chung math-results.txt (152 dòng): eval + chip. */
class MathEvaluatorTest {
    @Test fun sharedFixture() {
        val fails = ArrayList<String>()
        var n = 0
        for (raw in fixture("math-results.txt").readLines()) {
            if (raw.isBlank() || raw.startsWith("#")) continue
            val p = raw.split('|')
            val input = p[1].replace("␣", " ")
            val want = p[2].takeIf { it != "-" }
            val got = when (p[0]) {
                "eval" -> MathEvaluator.evaluate(input)?.text
                "chip" -> MathEvaluator.chip(input)
                else -> error(raw)
            }
            n++
            if (got != want) fails += "$raw → $got"
        }
        assertTrue(n > 140)
        assertEquals("sai ${fails.size}/$n:\n" + fails.joinToString("\n"), 0, fails.size)
    }

    @Test fun claudeMdExample() {
        assertEquals("65,400,000", MathEvaluator.chip("26,160*2,500="))
    }
}

/** Thêm dấu cả câu: khớp bộ ca chung add-tones.txt. */
class ToneRestorerTest {
    @Before fun setUp() = TestAssets.install()

    @Test fun sharedFixture() {
        val fails = ArrayList<String>()
        var n = 0
        for (line in fixture("add-tones.txt").readLines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val (input, want) = line.split('\t', limit = 2)
            val got = ToneRestorer.restore(input) ?: input
            n++
            if (got != want) fails += "$input → $got (cần $want)"
        }
        assertTrue(n > 10)
        assertEquals("sai ${fails.size}/$n:\n" + fails.joinToString("\n"), 0, fails.size)
    }

    @Test fun planBeforeCursor() {
        val e = ToneRestorer.planBefore("Chào bạn. hom nay troi dep qua ")
        assertTrue(e != null)
        assertTrue(e!!.replacement.contains("ôm nay trời đẹp quá"))
        assertEquals("om nay troi dep qua ", e.original)
        assertNull(ToneRestorer.planBefore("hôm nay trời đẹp quá "))
    }

    @Test fun looksUntoned() {
        assertTrue(ToneRestorer.looksUntoned("hom nay troi dep"))
        assertFalse(ToneRestorer.looksUntoned("hôm nay trời"))
        assertFalse(ToneRestorer.looksUntoned("ok"))
    }

    @Test fun lastSentence() {
        assertEquals("good morning", com.customkey.vietime.ime.ToneRestorerSentence.last("Xin chào! good morning"))
    }
}

class ClipboardVaultTest {
    @Test fun addDedupePinAndCap() {
        val v = ClipboardVault()
        assertTrue(v.add("a", 1)); assertTrue(v.add("b", 2))
        assertFalse(v.add("b", 3))                  // đã ở đầu
        assertTrue(v.add("a", 4))                   // đưa lên đầu
        assertEquals(listOf("a", "b"), v.items.map { it.text })
        val b = v.items.first { it.text == "b" }
        v.setPinned(b.id, true)
        assertEquals("b", v.items.first().text)     // ghim lên trước
        for (i in 0 until 80) v.add("x$i", 10L + i)
        assertEquals(ClipboardVault.MAX_UNPINNED, v.items.count { !it.pinned })
        assertTrue(v.items.any { it.text == "b" })  // mục ghim không bị đẩy ra
        v.clearUnpinned()
        assertEquals(listOf("b"), v.items.map { it.text })
        assertFalse(v.add("   ", 99))
    }

    @Test fun encodeDecode() {
        val v = ClipboardVault()
        v.add("dòng 1\ndòng 2\tcó tab \\ gạch", 5); v.add("https://txa.vn", 6)
        v.setPinned(v.items.last().id, true)
        val back = ClipboardVault.decode(v.encode())
        assertEquals(v.items, back.items)
        assertTrue(ClipboardVault.decode("rác\n1\t2").items.isEmpty())
    }

    @Test fun detect() {
        assertEquals(ClipMatch(ClipKind.OTP, "123456"), ClipboardVault.detect("123456"))
        assertEquals(ClipMatch(ClipKind.OTP, "482913"), ClipboardVault.detect("Ma OTP cua ban la 482913. Khong chia se ma nay."))
        assertEquals(ClipKind.URL, ClipboardVault.detect("https://github.com/TXAVL/txtelex").kind)
        assertEquals(ClipKind.URL, ClipboardVault.detect("txa.vn/abc").kind)
        assertEquals(ClipKind.EMAIL, ClipboardVault.detect("txa@gmail.com").kind)
        assertEquals(ClipMatch(ClipKind.PHONE, "0912345678"), ClipboardVault.detect("0912 345 678"))
        assertEquals(ClipKind.ACCOUNT, ClipboardVault.detect("1903 6655 8899 012").kind)
        assertEquals(ClipMatch(ClipKind.MATH, "36"), ClipboardVault.detect("12*3"))
        assertEquals(ClipKind.TEXT, ClipboardVault.detect("xin chào bạn").kind)
        assertTrue(ClipboardVault.chipLabel(ClipMatch(ClipKind.OTP, "123456")).contains("123456"))
    }
}

class SnippetStoreTest {
    @Test fun crudAndOrder() {
        val s = SnippetStore(emptyList())
        val a = s.add("A")!!; val b = s.add("B")!!
        assertNull(s.add("A"))
        assertEquals(listOf("B", "A"), s.items.map { it.text })
        assertTrue(s.move(a.id, -1))
        assertEquals(listOf("A", "B"), s.items.map { it.text })
        assertFalse(s.move(a.id, -1))
        assertTrue(s.update(b.id, "B2"))
        assertTrue(s.delete(a.id))
        assertEquals(listOf("B2"), SnippetStore.decode(s.encode()).items.map { it.text })
    }

    @Test fun defaultsAndEmpty() {
        assertEquals(SnippetStore.DEFAULTS, SnippetStore.decode(null).items)
        assertTrue(SnippetStore.decode("").items.isEmpty())   // người dùng đã xoá hết ⇒ không tự thêm lại
    }
}

class MiscToolsTest {
    @Test fun translateDirection() {
        assertEquals("vi" to "en", Translator.direction("xin chào"))
        assertEquals("en" to "vi", Translator.direction("hello world"))
    }

    @Test fun plusUnlockedWhilePaywallOff() {
        assertFalse(PlusGate.PAYWALL_ENABLED)
    }
}
