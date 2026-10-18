package com.customkey.vietime.ime

import kotlin.math.abs

/**
 * Cử chỉ trên phím Cách (toán thuần, đơn vị px + ms; KeyboardView đưa sự kiện vào):
 * - Chạm nhanh, không dời ⇒ [Result.TAP] (dấu cách).
 * - Vuốt ngang nhanh (≥ [Params.flickMinPx] trong ≤ [Params.flickMaxMs]) rồi nhấc ⇒ [Result.FLICK]
 *   (đổi VI ↔ EN).
 * - Giữ ≥ [Params.longPressMs] HOẶC kéo chậm ⇒ trackpad: [move] trả số bước con trỏ (ngang = ký tự,
 *   dọc = dòng) kể từ lần trước.
 */
class SpaceGesture(var params: Params = Params()) {
    data class Params(
        val longPressMs: Long = 300,
        val flickMinPx: Float = 120f,
        val flickMaxMs: Long = 350,
        /** Px mỗi bước con trỏ ngang (~1/3 phím). */
        val stepPx: Float = 28f,
        /** Px mỗi dòng khi kéo dọc. */
        val lineStepPx: Float = 90f,
        /** Dời tối thiểu để không còn là chạm. */
        val slopPx: Float = 18f,
    )

    enum class State { IDLE, PENDING, TRACKPAD }
    enum class Result { NONE, TAP, FLICK, TRACKPAD_END }

    /** Bước con trỏ cần dời. */
    data class Steps(val dx: Int, val dy: Int) { val isZero get() = dx == 0 && dy == 0 }

    var state = State.IDLE; private set
    private var x0 = 0f; private var y0 = 0f; private var t0 = 0L
    private var ax = 0f; private var ay = 0f
    private var emittedX = 0; private var emittedY = 0
    private var maxDx = 0f
    /** Hướng vuốt cuối (âm = trái) cho FLICK. */
    var flickDirection = 0; private set

    fun down(x: Float, y: Float, t: Long) {
        state = State.PENDING
        x0 = x; y0 = y; t0 = t
        maxDx = 0f; flickDirection = 0
        emittedX = 0; emittedY = 0
    }

    /** Hẹn giờ long-press của View gọi khi giữ đủ lâu mà chưa nhấc. */
    fun longPressElapsed(x: Float, y: Float): Boolean {
        if (state != State.PENDING) return false
        startTrackpad(x, y); return true
    }

    private fun startTrackpad(x: Float, y: Float) {
        state = State.TRACKPAD
        ax = x; ay = y; emittedX = 0; emittedY = 0
    }

    fun move(x: Float, y: Float, t: Long): Steps {
        when (state) {
            State.IDLE -> return ZERO
            State.PENDING -> {
                val dx = x - x0
                if (abs(dx) > abs(maxDx)) maxDx = dx
                val elapsed = t - t0
                val moved = abs(dx) > params.slopPx || abs(y - y0) > params.slopPx
                if (elapsed >= params.longPressMs || (moved && elapsed > params.flickMaxMs)) {
                    startTrackpad(x, y)
                }
                return ZERO
            }
            State.TRACKPAD -> {
                val sx = ((x - ax) / params.stepPx).toInt()
                val sy = ((y - ay) / params.lineStepPx).toInt()
                val out = Steps(sx - emittedX, sy - emittedY)
                emittedX = sx; emittedY = sy
                return out
            }
        }
    }

    fun up(x: Float, t: Long): Result {
        val s = state
        state = State.IDLE
        return when (s) {
            State.IDLE -> Result.NONE
            State.TRACKPAD -> Result.TRACKPAD_END
            State.PENDING -> {
                val dx = x - x0
                if (abs(dx) > abs(maxDx)) maxDx = dx
                if (abs(maxDx) >= params.flickMinPx && t - t0 <= params.flickMaxMs) {
                    flickDirection = if (maxDx < 0) -1 else 1
                    Result.FLICK
                } else if (abs(dx) <= params.slopPx * 2) Result.TAP else Result.NONE
            }
        }
    }

    fun cancel() { state = State.IDLE }

    companion object { private val ZERO = Steps(0, 0) }
}

/**
 * Vuốt trái trên phím ⌫: mỗi [Params.wordStepPx] thêm một từ (kéo ngược lại giảm, về điểm
 * đầu = huỷ). Nhấc tay ⇒ IME xoá đúng số từ và hiện nút Hoàn tác.
 */
class BackspaceSwipe(var params: Params = Params()) {
    data class Params(
        /** Kéo trái tối thiểu để bắt đầu chế độ xoá theo từ. */
        val startPx: Float = 40f,
        val wordStepPx: Float = 60f,
        val maxWords: Int = 50,
    )

    var active = false; private set
    var swiping = false; private set
    private var x0 = 0f
    /** Số từ sẽ xoá nếu nhấc tay bây giờ. */
    var words = 0; private set

    fun down(x: Float) { active = true; swiping = false; x0 = x; words = 0 }

    /** Trả true nếu số từ đổi (View cập nhật xem trước). */
    fun move(x: Float): Boolean {
        if (!active) return false
        val pull = x0 - x
        if (!swiping && pull < params.startPx) return false
        swiping = true
        val n = if (pull < params.startPx * 0.5f) 0
            else (1 + ((pull - params.startPx) / params.wordStepPx).toInt()).coerceIn(1, params.maxWords)
        val changed = n != words
        words = n
        return changed
    }

    /** Nhấc tay: số từ cần xoá (0 = không xoá / huỷ). */
    fun up(): Int { val n = if (swiping) words else 0; reset(); return n }

    fun reset() { active = false; swiping = false; words = 0 }
}
