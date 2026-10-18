// MIT License — Copyright (c) 2026 Phil Trinh
// Ported from ptrinh/viettelex tests; modified for com.customkey.vietime.

package com.customkey.vietime.engine

import org.junit.Assume.assumeTrue

/**
 * Test NẶNG / đo đạc (heldout độ chính xác, độ trễ) — tách khỏi bộ mặc định (cùng danh sách
 * với iOS KeyboardTests/SlowTests.swift) vì làm runner chậm/bị kill khi máy tải cao và số đo
 * thời gian dao động. Vẫn là ngưỡng hồi quy: phải PASS khi chạy riêng.
 *
 * Chạy: `VT_SLOW_TESTS=1 ./gradlew --offline :keyboard:test` (build.gradle.kts khai biến này
 * là input của task test nên đổi giá trị là chạy lại, không bị UP-TO-DATE).
 */
object SlowTests {
    val enabled: Boolean get() = System.getenv("VT_SLOW_TESTS") == "1"

    fun assume() = assumeTrue("Test chậm — bật bằng VT_SLOW_TESTS=1", enabled)
}
