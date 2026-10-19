package com.customkey.vietime.tools

import com.customkey.vietime.engine.NumberChips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TextTransformTest {
    @Test fun cases() {
        assertEquals("XIN CHÀO VIỆT NAM", TextTransform.UPPER.apply("xin chào Việt Nam"))
        assertEquals("xin chào việt nam", TextTransform.LOWER.apply("XIN Chào VIỆT Nam"))
        assertEquals("Xin Chào Việt Nam", TextTransform.TITLE.apply("xin CHÀO việt nam"))
        assertEquals("Hôm nay đẹp. Mai đi chơi!", TextTransform.SENTENCE.apply("hôm nay ĐẸP. mai đi chơi!"))
    }

    @Test fun stripTones() {
        assertEquals("Tieng Viet that dep, Duong pho", TextTransform.STRIP_TONES.apply("Tiếng Việt thật đẹp, Đường phố"))
        assertEquals("abc 123 !?", TextTransform.STRIP_TONES.apply("abc 123 !?"))
    }

    /** Mọi phép giữ nguyên độ dài + vị trí khoảng trắng (bàn phím thay đúng đoạn cũ). */
    @Test fun whitespaceUntouched() {
        val src = "xin  chào bạn "
        for (t in TextTransform.entries) {
            val out = t.apply(src)
            assertEquals(t.name, src.length, out.length)
            for (i in src.indices) if (src[i] == ' ') assertEquals("${t.name}@$i", ' ', out[i])
        }
    }
}

/** Chip số: khớp bộ ca chung number-chips.txt của bản gốc (iOS ⇔ Android). */
class NumberChipsFixtureTest {
    private fun fixture(): File = listOf(File("src/test/fixtures/number-chips.txt"), File("app/src/test/fixtures/number-chips.txt")).first { it.exists() }

    @Test fun sharedFixture() {
        val fails = ArrayList<String>()
        var n = 0
        for (raw in fixture().readLines()) {
            if (raw.isBlank() || raw.startsWith("#")) continue
            val p = raw.split('|').map { it.replace("␣", " ") }
            n++
            val ok = when (p[0]) {
                "spell" -> NumberChips.spell(p[1]) == p[2]
                "spellLe" -> NumberChips.spell(p[1], le = true) == p[2]
                "chip" -> {
                    val c = NumberChips.chip(p[1])
                    if (p.getOrNull(2) == "-") c == null else c != null && c.display == p[2] && c.replace == p[3] && c.insert == p[4]
                }
                else -> true
            }
            if (!ok) fails += raw
        }
        assertTrue(n > 20)
        assertEquals("sai ${fails.size}/$n:\n" + fails.joinToString("\n"), 0, fails.size)
    }

    @Test fun moneyShorthand() {
        val c = NumberChips.chip("150k")
        assertTrue("150k phải ra tiền: $c", c != null && c.insert.contains("150.000"))
    }
}
