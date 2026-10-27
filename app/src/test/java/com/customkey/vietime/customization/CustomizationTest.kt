package com.customkey.vietime.customization

import com.customkey.vietime.engine.Keys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class ThemeConfigTest {
    @Test fun encodeDecodeRoundTrip() {
        for (p in ThemePresets.all) {
            val c = p.config.copy(keyOpacity = 0.4f, labelOpacity = 0.75f, wallpaper = true, blur = 12, dim = 0.5f)
            assertEquals(c, ThemeConfig.decode(c.encode()))
        }
    }

    @Test fun decodeIsRobust() {
        assertEquals(ThemeConfig(), ThemeConfig.decode(null))
        assertEquals(ThemeConfig(), ThemeConfig.decode("rác;;=;key=ZZZ;keyOp=NaN"))
        val c = ThemeConfig.decode("keyOp=5;blur=99;dim=-1;radius=100")
        assertEquals(1f, c.keyOpacity); assertEquals(25, c.blur); assertEquals(0f, c.dim); assertEquals(24f, c.cornerRadiusDp)
    }

    @Test fun presetsUniqueAndReadable() {
        assertEquals(ThemePresets.all.size, ThemePresets.all.map { it.id }.toSet().size)
        for (p in ThemePresets.all) {
            val contrast = abs(Colors.luminance(p.config.keyColor) - Colors.luminance(p.config.labelColor))
            assertTrue("${p.id} chữ phải nổi trên phím ($contrast)", contrast > 0.5f)
        }
        assertEquals("system", ThemePresets.byId("không-có").id)
    }

    @Test fun styleMapping() {
        val s = ThemeConfig(keyOpacity = 0.3f, wallpaper = false).toStyle(null)
        assertEquals(0.3f, s.keyOpacity)
        assertNull(s.wallpaper)
    }

    @Test fun colorMath() {
        assertEquals(0x80FF0000.toInt(), Colors.withAlpha(0xFFFF0000.toInt(), 128 / 255f) or 0)
        assertEquals(0xFF808080.toInt(), Colors.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.502f))
    }
}

class ImageFxTest {
    @Test fun uniformImageStaysUniform() {
        val px = IntArray(40 * 30) { 0xFF336699.toInt() }
        ImageFx.blur(px, 40, 30, 6)
        assertTrue(px.all { it == 0xFF336699.toInt() })
    }

    @Test fun blurSpreadsAPoint() {
        val w = 31; val h = 31
        val px = IntArray(w * h) { 0xFF000000.toInt() }
        px[15 * w + 15] = 0xFFFFFFFF.toInt()
        ImageFx.blur(px, w, h, 3)
        val center = px[15 * w + 15] and 0xFF
        val near = px[15 * w + 18] and 0xFF
        assertTrue(center in 1..254)
        assertTrue(near > 0)
        assertEquals(0, px[0] and 0xFF)
    }

    @Test fun dimDarkens() {
        val px = intArrayOf(0xFFFFFFFF.toInt(), 0xFF808080.toInt())
        ImageFx.dim(px, 0.5f)
        assertEquals(127, px[0] and 0xFF)
        assertEquals(64, px[1] and 0xFF)
    }
}

class KeySynthTest {
    @Test fun everyProfileAndKindIsAudibleAndClean() {
        for (p in SoundProfile.entries.filter { it.synthesized }) for (k in SoundKind.entries) {
            val pcm = KeySynth.render(p, k)
            assertTrue("$p/$k ngắn", pcm.size in KeySynth.RATE / 100..KeySynth.RATE / 2)
            val peak = pcm.maxOf { abs(it) }
            assertTrue("$p/$k peak $peak", peak in 0.5f..0.901f)
            assertTrue("$p/$k không có NaN", pcm.none { it.isNaN() })
            assertTrue("$p/$k đuôi tắt dần", abs(pcm.last()) < 0.01f)
        }
    }

    @Test fun kindsSoundDifferent() {
        val a = KeySynth.render(SoundProfile.BLUE, SoundKind.KEY)
        val b = KeySynth.render(SoundProfile.BLUE, SoundKind.SPACE)
        assertFalse(a.contentEquals(b))
        // Chuông Enter của máy đánh chữ dài hơn hẳn phím thường.
        assertTrue(KeySynth.render(SoundProfile.TYPEWRITER, SoundKind.ENTER).size > 3 * KeySynth.render(SoundProfile.TYPEWRITER, SoundKind.KEY).size)
    }

    @Test fun wavHeader() {
        val pcm = KeySynth.render(SoundProfile.WATER, SoundKind.KEY)
        val wav = KeySynth.toWav(pcm)
        assertEquals(44 + pcm.size * 2, wav.size)
        assertEquals("RIFF", String(wav, 0, 4)); assertEquals("WAVE", String(wav, 8, 4)); assertEquals("data", String(wav, 36, 4))
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(KeySynth.RATE, b.getInt(24))
        assertEquals(pcm.size * 2, b.getInt(40))
    }

    @Test fun profileIds() {
        assertEquals(SoundProfile.entries.size, SoundProfile.entries.map { it.id }.toSet().size)
        assertEquals(SoundProfile.BLUE, SoundProfile.of(null))
        assertEquals(SoundProfile.WATER, SoundProfile.of("water"))
    }
}

class HapticAndLayoutTest {
    @Test fun hapticPulse() {
        assertNull(HapticConfig.pulse(HapticKind.KEY, 0, true))
        val weak = HapticConfig.pulse(HapticKind.KEY, 10, true)!!
        val strong = HapticConfig.pulse(HapticKind.KEY, 100, true)!!
        assertTrue(strong.second > weak.second)
        assertEquals(255, strong.second)
        val noAmp = HapticConfig.pulse(HapticKind.DELETE, 100, false)!!
        assertTrue(noAmp.first > HapticConfig.pulse(HapticKind.DELETE, 20, false)!!.first)
        assertTrue(HapticConfig.pulse(HapticKind.CURSOR, 100, true)!!.first < strong.first)
    }

    @Test fun hapticLoadClamps() {
        val h = HapticConfig.load { when (it) { HapticConfig.KEY_KEY -> 500; HapticConfig.KEY_DELETE -> -3; else -> null } }
        assertEquals(100, h.key); assertEquals(0, h.delete); assertEquals(HapticConfig().cursor, h.cursor)
    }

    @Test fun layoutLoadAndOptions() {
        val prefs = mapOf<String, Any>(Keys.NUMBER_ROW to true, LayoutConfig.KEY_HEIGHT to 300, LayoutConfig.KEY_WIDTH to 10,
            LayoutConfig.KEY_SPLIT to "always", LayoutConfig.KEY_LONG_PRESS to 50)
        val c = LayoutConfig.load { prefs[it] }
        assertEquals(LayoutConfig.MAX_HEIGHT, c.heightPercent)
        assertEquals(LayoutConfig.MIN_WIDTH, c.widthPercent)
        assertEquals(LayoutConfig.MIN_LONG, c.longPressMs)
        assertEquals(54f * 1.4f, c.rowHeightDp(), 0.01f)
        assertTrue(c.options(wide = false).numberRow)
        assertTrue(c.options(wide = false).splitGap > 0f)
        val d = LayoutConfig()
        assertEquals(0f, d.options(wide = false).splitGap)
        assertTrue(d.options(wide = true).splitGap > 0f)
        assertEquals(0f, d.copy(split = SplitMode.OFF).options(wide = true).splitGap)
    }
}
