package com.bdokgg.dice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Draws 1–6 dice on a dark table and animates them tumbling when rolled. */
class DiceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var count: Int = 2
        set(value) {
            val v = value.coerceIn(1, MAX_DICE)
            if (v == field && dice.size == v) return
            field = v
            val old = dice.map { it.face }
            dice = List(v) { i -> Die(old.getOrElse(i) { DEFAULT_FACES[i] }) }
            updateDescription()
            invalidate()
        }

    val faces: IntArray get() = IntArray(dice.size) { dice[it].face }
    val isRolling: Boolean get() = animator?.isRunning == true

    /** Called whenever a tumbling die shows a new face (for haptic ticks). */
    var onFaceChange: (() -> Unit)? = null

    private var dice: List<Die> = List(count) { Die(DEFAULT_FACES[it]) }
    private var animator: ValueAnimator? = null
    private val density = resources.displayMetrics.density

    private val tablePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tableBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = ORANGE
        alpha = 150
    }
    private val tableRect = RectF()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(196, 186, 170) }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(60, 0, 0, 0)
    }
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pipRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 255, 255) }
    private val rect = RectF()

    private var dieSize = 0f
    private var slotX = FloatArray(0)
    private var slotY = FloatArray(0)

    init {
        isClickable = true
        isFocusable = true
        updateDescription()
    }

    /** Rolls the dice so that they land on [result], then calls [onEnd]. */
    fun roll(result: IntArray, onEnd: () -> Unit) {
        if (isRolling) return
        val rng = Random.Default
        dice.forEachIndexed { i, d ->
            d.target = result.getOrElse(i) { 1 }
            d.delay = rng.nextFloat() * 0.08f
            d.spin = (if (rng.nextBoolean()) 1 else -1) * (540f + rng.nextFloat() * 420f)
            d.restAngle = rng.nextFloat() * 14f - 7f
            d.startAngle = d.angle
            d.drift = rng.nextFloat() * 2f - 1f
            d.lastStep = 0
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ROLL_MS
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                var changed = false
                dice.forEach { d -> changed = d.update(t, rng) || changed }
                if (changed) onFaceChange?.invoke()
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    dice.forEach { it.settle() }
                    updateDescription()
                    invalidate()
                    onEnd()
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val inset = tableBorder.strokeWidth
        tableRect.set(inset, inset, w - inset, h - inset)
        tablePaint.shader = RadialGradient(
            w / 2f, h * 0.42f, maxOf(w, h) * 0.75f,
            intArrayOf(Color.rgb(46, 34, 22), Color.rgb(20, 16, 12), Color.rgb(8, 8, 8)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
        )
    }

    private fun layoutDice() {
        val n = dice.size
        val cols = when (n) { 1 -> 1; 2, 4 -> 2; else -> 3 }
        val rows = ceil(n / cols.toFloat()).toInt()
        val w = width.toFloat()
        val h = height.toFloat()
        dieSize = min(min(w / (cols * 1.6f), h / (rows * 1.75f)), 130f * density)
        val cellW = dieSize * 1.6f
        val cellH = dieSize * 1.6f
        val top = (h - rows * cellH) / 2f + cellH / 2f
        slotX = FloatArray(n)
        slotY = FloatArray(n)
        for (i in 0 until n) {
            val row = i / cols
            val inRow = if (row == rows - 1) n - row * cols else cols
            val left = (w - inRow * cellW) / 2f + cellW / 2f
            slotX[i] = left + (i % cols) * cellW
            slotY[i] = top + row * cellH
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = 22f * density
        canvas.drawRoundRect(tableRect, r, r, tablePaint)
        canvas.drawRoundRect(tableRect, r, r, tableBorder)
        if (width == 0 || height == 0) return
        layoutDice()

        val s = dieSize
        val maxJump = min(s * 0.55f, slotY.minOrNull()?.minus(s * 0.55f) ?: s)
        dice.forEachIndexed { i, d ->
            val cx = slotX[i] + d.dx * s * 0.18f
            val cy = slotY[i]
            val lift = d.height * maxJump.coerceAtLeast(0f)
            drawShadow(canvas, cx, cy, s, d.height)
            drawDie(canvas, cx, cy - lift, s * (1f + d.height * 0.12f), d.angle, d.face)
        }
    }

    private fun drawShadow(canvas: Canvas, cx: Float, cy: Float, s: Float, height: Float) {
        val rad = s * (0.72f - height * 0.2f)
        val sx = cx + s * 0.06f
        val sy = cy + s * 0.5f
        val alpha = (150 * (1f - height * 0.6f)).toInt()
        shadowPaint.shader = RadialGradient(
            sx, sy, rad, Color.argb(alpha, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP,
        )
        rect.set(sx - rad, sy - rad * 0.32f, sx + rad, sy + rad * 0.32f)
        canvas.drawOval(rect, shadowPaint)
    }

    private fun drawDie(canvas: Canvas, cx: Float, cy: Float, s: Float, angle: Float, face: Int) {
        val half = s / 2f
        val corner = s * 0.2f
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(angle)

        // Thickness: darker body peeking out below the face.
        rect.set(-half, -half + s * 0.07f, half, half + s * 0.07f)
        canvas.drawRoundRect(rect, corner, corner, sidePaint)

        rect.set(-half, -half, half, half)
        facePaint.shader = LinearGradient(
            -half, -half, half, half,
            Color.rgb(255, 255, 255), Color.rgb(232, 225, 212), Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(rect, corner, corner, facePaint)
        edgePaint.strokeWidth = s * 0.012f
        canvas.drawRoundRect(rect, corner, corner, edgePaint)

        // Soft highlight on the upper-left.
        glossPaint.shader = RadialGradient(
            -half * 0.45f, -half * 0.5f, s * 0.6f,
            Color.argb(150, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(rect, corner, corner, glossPaint)

        val single = face == 1
        val pr = if (single) s * 0.15f else s * 0.088f
        for ((px, py) in PIPS[face - 1]) {
            // Grid positions 0.25 / 0.5 / 0.75 map to -0.28s / 0 / +0.28s from the centre.
            val x = (px - 0.5f) * s * 1.12f
            val y = (py - 0.5f) * s * 1.12f
            canvas.drawCircle(x + pr * 0.12f, y + pr * 0.16f, pr, pipRimPaint)
            pipPaint.shader = RadialGradient(
                x - pr * 0.3f, y - pr * 0.35f, pr * 1.3f,
                if (single) intArrayOf(Color.rgb(255, 178, 64), ORANGE, Color.rgb(176, 84, 0))
                else intArrayOf(Color.rgb(70, 70, 70), Color.rgb(22, 22, 22), Color.BLACK),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(x, y, pr, pipPaint)
        }
        canvas.restore()
    }

    private fun updateDescription() {
        contentDescription = context.getString(
            R.string.cd_dice, dice.joinToString(", ") { it.face.toString() },
        )
    }

    private class Die(var face: Int) {
        var target = face
        var angle = 0f
        var startAngle = 0f
        var restAngle = 0f
        var spin = 0f
        var delay = 0f
        var drift = 0f
        var dx = 0f
        var height = 0f
        var lastStep = 0

        /** Advances the tumble; returns true if the visible face changed. */
        fun update(t: Float, rng: Random): Boolean {
            val p = ((t - delay) / (1f - delay)).coerceIn(0f, 1f)
            val ease = 1f - (1f - p).pow(3)
            angle = startAngle + (restAngle - startAngle + spin) * ease
            // Three bounces that get lower each time.
            height = abs(sin(p * PI.toFloat() * 3f)) * (1f - p).pow(1.6f)
            dx = drift * sin(p * PI.toFloat()) * (1f - p)
            // A new face every quarter turn while tumbling.
            val step = floor(abs(angle - startAngle) / 90f).toInt()
            if (step != lastStep && p < 0.97f) {
                lastStep = step
                var f: Int
                do { f = rng.nextInt(1, 7) } while (f == face)
                face = f
                return true
            }
            if (p >= 0.97f && face != target) {
                face = target
                return true
            }
            return false
        }

        fun settle() {
            face = target
            angle = restAngle
            height = 0f
            dx = 0f
        }
    }

    companion object {
        const val MAX_DICE = 6
        const val ROLL_MS = 1250L
        private val ORANGE = Color.rgb(255, 144, 0)
        private val DEFAULT_FACES = intArrayOf(5, 3, 6, 2, 4, 1)

        private val PIPS: Array<List<Pair<Float, Float>>> = arrayOf(
            listOf(0.5f to 0.5f),
            listOf(0.25f to 0.25f, 0.75f to 0.75f),
            listOf(0.25f to 0.25f, 0.5f to 0.5f, 0.75f to 0.75f),
            listOf(0.25f to 0.25f, 0.75f to 0.25f, 0.25f to 0.75f, 0.75f to 0.75f),
            listOf(0.25f to 0.25f, 0.75f to 0.25f, 0.5f to 0.5f, 0.25f to 0.75f, 0.75f to 0.75f),
            listOf(0.25f to 0.25f, 0.75f to 0.25f, 0.25f to 0.5f, 0.75f to 0.5f, 0.25f to 0.75f, 0.75f to 0.75f),
        )
    }
}
