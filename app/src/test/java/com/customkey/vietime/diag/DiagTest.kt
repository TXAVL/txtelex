package com.customkey.vietime.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TxaCrashTest {
    private val stackA = """
        java.lang.IllegalStateException: hỏng rồi
        	at com.customkey.vietime.ime.TypingSession.space(TypingSession.kt:120)
        	at com.customkey.vietime.ime.CustomImeService.onSpace(CustomImeService.kt:212)
        	at com.customkey.vietime.ui.keyboard.KeyboardView.pointerUp(KeyboardView.kt:401)
        	at android.view.View.dispatchTouchEvent(View.java:15000)
    """.trimIndent()

    private fun crash(stack: String = stackA, title: String = "java.lang.IllegalStateException: hỏng rồi") = TxaCrash(
        TxaCrashKind.JAVA, 1_760_000_000_000, "main#2", title, "msg", stack,
        listOf("+0.120s  Mở MainActivity", "+3.002s  Gõ vào com.zing.zalo · ô TEXT"), "main", "AB12CD", "0.4.0-alpha (5)", 3002,
        listOf("2026-10-18 | I | main#2 | IME | dòng log\n  tiếp dòng"),
    )

    @Test fun encodeDecodeRoundTrip() {
        val c = crash()
        val back = TxaCrash.decode(c.encode())!!
        assertEquals(c.copy(logTail = emptyList()), back.copy(logTail = emptyList()))
        assertTrue(back.logTail.first().contains("dòng log"))
        assertNull(TxaCrash.decode("không phải báo cáo"))
    }

    @Test fun fingerprintIgnoresLineNumbersAndMessage() {
        val a = crash()
        val shifted = crash(stackA.replace(":120)", ":131)").replace(":212)", ":250)"), "java.lang.IllegalStateException: thông điệp khác")
        assertEquals(a.fingerprint, shifted.fingerprint)
        assertTrue(Regex("^TXA-[2-9A-Z]{4}-[2-9A-Z]{4}$").matches(a.fingerprint))
        val other = crash(stackA.replace("space", "letter"))
        assertNotEquals(a.fingerprint, other.fingerprint)
    }

    @Test fun hints() {
        assertTrue(TxaCrash.hintFor(TxaCrashKind.JAVA, "java.lang.OutOfMemoryError", "", "").contains("RAM"))
        assertTrue(TxaCrash.hintFor(TxaCrashKind.NATIVE, "SIGSEGV (SEGV_MAPERR)", "", "").contains("native"))
        assertTrue(TxaCrash.hintFor(TxaCrashKind.ANR, "", "", "").contains("Luồng"))
        assertTrue(TxaCrash.hintFor(TxaCrashKind.JAVA, "java.lang.ClassNotFoundException: x", "", "").contains("Thiếu"))
    }
}

class TxaNativeDumpTest {
    private val dump = """
        TXA-NATIVE v1
        signal=11 SIGSEGV
        code=1 SEGV_MAPERR
        addr=0x10
        pid=4242
        tid=4250
        thread=RenderThread
        time=1760000000123
        abi=arm64-v8a
        regs=pc 0x7a1b2c lr 0x7a1b00 sp 0x7ff000
        --- frames ---
        #00 pc 0x1a2c /data/app/nm.txa.telex/lib/arm64/libtxaguard.so (Java_com_customkey_vietime_diag_TxaNativeGuard_nativeCrashTest+28)
        #01 lr 0x1a00 /data/app/nm.txa.telex/lib/arm64/libtxaguard.so (Java_com_customkey_vietime_diag_TxaNativeGuard_nativeCrashTest+0)
        #02 bt 0x2203c /apex/com.android.art/lib64/libart.so (art_quick_generic_jni_trampoline+92)
    """.trimIndent()

    @Test fun parse() {
        val c = TxaNativeGuard.parseDump(dump)!!
        assertEquals(TxaCrashKind.NATIVE, c.kind)
        assertEquals("SIGSEGV (SEGV_MAPERR)", c.title)
        assertEquals(1_760_000_000_123, c.time)
        assertTrue(c.thread.startsWith("RenderThread"))
        assertTrue(c.message.contains("0x10") && c.message.contains("arm64-v8a"))
        assertEquals(3, c.stack.lines().size)
        assertTrue(c.hint.contains("native"))
    }

    @Test fun fingerprintStableAcrossAddresses() {
        val a = TxaNativeGuard.parseDump(dump)!!
        val b = TxaNativeGuard.parseDump(dump.replace("0x1a2c", "0x1a3c").replace("+28", "+40"))!!
        assertEquals(a.fingerprint, b.fingerprint)
    }

    @Test fun rejectsGarbage() {
        assertNull(TxaNativeGuard.parseDump("rác"))
        assertNull(TxaNativeGuard.parseDump("TXA-NATIVE v1\npid=1"))
    }
}
