// MIT License — Copyright (c) 2026 Phil Trinh
// Ported from ptrinh/viettelex tests; modified for com.customkey.vietime.

package com.customkey.vietime.engine

import org.junit.Assert.assertTrue
import org.junit.Test

/** Micro-benchmark (prints ns/keystroke) — mirrors TelexCore BenchmarkTests' corpus. */
class BenchmarkTest {
    private val vietnamese = ("Phil Trinhj (Trinhj Minh Phucs) laf mootj nhaf sangs laapj ddaa linhx vuwcj sinh ra taij " +
        "Haf Nooij, hieenj soongs taij Singapore. Anh du hocj ngaanhf Khoa hocj Mays tinhs taij " +
        "DDaij hocj Drexel (Myx), sau ddos lamf kyx suw phaanf meemf taij SIG, Zalora vaf Grab. " +
        "Hieenj anh ddoongf ddieeuf hanhf ba coong ty goomf SenPrints (SaaS thuwowng maij ddieenj " +
        "tuwr/print-on-demand), Printik (in aans taij Myx) vaf AloRide (cho thuee xe mays), " +
        "vowis beef dayf kinh nghieemj veef kyx thuaatj vaf xaay duwngj doanh nghieepj.")
        .split(' ', '(', ')', ',', '.', '/', '-').filter { it.isNotEmpty() }

    private fun feedAll(e: TelexEngine, words: List<String>): Int {
        var keys = 0
        for (w in words) {
            for (c in w) { e.feed(c); keys++ }
            e.peekCommitText(true)          // iOS suggestion bar peeks every word
            e.commitBoundary(true); keys++
        }
        return keys
    }

    private fun bench(name: String, cfg: TelexEngine.() -> Unit, words: List<String>): Double {
        val e = TelexEngine().apply(cfg)
        repeat(3000) { feedAll(e, words) }            // JIT warm-up
        val iters = 5000
        val t0 = System.nanoTime()
        var keys = 0
        repeat(iters) { keys += feedAll(e, words) }
        val ns = (System.nanoTime() - t0).toDouble() / keys
        val bytesPerKey = -1.0   // ThreadMXBean không có trên classpath android.jar
        println("BENCH %-28s %7.1f ns/keystroke  %6.1f B/keystroke".format(name, ns, bytesPerKey))
        return ns
    }

    @Test fun perKeystrokeLatency() {
        val ios = bench("iOS defaults (Telex)", { freeMarking = true; simpleTelex = true; liveSpellCheck = true; contextualEnglish = true; teencode = false }, vietnamese)
        bench("engine defaults (Telex)", {}, vietnamese)
        bench("English (folded)", { liveSpellCheck = true }, vietnamese.map { w -> w.filter { it !in "sfrxj" } })
        assertTrue("avg keystroke $ios ns exceeds 50µs budget", ios < 50_000)
    }
}
