package com.sylvainrousseau.wellrest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Rest dial, used like a watch crown: drag anywhere around the ring to set the rest length.
 * One full turn = 60 s, a detent every 5 s, a firmer click on whole minutes, a bump at the limits.
 * The centre is a START / STOP button. While a rest runs, the ring drains with the countdown.
 */
class RestDialView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var onValueChanged: ((seconds: Int, final: Boolean) -> Unit)? = null
    var onStartStop: (() -> Unit)? = null

    /** Rest length in seconds (shown when idle). */
    var value = 60
        private set

    /** When true, the dial shows the running countdown instead of the setting. */
    var running = false
        set(v) {
            val wasRunning = field
            field = v
            if (v) { dragging = false; refilling = false }
            else if (wasRunning && isAttachedToWindow) startRefill()
            invalidate(); updateDescription()
        }

    private val minS = 15
    private val maxS = 600
    private val step = 5
    private val secondsPerTurn = 60f

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val haptics = Haptics(this)

    private val cGreen = ctx.getColor(R.color.green)
    private val cRing = ctx.getColor(R.color.ring)
    private val cText = ctx.getColor(R.color.text)
    private val cDim = ctx.getColor(R.color.text_dim)
    private val cFace = 0xFF0E1215.toInt()
    private val cOnGreen = 0xFF0E1215.toInt()

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = cRing; strokeCap = Paint.Cap.ROUND }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = cGreen; strokeCap = Paint.Cap.ROUND }
    private val lap = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = cGreen; alpha = 70 }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cFace }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cText }
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = cGreen }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cGreen; alpha = 50 }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cText; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.16f
    }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cText; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        isFakeBoldText = false
    }
    private val btn = Paint(Paint.ANTI_ALIAS_FLAG)
    private val btnText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); letterSpacing = 0.12f
    }

    private var cx = 0f
    private var cy = 0f
    private var r = 0f            // ring radius (centre of the stroke)
    private val oval = RectF()
    private val btnRect = RectF()

    // drag state
    private var dragging = false
    private var lastAngle = 0f
    private var accum = 0f        // unrounded seconds while dragging
    private var atLimit = false
    private var pressingButton = false

    // knob animation, in seconds (not degrees) so it moves continuously across each minute
    private var shownSec = 60f

    // refill animation after a rest is stopped or finishes
    private var refilling = false
    private var refillStart = 0L
    private var refillFromSec = 0f
    private var refillFromMs = 0L
    private var lastRunLeftMs = 0L
    private val refillMs = 950L
    private val refillEase = android.view.animation.OvershootInterpolator(1.3f)

    private fun startRefill() {
        refilling = true
        refillStart = android.os.SystemClock.uptimeMillis()
        refillFromSec = lastRunLeftMs / 1000f
        refillFromMs = lastRunLeftMs
    }

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateDescription()
    }

    fun setValue(seconds: Int, animate: Boolean = true) {
        value = seconds.coerceIn(minS, maxS)
        if (!animate && !refilling) shownSec = value.toFloat()
        invalidate(); updateDescription()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) w else min(w, h)
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f; cy = h / 2f
        val stroke = 12 * dp
        r = min(w, h) / 2f - stroke / 2 - 16 * dp    // room for the knob glow
        track.strokeWidth = stroke; arc.strokeWidth = stroke; lap.strokeWidth = stroke
        knobRing.strokeWidth = 3 * dp
        oval.set(cx - r, cy - r, cx + r, cy + r)
        label.textSize = 14 * sp
        big.textSize = r * 0.42f
        btnText.textSize = 15 * sp
        val bw = r * 0.78f; val bh = 50 * dp
        val by = cy + r * 0.36f
        btnRect.set(cx - bw / 2, by - bh / 2, cx + bw / 2, by + bh / 2)
        if (!refilling) shownSec = value.toFloat()
    }

    /**
     * Arc for a given number of seconds: one turn per minute. Completed minutes show as a faint
     * full ring underneath; exactly on a minute the ring is shown full and bright.
     */
    private fun sweepFor(sec: Float): Float {
        val laps = (sec / secondsPerTurn).toInt()
        val frac = sec - laps * secondsPerTurn
        return if (laps >= 1 && frac < 0.01f) 360f else frac / secondsPerTurn * 360f
    }

    override fun onDraw(canvas: Canvas) {
        // face + track
        canvas.drawCircle(cx, cy, r - track.strokeWidth / 2, face)
        canvas.drawCircle(cx, cy, r, track)

        if (running) drawRunning(canvas) else drawIdle(canvas)
    }

    private fun drawTicks(canvas: Canvas, litDeg: Float, faded: Boolean) {
        val inner = r - track.strokeWidth / 2 - 10 * dp
        for (i in 0 until 60) {
            val deg = i * 6f
            val major = i % 5 == 0
            val len = if (major) 10 * dp else 5 * dp
            val a = Math.toRadians((deg - 90).toDouble())
            val x1 = cx + (inner * cos(a)).toFloat(); val y1 = cy + (inner * sin(a)).toFloat()
            val x2 = cx + ((inner - len) * cos(a)).toFloat(); val y2 = cy + ((inner - len) * sin(a)).toFloat()
            val lit = deg < litDeg - 0.5f || litDeg >= 360f
            tick.strokeWidth = if (major) 2.5f * dp else 1.5f * dp
            tick.color = if (lit && !faded) cGreen else cRing
            tick.alpha = if (faded) 90 else if (lit) 255 else if (major) 200 else 140
            canvas.drawLine(x1, y1, x2, y2, tick)
        }
    }

    private fun drawIdle(canvas: Canvas) {
        val target = value.toFloat()
        var shownMs = value * 1000L
        if (refilling) {
            val t = ((android.os.SystemClock.uptimeMillis() - refillStart) / refillMs.toFloat()).coerceIn(0f, 1f)
            val p = refillEase.getInterpolation(t)            // overshoots slightly past 1
            shownSec = (refillFromSec + (target - refillFromSec) * p).coerceAtLeast(0f)
            val eased = 1 - (1 - t) * (1 - t) * (1 - t)
            shownMs = (refillFromSec * 1000 + (value * 1000L - refillFromSec * 1000) * eased).toLong().coerceIn(0, value * 1000L)
            if (t >= 1f) { refilling = false; shownSec = target; shownMs = value * 1000L; haptics.grab() }
            postInvalidateOnAnimation()
        } else {
            // ease the knob toward its detent; snappier while the finger is on the dial
            val diff = target - shownSec
            shownSec = if (abs(diff) < 0.02f) target else shownSec + diff * (if (dragging) 0.5f else 0.3f)
            if (shownSec != target) postInvalidateOnAnimation()
        }

        val sweep = sweepFor(shownSec)
        // completed minutes: faint full ring underneath
        if (shownSec >= secondsPerTurn) {
            lap.alpha = 70
            canvas.drawCircle(cx, cy, r, lap)
        }
        if (sweep > 0.5f) canvas.drawArc(oval, -90f, sweep, false, arc)
        drawTicks(canvas, sweep, faded = false)

        // knob: always follows the continuous position, never the wrapped angle
        val knobDeg = (shownSec % secondsPerTurn) / secondsPerTurn * 360f
        val a = Math.toRadians((knobDeg - 90).toDouble())
        val kx = cx + (r * cos(a)).toFloat(); val ky = cy + (r * sin(a)).toFloat()
        val kr = (if (dragging) 15f else 12f) * dp
        if (dragging) canvas.drawCircle(kx, ky, kr * 2.1f, glow)
        canvas.drawCircle(kx, ky, kr, knob)
        canvas.drawCircle(kx, ky, kr - knobRing.strokeWidth / 2, knobRing)

        // centre
        label.color = if (dragging) cGreen else cText
        canvas.drawText("REST", cx, cy - r * 0.36f, label)
        drawTime(canvas, shownMs)
        drawButton(canvas, "START", filled = true)
    }

    private fun drawRunning(canvas: Canvas) {
        val total = RestTimer.totalDurationMs.coerceAtLeast(1)
        val left = RestTimer.remainingMs
        val sweep = 360f * left / total
        lastRunLeftMs = left
        drawTicks(canvas, 0f, faded = true)
        if (sweep > 0.5f) canvas.drawArc(oval, -90f, sweep, false, arc)
        // leading dot
        val a = Math.toRadians((sweep - 90).toDouble())
        canvas.drawCircle(cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat(), 7 * dp, knob)

        label.color = cGreen
        canvas.drawText("RESTING", cx, cy - r * 0.36f, label)
        drawTime(canvas, left + 999)          // 1:00 → 0:01, like the notification
        drawButton(canvas, "STOP", filled = false)
        postInvalidateOnAnimation()
    }

    private fun drawTime(canvas: Canvas, ms: Long) {
        big.color = if (running) cGreen else cText
        val y = cy - (big.descent() + big.ascent()) / 2 - r * 0.04f
        canvas.drawText(RestTimer.fmt(ms), cx, y, big)
    }

    private fun drawButton(canvas: Canvas, text: String, filled: Boolean) {
        val rect = RectF(btnRect)
        if (pressingButton) rect.inset(btnRect.width() * 0.03f, btnRect.height() * 0.06f)
        val rad = rect.height() / 2
        if (filled) {
            btn.style = Paint.Style.FILL; btn.color = cGreen
            btnText.color = cOnGreen
        } else {
            btn.style = Paint.Style.STROKE; btn.strokeWidth = 2 * dp; btn.color = cGreen
            btnText.color = cGreen
        }
        canvas.drawRoundRect(rect, rad, rad, btn)
        canvas.drawText(text, rect.centerX(), rect.centerY() - (btnText.descent() + btnText.ascent()) / 2, btnText)
    }

    // ---- touch

    private fun angleOf(x: Float, y: Float) = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val inButton = btnRect.contains(e.x, e.y) ||
                    hypot(e.x - btnRect.centerX(), e.y - btnRect.centerY()) < btnRect.height()
                if (inButton) {
                    pressingButton = true; invalidate(); return true
                }
                // anywhere else on the dial grabs the crown (ignore the very centre)
                if (running || hypot(e.x - cx, e.y - cy) < r * 0.3f) return false
                dragging = true
                if (refilling) { refilling = false; shownSec = value.toFloat() }
                lastAngle = angleOf(e.x, e.y)
                accum = value.toFloat()
                atLimit = false
                parent?.requestDisallowInterceptTouchEvent(true)
                haptics.grab()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressingButton) {
                    val still = btnRect.contains(e.x, e.y)
                    if (!still) { pressingButton = false; invalidate() }
                    return true
                }
                if (!dragging) return false
                val ang = angleOf(e.x, e.y)
                var d = ang - lastAngle
                if (d > 180) d -= 360f
                if (d < -180) d += 360f
                lastAngle = ang
                accum = (accum + d / 360f * secondsPerTurn).coerceIn(minS - 2f, maxS + 2f)
                val stepped = ((accum / step).roundToInt() * step).coerceIn(minS, maxS)
                if (stepped != value) {
                    val crossedMinute = stepped / 60 != value / 60 || stepped % 60 == 0
                    value = stepped
                    if (crossedMinute) haptics.minute() else haptics.detent()
                    atLimit = false
                    onValueChanged?.invoke(value, false)
                    updateDescription()
                    invalidate()
                } else if ((accum < minS - 1f || accum > maxS + 1f) && !atLimit) {
                    atLimit = true
                    haptics.limit()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pressingButton) {
                    pressingButton = false; invalidate()
                    if (e.actionMasked == MotionEvent.ACTION_UP && btnRect.contains(e.x, e.y)) {
                        haptics.confirm()
                        performClick()
                    }
                    return true
                }
                if (dragging) {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    onValueChanged?.invoke(value, true)
                    invalidate()
                    return true
                }
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onStartStop?.invoke()
        return true
    }

    // ---- accessibility: behaves like a slider with a click action

    private fun updateDescription() {
        val m = value / 60; val s = value % 60
        val len = listOfNotNull(if (m > 0) "$m minute${if (m > 1) "s" else ""}" else null,
            if (s > 0) "$s seconds" else null).joinToString(" ")
        contentDescription = if (running) "Rest running. Double-tap to stop."
        else "Rest length $len. Swipe up or down to adjust. Double-tap to start."
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        if (!running) {
            if (value < maxS) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            if (value > minS) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val delta = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 15
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -15
            else -> return super.performAccessibilityAction(action, arguments)
        }
        if (running) return false
        setValue(value + delta)
        onValueChanged?.invoke(value, true)
        return true
    }
}
