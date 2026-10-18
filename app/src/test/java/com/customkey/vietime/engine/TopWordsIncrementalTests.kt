// MIT License — Copyright (c) 2026 Phil Trinh
// Ported from ptrinh/viettelex tests; modified for com.customkey.vietime.

package com.customkey.vietime.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * topWords cập nhật TĂNG DẦN khi record (không sắp xếp lại cả uni mỗi dấu cách) phải ra
 * đúng như tính lại từ đầu — kể cả từ lạ vừa vượt ngưỡng gợi ý và từ hoà điểm.
 */
class TopWordsIncrementalTests {
    private val known = setOf("anh", "em", "đi", "học", "nhà", "ăn", "cơm", "chưa", "rồi", "nhé")

    private fun model() = UserLangModel().also { it.isKnownWord = { w -> w in known } }

    @Test fun incrementalMatchesFullRecompute() {
        val rnd = Random(7)
        val words = known.toList() + listOf("zalo", "okela", "hihi", "gg")   // từ lạ: cần ≥3 lần
        val m = model()
        val ref = model()
        repeat(400) { i ->
            val k = if (i % 3 == 0) 3 else 6
            val cached = m.topWords(k)                 // tạo/giữ cache
            val w = words[rnd.nextInt(words.size)]
            val wt = 1 + rnd.nextInt(2)
            m.record(w, null, weight = wt)
            ref.record(w, null, weight = wt)
            val inc = m.topWords(k)
            // Model đối chứng: cùng dữ liệu, cache bị xoá (setter isKnownWord) ⇒ sắp xếp đầy đủ.
            ref.isKnownWord = ref.isKnownWord
            val fresh = ref.topWords(k)
            assertEquals("lượt $i (trước: $cached)", fresh, inc)
        }
    }
}
