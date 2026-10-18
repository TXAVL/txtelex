package com.customkey.vietime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Bất biến bắt buộc của CLAUDE.md §4 — mọi thay đổi trong `engine/` phải giữ xanh:
 * Telex/VNI cơ bản, khôi phục tiếng Anh (google, github, he is), token lập trình (camelCase),
 * không compose trong ô mật khẩu, và engine không phụ thuộc Android.
 */
class RequiredRegressionTest {
    @Before fun setUp() = TestAssets.install()

    /** Bộ gõ tối giản như tầng ime: chữ → letter, số (VNI) → vniDigit, còn lại → boundary. */
    private fun type(keys: String, settings: EngineSettings = EngineSettings(), secure: Boolean = false): String {
        val p = MockProxy(secure)
        val b = EngineBridge(settings)
        for (c in keys) when {
            c == '⌫' -> { if (!b.backspace(p)) p.deleteBackward() }
            c.isLetter() -> b.letter(c, p)
            c in '0'..'9' && b.vniDigit(c, p) -> {}
            else -> b.boundary(c.toString(), p)
        }
        return p.text
    }

    private val vni = EngineSettings(vniMode = true)

    @Test fun telexBasics() {
        assertEquals("việt ", type("vieejt "))
        assertEquals("Tiếng Việt ", type("Tieengs Vieejt "))
        assertEquals("đường ", type("dduwowngf "))
        assertEquals("người ", type("nguowif "))
    }

    @Test fun vniBasics() {
        assertEquals("tiếng việt ", type("tie6ng1 vie6t5 ", vni))
        assertEquals("đường ", type("d9u7o7ng2 ", vni))
        assertEquals("hòa ", type("hoa2 ", vni))
        assertEquals("2026 ", type("2026 ", vni))
        assertEquals("nam 2026.", type("nam 2026.", vni))
    }

    @Test fun englishRestore() {
        assertEquals("google ", type("google "))
        assertEquals("github ", type("github "))
        assertEquals("he is ", type("he is "))
        assertEquals("github repo is ", type("github repo is "))
    }

    @Test fun camelCaseTokensKept() {
        for (w in listOf("camelCase", "useState", "setTimeout", "getElementById", "isEmpty", "onCreate"))
            assertEquals("$w ", type("$w "))
    }

    @Test fun secureFieldNeverComposes() {
        assertEquals("vieejt ", type("vieejt ", secure = true))
        assertEquals("ddaay1 ", type("ddaay1 ", vni, secure = true))
    }

    @Test fun enginePackageHasNoAndroidImports() {
        val dir = listOf(File("src/main/java/com/customkey/vietime/engine"), File("app/src/main/java/com/customkey/vietime/engine"))
            .first { it.isDirectory }
        val files = dir.listFiles { f -> f.extension == "kt" }!!
        assertTrue(files.size > 10)
        val offenders = files.filter { f -> f.readLines().any { it.startsWith("import android.") || it.startsWith("import androidx.") } }
        assertEquals("engine/ phải thuần Kotlin", emptyList<String>(), offenders.map { it.name })
    }

    @Test fun mitHeaderOnEveryEngineFile() {
        val dir = listOf(File("src/main/java/com/customkey/vietime/engine"), File("app/src/main/java/com/customkey/vietime/engine"))
            .first { it.isDirectory }
        val missing = dir.listFiles { f -> f.extension == "kt" }!!
            .filter { !it.readText().contains("Copyright (c) 2026 Phil Trinh") }.map { it.name }
        assertEquals(emptyList<String>(), missing)
    }
}
