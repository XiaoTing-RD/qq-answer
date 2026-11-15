package com.ridevoice.qq

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import java.util.Random

class SakuraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private class Petal {
        var x = 0f
        var y = 0f
        var size = 0f
        var speed = 0f
        var sway = 0f
        var phase = 0f
        var angle = 0f
        var spin = 0f
        var alpha = 0
        var color = 0
        val path = Path()
    }

    private val petals = ArrayList<Petal>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val random = Random()
    private val density = resources.displayMetrics.density
    private val colors = intArrayOf(
        0xFFF7A8C0.toInt(),
        0xFFFBC6D7.toInt(),
        0xFFF28FAD.toInt(),
        0xFFFFD8E6.toInt(),
        0xFFE86A92.toInt(),
        0xFFFFE3EE.toInt()
    )

    private var lastFrame = 0L
    private var running = false

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val delta = ((now - lastFrame).coerceIn(0L, 50L)) / 1000f
            lastFrame = now
            update(delta)
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        paint.style = Paint.Style.FILL
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        seed(w, h)
    }

    private fun seed(width: Int, height: Int) {
        petals.clear()
        if (width <= 0 || height <= 0) return
        repeat(26) {
            val petal = Petal()
            reset(petal, width, height, true)
            petal.color = colors[random.nextInt(colors.size)]
            petals.add(petal)
        }
    }

    private fun reset(petal: Petal, width: Int, height: Int, anywhere: Boolean) {
        petal.size = (9f + random.nextFloat() * 13f) * density * 0.5f
        petal.x = random.nextFloat() * width
        petal.y = if (anywhere) random.nextFloat() * height else -petal.size * 4f
        petal.speed = (18f + random.nextFloat() * 42f) * density
        petal.sway = (12f + random.nextFloat() * 28f) * density
        petal.phase = random.nextFloat() * (Math.PI.toFloat() * 2f)
        petal.angle = random.nextFloat() * 360f
        petal.spin = (random.nextFloat() - 0.5f) * 90f
        petal.alpha = 90 + random.nextInt(110)
        buildPath(petal)
    }

    private fun buildPath(petal: Petal) {
        val size = petal.size * 2f
        petal.path.reset()
        petal.path.moveTo(0f, -size * 0.55f)
        petal.path.cubicTo(size * 0.62f, -size * 0.42f, size * 0.5f, size * 0.5f, 0f, size * 0.62f)
        petal.path.cubicTo(-size * 0.5f, size * 0.5f, -size * 0.62f, -size * 0.42f, 0f, -size * 0.55f)
        petal.path.close()
    }

    private fun update(delta: Float) {
        if (width <= 0 || height <= 0) return
        for (petal in petals) {
            petal.phase += delta * 1.4f
            petal.x += (Math.sin(petal.phase.toDouble()) * petal.sway * delta + 8f * density * delta).toFloat()
            petal.y += petal.speed * delta
            petal.angle += petal.spin * delta
            if (petal.y - petal.size > height || petal.x - petal.size > width + 40 || petal.x + petal.size < -40) {
                reset(petal, width, height, false)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (petal in petals) {
            paint.color = petal.color
            paint.alpha = petal.alpha
            val save = canvas.save()
            canvas.translate(petal.x, petal.y)
            canvas.rotate(petal.angle)
            canvas.drawPath(petal.path, paint)
            canvas.restoreToCount(save)
        }
        paint.alpha = 255
    }

    private fun start() {
        if (running || !isAttachedToWindow || visibility != VISIBLE) return
        running = true
        lastFrame = SystemClock.uptimeMillis()
        postOnAnimation(frame)
    }

    private fun stop() {
        running = false
        removeCallbacks(frame)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) start() else stop()
    }
}
