package com.customkey.vietime.ui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.customkey.vietime.engine.GestureClassifier
import com.customkey.vietime.engine.SwipeLayout
import com.customkey.vietime.engine.SwipePath
import com.customkey.vietime.engine.SwipeSuggest
import com.customkey.vietime.ime.BackspaceSwipe
import com.customkey.vietime.ime.EnterAction
import com.customkey.vietime.ime.FieldKind
import com.customkey.vietime.ime.SpaceGesture
import kotlin.math.max

/**
 * Vùng phím vẽ bằng Canvas (độ trễ chạm thấp, không cấp phát khi vẽ). Không biết gì về
 * InputConnection: mọi phím đi qua [Listener]; IME / khu Test phím nối vào.
 *
 * Chạm: phím chữ chốt lúc NHẤC (hoặc khi ngón khác chạm xuống — gõ nhanh hai ngón không mất
 * phím); giữ phím chữ ra ký tự phụ; một ngón vuốt qua các chữ = gõ vuốt; phím Cách giữ =
 * trackpad, vuốt nhanh = đổi VI/EN; ⌫ giữ = xoá lặp, vuốt trái = xoá theo từ.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onLetter(ch: Char)
        fun onText(text: String)
        fun onSpace()
        fun onBackspace()
        fun onEnter()
        fun onGlobe(longPress: Boolean)
        /** Phản hồi chạm (âm thanh + rung) — [kind] phân biệt phím thường / ⌫ / cách / trackpad. */
        fun onFeedback(kind: FeedbackKind) {}
        fun onTrackpadStart() {}
        fun onTrackpadMove(dx: Int, dy: Int) {}
        fun onTrackpadEnd() {}
        fun onLanguageFlick() {}
        /** Đang vuốt ⌫: xem trước [words] từ sẽ xoá (0 = huỷ). */
        fun onDeleteWordsPreview(words: Int) {}
        fun onDeleteWords(words: Int) {}
        fun onSwipeLayout(layout: SwipeLayout) {}
        /** Bắt đầu nét vuốt — false = không cho gõ vuốt ở ô này. */
        fun onSwipeTypingStart(): Boolean = false
        fun onSwipeTypingEnd(path: SwipePath, case: SwipeSuggest.Case) {}
    }

    enum class FeedbackKind { KEY, DELETE, SPACE, MODIFIER, TRACKPAD_TICK }
    enum class Shift { OFF, ON, LOCK }

    var listener: Listener? = null

    var style: KeyboardStyle = KeyboardStyle.light
        set(v) { field = v; applyStyle(); invalidate() }

    var plane = KeyboardPlane.LETTERS
        set(v) { if (field != v) { field = v; relayout() } }

    var fieldKind = FieldKind.TEXT
        set(v) { if (field != v) { field = v; relayout() } }

    var enterAction = EnterAction.NEWLINE
        set(v) { if (field != v) { field = v; relayout() } }

    var options = LayoutOptions()
        set(v) { if (field != v) { field = v; relayout() } }

    /** Nhãn phím cách ("Tiếng Việt" / "English"). */
    var spaceLabel = ""
        set(v) { if (field != v) { field = v; invalidate() } }

    var shift = Shift.OFF
        set(v) { if (field != v) { field = v; invalidate() } }

    var swipeTyping = false
        set(v) { field = v; publishSwipeLayout() }

    var longPressDelayMs = 300L
    var rowHeightDp = 54f
        set(v) { field = v; requestLayout() }

    private val d = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private var keys: List<LaidKey> = emptyList()
    private val handler = Handler(Looper.getMainLooper())

    // MARK: vẽ

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val trail = Path()
    private var trailActive = false

    private fun applyStyle() {
        val s = style
        labelPaint.textSize = s.labelSizeSp * sp
        smallPaint.textSize = 14f * sp
        hintPaint.textSize = 10f * sp
        borderPaint.strokeWidth = s.borderWidthDp * d
        borderPaint.color = s.borderColor
        shadowPaint.color = s.keyShadow
        trailPaint.color = s.accent
        trailPaint.alpha = 160
        trailPaint.strokeWidth = 5f * d
    }

    init { applyStyle(); isHapticFeedbackEnabled = false }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val rows = KeyboardLayout.rows(plane, fieldKind, enterAction, options).size
        val h = (rows * rowHeightDp * d + 4 * d).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { relayout() }

    private fun relayout() {
        val w = width.toFloat()
        if (w <= 0f) { requestLayout(); return }
        val rows = KeyboardLayout.rows(plane, fieldKind, enterAction, options)
        val rh = rowHeightDp * d
        keys = KeyboardLayout.place(rows, w, rh, 2 * d, options)
        cancelAllTouches()
        publishSwipeLayout()
        requestLayout()
        invalidate()
    }

    private fun publishSwipeLayout() {
        if (!swipeTyping || plane != KeyboardPlane.LETTERS || keys.isEmpty()) { swipeLayoutReady = null; return }
        val m = KeyboardLayout.letterCenters(keys)
        val q = m['q'] ?: return
        val w = m['w'] ?: return
        val kw = w.first - q.first
        if (kw <= 0f) return
        val l = SwipeLayout(kw, m)
        if (l == swipeLayoutReady) return
        swipeLayoutReady = l
        swipePath = SwipePath(minDistance = kw / 5f)
        listener?.onSwipeLayout(l)
    }

    /** false = nền do [KeyboardPanel] vẽ (màu + ảnh nền phủ cả thanh gợi ý). */
    var drawsBackground = true

    override fun onDraw(canvas: Canvas) {
        val s = style
        if (drawsBackground) canvas.drawColor(s.background)
        val gx = s.keyGapDp * d / 2f
        val gy = s.rowGapDp * d / 2f
        val r = s.cornerRadiusDp * d
        for (k in keys) {
            val pressed = pressedKeys.contains(k)
            val fill = when {
                pressed -> s.pressedFill
                k.def.action is KeyAction.Shift && shift != Shift.OFF -> s.keyFill
                k.def.modifier -> s.modifierFill
                else -> s.keyFill
            }
            rect.set(k.left + gx, k.top + gy, k.right - gx, k.bottom - gy)
            if (s.keyOpacity >= 0.99f) {
                rect.offset(0f, d); canvas.drawRoundRect(rect, r, r, shadowPaint); rect.offset(0f, -d)
            }
            keyPaint.color = withAlpha(fill, s.keyOpacity)
            canvas.drawRoundRect(rect, r, r, keyPaint)
            if (s.borderWidthDp > 0f) canvas.drawRoundRect(rect, r, r, borderPaint)
            drawLabel(canvas, k, pressed)
        }
        altPopup?.let { drawAltPopup(canvas, it) }
        if (trailActive) canvas.drawPath(trail, trailPaint)
    }

    private fun drawLabel(canvas: Canvas, k: LaidKey, pressed: Boolean) {
        val s = style
        val color = withAlpha(if (k.def.modifier) s.modifierLabel else s.label, s.labelOpacity)
        val a = k.def.action
        val label = when (a) {
            is KeyAction.Letter -> if (shift != Shift.OFF) k.def.label.uppercase() else k.def.label
            is KeyAction.Space -> spaceLabel
            is KeyAction.Shift -> if (shift == Shift.LOCK) "⇪" else "⇧"
            else -> k.def.label
        }
        val paint = if (a is KeyAction.Letter || (a is KeyAction.Text && label.length == 1)) labelPaint else smallPaint
        paint.color = color
        paint.typeface = if (a is KeyAction.Shift && shift != Shift.OFF) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        if (a is KeyAction.Shift && shift != Shift.OFF) paint.color = s.accent
        val y = k.centerY - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(label, k.centerX, y, paint)
        // Gợi ý ký tự phụ (số hàng trên) ở góc phải trên.
        if (plane == KeyboardPlane.LETTERS && !options.numberRow && a is KeyAction.Letter && k.row == 0 && k.def.alternates.isNotEmpty()) {
            hintPaint.color = withAlpha(s.label, 0.55f * s.labelOpacity)
            canvas.drawText(k.def.alternates[0], k.right - 6 * d, k.top + 16 * d, hintPaint)
        }
    }

    private fun drawAltPopup(canvas: Canvas, p: AltPopup) {
        val s = style
        val r = s.cornerRadiusDp * d
        rect.set(p.left, p.top, p.left + p.cell * p.items.size, p.top + p.height)
        keyPaint.color = s.keyFill
        canvas.drawRoundRect(rect, r, r, shadowPaint)
        canvas.drawRoundRect(rect, r, r, keyPaint)
        for ((i, it) in p.items.withIndex()) {
            val cx = p.left + p.cell * (i + 0.5f)
            if (i == p.selected) {
                rect.set(p.left + p.cell * i + 2 * d, p.top + 2 * d, p.left + p.cell * (i + 1) - 2 * d, p.top + p.height - 2 * d)
                keyPaint.color = s.accent
                canvas.drawRoundRect(rect, r, r, keyPaint)
            }
            val paint = if (it.length == 1) labelPaint else smallPaint
            paint.color = if (i == p.selected) 0xFFFFFFFF.toInt() else s.label
            canvas.drawText(it, cx, p.top + p.height / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        }
    }

    private fun withAlpha(c: Int, a: Float): Int {
        val base = (c ushr 24) and 0xFF
        val na = (base * a.coerceIn(0f, 1f)).toInt()
        return (c and 0x00FFFFFF) or (na shl 24)
    }

    // MARK: chạm

    private class Touch(val key: LaidKey, val downX: Float, val downY: Float, val downT: Long) {
        var consumed = false
        var longFired = false
    }

    private class AltPopup(val key: LaidKey, val items: List<String>, val left: Float, val top: Float,
                           val cell: Float, val height: Float, var selected: Int)

    private val touches = HashMap<Int, Touch>()
    private val pressedKeys = HashSet<LaidKey>()
    private var altPopup: AltPopup? = null
    private val longPressRuns = HashMap<Int, Runnable>()

    private val spaceGesture = SpaceGesture()
    private var spacePid = -1
    private val backSwipe = BackspaceSwipe()
    private var backPid = -1
    private var backRepeating = false
    private var backRepeatCount = 0

    private val classifier = GestureClassifier()
    private var swipeLayoutReady: SwipeLayout? = null
    private var swipePath: SwipePath? = null
    private var swipePid = -1
    private var swiping = false
    private var swipeShift = Shift.OFF
    private var lastLetterAt = 0L

    private var lastShiftTap = 0L

    private fun keyAt(x: Float, y: Float): LaidKey? {
        keys.firstOrNull { it.contains(x, y) }?.let { return it }
        // ngoài mọi phím (lề trên/dưới): phím gần nhất theo khoảng cách tâm
        return keys.minByOrNull { val dx = it.centerX - x; val dy = it.centerY - y; dx * dx + dy * dy }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                pointerDown(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) pointerMove(e, i)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                pointerUp(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> cancelAllTouches()
        }
        return true
    }

    private fun pointerDown(pid: Int, x: Float, y: Float, t: Long) {
        // Ngón mới chạm ⇒ chốt ngay phím chữ của các ngón đang giữ (gõ lăn hai ngón đúng thứ tự).
        for ((opid, ot) in touches) if (opid != spacePid && opid != backPid) flushLetter(ot)
        if (swipePid >= 0 && !swiping) stopSwipeTracking()
        if (swiping) return   // đang gõ vuốt: bỏ qua ngón thứ hai
        val k = keyAt(x, y) ?: return
        val touch = Touch(k, x, y, t)
        touches[pid] = touch
        pressedKeys.add(k)
        when (val a = k.def.action) {
            is KeyAction.Space -> {
                spacePid = pid
                spaceGesture.params = spaceGesture.params.copy(longPressMs = longPressDelayMs + 100, stepPx = k.width / 14f,
                    flickMinPx = max(80f * d, rowHeightDp * d * 1.6f), lineStepPx = rowHeightDp * d)
                spaceGesture.down(x, y, t)
                schedule(pid, longPressDelayMs + 100) {
                    if (spaceGesture.longPressElapsed(x, y)) { listener?.onTrackpadStart(); listener?.onFeedback(FeedbackKind.TRACKPAD_TICK); invalidate() }
                }
                listener?.onFeedback(FeedbackKind.SPACE)
            }
            is KeyAction.Backspace -> {
                backPid = pid
                backSwipe.params = backSwipe.params.copy(startPx = 24 * d, wordStepPx = 36 * d)
                backSwipe.down(x)
                backRepeating = false; backRepeatCount = 0
                schedule(pid, longPressDelayMs + 100) { startBackRepeat(pid) }
                listener?.onFeedback(FeedbackKind.DELETE)
            }
            is KeyAction.Letter -> {
                listener?.onFeedback(FeedbackKind.KEY)
                if (k.def.alternates.isNotEmpty()) schedule(pid, longPressDelayMs) { showAlternates(pid) }
                if (touches.size == 1) startSwipeTracking(pid, k, x, y, t)
            }
            is KeyAction.Text -> {
                listener?.onFeedback(FeedbackKind.KEY)
                if (k.def.alternates.isNotEmpty()) schedule(pid, longPressDelayMs) { showAlternates(pid) }
            }
            is KeyAction.Globe -> {
                listener?.onFeedback(FeedbackKind.MODIFIER)
                schedule(pid, longPressDelayMs + 200) { touches[pid]?.let { it.longFired = true; listener?.onGlobe(true) } }
            }
            else -> {
                listener?.onFeedback(FeedbackKind.MODIFIER)
                if (a is KeyAction.Shift) onShiftDown()
            }
        }
        invalidate()
    }

    private fun pointerMove(e: MotionEvent, i: Int) {
        val pid = e.getPointerId(i)
        val t = touches[pid] ?: return
        val x = e.getX(i); val y = e.getY(i)
        if (pid == spacePid) {
            val steps = spaceGesture.move(x, y, e.eventTime)
            if (spaceGesture.state == SpaceGesture.State.TRACKPAD) {
                if (!t.longFired) { t.longFired = true; cancelSchedule(pid); listener?.onTrackpadStart() }
                if (!steps.isZero) { listener?.onTrackpadMove(steps.dx, steps.dy); listener?.onFeedback(FeedbackKind.TRACKPAD_TICK) }
            }
            return
        }
        if (pid == backPid) {
            if (backSwipe.move(x)) {
                if (!backRepeating) cancelSchedule(pid)
                listener?.onDeleteWordsPreview(backSwipe.words)
                listener?.onFeedback(FeedbackKind.TRACKPAD_TICK)
            }
            if (backSwipe.swiping) { stopBackRepeat(); cancelSchedule(pid) }
            return
        }
        altPopup?.takeIf { it.key === t.key }?.let { p ->
            val idx = ((x - p.left) / p.cell).toInt().coerceIn(0, p.items.size - 1)
            if (idx != p.selected) { p.selected = idx; invalidate() }
            return
        }
        if (pid == swipePid) swipeMove(e, i)
    }

    private fun pointerUp(pid: Int, x: Float, y: Float, tm: Long) {
        val t = touches.remove(pid) ?: return
        cancelSchedule(pid)
        pressedKeys.remove(t.key)
        val k = t.key
        when (val a = k.def.action) {
            is KeyAction.Space -> {
                spacePid = -1
                when (spaceGesture.up(x, tm)) {
                    SpaceGesture.Result.TAP -> listener?.onSpace()
                    SpaceGesture.Result.FLICK -> listener?.onLanguageFlick()
                    SpaceGesture.Result.TRACKPAD_END -> listener?.onTrackpadEnd()
                    SpaceGesture.Result.NONE -> {}
                }
            }
            is KeyAction.Backspace -> {
                backPid = -1
                val words = backSwipe.up()
                val repeated = backRepeating
                stopBackRepeat()
                if (words > 0) listener?.onDeleteWords(words)
                else if (backSwipeWasActive(t, x)) listener?.onDeleteWordsPreview(0)
                else if (!repeated) listener?.onBackspace()
            }
            is KeyAction.Letter -> {
                if (pid == swipePid) {
                    if (swiping) { endSwipe(x, y, tm); return }
                    stopSwipeTracking()
                }
                commitAltOrKey(t)
            }
            is KeyAction.Text -> commitAltOrKey(t)
            is KeyAction.Shift -> {}
            is KeyAction.Enter -> listener?.onEnter()
            is KeyAction.Plane -> plane = a.target
            is KeyAction.Globe -> if (!t.longFired) listener?.onGlobe(false)
        }
        invalidate()
    }

    private fun backSwipeWasActive(t: Touch, x: Float) = t.downX - x > 24 * d

    private fun commitAltOrKey(t: Touch) {
        val k = t.key
        val popup = altPopup
        if (popup != null && popup.key === k) {
            altPopup = null
            listener?.onText(popup.items[popup.selected])
            consumeShiftOnce()
            return
        }
        if (!t.consumed) emitKey(k)
    }

    /** Chốt phím chữ của ngón đang giữ (ngón khác chạm xuống). */
    private fun flushLetter(t: Touch) {
        if (t.consumed || altPopup?.key === t.key) return
        val a = t.key.def.action
        if (a is KeyAction.Letter || a is KeyAction.Text) {
            t.consumed = true
            cancelScheduleFor(t)
            emitKey(t.key)
        }
    }

    private fun emitKey(k: LaidKey) {
        when (val a = k.def.action) {
            is KeyAction.Letter -> {
                val ch = if (shift != Shift.OFF) a.ch.uppercaseChar() else a.ch
                lastLetterAt = SystemClock.uptimeMillis()
                listener?.onLetter(ch)
                consumeShiftOnce()
            }
            is KeyAction.Text -> {
                listener?.onText(a.text)
                // ký hiệu kết thúc câu ở plane ký hiệu ⇒ quay về chữ sau dấu cách (như iOS): giữ nguyên
            }
            else -> {}
        }
    }

    private fun consumeShiftOnce() { if (shift == Shift.ON) shift = Shift.OFF }

    private fun onShiftDown() {
        val now = SystemClock.uptimeMillis()
        shift = when {
            shift == Shift.LOCK -> Shift.OFF
            now - lastShiftTap < 350 -> Shift.LOCK
            shift == Shift.ON -> Shift.OFF
            else -> Shift.ON
        }
        lastShiftTap = now
    }

    /** IME đặt shift tự động (đầu câu) — không đè Caps Lock. */
    fun setAutoShift(on: Boolean) {
        if (shift == Shift.LOCK) return
        shift = if (on) Shift.ON else Shift.OFF
    }

    private fun showAlternates(pid: Int) {
        val t = touches[pid] ?: return
        if (t.consumed) return
        val k = t.key
        if (pid == swipePid && swiping) return
        if (pid == swipePid) stopSwipeTracking()
        t.longFired = true
        val items = k.def.alternates
        val cell = k.width
        val h = k.bottom - k.top
        val total = cell * items.size
        val left = (k.centerX - cell / 2f).coerceIn(0f, max(0f, width - total))
        // Hàng trên cùng không có chỗ phía trên trong cửa sổ IME ⇒ popup đè lên chính hàng đó.
        val top = if (k.row == 0) k.top else k.top - h
        altPopup = AltPopup(k, items, left, top, cell, h, 0)
        listener?.onFeedback(FeedbackKind.MODIFIER)
        invalidate()
    }

    private fun startBackRepeat(pid: Int) {
        if (touches[pid] == null || backSwipe.swiping) return
        backRepeating = true
        val run = object : Runnable {
            override fun run() {
                if (!backRepeating || backPid != pid) return
                listener?.onBackspace()
                backRepeatCount++
                handler.postDelayed(this, if (backRepeatCount > 20) 35 else 60)
            }
        }
        longPressRuns[pid] = run
        run.run()
    }

    private fun stopBackRepeat() { backRepeating = false }

    private fun schedule(pid: Int, delay: Long, block: () -> Unit) {
        cancelSchedule(pid)
        val r = Runnable { longPressRuns.remove(pid); block() }
        longPressRuns[pid] = r
        handler.postDelayed(r, delay)
    }

    private fun cancelSchedule(pid: Int) { longPressRuns.remove(pid)?.let { handler.removeCallbacks(it) } }
    private fun cancelScheduleFor(t: Touch) { touches.entries.firstOrNull { it.value === t }?.let { cancelSchedule(it.key) } }

    fun cancelAllTouches() {
        for (pid in longPressRuns.keys.toList()) cancelSchedule(pid)
        touches.clear(); pressedKeys.clear(); altPopup = null
        if (spacePid >= 0) { if (spaceGesture.state == SpaceGesture.State.TRACKPAD) listener?.onTrackpadEnd(); spaceGesture.cancel() }
        if (backSwipe.swiping) listener?.onDeleteWordsPreview(0)
        spacePid = -1; backPid = -1; backSwipe.reset(); stopBackRepeat()
        stopSwipeTracking()
        trailActive = false; trail.reset()
        invalidate()
    }

    // MARK: gõ vuốt

    private fun startSwipeTracking(pid: Int, k: LaidKey, x: Float, y: Float, t: Long) {
        val l = swipeLayoutReady ?: return
        val p = swipePath ?: return
        val since = if (lastLetterAt == 0L) Long.MAX_VALUE else SystemClock.uptimeMillis() - lastLetterAt
        classifier.begin(x / d, y / d, t, k.left / d, k.top / d, k.right / d, k.bottom / d, l.keyWidth / d, since)
        p.reset()
        p.add(x, y, t / 1000.0)
        swipePid = pid; swipeShift = shift
        trail.reset(); trail.moveTo(x, y)
    }

    private fun stopSwipeTracking() {
        classifier.end()
        swipePid = -1
        swiping = false
    }

    private fun swipeMove(e: MotionEvent, i: Int) {
        for (h in 0 until e.historySize) {
            swipePoint(e.getHistoricalX(i, h), e.getHistoricalY(i, h), e.getHistoricalEventTime(h))
            if (swipePid < 0) return
        }
        swipePoint(e.getX(i), e.getY(i), e.eventTime)
    }

    private fun swipePoint(x: Float, y: Float, t: Long) {
        val p = swipePath ?: return
        p.add(x, y, t / 1000.0)
        if (swiping) { trail.lineTo(x, y); invalidate(); return }
        when (classifier.move(x / d, y / d, t)) {
            GestureClassifier.State.SWIPE -> beginSwipe(x, y)
            GestureClassifier.State.TAP -> stopSwipeTracking()
            else -> Unit
        }
    }

    private fun beginSwipe(x: Float, y: Float) {
        if (listener?.onSwipeTypingStart() != true) { stopSwipeTracking(); return }
        swiping = true
        touches[swipePid]?.let { it.consumed = true; cancelSchedule(swipePid); pressedKeys.remove(it.key) }
        altPopup = null
        trailActive = true
        trail.lineTo(x, y)
        invalidate()
    }

    private fun endSwipe(x: Float, y: Float, t: Long) {
        val p = swipePath
        val case = when (swipeShift) {
            Shift.LOCK -> SwipeSuggest.Case.ALL
            Shift.ON -> SwipeSuggest.Case.FIRST
            Shift.OFF -> SwipeSuggest.Case.LOWER
        }
        stopSwipeTracking()
        trailActive = false; trail.reset()
        consumeShiftOnce()
        invalidate()
        if (p != null) {
            p.add(x, y, t / 1000.0, force = true)
            listener?.onSwipeTypingEnd(p, case)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAllTouches()
    }

    /** Tổng chiều cao mong muốn (px) — IME dùng tính insets. */
    fun desiredHeight(): Int = (KeyboardLayout.rows(plane, fieldKind, enterAction, options).size * rowHeightDp * d + 4 * d).toInt()
}
